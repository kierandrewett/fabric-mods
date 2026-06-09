package sh.kier.mc.bingusify.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public interface ServerEntityTrackerAccessor {
    @Invoker("stopTracking")
    void skin_tools$stopTracking(ServerPlayerEntity player);

    @Invoker("updateTrackedStatus")
    void skin_tools$updateTrackedStatus(ServerPlayerEntity player);
}
