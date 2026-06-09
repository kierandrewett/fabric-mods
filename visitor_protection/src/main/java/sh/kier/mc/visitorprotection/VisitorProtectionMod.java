package sh.kier.mc.visitorprotection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
            config.builders(),
            config.blockedMessage()
        ).normalized();
        saveConfig(source);
        rebuildBuilderSets();
        source.sendFeedback(() -> Text.literal("Build list is now " + (enabled ? "enabled" : "disabled") + "."), true);
        return 1;
    }

    private static int listBuilders(ServerCommandSource source) {
        List<String> builders = config.builders();
        String names = builders.isEmpty() ? "(none)" : String.join(", ", builders);
        source.sendFeedback(() -> Text.literal("Build list: " + names), false);
        return builders.size();
    }

    private static int addBuilder(ServerCommandSource source, String player) {
        List<String> builders = new ArrayList<>(config.builders());
        if (builders.stream().anyMatch(existing -> existing.equalsIgnoreCase(player))) {
            source.sendFeedback(() -> Text.literal(player + " is already on the build list."), false);
            return 0;
        }

        builders.add(player);
        config = new ProtectionConfig(config.enabled(), config.allowOps(), builders, config.blockedMessage()).normalized();
        saveConfig(source);
        rebuildBuilderSets();
        source.sendFeedback(() -> Text.literal("Added " + player + " to the build list."), true);
        return 1;
    }

    private static int removeBuilder(ServerCommandSource source, String player) {
        List<String> builders = config.builders().stream()
            .filter(existing -> !existing.equalsIgnoreCase(player))
            .toList();
        if (builders.size() == config.builders().size()) {
            source.sendFeedback(() -> Text.literal(player + " is not on the build list."), false);
            return 0;
        }

        config = new ProtectionConfig(config.enabled(), config.allowOps(), builders, config.blockedMessage()).normalized();
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
                loaded = GSON.fromJson(Files.readString(configPath), ProtectionConfig.class);
                if (loaded == null) {
                    loaded = ProtectionConfig.defaults();
                }
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

    private static void rebuildBuilderSets() {
        builderNames = config.builders().stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .filter(value -> !isUuid(value))
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        builderUuids = config.builders().stream()
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

    private record ProtectionConfig(boolean enabled, boolean allowOps, List<String> builders, String blockedMessage) {
        private static ProtectionConfig defaults() {
            return new ProtectionConfig(
                true,
                true,
                List.of(),
                "You can look around, but you need to be added as a builder before editing the world."
            );
        }

        private ProtectionConfig normalized() {
            ProtectionConfig defaults = defaults();
            List<String> normalizedBuilders = builders == null
                ? defaults.builders()
                : new ArrayList<>(new LinkedHashSet<>(builders.stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList()));

            return new ProtectionConfig(
                enabled,
                allowOps,
                normalizedBuilders,
                blockedMessage == null || blockedMessage.isBlank() ? defaults.blockedMessage() : blockedMessage
            );
        }
    }
}
