package dev.kizuna.meridian.story.fabric.client.mixin;

import dev.kizuna.meridian.story.fabric.client.YunxiRecording;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class RecordingCameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void pinSideView(float partialTick, CallbackInfo info) {
        // 固定侧视机位；镜头实体仍是本地玩家，可以走动和施放。
        var view = YunxiRecording.camera();
        if (view != null) {
            setRotation(view.yaw(), view.pitch());
            setPosition(view.x(), view.y(), view.z());
        }
    }
}
