package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/** 月弧轻撩接连珠两剑：起手窗、同一次持守、同手同剑，末拍命中费加倍并可同笔破防。 */
public final class YunxiComboTest {
    private static final int TIMEOUT = 120;
    private static final int SPAWN_GRACE = 60;
    private static final int GUARD_READY = 10;
    private static final int LIFT_HIT = 4;
    private static final int FIRST_BEAT = 6;
    private static final int BEAT_GAP = 7;
    private static final int WINDOW_OPEN = 9;
    private static final int WINDOW_CLOSE = 20;
    private static final int THRUST_AT = 9;
    private static final int AFTER_THRUST = 17;
    private static final int RELEASE_AT = 1;
    private static final int REGUARD_AT = 8;
    private static final int AFTER_REGUARD = 18;
    private static final int FACE_ATTACKER = 180;
    private static final double REACH = 1.5;
    private static final double SCALE = 2.0;
    private static final double BREAK_MARGIN = 1.5;
    /** 受伤当刻手臂余力另有约百分之一的浮动，容差放到百分之二，仍远小于一倍与两倍之差。 */
    private static final double TOLERANCE = 0.02;
    private static final List<YunxiCombo.Outcome> completed = new ArrayList<>();

    static {
        YunxiCombo.COMPLETED.register(completed::add);
    }

    private enum Opening { LIFT, NONE }

    private enum Between { NOTHING, THRUST, SWAP_SWORD, REGUARD }

    private enum Final { PLAIN, DRAINED }

    private record Plan(Opening opening, int chainAfterHit, Between between, Final ending) { }

    private enum Expect { DOUBLED, ORDINARY }

    @GameTest(maxTicks = TIMEOUT)
    public void comboAtWindowOpenDoublesFinalFee(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, WINDOW_OPEN, Between.NOTHING, Final.PLAIN), Expect.DOUBLED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void comboAtWindowCloseDoublesFinalFee(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, WINDOW_CLOSE, Between.NOTHING, Final.PLAIN), Expect.DOUBLED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void chainOneTickEarlyPaysOrdinaryFee(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, WINDOW_OPEN - 1, Between.NOTHING, Final.PLAIN), Expect.ORDINARY);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void chainOneTickLatePaysOrdinaryFee(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, WINDOW_CLOSE + 1, Between.NOTHING, Final.PLAIN), Expect.ORDINARY);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void chainWithoutLiftPaysOrdinaryFee(GameTestHelper test) {
        run(test, new Plan(Opening.NONE, WINDOW_OPEN, Between.NOTHING, Final.PLAIN), Expect.ORDINARY);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void thrustBetweenBreaksCombo(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, AFTER_THRUST, Between.THRUST, Final.PLAIN), Expect.ORDINARY);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void swappedSwordBreaksCombo(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, WINDOW_OPEN, Between.SWAP_SWORD, Final.PLAIN), Expect.ORDINARY);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void reraisedGuardBreaksCombo(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, AFTER_REGUARD, Between.REGUARD, Final.PLAIN), Expect.ORDINARY);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void comboFinalBeatBreaksGuardInSamePayment(GameTestHelper test) {
        run(test, new Plan(Opening.LIFT, WINDOW_OPEN, Between.NOTHING, Final.DRAINED), Expect.DOUBLED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void ordinaryChainLeavesSameDrainedGuardStanding(GameTestHelper test) {
        run(test, new Plan(Opening.NONE, WINDOW_OPEN, Between.NOTHING, Final.DRAINED), Expect.ORDINARY);
    }

    private static void run(GameTestHelper test, Plan plan, Expect expect) {
        // 守方先成架；攻方按计划出轻撩与连珠，分别在首拍、末拍前后量守方余力。
        var attackerSeat = YunxiLowerTest.swordsman(test, InteractionHand.MAIN_HAND);
        var attacker = attackerSeat.player();
        var defenderSeat = defender(test, attacker);
        var defender = defenderSeat.player();
        YunxiLowerTest.accepted(test, defender, GUARD);
        var fees = new Fees();
        int chainAt = LIFT_HIT + plan.chainAfterHit();
        var sequence = test.startSequence()
                .thenExecuteAfter(GUARD_READY, () -> {
                    test.assertTrue(guarding(defender), "守方未成架");
                    if (plan.opening() == Opening.LIFT) {
                        YunxiLowerTest.accepted(test, attacker, LIFT);
                    }
                })
                .thenExecuteAfter(LIFT_HIT + betweenAt(plan), () -> between(test, plan, attacker, defender))
                .thenExecuteAfter(chainAt - LIFT_HIT - betweenAt(plan), () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(guarding(defender), "连珠起手前守方已不在架上");
                    YunxiLowerTest.accepted(test, attacker, TWIN);
                })
                .thenExecuteAfter(FIRST_BEAT - 1, () -> fees.beforeFirst = settled(defender))
                .thenExecuteAfter(1, () -> fees.afterFirst = settled(defender))
                .thenExecuteAfter(1, () -> fees.afterQuiet = settled(defender))
                .thenExecuteAfter(BEAT_GAP - 2, () -> {
                    fees.beforeFinal = settled(defender);
                    if (plan.ending() == Final.DRAINED) {
                        fees.beforeFinal = drain(test, defender, fees.hold() + fees.first() * BREAK_MARGIN);
                    }
                })
                .thenExecuteAfter(1, () -> {
                    try (attackerSeat; defenderSeat) {
                        fees.afterFinal = settled(defender);
                        check(test, plan, expect, fees, new Pair(attacker, defender));
                    }
                });
        sequence.thenSucceed();
    }

    private record Pair(ServerPlayer attacker, ServerPlayer defender) { }

    private static final class Fees {
        private double beforeFirst;
        private double afterFirst;
        private double afterQuiet;
        private double beforeFinal;
        private double afterFinal;

        double hold() {
            // 首拍后的一刻没有来击，只扣一刻持守。
            return afterFirst - afterQuiet;
        }

        double first() {
            return beforeFirst - afterFirst - hold();
        }

        double last() {
            return beforeFinal - afterFinal - hold();
        }
    }

    private static void check(GameTestHelper test, Plan plan, Expect expect, Fees fees, Pair pair) {
        // 首拍的命中费作基准；合格末拍收两倍，其余情形与首拍相同。
        var defender = pair.defender();
        test.assertTrue(fees.hold() > 0 && fees.first() > 0, "首拍没有被持守接住 " + fees.first());
        var outcomes = completed.stream()
                .filter(outcome -> outcome.attacker().equals(pair.attacker().getUUID()))
                .toList();
        boolean drained = plan.ending() == Final.DRAINED;
        var sustained = progress(defender).getActiveCast().getSustained();
        if (expect == Expect.ORDINARY) {
            test.assertTrue(outcomes.isEmpty(), "不合条件的连珠记成了连招");
            test.assertTrue(guarding(defender) && !sustained.getExhausted(), "普通连珠打破了持守");
            near(test, fees.last(), fees.first(), "普通末拍命中费");
            return;
        }
        test.assertTrue(outcomes.size() == 1, "合格连招应通知一次，实际 " + outcomes.size());
        var outcome = outcomes.getFirst();
        test.assertTrue(outcome.defender().equals(defender.getUUID()) && outcome.broken() == drained,
                "连招结果的守方或破架事实错误 " + outcome);
        if (drained) {
            test.assertTrue(sustained.getExhausted() && !guarding(defender), "加倍命中费未在同笔打破持守");
            test.assertTrue(progress(defender).containsGuardBreakEnds(GUARD), "破架没有写禁守期");
            return;
        }
        test.assertTrue(guarding(defender), "余力尚足却被打破");
        near(test, fees.last(), fees.first() * SCALE, "合格末拍命中费");
    }

    private static int betweenAt(Plan plan) {
        // 中途动作相对轻撩命中的刻数；没有动作时也占一步，保持时间轴一致。
        return switch (plan.between()) {
            case THRUST -> THRUST_AT;
            case REGUARD -> RELEASE_AT;
            default -> 1;
        };
    }

    private static void between(GameTestHelper test, Plan plan, ServerPlayer attacker, ServerPlayer defender) {
        TechniqueFabric.service().tick();
        switch (plan.between()) {
            case THRUST -> YunxiLowerTest.accepted(test, attacker, THRUST);
            case SWAP_SWORD -> attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            case REGUARD -> reguard(test, defender);
            default -> { }
        }
    }

    private static void reguard(GameTestHelper test, ServerPlayer defender) {
        // 松开再起架会换一个施放号；收势走完后的那一刻重新举起。
        var active = progress(defender).getActiveCast();
        var request = EndActiveRequest.newBuilder()
                .setBodyId(defender.getStringUUID())
                .setCastId(active.getRequest().getCastId())
                .build();
        TechniqueFabric.service().endActive(defender.getUUID(), request);
        test.runAfterDelay(REGUARD_AT - RELEASE_AT, () -> {
            TechniqueFabric.service().tick();
            YunxiLowerTest.accepted(test, defender, GUARD);
        });
    }

    private static StoryPlayer defender(GameTestHelper test, ServerPlayer attacker) {
        // 生存模式的守方持剑面向攻方站在近处，来击由兵器承力；先走完登录保护期，正式受击入口才会受理。
        var seat = StoryPlayer.join(test, UUID.randomUUID());
        var player = seat.player();
        player.setGameMode(GameType.SURVIVAL);
        player.setNoGravity(true /* noGravity */);
        var spot = attacker.position().add(0, 0, REACH);
        player.snapTo(spot.x, spot.y, spot.z, FACE_ATTACKER, 0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        for (int tick = 0; tick < SPAWN_GRACE; tick++) {
            player.doTick();
        }
        return seat;
    }

    private static double drain(GameTestHelper test, ServerPlayer player, double remaining) {
        // 直接写疲劳，把余力压到指定焦耳数。
        var bodies = new StoryBodies();
        var body = bodies.read(player).orElseThrow();
        var budget = HumanStamina.from(BodyProtobuf.fromMessage(body));
        var fatigue = budget.cost(budget.availableJoules() - remaining);
        var plan = BodyActivity.newBuilder().addAllTissueFatigue(fatigue).build();
        test.assertTrue(bodies.apply(player, body, "meridian_story_test:fatigue", plan), "疲劳未写入");
        return settled(player);
    }

    private static double settled(ServerPlayer player) {
        // 先交付已到期的打击，再读结算到当前时刻的余力。
        TechniqueFabric.service().tick();
        var body = TechniqueFabric.service().observe(player.getUUID()).orElseThrow().body();
        return HumanStamina.from(body).availableJoules();
    }

    private static boolean guarding(ServerPlayer player) {
        return TechniqueFabric.service().activeGuard(player.getUUID()).isPresent();
    }

    private static void near(GameTestHelper test, double actual, double expected, String name) {
        double scale = Math.max(1, Math.abs(expected));
        test.assertTrue(Math.abs(actual - expected) <= TOLERANCE * scale, name + " 实际 " + actual + " 应为 " + expected);
    }
}
