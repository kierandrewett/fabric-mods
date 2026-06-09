package sh.kier.mc.bingusify;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.google.common.collect.ArrayListMultimap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.kier.mc.bingusify.mixin.PlayerEntityAccessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class SkinToolsMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("skin_tools");
    private static final String TEXTURES = "textures";

    private static boolean globalSkinEnabled = false;
    private static List<Property> globalSkinTextures = List.of();
    private static final Map<UUID, List<Property>> originalSkins = new HashMap<>();
    private static final Map<UUID, List<Property>> personalSkins = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            rememberOriginalSkin(player);
            if (hasEffectiveOverride(player)) {
                applyEffectiveSkin(player);
                refreshPlayerList(server, List.of(player));
            }
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("globalskin")
            .requires(SkinToolsMod::canUseGlobalSkin)
            .then(literal("set")
                .then(argument("username", StringArgumentType.word())
                    .executes(context -> setGlobalSkin(
                        context.getSource(),
                        StringArgumentType.getString(context, "username")
                    ))))
            .then(literal("clear")
                .executes(context -> clearGlobalSkin(context.getSource()))));

        dispatcher.register(literal("skin")
            .then(literal("set")
                .then(argument("username", StringArgumentType.word())
                    .executes(context -> setPersonalSkin(
                        context.getSource(),
                        StringArgumentType.getString(context, "username")
                    ))))
            .then(literal("clear")
                .executes(context -> clearPersonalSkin(context.getSource()))));
    }

    private static int setGlobalSkin(ServerCommandSource source, String username) {
        List<Property> textures = resolveSkinTextures(source, username);
        if (textures.isEmpty()) {
            source.sendError(Text.literal("Could not find a skin for " + username + ". Check the username and try again."));
            return 0;
        }

        globalSkinEnabled = true;
        globalSkinTextures = textures;

        MinecraftServer server = source.getServer();
        try {
            for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
                rememberOriginalSkin(onlinePlayer);
                applyEffectiveSkin(onlinePlayer);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to apply global skin from {}", username, exception);
            source.sendError(Text.literal("Failed to apply the global skin. Check the server logs for details."));
            return 0;
        }
        refreshPlayerList(server, server.getPlayerManager().getPlayerList());

        source.sendFeedback(() -> Text.literal("Global skin set to " + username + "."), true);
        return 1;
    }

    private static int clearGlobalSkin(ServerCommandSource source) {
        if (!globalSkinEnabled) {
            source.sendFeedback(() -> Text.literal("No global skin is currently set."), false);
            return 0;
        }

        globalSkinEnabled = false;
        globalSkinTextures = List.of();

        MinecraftServer server = source.getServer();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            applyEffectiveSkin(player);
        }
        refreshPlayerList(server, server.getPlayerManager().getPlayerList());

        source.sendFeedback(() -> Text.literal("Global skin cleared."), true);
        return 1;
    }

    private static int setPersonalSkin(ServerCommandSource source, String username) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        List<Property> textures = resolveSkinTextures(source, username);
        if (textures.isEmpty()) {
            source.sendError(Text.literal("Could not find a skin for " + username + ". Check the username and try again."));
            return 0;
        }

        try {
            rememberOriginalSkin(player);
            personalSkins.put(player.getUuid(), textures);
            applyEffectiveSkin(player);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to apply personal skin from {} to {}", username, player.getName().getString(), exception);
            source.sendError(Text.literal("Failed to apply that skin. Check the server logs for details."));
            return 0;
        }
        refreshPlayerList(source.getServer(), List.of(player));

        source.sendFeedback(() -> Text.literal("Your skin has been set to " + username + "."), false);
        return 1;
    }

    private static int clearPersonalSkin(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (personalSkins.remove(player.getUuid()) == null) {
            source.sendFeedback(() -> Text.literal("You do not have a personal skin override."), false);
            return 0;
        }

        applyEffectiveSkin(player);
        refreshPlayerList(source.getServer(), List.of(player));

        source.sendFeedback(() -> Text.literal("Your personal skin override has been cleared."), false);
        return 1;
    }

    private static void applyEffectiveSkin(ServerPlayerEntity player) {
        if (globalSkinEnabled && !globalSkinTextures.isEmpty()) {
            setTextures(player, globalSkinTextures);
            return;
        }

        List<Property> personal = personalSkins.get(player.getUuid());
        if (personal != null) {
            setTextures(player, personal);
            return;
        }

        List<Property> original = originalSkins.get(player.getUuid());
        if (original != null) {
            setTextures(player, original);
        }
    }

    private static boolean hasEffectiveOverride(ServerPlayerEntity player) {
        return (globalSkinEnabled && !globalSkinTextures.isEmpty()) || personalSkins.containsKey(player.getUuid());
    }

    private static void rememberOriginalSkin(ServerPlayerEntity player) {
        originalSkins.computeIfAbsent(player.getUuid(), ignored -> copyTextures(player.getGameProfile()));
    }

    private static List<Property> copyTextures(GameProfile profile) {
        return new ArrayList<>(profile.properties().get(TEXTURES));
    }

    private static List<Property> resolveSkinTextures(ServerCommandSource source, String username) {
        MinecraftServer server = source.getServer();
        ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(username);
        if (onlinePlayer != null) {
            List<Property> textures = copyTextures(onlinePlayer.getGameProfile());
            if (!textures.isEmpty()) {
                return textures;
            }
        }

        try {
            return server.getApiServices()
                .profileRepository()
                .findProfileByName(username)
                .map(profile -> fetchSkinTextures(server, profile.id()))
                .orElse(List.of());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to resolve skin profile for {}", username, exception);
            source.sendError(Text.literal("Could not look up " + username + "'s skin right now."));
            return List.of();
        }
    }

    private static List<Property> fetchSkinTextures(MinecraftServer server, UUID uuid) {
        ProfileResult result = server.getApiServices().sessionService().fetchProfile(uuid, true);
        if (result == null) {
            return List.of();
        }

        return copyTextures(result.profile());
    }

    private static void setTextures(ServerPlayerEntity player, Collection<Property> textures) {
        GameProfile profile = player.getGameProfile();
        PropertyMap properties = new PropertyMap(ArrayListMultimap.create());
        for (Map.Entry<String, Property> property : profile.properties().entries()) {
            if (!TEXTURES.equals(property.getKey())) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        for (Property texture : textures) {
            properties.put(TEXTURES, texture);
        }

        ((PlayerEntityAccessor) player).skin_tools$setGameProfile(new GameProfile(profile.id(), profile.name(), properties));
    }

    private static boolean canUseGlobalSkin(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return true;
        }

        OperatorEntry entry = source.getServer()
            .getPlayerManager()
            .getOpList()
            .get(new PlayerConfigEntry(player.getGameProfile()));

        return entry != null && entry.getLevel().getLevel().isAtLeast(PermissionLevel.OWNERS);
    }

    private static void refreshPlayerList(MinecraftServer server, Collection<ServerPlayerEntity> changedPlayers) {
        PlayerManager playerManager = server.getPlayerManager();
        PlayerListS2CPacket packet = new PlayerListS2CPacket(
            EnumSet.of(
                PlayerListS2CPacket.Action.ADD_PLAYER,
                PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
                PlayerListS2CPacket.Action.UPDATE_LISTED
            ),
            changedPlayers
        );

        for (ServerPlayerEntity viewer : playerManager.getPlayerList()) {
            viewer.networkHandler.sendPacket(packet);
        }
    }
}
