package sh.kier.mc.ntfy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.literal;

public final class NtfyMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ntfy");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ntfy-alerts");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .executor(EXECUTOR)
        .build();

    private static NtfyConfig config = NtfyConfig.defaults();
    private static Path configPath;

    @Override
    public void onInitialize() {
        loadConfig();
        registerCommands();
        registerEvents();
    }

    private static void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            notifyJoin(handler.player, handler.getConnectionAddress(), server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            notifyLeave(handler.player, handler.getConnectionAddress(), server));
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            literal("ntfy")
                .requires(NtfyMod::canManage)
                .then(literal("status")
                    .executes(context -> status(context.getSource())))
                .then(literal("reload")
                    .executes(context -> reload(context.getSource())))
                .then(literal("test")
                    .executes(context -> test(context.getSource())))
                .then(literal("placeholders")
                    .executes(context -> placeholders(context.getSource())))
        ));
    }

    private static int status(ServerCommandSource source) {
        BuilderSnapshot builders = loadBuilders();
        source.sendFeedback(() -> Text.literal("ntfy: " + (isConfigured() ? "configured" : "not configured"))
            .formatted(isConfigured() ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("enabled=" + config.enabled()
            + ", url=" + redactedUrl()
            + ", joins=" + config.notifyJoins()
            + ", leaves=" + config.notifyLeaves()
            + ", nonBuilders=" + config.alertNonBuilders()), false);
        source.sendFeedback(() -> Text.literal("builder list: " + builders.builderNames().size()
            + " names, " + builders.builderUuids().size()
            + " UUIDs, allowOps=" + builders.allowOps()), false);
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        loadConfig();
        BuilderSnapshot builders = loadBuilders();
        source.sendFeedback(() -> Text.literal("ntfy config reloaded. Builder list has "
            + builders.builderNames().size() + " names and "
            + builders.builderUuids().size() + " UUIDs."), true);
        return 1;
    }

    private static int test(ServerCommandSource source) {
        if (!isConfigured()) {
            source.sendError(Text.literal("ntfy is not configured. Set config/ntfy.json url first."));
            return 0;
        }

        Map<String, String> placeholders = testPlaceholders(source);
        send(
            render(config.testTitle(), placeholders),
            render(config.testBody(), placeholders),
            render(config.testPriority(), placeholders),
            render(config.testTags(), placeholders)
        );
        source.sendFeedback(() -> Text.literal("Queued ntfy test notification."), false);
        return 1;
    }

    private static int placeholders(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("ntfy placeholders:").formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("{player}, {uuid}, {action}, {server}, {builder}, {operator}"), false);
        source.sendFeedback(() -> Text.literal("{world}, {x}, {y}, {z}, {position}, {address}"), false);
        source.sendFeedback(() -> Text.literal("{online}, {max_players}, {builder_names}, {builder_uuids}, {time}"), false);
        source.sendFeedback(() -> Text.literal("Both {player} and ${player} forms are supported."), false);
        return 1;
    }

    private static boolean canManage(ServerCommandSource source) {
        return source.getPermissions() instanceof LeveledPermissionPredicate permissions
            && permissions.getLevel().isAtLeast(PermissionLevel.GAMEMASTERS);
    }

    private static void notifyJoin(ServerPlayerEntity player, Object address, MinecraftServer server) {
        BuilderSnapshot builders = loadBuilders();
        boolean builder = isBuilder(player, server, builders);
        if (!config.notifyJoins() && !(config.alertNonBuilders() && !builder)) {
            return;
        }

        String title = builder ? config.joinTitle() : config.nonBuilderTitle();
        String body = builder ? config.joinBody() : config.nonBuilderBody();
        String priority = builder ? config.joinPriority() : config.nonBuilderPriority();
        String tags = builder ? config.joinTags() : config.nonBuilderTags();
        Map<String, String> placeholders = eventPlaceholders("joined", player, address, server, builders, builder);
        send(
            render(title, placeholders),
            render(body, placeholders),
            render(priority, placeholders),
            render(tags, placeholders)
        );
    }

    private static void notifyLeave(ServerPlayerEntity player, Object address, MinecraftServer server) {
        if (!config.notifyLeaves()) {
            return;
        }

        BuilderSnapshot builders = loadBuilders();
        boolean builder = isBuilder(player, server, builders);
        Map<String, String> placeholders = eventPlaceholders("left", player, address, server, builders, builder);
        send(
            render(config.leaveTitle(), placeholders),
            render(config.leaveBody(), placeholders),
            render(config.leavePriority(), placeholders),
            render(config.leaveTags(), placeholders)
        );
    }

    private static Map<String, String> eventPlaceholders(
        String action,
        ServerPlayerEntity player,
        Object address,
        MinecraftServer server,
        BuilderSnapshot builders,
        boolean builder
    ) {
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Identifier world = player.getEntityWorld().getRegistryKey().getValue();
        int online = server.getPlayerManager().getPlayerList().size();
        String addressText = address == null ? "unknown" : address.toString();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName().getString());
        values.put("username", player.getName().getString());
        values.put("uuid", player.getUuidAsString());
        values.put("action", action);
        values.put("server", config.serverName());
        values.put("builder", builder ? "yes" : "no");
        values.put("operator", isOperator(player, server) ? "yes" : "no");
        values.put("world", world.toString());
        values.put("x", formatCoordinate(pos.x));
        values.put("y", formatCoordinate(pos.y));
        values.put("z", formatCoordinate(pos.z));
        values.put("position", formatPosition(pos));
        values.put("address", addressText);
        values.put("online", Integer.toString(online));
        values.put("max_players", Integer.toString(server.getMaxPlayerCount()));
        values.put("builder_names", Integer.toString(builders.builderNames().size()));
        values.put("builder_uuids", Integer.toString(builders.builderUuids().size()));
        values.put("time", Instant.now().toString());
        return values;
    }

    private static Map<String, String> testPlaceholders(ServerCommandSource source) {
        String sender = source.getName();
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            sender = player.getName().getString();
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", sender);
        values.put("username", sender);
        values.put("uuid", player == null ? "console" : player.getUuidAsString());
        values.put("action", "tested");
        values.put("server", config.serverName());
        values.put("builder", "unknown");
        values.put("operator", "unknown");
        values.put("world", player == null ? "console" : player.getEntityWorld().getRegistryKey().getValue().toString());
        values.put("x", player == null ? "0.0" : formatCoordinate(player.getX()));
        values.put("y", player == null ? "0.0" : formatCoordinate(player.getY()));
        values.put("z", player == null ? "0.0" : formatCoordinate(player.getZ()));
        values.put("position", player == null ? "0.0 0.0 0.0" : formatPosition(new Vec3d(player.getX(), player.getY(), player.getZ())));
        values.put("address", "test");
        values.put("online", Integer.toString(source.getServer().getPlayerManager().getPlayerList().size()));
        values.put("max_players", Integer.toString(source.getServer().getMaxPlayerCount()));
        BuilderSnapshot builders = loadBuilders();
        values.put("builder_names", Integer.toString(builders.builderNames().size()));
        values.put("builder_uuids", Integer.toString(builders.builderUuids().size()));
        values.put("time", Instant.now().toString());
        return values;
    }

    private static boolean isBuilder(ServerPlayerEntity player, MinecraftServer server, BuilderSnapshot builders) {
        if (config.treatOpsAsBuilders() && builders.allowOps() && isOperator(player, server)) {
            return true;
        }

        if (builders.builderUuids().contains(player.getUuid())) {
            return true;
        }

        return builders.builderNames().contains(player.getGameProfile().name().toLowerCase(Locale.ROOT));
    }

    private static boolean isOperator(ServerPlayerEntity player, MinecraftServer server) {
        OperatorEntry entry = server.getPlayerManager()
            .getOpList()
            .get(new PlayerConfigEntry(player.getGameProfile()));

        return entry != null;
    }

    private static void send(String title, String body, String priority, String tags) {
        if (!isConfigured()) {
            LOGGER.debug("Skipping ntfy notification because url is not configured.");
            return;
        }

        URI uri;
        try {
            uri = URI.create(config.url());
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Skipping ntfy notification because url is invalid: {}", config.url(), exception);
            return;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
            .header("Content-Type", "text/markdown; charset=utf-8")
            .header("Markdown", "yes")
            .header("Title", title)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (!priority.isBlank()) {
            builder.header("Priority", priority);
        }

        if (!tags.isBlank()) {
            builder.header("Tags", tags);
        }

        String token = resolveToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
            .whenComplete((response, throwable) -> {
                if (throwable != null) {
                    LOGGER.warn("Failed to send ntfy notification '{}'.", title, throwable);
                    return;
                }

                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    LOGGER.warn("ntfy notification '{}' returned HTTP {}.", title, status);
                }
            });
    }

    private static String resolveToken() {
        if (!config.tokenFile().isBlank()) {
            Path path = Path.of(config.tokenFile());
            try {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException exception) {
                LOGGER.warn("Failed to read ntfy tokenFile {}", path, exception);
                return "";
            }
        }

        return config.token().trim();
    }

    private static boolean isConfigured() {
        return config.enabled() && !config.url().isBlank();
    }

    private static String redactedUrl() {
        if (config.url().isBlank()) {
            return "(empty)";
        }

        return config.url().replaceAll("(?i)(token=)[^&]+", "$1<redacted>");
    }

    private static String render(String template, Map<String, String> placeholders) {
        String rendered = template == null ? "" : template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered
                .replace("{" + entry.getKey() + "}", entry.getValue())
                .replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    private static String formatCoordinate(double coordinate) {
        return String.format(Locale.ROOT, "%.1f", coordinate);
    }

    private static String formatPosition(Vec3d pos) {
        return formatCoordinate(pos.x) + " " + formatCoordinate(pos.y) + " " + formatCoordinate(pos.z);
    }

    private static void loadConfig() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("ntfy.json");
        NtfyConfig loaded = NtfyConfig.defaults();

        if (Files.exists(configPath)) {
            try {
                loaded = parseConfig(Files.readString(configPath));
            } catch (IOException | JsonSyntaxException exception) {
                LOGGER.error("Failed to read ntfy config at {}", configPath, exception);
            }
        } else {
            try {
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath, GSON.toJson(loaded) + "\n", StandardCharsets.UTF_8);
                LOGGER.info("Created default ntfy config at {}", configPath);
            } catch (IOException exception) {
                LOGGER.error("Failed to create default ntfy config at {}", configPath, exception);
            }
        }

        config = loaded.normalized();
        LOGGER.info("ntfy {}. URL configured: {}", config.enabled() ? "enabled" : "disabled", !config.url().isBlank());
    }

    private static NtfyConfig parseConfig(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        NtfyConfig defaults = NtfyConfig.defaults();
        if (root == null) {
            return defaults;
        }

        return new NtfyConfig(
            getBoolean(root, "enabled", defaults.enabled()),
            getString(root, "url", defaults.url()),
            getString(root, "token", defaults.token()),
            getString(root, "tokenFile", defaults.tokenFile()),
            getString(root, "serverName", defaults.serverName()),
            getBoolean(root, "notifyJoins", defaults.notifyJoins()),
            getBoolean(root, "notifyLeaves", defaults.notifyLeaves()),
            getBoolean(root, "alertNonBuilders", defaults.alertNonBuilders()),
            getBoolean(root, "treatOpsAsBuilders", defaults.treatOpsAsBuilders()),
            getString(root, "builderListPath", defaults.builderListPath()),
            getInt(root, "requestTimeoutSeconds", defaults.requestTimeoutSeconds()),
            getString(root, "joinTitle", defaults.joinTitle()),
            getString(root, "leaveTitle", defaults.leaveTitle()),
            getString(root, "nonBuilderTitle", defaults.nonBuilderTitle()),
            getString(root, "joinBody", defaults.joinBody()),
            getString(root, "leaveBody", defaults.leaveBody()),
            getString(root, "nonBuilderBody", defaults.nonBuilderBody()),
            getString(root, "testTitle", defaults.testTitle()),
            getString(root, "testBody", defaults.testBody()),
            getString(root, "joinPriority", defaults.joinPriority()),
            getString(root, "leavePriority", defaults.leavePriority()),
            getString(root, "nonBuilderPriority", defaults.nonBuilderPriority()),
            getString(root, "testPriority", defaults.testPriority()),
            getString(root, "joinTags", defaults.joinTags()),
            getString(root, "leaveTags", defaults.leaveTags()),
            getString(root, "nonBuilderTags", defaults.nonBuilderTags()),
            getString(root, "testTags", defaults.testTags())
        ).normalized();
    }

    private static BuilderSnapshot loadBuilders() {
        Path path = builderListPath();
        if (!Files.exists(path)) {
            return BuilderSnapshot.empty();
        }

        try {
            JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) {
                return BuilderSnapshot.empty();
            }

            boolean allowOps = getBoolean(root, "allowOps", true);
            List<BuilderEntry> builders = parseBuilders(root.get("builders"));
            Set<String> names = builders.stream()
                .filter(entry -> !entry.isExpired())
                .map(BuilderEntry::player)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(value -> !isUuid(value))
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
            Set<UUID> uuids = builders.stream()
                .filter(entry -> !entry.isExpired())
                .map(BuilderEntry::player)
                .map(String::trim)
                .filter(NtfyMod::isUuid)
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());

            return new BuilderSnapshot(names, uuids, allowOps);
        } catch (IOException | JsonSyntaxException exception) {
            LOGGER.warn("Failed to read builder list at {}", path, exception);
            return BuilderSnapshot.empty();
        }
    }

    private static Path builderListPath() {
        Path configured = Path.of(config.builderListPath());
        if (configured.isAbsolute()) {
            return configured;
        }

        return FabricLoader.getInstance().getConfigDir().resolve(configured);
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

        return new ArrayList<>(new LinkedHashSet<>(builders));
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
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

    private record BuilderEntry(String player, String expiresAt) {
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

    private record BuilderSnapshot(Set<String> builderNames, Set<UUID> builderUuids, boolean allowOps) {
        private static BuilderSnapshot empty() {
            return new BuilderSnapshot(Set.of(), Set.of(), true);
        }
    }

    private record NtfyConfig(
        boolean enabled,
        String url,
        String token,
        String tokenFile,
        String serverName,
        boolean notifyJoins,
        boolean notifyLeaves,
        boolean alertNonBuilders,
        boolean treatOpsAsBuilders,
        String builderListPath,
        int requestTimeoutSeconds,
        String joinTitle,
        String leaveTitle,
        String nonBuilderTitle,
        String joinBody,
        String leaveBody,
        String nonBuilderBody,
        String testTitle,
        String testBody,
        String joinPriority,
        String leavePriority,
        String nonBuilderPriority,
        String testPriority,
        String joinTags,
        String leaveTags,
        String nonBuilderTags,
        String testTags
    ) {
        private static NtfyConfig defaults() {
            return new NtfyConfig(
                true,
                "",
                "",
                "",
                "kier.sh minecraft",
                true,
                true,
                true,
                true,
                "visitor-protection.json",
                8,
                "{player} joined {server}",
                "{player} left {server}",
                "Non-builder {player} joined {server}",
                defaultBody(),
                defaultBody(),
                defaultBody(),
                "Minecraft ntfy test",
                "Test notification from {server} by {player} at {time}.",
                "default",
                "default",
                "high",
                "default",
                "minecraft,join",
                "minecraft,leave",
                "minecraft,warning",
                "minecraft,test"
            );
        }

        private NtfyConfig normalized() {
            NtfyConfig defaults = defaults();
            return new NtfyConfig(
                enabled,
                trim(url),
                trim(token),
                trim(tokenFile),
                blankDefault(serverName, defaults.serverName()),
                notifyJoins,
                notifyLeaves,
                alertNonBuilders,
                treatOpsAsBuilders,
                blankDefault(builderListPath, defaults.builderListPath()),
                requestTimeoutSeconds <= 0 ? defaults.requestTimeoutSeconds() : requestTimeoutSeconds,
                blankDefault(joinTitle, defaults.joinTitle()),
                blankDefault(leaveTitle, defaults.leaveTitle()),
                blankDefault(nonBuilderTitle, defaults.nonBuilderTitle()),
                blankDefault(joinBody, defaults.joinBody()),
                blankDefault(leaveBody, defaults.leaveBody()),
                blankDefault(nonBuilderBody, defaults.nonBuilderBody()),
                blankDefault(testTitle, defaults.testTitle()),
                blankDefault(testBody, defaults.testBody()),
                blankDefault(joinPriority, defaults.joinPriority()),
                blankDefault(leavePriority, defaults.leavePriority()),
                blankDefault(nonBuilderPriority, defaults.nonBuilderPriority()),
                blankDefault(testPriority, defaults.testPriority()),
                trim(joinTags),
                trim(leaveTags),
                trim(nonBuilderTags),
                trim(testTags)
            );
        }

        private static String blankDefault(String value, String fallback) {
            String trimmed = trim(value);
            return trimmed.isBlank() ? fallback : trimmed;
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }

        private static String defaultBody() {
            return """
                **{player}** {action} **{server}**

                - UUID: `{uuid}`
                - Builder: **{builder}**
                - Operator: **{operator}**
                - World: `{world}`
                - Position: `{position}`
                - Address: `{address}`
                - Online: `{online}/{max_players}`
                - Builder list: `{builder_names} names, {builder_uuids} UUIDs`
                - Time: `{time}`
                """;
        }
    }
}
