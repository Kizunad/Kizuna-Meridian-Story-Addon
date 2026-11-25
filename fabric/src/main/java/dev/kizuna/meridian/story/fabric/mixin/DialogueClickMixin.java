package dev.kizuna.meridian.story.fabric.mixin;

import dev.kizuna.meridian.story.fabric.StoryChat;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class DialogueClickMixin {
    // 原版在此之前已切到服务端线程，转发给 MinecraftServer 后则不再携带连接身份。
    @Inject(method = "handleCustomClickAction", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction("
                    + "Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"))
    private void meridianStory$choose(ServerboundCustomClickActionPacket packet, CallbackInfo callback) {
        if ((Object) this instanceof ServerGamePacketListenerImpl play && StoryChat.clicked(play.player, packet)) {
            callback.cancel();
        }
    }
}
