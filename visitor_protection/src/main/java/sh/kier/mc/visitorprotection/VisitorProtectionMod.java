package sh.kier.mc.visitorprotection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.loader.api.FabricLoader;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class VisitorProtectionMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("visitor_protection");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ProtectionConfig config = ProtectionConfig.defaults();
    private static Set<String> builderNames = Set.of();
    private static Set<UUID> builderUuids = Set.of();
    private static final Map<UUID, Long> messageTimestamps = new HashMap<>();
    private static Path configPath;

    @Override
    public void onInitialize() {
        loadConfig();
        registerCommands();
        registerEvents();
        registerPermissionProvider();
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("buildlist")
                .requires(VisitorProtectionMod::isOperator)
                .then(literal("on")
                    .executes(context -> setBuildListEnabled(context.getSource(), true)))
                .then(literal("off")
                    .executes(context -> setBuildListEnabled(context.getSource(), false)))
                .then(literal("list")
                    .executes(context -> listBuilders(context.getSource())))
                .then(literal("add")
                    .then(argument("player", StringArgumentType.word())
                        .executes(context -> addBuilder(
                            context.getSource(),
                            StringArgumentType.getString(context, "player")
                        ))))
                .then(literal("remove")
                    .then(argument("player", StringArgumentType.word())
                        .executes(context -> removeBuilder(
                            context.getSource(),
                            StringArgumentType.getString(context, "player")
                        ))))
                .then(literal("reload")
                    .executes(context -> reloadConfig(context.getSource()))));

            dispatcher.register(literal("visitorprotection")
                .requires(VisitorProtectionMod::isOperator)
                .then(literal("reload")
                    .executes(context -> reloadConfig(context.getSource()))));
        });
    }

    private static int reloadConfig(ServerCommandSource source) {
        loadConfig();
        source.sendFeedback(() -> Text.literal("Build list reloaded."), true);
        return 1;
    }

    private static int setBuildListEnabled(ServerCommandSource source, boolean enabled) {
        config = new ProtectionConfig(
            enabled,
            config.allowOps(),
            config.buildPermissionDays(),
            config.builders(),
            config.blockedMessage()
        ).normalized();
        saveConfig(source);
        rebuildBuilderSets();
        source.sendFeedback(() -> Text.literal("Build list is now " + (enabled ? "enabled" : "disabled") + "."), true);
        return 1;
    }

    private static int listBuilders(ServerCommandSource source) {
        List<String> builders = config.builders().stream()
            .map(VisitorProtectionMod::formatBuilder)
            .toList();
        String names = builders.isEmpty() ? "(none)" : String.join(", ", builders);
        source.sendFeedback(() -> Text.literal("Build list: " + names), false);
        return builders.size();
    }

    private static int addBuilder(ServerCommandSource source, String player) {
        BuilderEntry newEntry = new BuilderEntry(player, expiresAtFromNow()).normalized();
        List<BuilderEntry> builders = new ArrayList<>(config.builders());
        if (builders.stream().anyMatch(existing -> existing.matches(player) && !existing.isExpired())) {
            source.sendFeedback(() -> Text.literal(player + " is already on the build list."), false);
            return 0;
        }

        builders = builders.stream()
            .filter(existing -> !existing.matches(player))
            .collect(Collectors.toCollection(ArrayList::new));
        builders.add(newEntry);
        config = new ProtectionConfig(
            config.enabled(),
            config.allowOps(),
            config.buildPermissionDays(),
            builders,
            config.blockedMessage()
        ).normalized();
        saveConfig(source);
        rebuildBuilderSets();
        source.sendFeedback(() -> Text.literal("Added " + player + " to the build list until " + newEntry.expiresAt() + "."), true);
        return 1;
    }

    private static int removeBuilder(ServerCommandSource source, String player) {
        List<BuilderEntry> builders = config.builders().stream()
            .filter(existing -> !existing.matches(player))
            .toList();
        if (builders.size() == config.builders().size()) {
            source.sendFeedback(() -> Text.literal(player + " is not on the build list."), false);
            return 0;
        }

        config = new ProtectionConfig(
            config.enabled(),
            config.allowOps(),
            config.buildPermissionDays(),
            builders,
            config.blockedMessage()
        ).normalized();
        saveConfig(source);
        rebuildBuilderSets();
        source.sendFeedback(() -> Text.literal("Removed " + player + " from the build list."), true);
        return 1;
    }

    private static void registerEvents() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !canEdit(serverPlayer)) {
                notifyDenied(serverPlayer);
                return false;
            }

            return true;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !canEdit(serverPlayer)) {
                notifyDenied(serverPlayer);
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !canEdit(serverPlayer)) {
                notifyDenied(serverPlayer);
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !canEdit(serverPlayer)) {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof BlockItem) {
                    notifyDenied(serverPlayer);
                    return ActionResult.FAIL;
                }
            }

            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !canEdit(serverPlayer)) {
                notifyDenied(serverPlayer);
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !canEdit(serverPlayer)) {
                notifyDenied(serverPlayer);
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });
    }

    private static void registerPermissionProvider() {
        PermissionCheckEvent.EVENT.register((source, permission) -> {
            if (!isWorldEditPermission(permission) || !(source instanceof ServerCommandSource serverSource)) {
                return TriState.DEFAULT;
            }

            ServerPlayerEntity player = serverSource.getPlayer();
            if (player == null || !canEdit(player)) {
                return TriState.DEFAULT;
            }

            return TriState.TRUE;
        });
    }

    private static boolean canEdit(ServerPlayerEntity player) {
        if (!config.enabled()) {
            return true;
        }

        if (config.allowOps() && isOperator(player.getCommandSource())) {
            return true;
        }

        if (builderUuids.contains(player.getUuid())) {
            return true;
        }

        return builderNames.contains(player.getGameProfile().name().toLowerCase(Locale.ROOT));
    }

    private static boolean isWorldEditPermission(String permission) {
        String normalized = permission.toLowerCase(Locale.ROOT);
        return normalized.equals("worldedit") || normalized.equals("worldedit.*") || normalized.startsWith("worldedit.");
    }

    private static void notifyDenied(ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        long lastSent = messageTimestamps.getOrDefault(player.getUuid(), 0L);
        if (now - lastSent < 2000L) {
            return;
        }

        messageTimestamps.put(player.getUuid(), now);
        player.sendMessage(Text.literal(config.blockedMessage()), true);
    }

    private static boolean isOperator(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return true;
        }

        OperatorEntry entry = source.getServer()
            .getPlayerManager()
            .getOpList()
            .get(new PlayerConfigEntry(player.getGameProfile()));

        return entry != null;
    }

    private static void loadConfig() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("visitor-protection.json");
        ProtectionConfig loaded = ProtectionConfig.defaults();

        if (Files.exists(configPath)) {
            try {
                loaded = parseConfig(Files.readString(configPath));
            } catch (IOException | JsonSyntaxException exception) {
                LOGGER.error("Failed to read visitor protection config at {}", configPath, exception);
            }
        } else {
            LOGGER.warn("Visitor protection config does not exist at {}; using defaults.", configPath);
        }

        config = loaded.normalized();
        rebuildBuilderSets();

        LOGGER.info(
            "Visitor protection {} with {} builder names and {} builder UUIDs.",
            config.enabled() ? "enabled" : "disabled",
            builderNames.size(),
            builderUuids.size()
        );
    }

    private static ProtectionConfig parseConfig(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        ProtectionConfig defaults = ProtectionConfig.defaults();
        if (root == null) {
            return defaults;
        }

        boolean enabled = getBoolean(root, "enabled", defaults.enabled());
        boolean allowOps = getBoolean(root, "allowOps", defaults.allowOps());
        int buildPermissionDays = Math.max(1, getInt(root, "buildPermissionDays", defaults.buildPermissionDays()));
        String blockedMessage = getString(root, "blockedMessage", defaults.blockedMessage());
        List<BuilderEntry> builders = parseBuilders(root.get("builders"));

        return new ProtectionConfig(enabled, allowOps, buildPermissionDays, builders, blockedMessage).normalized();
    }

    private static List<BuilderEntry> parseBuilders(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }

        List<BuilderEntry> builders = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) {
                builders.add(new BuilderEntry(item.getAsString(), null));
            } else if (item.isJsonObject()) {
                JsonObject object = item.getAsJsonObject();
                builders.add(new BuilderEntry(
                    getString(object, "player", ""),
                    getString(object, "expiresAt", null)
                ));
            }
        }

        return builders;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean defaultValue) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : defaultValue;
    }

    private static int getInt(JsonObject object, String key, int defaultValue) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : defaultValue;
    }

    private static String getString(JsonObject object, String key, String defaultValue) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : defaultValue;
    }

    private static void rebuildBuilderSets() {
        builderNames = config.builders().stream()
            .filter(entry -> !entry.isExpired())
            .map(BuilderEntry::player)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .filter(value -> !isUuid(value))
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        builderUuids = config.builders().stream()
            .filter(entry -> !entry.isExpired())
            .map(BuilderEntry::player)
            .map(String::trim)
            .filter(VisitorProtectionMod::isUuid)
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static void saveConfig(ServerCommandSource source) {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(config) + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("Failed to write visitor protection config at {}", configPath, exception);
            source.sendError(Text.literal("Failed to save the build list. Check server logs."));
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String expiresAtFromNow() {
        return Instant.now()
            .plus(Duration.ofDays(config.buildPermissionDays()))
            .truncatedTo(ChronoUnit.SECONDS)
            .toString();
    }

    private static String formatBuilder(BuilderEntry entry) {
        if (entry.expiresAt() == null || entry.expiresAt().isBlank()) {
            return entry.player() + " (never expires)";
        }

        if (entry.isExpired()) {
            return entry.player() + " (expired " + entry.expiresAt() + ")";
        }

        return entry.player() + " (expires " + entry.expiresAt() + ")";
    }

    private record BuilderEntry(String player, String expiresAt) {
        private BuilderEntry normalized() {
            String normalizedPlayer = player == null ? "" : player.trim();
            String normalizedExpiresAt = expiresAt == null || expiresAt.isBlank() ? null : expiresAt.trim();
            return new BuilderEntry(normalizedPlayer, normalizedExpiresAt);
        }

        private boolean matches(String value) {
            return player != null && player.equalsIgnoreCase(value);
        }

        private boolean isExpired() {
            if (expiresAt == null || expiresAt.isBlank()) {
                return false;
            }

            try {
                return !Instant.parse(expiresAt).isAfter(Instant.now());
            } catch (RuntimeException exception) {
                LOGGER.warn("Ignoring invalid build list expiry '{}' for '{}'.", expiresAt, player);
                return true;
            }
        }
    }

    private record ProtectionConfig(
        boolean enabled,
        boolean allowOps,
        int buildPermissionDays,
        List<BuilderEntry> builders,
        String blockedMessage
    ) {
        private static ProtectionConfig defaults() {
            return new ProtectionConfig(
                true,
                true,
                14,
                List.of(),
                "You can look around, but you need to be added as a builder before editing the world."
            );
        }

        private ProtectionConfig normalized() {
            ProtectionConfig defaults = defaults();
            List<BuilderEntry> normalizedBuilders = builders == null
                ? defaults.builders()
                : new ArrayList<>(new LinkedHashSet<>(builders.stream()
                    .map(BuilderEntry::normalized)
                    .filter(entry -> !entry.player().isEmpty())
                    .toList()));

            return new ProtectionConfig(
                enabled,
                allowOps,
                buildPermissionDays <= 0 ? defaults.buildPermissionDays() : buildPermissionDays,
                normalizedBuilders,
                blockedMessage == null || blockedMessage.isBlank() ? defaults.blockedMessage() : blockedMessage
            );
        }
    }
}
