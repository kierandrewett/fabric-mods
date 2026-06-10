package sh.kier.mc.kierworlds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class KierWorldsMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("kier_worlds");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String NAMESPACE = "kier_worlds";
    private static final Pattern WORLD_NAME = Pattern.compile("[a-z0-9_][a-z0-9_\\-/]{0,63}");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(commandRoot("kworld"));
            dispatcher.register(commandRoot("world"));
            dispatcher.register(commandRoot("worlds"));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> commandRoot(String name) {
        return literal(name)
                .requires(KierWorldsMod::canManageWorlds)
                .then(literal("list")
                    .executes(context -> listWorlds(context.getSource())))
                .then(literal("here")
                    .executes(context -> showCurrentWorldInfo(context.getSource())))
                .then(literal("current")
                    .executes(context -> showCurrentWorldInfo(context.getSource())))
                .then(literal("info")
                    .then(argument("name", IdentifierArgumentType.identifier())
                        .suggests((context, builder) -> suggestWorlds(context.getSource(), builder))
                        .executes(context -> showWorldInfo(
                            context.getSource(),
                            input(context, "name")
                        ))))
                .then(literal("create")
                    .then(argument("name", IdentifierArgumentType.identifier())
                        .executes(context -> createWorld(
                            context.getSource(),
                            input(context, "name"),
                            "vanilla",
                            null
                        ))
                        .then(argument("template", StringArgumentType.string())
                            .suggests((context, builder) -> suggestTemplates(builder))
                            .executes(context -> createWorld(
                                context.getSource(),
                                input(context, "name"),
                                StringArgumentType.getString(context, "template"),
                                null
                            ))
                            .then(argument("seed", LongArgumentType.longArg())
                                .executes(context -> createWorld(
                                    context.getSource(),
                                    input(context, "name"),
                                    StringArgumentType.getString(context, "template"),
                                    LongArgumentType.getLong(context, "seed")
                                ))))))
                .then(literal("tp")
                    .then(argument("name", IdentifierArgumentType.identifier())
                        .suggests((context, builder) -> suggestWorlds(context.getSource(), builder))
                        .executes(context -> teleport(
                            context.getSource(),
                            input(context, "name")
                        ))
                        .then(argument("pos", Vec3ArgumentType.vec3())
                            .executes(context -> teleport(
                                context.getSource(),
                                input(context, "name"),
                                Vec3ArgumentType.getVec3(context, "pos")
                            ))
                            .then(argument("player", EntityArgumentType.player())
                                .executes(context -> teleport(
                                    context.getSource(),
                                    input(context, "name"),
                                    Vec3ArgumentType.getVec3(context, "pos"),
                                    EntityArgumentType.getPlayer(context, "player")
                                ))))
                        .then(argument("player", EntityArgumentType.player())
                            .executes(context -> teleport(
                                context.getSource(),
                                input(context, "name"),
                                EntityArgumentType.getPlayer(context, "player")
                            )))))
                .then(literal("setspawn")
                    .then(argument("name", IdentifierArgumentType.identifier())
                        .suggests((context, builder) -> suggestWorlds(context.getSource(), builder))
                        .executes(context -> setSpawn(
                            context.getSource(),
                            input(context, "name")
                        ))))
                .then(literal("delete")
                    .then(argument("name", IdentifierArgumentType.identifier())
                        .suggests((context, builder) -> suggestManagedWorldNames(context.getSource(), builder))
                        .executes(context -> requestDeleteWorld(
                            context.getSource(),
                            input(context, "name")
                        ))
                        .then(literal("confirm")
                            .executes(context -> deleteWorld(
                                context.getSource(),
                                input(context, "name")
                            )))))
                .then(literal("templates")
                    .executes(context -> listTemplates(context.getSource())));
    }

    private static String input(CommandContext<ServerCommandSource> context, String name) {
        return context.getNodes().stream()
            .filter(node -> node.getNode().getName().equals(name))
            .findFirst()
            .map(node -> node.getRange().get(context.getInput()))
            .orElseGet(() -> IdentifierArgumentType.getIdentifier(context, name).toString());
    }

    private static int createWorld(ServerCommandSource source, String rawName, String template, Long seed) {
        String name = normalizeWorldName(rawName);
        if (name == null) {
            source.sendError(Text.literal("World names must be lowercase letters, numbers, _, -, or /."));
            return 0;
        }

        Path dimensionFile = dimensionFile(source.getServer(), name);
        if (Files.exists(dimensionFile)) {
            source.sendError(Text.literal("World '" + name + "' already exists in the Kier Worlds datapack."));
            return 0;
        }

        try {
            writePackMetadata(source.getServer());
            Files.createDirectories(dimensionFile.getParent());
            Files.writeString(
                dimensionFile,
                GSON.toJson(dimensionJson(template, seed)) + "\n",
                StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal(exception.getMessage()));
            return 0;
        } catch (IOException exception) {
            LOGGER.error("Failed to create world {}", name, exception);
            source.sendError(Text.literal("Failed to write world datapack file: " + exception.getMessage()));
            return 0;
        }

        source.sendFeedback(
            () -> Text.literal("Created world '" + name + "' using template '" + template + "'. Restart required. ")
                .append(action("[info]", "/kworld info " + name, "Inspect " + NAMESPACE + ":" + name, Formatting.YELLOW))
                .append(Text.literal(" "))
                .append(action("[tp after restart]", "/kworld tp " + name, "Teleport after the server has restarted", Formatting.GRAY)),
            true
        );
        return 1;
    }

    private static boolean canManageWorlds(ServerCommandSource source) {
        return source.getPermissions() instanceof LeveledPermissionPredicate permissions
            && permissions.getLevel().isAtLeast(PermissionLevel.GAMEMASTERS);
    }

    private static int teleport(ServerCommandSource source, String rawName) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("Only players can use /kworld tp unless a target player is specified."));
            return 0;
        }

        return teleport(source, rawName, player);
    }

    private static int teleport(ServerCommandSource source, String rawName, Vec3d pos) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("Only players can use /kworld tp <world> <pos> unless a target player is specified."));
            return 0;
        }

        return teleport(source, rawName, pos, player);
    }

    private static int teleport(ServerCommandSource source, String rawName, ServerPlayerEntity player) {
        RegistryKey<World> key = worldKeyForInput(rawName);
        if (key == null) {
            source.sendError(Text.literal("Invalid world name or identifier."));
            return 0;
        }

        ServerWorld world = source.getServer().getWorld(key);
        if (world == null) {
            source.sendError(Text.literal("World '" + rawName + "' is not loaded. Create it first, then restart the server."));
            return 0;
        }

        teleportToWorldSpawn(player, world);
        source.sendFeedback(() -> Text.literal("Teleported " + player.getName().getString() + " to " + key.getValue() + "."), false);
        return 1;
    }

    private static int teleport(ServerCommandSource source, String rawName, Vec3d pos, ServerPlayerEntity player) {
        RegistryKey<World> key = worldKeyForInput(rawName);
        if (key == null) {
            source.sendError(Text.literal("Invalid world name or identifier."));
            return 0;
        }

        ServerWorld world = source.getServer().getWorld(key);
        if (world == null) {
            source.sendError(Text.literal("World '" + rawName + "' is not loaded. Create it first, then restart the server."));
            return 0;
        }

        player.teleport(
            world,
            pos.x,
            pos.y,
            pos.z,
            Set.of(),
            player.getYaw(),
            player.getPitch(),
            true
        );
        source.sendFeedback(
            () -> Text.literal("Teleported " + player.getName().getString() + " to " + key.getValue() + " at " + formatPosition(pos) + "."),
            false
        );
        return 1;
    }

    private static int setSpawn(ServerCommandSource source, String rawName) {
        RegistryKey<World> key = worldKeyForInput(rawName);
        if (key == null) {
            source.sendError(Text.literal("Invalid world name or identifier."));
            return 0;
        }

        ServerWorld world = source.getServer().getWorld(key);
        if (world == null) {
            source.sendError(Text.literal("World '" + rawName + "' is not loaded."));
            return 0;
        }

        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("Only players can use /kworld setspawn."));
            return 0;
        }

        if (!player.getEntityWorld().getRegistryKey().equals(key)) {
            source.sendError(Text.literal("Stand in " + key.getValue() + " before setting its spawn."));
            return 0;
        }

        world.setSpawnPoint(WorldProperties.SpawnPoint.create(key, player.getBlockPos(), player.getYaw(), player.getPitch()));
        source.sendFeedback(() -> Text.literal("Set spawn for " + key.getValue() + "."), true);
        return 1;
    }

    private static int showCurrentWorldInfo(ServerCommandSource source) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("Only players can use /kworld here."));
            return 0;
        }

        RegistryKey<World> key = player.getEntityWorld().getRegistryKey();
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        String id = key.getValue().toString();
        String commandTarget = id.startsWith(NAMESPACE + ":") ? id.substring((NAMESPACE + ":").length()) : id;

        source.sendFeedback(() -> Text.literal("You are in " + id + " at " + formatPosition(pos)).formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("Actions: ")
            .append(action("[info]", "/kworld info " + commandTarget, "Show info for " + id, Formatting.YELLOW))
            .append(Text.literal(" "))
            .append(action("[setspawn]", "/kworld setspawn " + commandTarget, "Set this world's spawn to your current position", Formatting.AQUA))
            .append(Text.literal(" "))
            .append(copyAction("[copy tp]", "/kworld tp " + commandTarget + " " + formatPosition(pos), "Copy a teleport command for this position", Formatting.GREEN)), false);
        return 1;
    }

    private static void teleportToWorldSpawn(ServerPlayerEntity player, ServerWorld world) {
        BlockPos spawn = world.getSpawnPoint().getPos();
        player.teleport(
            world,
            spawn.getX() + 0.5,
            spawn.getY(),
            spawn.getZ() + 0.5,
            Set.of(),
            player.getYaw(),
            player.getPitch(),
            true
        );
    }

    private static int requestDeleteWorld(ServerCommandSource source, String rawName) {
        String name = normalizeWorldName(rawName);
        if (name == null) {
            source.sendError(Text.literal("Invalid world name."));
            return 0;
        }

        Path dimensionFile = dimensionFile(source.getServer(), name);
        if (!Files.exists(dimensionFile)) {
            source.sendError(Text.literal("No managed world named '" + name + "' exists."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Delete managed world definition '" + name + "'? Region data remains on disk. ")
            .formatted(Formatting.RED)
            .append(action("[confirm]", "/kworld delete " + name + " confirm", "Delete the managed dimension JSON for " + name, Formatting.RED))
            .append(Text.literal(" "))
            .append(action("[info]", "/kworld info " + name, "Inspect before deleting", Formatting.YELLOW)), false);
        return 1;
    }

    private static int deleteWorld(ServerCommandSource source, String rawName) {
        String name = normalizeWorldName(rawName);
        if (name == null) {
            source.sendError(Text.literal("Invalid world name."));
            return 0;
        }

        Path dimensionFile = dimensionFile(source.getServer(), name);
        if (!Files.exists(dimensionFile)) {
            source.sendError(Text.literal("No managed world named '" + name + "' exists."));
            return 0;
        }

        try {
            Files.delete(dimensionFile);
        } catch (IOException exception) {
            LOGGER.error("Failed to delete world {}", name, exception);
            source.sendError(Text.literal("Failed to delete dimension file: " + exception.getMessage()));
            return 0;
        }

        source.sendFeedback(
            () -> Text.literal("Removed datapack definition for '" + name + "'. Restart the server. Region data is left on disk. ")
                .append(action("[list]", "/kworld list", "Show worlds", Formatting.YELLOW)),
            true
        );
        return 1;
    }

    private static int listWorlds(ServerCommandSource source) {
        List<String> managed = managedWorlds(source.getServer());
        List<String> loaded = source.getServer().getWorldRegistryKeys().stream()
            .map(key -> key.getValue().toString())
            .sorted()
            .toList();

        source.sendFeedback(() -> Text.literal("Loaded worlds").formatted(Formatting.GOLD), false);
        if (loaded.isEmpty()) {
            source.sendFeedback(() -> Text.literal(" - (none)").formatted(Formatting.GRAY), false);
        } else {
            for (String id : loaded) {
                boolean managedByKier = id.startsWith(NAMESPACE + ":");
                String commandTarget = managedByKier ? id.substring((NAMESPACE + ":").length()) : id;
                source.sendFeedback(() -> worldListLine(id, commandTarget, true, managedByKier), false);
            }
        }

        source.sendFeedback(() -> Text.literal("Managed Kier worlds").formatted(Formatting.GOLD), false);
        if (managed.isEmpty()) {
            source.sendFeedback(() -> Text.literal(" - (none)").formatted(Formatting.GRAY), false);
        } else {
            for (String name : managed) {
                boolean loadedNow = source.getServer().getWorld(worldKey(name)) != null;
                source.sendFeedback(() -> worldListLine(NAMESPACE + ":" + name, name, loadedNow, true), false);
            }
        }
        return managed.size();
    }

    private static int showWorldInfo(ServerCommandSource source, String rawName) {
        RegistryKey<World> key = worldKeyForInput(rawName);
        if (key == null) {
            source.sendError(Text.literal("Invalid world name or identifier."));
            return 0;
        }

        ServerWorld world = source.getServer().getWorld(key);
        Path managedFile = managedFileForInput(source.getServer(), rawName);

        source.sendFeedback(() -> Text.literal("World: " + key.getValue()), false);
        source.sendFeedback(() -> Text.literal("Loaded: " + (world == null ? "no" : "yes")), false);
        if (world != null) {
            BlockPos spawn = world.getSpawnPoint().getPos();
            source.sendFeedback(() -> Text.literal("Spawn: " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ()), false);
            String commandTarget = rawName.contains(":") ? key.getValue().toString() : rawName;
            source.sendFeedback(() -> Text.literal("Actions: ")
                .append(action("[tp]", "/kworld tp " + commandTarget, "Teleport to " + key.getValue(), Formatting.AQUA))
                .append(Text.literal(" "))
                .append(action("[setspawn]", "/kworld setspawn " + commandTarget, "Set this world's spawn to your current position", Formatting.YELLOW)), false);
        }

        if (managedFile != null && Files.exists(managedFile)) {
            source.sendFeedback(() -> Text.literal("Managed file: " + managedFile), false);
            describeDimensionFile(source, managedFile);
        } else {
            source.sendFeedback(() -> Text.literal("Managed by Kier Worlds: no"), false);
        }
        return 1;
    }

    private static void describeDimensionFile(ServerCommandSource source, Path path) {
        try {
            JsonObject dimension = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject generator = dimension.getAsJsonObject("generator");
            if (generator == null) {
                return;
            }

            String generatorType = stringProperty(generator, "type", "unknown");
            String settings = stringProperty(generator, "settings", "default");
            String preset = "default";
            JsonObject biomeSource = generator.getAsJsonObject("biome_source");
            if (biomeSource != null) {
                preset = stringProperty(biomeSource, "preset", "default");
            }

            String description = "Generator: " + generatorType + ", settings: " + settings + ", biome preset: " + preset;
            source.sendFeedback(() -> Text.literal(description), false);
        } catch (Exception exception) {
            source.sendFeedback(() -> Text.literal("Could not parse managed dimension JSON: " + exception.getMessage()), false);
        }
    }

    private static MutableText worldListLine(String id, String commandTarget, boolean loaded, boolean managed) {
        Formatting statusColor = loaded ? Formatting.GREEN : Formatting.RED;
        String status = loaded ? "loaded" : "restart needed";
        MutableText line = Text.literal(" - ").formatted(Formatting.DARK_GRAY)
            .append(action(id, "/kworld tp " + commandTarget, "Teleport to " + id, loaded ? Formatting.AQUA : Formatting.GRAY))
            .append(Text.literal(" [" + status + "]").formatted(statusColor));

        if (managed) {
            line.append(Text.literal(" [managed]").formatted(Formatting.DARK_AQUA));
        }

        line.append(Text.literal(" "))
            .append(action("[tp]", "/kworld tp " + commandTarget, "Teleport to " + id, loaded ? Formatting.AQUA : Formatting.GRAY))
            .append(Text.literal(" "))
            .append(action("[info]", "/kworld info " + commandTarget, "Show info for " + id, Formatting.YELLOW));

        if (managed) {
            line.append(Text.literal(" "))
                .append(action("[delete]", "/kworld delete " + commandTarget, "Ask for delete confirmation", Formatting.RED));
        }

        return line;
    }

    private static MutableText action(String label, String command, String hover, Formatting color) {
        return Text.literal(label)
            .styled(style -> style
                .withColor(color)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover + "\n" + command).formatted(Formatting.GRAY)))
                .withInsertion(command));
    }

    private static MutableText suggestAction(String label, String command, String hover, Formatting color) {
        return Text.literal(label)
            .styled(style -> style
                .withColor(color)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover + "\n" + command).formatted(Formatting.GRAY)))
                .withInsertion(command));
    }

    private static MutableText copyAction(String label, String value, String hover, Formatting color) {
        return Text.literal(label)
            .styled(style -> style
                .withColor(color)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(value))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover + "\n" + value).formatted(Formatting.GRAY)))
                .withInsertion(value));
    }

    private static int listTemplates(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("World templates").formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> templateLine("vanilla", "Normal overworld generation"), false);
        source.sendFeedback(() -> templateLine("amplified", "Amplified terrain"), false);
        source.sendFeedback(() -> templateLine("large_biomes", "Large biome overworld generation"), false);
        source.sendFeedback(() -> templateLine("flat", "Flat creative/test world"), false);
        source.sendFeedback(() -> templateLine("custom:minecraft:overworld:minecraft:overworld", "Custom registry IDs: custom:<settings_ns>:<settings_path>:<preset_ns>:<preset_path>"), false);
        source.sendFeedback(() -> Text.literal("Click [use] to prefill /kworld create <name> with that template.").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static MutableText templateLine(String template, String description) {
        String commandTemplate = template.contains(":") ? "\"" + template + "\"" : template;
        return Text.literal(" - ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal(template).formatted(Formatting.AQUA))
            .append(Text.literal(" "))
            .append(suggestAction("[use]", "/kworld create new_world " + commandTemplate, description, Formatting.GREEN))
            .append(Text.literal(" "))
            .append(copyAction("[copy]", template, "Copy template value", Formatting.GRAY))
            .append(Text.literal(" - " + description).formatted(Formatting.DARK_GRAY));
    }

    private static CompletableFuture<Suggestions> suggestTemplates(SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(List.of(
            "vanilla",
            "amplified",
            "large_biomes",
            "flat",
            "\"custom:minecraft:overworld:minecraft:overworld\""
        ), builder);
    }

    private static CompletableFuture<Suggestions> suggestWorlds(ServerCommandSource source, SuggestionsBuilder builder) {
        Stream<String> loaded = source.getServer().getWorldRegistryKeys().stream()
            .map(key -> key.getValue().toString());
        Stream<String> managedShortNames = managedWorlds(source.getServer()).stream();
        return CommandSource.suggestMatching(Stream.concat(loaded, managedShortNames).distinct(), builder);
    }

    private static CompletableFuture<Suggestions> suggestManagedWorldNames(ServerCommandSource source, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(managedWorlds(source.getServer()), builder);
    }

    private static JsonObject dimensionJson(String template, Long seed) {
        String normalized = template.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("raw:")) {
            JsonElement parsed = JsonParser.parseString(template.substring("raw:".length()));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("raw template must be a JSON object.");
            }
            return parsed.getAsJsonObject();
        }

        return switch (normalized) {
            case "vanilla" -> noiseDimension("minecraft:overworld", "minecraft:overworld", seed);
            case "amplified" -> noiseDimension("minecraft:amplified", "minecraft:overworld", seed);
            case "large_biomes" -> noiseDimension("minecraft:large_biomes", "minecraft:overworld", seed);
            case "flat" -> flatDimension();
            default -> {
                if (!normalized.startsWith("custom:")) {
                    throw new IllegalArgumentException("Unknown template '" + template + "'. Use /kworld templates.");
                }

                String[] parts = template.substring("custom:".length()).split(":", 4);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("custom template must be custom:<settings_namespace>:<settings_path>:<preset_namespace>:<preset_path>.");
                }

                yield noiseDimension(parts[0] + ":" + parts[1], parts[2] + ":" + parts[3], seed);
            }
        };
    }

    private static JsonObject noiseDimension(String settings, String biomePreset, Long seed) {
        JsonObject dimension = new JsonObject();
        dimension.addProperty("type", "minecraft:overworld");

        JsonObject generator = new JsonObject();
        generator.addProperty("type", "minecraft:noise");
        if (seed != null) {
            generator.addProperty("seed", seed);
        }
        generator.addProperty("settings", settings);

        JsonObject biomeSource = new JsonObject();
        biomeSource.addProperty("type", "minecraft:multi_noise");
        biomeSource.addProperty("preset", biomePreset);
        generator.add("biome_source", biomeSource);

        dimension.add("generator", generator);
        return dimension;
    }

    private static JsonObject flatDimension() {
        JsonObject dimension = new JsonObject();
        dimension.addProperty("type", "minecraft:overworld");

        JsonObject generator = new JsonObject();
        generator.addProperty("type", "minecraft:flat");

        JsonObject settings = new JsonObject();
        settings.addProperty("biome", "minecraft:plains");
        settings.addProperty("features", false);
        settings.addProperty("lakes", false);

        JsonArray layers = new JsonArray();
        layers.add(flatLayer("minecraft:bedrock", 1));
        layers.add(flatLayer("minecraft:dirt", 2));
        layers.add(flatLayer("minecraft:grass_block", 1));
        settings.add("layers", layers);

        generator.add("settings", settings);
        dimension.add("generator", generator);
        return dimension;
    }

    private static JsonObject flatLayer(String block, int height) {
        JsonObject layer = new JsonObject();
        layer.addProperty("block", block);
        layer.addProperty("height", height);
        return layer;
    }

    private static Path datapackRoot(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.DATAPACKS).resolve("kier-worlds");
    }

    private static Path dimensionFile(MinecraftServer server, String name) {
        return datapackRoot(server).resolve("data").resolve(NAMESPACE).resolve("dimension").resolve(name + ".json");
    }

    private static Path managedFileForInput(MinecraftServer server, String rawName) {
        String name = rawName.contains(":") ? null : normalizeWorldName(rawName);
        if (name == null) {
            return null;
        }
        return dimensionFile(server, name);
    }

    private static void writePackMetadata(MinecraftServer server) throws IOException {
        Path root = datapackRoot(server);
        Files.createDirectories(root);

        JsonObject metadata = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 80);
        pack.addProperty("description", "Kier Worlds managed dimensions");

        JsonObject supportedFormats = new JsonObject();
        supportedFormats.addProperty("min_inclusive", 0);
        supportedFormats.addProperty("max_inclusive", 999);
        pack.add("supported_formats", supportedFormats);
        metadata.add("pack", pack);

        Files.writeString(root.resolve("pack.mcmeta"), GSON.toJson(metadata) + "\n", StandardCharsets.UTF_8);
    }

    private static List<String> managedWorlds(MinecraftServer server) {
        Path root = datapackRoot(server).resolve("data").resolve(NAMESPACE).resolve("dimension");
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(path -> path.toString().endsWith(".json"))
                .map(root::relativize)
                .map(path -> path.toString().replace('\\', '/').replaceFirst("\\.json$", ""))
                .sorted(Comparator.naturalOrder())
                .toList();
        } catch (IOException exception) {
            LOGGER.error("Failed to list managed worlds", exception);
            return List.of();
        }
    }

    private static RegistryKey<World> worldKey(String name) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(NAMESPACE, name));
    }

    private static RegistryKey<World> worldKeyForInput(String rawName) {
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            Identifier identifier = Identifier.tryParse(normalized);
            if (identifier == null) {
                return null;
            }
            return RegistryKey.of(RegistryKeys.WORLD, identifier);
        }

        String managedName = normalizeWorldName(normalized);
        return managedName == null ? null : worldKey(managedName);
    }

    private static String stringProperty(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null ? fallback : value.getAsString();
    }

    private static String formatPosition(Vec3d pos) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }

    private static String normalizeWorldName(String rawName) {
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (!WORLD_NAME.matcher(normalized).matches() || normalized.contains("..")) {
            return null;
        }
        return normalized;
    }

}
