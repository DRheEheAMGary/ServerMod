package cn.dreamgary.hubsuite.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code DistanceManager.playersPerChunk} 是 private；用它做空值防护。 */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {

    @Accessor("playersPerChunk")
    Long2ObjectMap<ObjectSet<ServerPlayer>> hubsuite$playersPerChunk();
}
