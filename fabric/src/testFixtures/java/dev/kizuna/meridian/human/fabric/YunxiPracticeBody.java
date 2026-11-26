package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** 通过人体宿主的开脉、升境和库存入口准备试剑者。 */
public final class YunxiPracticeBody {
    private static final int TICK_MILLIS = 50;
    private static final double STORED_QI = 100;
    private static final double GAS_DENSITY = 0.1;
    public static final String FOOT = "left_stomach_meridian";
    public static final List<String> HANDS = List.of("left_lung_meridian", "right_large_intestine_meridian");

    private YunxiPracticeBody() { }

    public static void practitioner(ServerPlayer player) {
        // 只开需要的两手一足，其他经脉保持出生状态。
        ascend(player);
        HANDS.forEach(id -> open(player, id));
        open(player, FOOT);
        fill(player);
    }

    public static void ascend(ServerPlayer player) {
        HumanTechniqueHost.state().find(player.getUUID()).ascend(now(player));
    }

    public static void open(ServerPlayer player, String meridian) {
        var body = HumanTechniqueHost.state().find(player.getUUID());
        if (body.completeOpening(now(player), meridian) != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            throw new IllegalStateException("未能开脉 " + meridian);
        }
    }

    public static void empty(ServerPlayer player) {
        inventory(player, 0);
    }

    public static void fill(ServerPlayer player) {
        inventory(player, STORED_QI);
    }

    private static void inventory(ServerPlayer player, double amount) {
        var request = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setDensity(GAS_DENSITY)
                .setAmount(amount)
                .build();
        if (HumanDebugHost.debugQi(player, request) != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            throw new IllegalStateException("未能填充试剑者的真元");
        }
    }

    private static long now(ServerPlayer player) {
        return player.level().getServer().overworld().getGameTime() * TICK_MILLIS;
    }
}
