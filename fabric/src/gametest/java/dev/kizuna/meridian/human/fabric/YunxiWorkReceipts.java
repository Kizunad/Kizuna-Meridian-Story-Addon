package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.TissueFatigueIncrease;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** 读取玩家身体已经提交的做功命令，供剧情招式核对出手次数和肌肉剂量。 */
public final class YunxiWorkReceipts {
    private static final double FEE_TOLERANCE = 1e-8;

    private YunxiWorkReceipts() { }

    public static void checkLongAdvance(GameTestHelper test, ServerPlayer player, long limit, double holdCost) {
        // 两份人体从同一份已成架快照推进；长推进应在截止点释放肌肉，再按普通状态恢复。
        var original = HumanTechniqueHost.state().find(player.getUUID());
        var snapshot = original.snapshot();
        var receipts = original.receipts();
        var released = original.releasedQi();
        var single = new HumanPlayerBody(snapshot, receipts, released);
        var split = new HumanPlayerBody(snapshot, receipts, released);
        long end = snapshot.settledAt().elapsedMillis() + limit;
        long later = end + limit;
        single.settle(later);
        split.settle(end);
        double before = HumanStamina.from(snapshot).availableJoules();
        double atEnd = HumanStamina.from(split.snapshot()).availableJoules();
        double charged = before - atEnd;
        test.assertTrue(Math.abs(charged - holdCost) <= FEE_TOLERANCE, "长推进持守费错误 " + charged);
        split.settle(later);
        var singleAtEnd = single.snapshot();
        var splitAtEnd = split.snapshot();
        var active = TechniqueState.read(singleAtEnd).getActiveCast();
        test.assertTrue(active.getActiveEndMillis() == end && active.getSustained().getStaminaPaidMillis() == end
                && singleAtEnd.actions().equals(splitAtEnd.actions())
                && singleAtEnd.tissueStates().equals(splitAtEnd.tissueStates()), "人体一次跨过截止与分段进退不一致");
    }

    public static void expectHitFee(GameTestHelper test, ServerPlayer player, List<TissueFatigueIncrease> expected) {
        // 对照命中前的各块肌肉预算，在身体已提交的回执里核实同笔收费。
        var body = HumanTechniqueHost.state().find(player.getUUID());
        boolean matched = body.receipts().stream().map(receipt -> receipt.getCommand())
                .anyMatch(command -> sameFee(command.getExecuteActivity().getTissueFatigueList(), expected)
                        || sameFee(command.getDamageAttachment().getTissueFatigueList(), expected));
        test.assertTrue(matched && !expected.isEmpty(), "没有按实际减伤收取肌肉费用的回执");
    }

    private static boolean sameFee(List<TissueFatigueIncrease> actual, List<TissueFatigueIncrease> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            var paid = actual.get(index);
            var due = expected.get(index);
            if (!paid.getTissueId().equals(due.getTissueId())
                    || Math.abs(paid.getPoints() - due.getPoints()) > FEE_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    public static List<BodyCommand> read(ServerPlayer player) {
        // 组织活动和普通挥击使用不同命令，两种都计入实付笔数。
        var body = HumanTechniqueHost.state().find(player.getUUID());
        return body.receipts().stream()
                .map(receipt -> receipt.getCommand())
                .filter(command -> command.hasExertTissues() || command.getExecuteActivity().getEffortsCount() > 0)
                .toList();
    }
}
