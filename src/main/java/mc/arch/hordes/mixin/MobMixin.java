package mc.arch.hordes.mixin;

import mc.arch.hordes.HordeManager;
import mc.arch.hordes.Hordes;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The natural-spawn hook. Fabric API has no spawn-reason event, so this mixes into
 * Mob.finalizeSpawn and filters to NATURAL. Extras spawn with EntitySpawnReason.EVENT, so they
 * never re-enter this handler and cannot cascade.
 */
@Mixin(Mob.class)
public abstract class MobMixin {

    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void hordeevents$boostNaturalSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            EntitySpawnReason reason,
            SpawnGroupData data,
            CallbackInfoReturnable<SpawnGroupData> cir) {
        if (reason != EntitySpawnReason.NATURAL) {
            return;
        }
        HordeManager manager = Hordes.manager;
        if (manager == null) {
            return;
        }
        Mob self = (Mob) (Object) this;
        manager.spawnExtra(level.getLevel(), self.blockPosition(), self.getType());
    }
}
