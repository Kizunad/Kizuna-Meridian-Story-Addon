package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.HumanDebugHost;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** 服务端与集成客户端从同样的未开脉、零真元身体读卷和出手。 */
public final class YunxiMortal {
    private static final double FREE_DENSITY = 0.01;

    private YunxiMortal() { }

    public static void emptyQi(ServerPlayer player) {
        var lumen = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setAmount(0)
                .setDensity(FREE_DENSITY)
                .build();
        var wall = lumen.toBuilder().setWall(true /* wall */).build();
        for (var request : List.of(lumen, wall)) {
            var status = HumanDebugHost.debugQi(player, request);
            if (status != FailureCode.FAILURE_CODE_UNSPECIFIED) {
                throw new IllegalStateException("清空经脉库存失败 " + status);
            }
        }
        requireMortal(player);
    }

    public static void requireMortal(ServerPlayer player) {
        var body = new StoryBodies().read(player).orElseThrow();
        var channels = body.getMeridianStatesList();
        if (channels.isEmpty() || !channels.stream().allMatch(state ->
                state.getOpening().getStage() == MeridianOpeningStage.UNOPENED
                        && state.getLumenQi().getEntriesCount() == 0 && state.getWallQi().getEntriesCount() == 0)) {
            throw new IllegalStateException("身体须未开脉且内腔、外壁均无真元");
        }
    }
}
