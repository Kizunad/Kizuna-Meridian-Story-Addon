package dev.kizuna.meridian.story.fabric.mixin;

import dev.kizuna.meridian.story.fabric.GrittedCounter;
import java.util.Collection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class CounterEffectMixin {
    @Inject(method = "onEffectsRemoved", at = @At("TAIL"))
    private void meridianStory$removed(Collection<MobEffectInstance> removed, CallbackInfo callback) {
        // 只有服务端玩家持有出身体质。
        if ((Object) this instanceof ServerPlayer player) {
            GrittedCounter.removed(player, removed);
        }
    }
}
