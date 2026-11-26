package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.human.fabric.YunxiWorkReceipts;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import dev.kizuna.meridian.story.fabric.YunxiGameTest.ObservedCow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

public final class YunxiParryTest {
    private static final int ARM_MUSCLES = 3;
    private static final int TIMEOUT = 70;
    private static final int WINDOW = 10;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int REAR = 180;
    private static final double SWING_JOULES = 8;
    private static final double HOLD_JOULES = 3000;
    private static final double HIT_JOULES = 900;
    private static final double ALMOST_EMPTY = 100;
    private static final double PRICE = 0.9;
    private static final double TARGET_DISTANCE = 1.6;
    private static final double EPSILON = 0.0001;
    private static final float SHIELD_TEST_DOSE = 6;
    private static final float IRON_CLEAVE = 7.2f;

    @GameTest(maxTicks = TIMEOUT)
    public void emptyParryPaysSwordArmAndSixTicks(GameTestHelper test) {
        var actor = actor(test, InteractionHand.OFF_HAND);
        var player = actor.player();
        accepted(test, player, PARRY);
        var paid = YunxiWorkReceipts.read(player).getLast().getExecuteActivity();
        double joules = paid.getEffortsList().stream().mapToDouble(e -> e.getWatts() * e.getSeconds()).sum();
        near(test, joules, SWING_JOULES, "起架总功");
        String arm = player.getMainArm().getOpposite().getSerializedName();
        test.assertTrue(paid.getEffortsCount() == ARM_MUSCLES
                && paid.getEffortsList().stream().allMatch(e -> e.getTissueId().startsWith(arm)), "副手起架肌肉错误");
        test.assertTrue(paid.getAccountsCount() == 0, "起架动用了真元");
        double[] initial = new double[1];
        test.startSequence()
                .thenExecuteAfter(WARMUP - 1, () -> test.assertTrue(!guarding(player), "第七刻提前成架"))
                .thenExecuteAfter(1, () -> {
                    test.assertTrue(guarding(player), "第八刻未成架");
                    initial[0] = stamina(player).availableJoules();
                    YunxiWorkReceipts.checkLongAdvance(test, player, HOLD * TICK_MILLIS, HOLD_JOULES);
                })
                .thenExecuteAfter(HOLD - 1, () -> test.assertTrue(guarding(player), "末刻提早收架"))
                .thenExecuteAfter(1, () -> {
                    test.assertTrue(!guarding(player), "到期仍守");
                    near(test, initial[0] - stamina(player).availableJoules(), HOLD_JOULES, "六刻持守实付");
                })
                .thenExecuteAfter(RECOVERY + 1, () -> {
                    test.assertTrue(!progress(player).getLastCast().getGuardContact(), "空守给了还击");
                    actor.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void hitOneTickBeforeWarmupCannotParry(GameTestHelper test) {
        var actor = actor(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var enemy = enemy(test, player);
        accepted(test, player, PARRY);
        test.runAfterDelay(WARMUP - 1, () -> {
            try (actor) {
                incoming(player, enemy);
                var state = progress(player);
                test.assertTrue(!state.getActiveCast().getSustained().getGuardContact()
                        && !state.getLastCast().getGuardContact(), "成架前一刻收到守方回执");
                test.assertTrue(player.getMainHandItem().getDamageValue() == 0, "成架前一刻磨了剑");
                test.succeed();
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void failedPaymentPreservesRiposte(GameTestHelper test) {
        var actor = trained(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var enemy = enemy(test, player);
        accepted(test, player, PARRY);
        test.startSequence().thenExecuteAfter(WARMUP, () -> incoming(player, enemy))
                .thenExecuteAfter(RECOVERY, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        YunxiPracticeBody.empty(player);
                        test.assertTrue(!cast(player, CLEAVE).hasBinding(), "零真元也受理还击");
                        test.assertTrue(progress(player).getLastCast().getGuardContact(), "拒付消耗了窗口");
                        YunxiPracticeBody.fill(player);
                        checkCleave(test, player, SKIP);
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void raisedShieldCannotOfferRiposte(GameTestHelper test) {
        // 测试连接没有移动包来驱动玩家本地使用物品的计时。
        try (var actor = trained(test, InteractionHand.MAIN_HAND)) {
            var player = actor.player();
            var enemy = enemy(test, player);
            var off = InteractionHand.OFF_HAND;
            player.setItemInHand(off, new ItemStack(Items.SHIELD));
            player.startUsingItem(off);
            for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
                player.doTick();
            }
            test.assertTrue(player.isBlocking(), "守方原版盾未举好");
            test.assertTrue(!cast(player, PARRY).hasBinding(), "举盾中起了短格");
            var shield = player.getOffhandItem();
            int wear = shield.getDamageValue();
            player.hurtServer(test.getLevel(), player.damageSources().mobAttack(enemy), SHIELD_TEST_DOSE);
            test.assertTrue(shield.getDamageValue() > wear, "正面来击没有被原版盾承接");
            player.stopUsingItem();
            checkCleave(test, player, 0);
            test.succeed();
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void rearHitDoesNotGrantRiposte(GameTestHelper test) {
        var actor = actor(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var enemy = enemy(test, player);
        accepted(test, player, PARRY);
        test.runAfterDelay(WARMUP, () -> {
            try (actor) {
                test.assertTrue(guarding(player), "背击前未成架");
                player.setYRot(REAR);
                incoming(player, enemy);
                var state = progress(player);
                test.assertTrue(!state.getActiveCast().getSustained().getGuardContact()
                        && !state.getLastCast().getGuardContact(), "背击记成了格中");
                test.succeed();
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void cleaveRequiresTwoHandsFootRealmAndQi(GameTestHelper test) {
        try (var actor = actor(test, InteractionHand.MAIN_HAND)) {
            var player = actor.player();
            TechniqueFabric.service().learn(player.getUUID(), CLEAVE);
            test.assertTrue(cast(player, CLEAVE).getFailure() == TechniqueFailure.REALM_TOO_LOW, "凡人可斜劈");
            YunxiPracticeBody.ascend(player);
            for (var hand : YunxiPracticeBody.HANDS) {
                test.assertTrue(!cast(player, CLEAVE).hasBinding(), "手经未齐可起手");
                YunxiPracticeBody.open(player, hand);
            }
            test.assertTrue(!cast(player, CLEAVE).hasBinding(), "缺足经可起手");
            YunxiPracticeBody.open(player, YunxiPracticeBody.FOOT);
            test.assertTrue(!cast(player, CLEAVE).hasBinding(), "空内腔付出了斜劈");
            YunxiPracticeBody.fill(player);
            checkCleave(test, player, 0);
            test.succeed();
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void departingAfterContactClearsWindow(GameTestHelper test) {
        var actor = trained(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var enemy = enemy(test, player);
        accepted(test, player, PARRY);
        test.startSequence().thenExecuteAfter(WARMUP, () -> incoming(player, enemy))
                .thenExecuteAfter(RECOVERY + 1, () -> {
                    try (actor) {
                        test.assertTrue(progress(player).getLastCast().getGuardContact(), "离场前尚未开窗");
                        var service = TechniqueFabric.service();
                        service.cancelOnUnload(player.getUUID());
                        service.tick();
                        checkCleave(test, player, 0);
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void frontParryPaysHitAndRipostesAtFive(GameTestHelper test) {
        runContact(test, 0);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void lastWindowTickStillRipostes(GameTestHelper test) {
        runContact(test, WINDOW - 1);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void expiredWindowHitsAtNine(GameTestHelper test) {
        runContact(test, WINDOW);
    }

    private static void runContact(GameTestHelper test, int wait) {
        // 用副手覆盖窗口末刻，两个手别都核验同笔接触费用和兵器磨损。
        InteractionHand swordHand = wait == WINDOW - 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        var actor = trained(test, swordHand);
        var player = actor.player();
        var enemy = enemy(test, player);
        accepted(test, player, PARRY);
        test.startSequence()
                .thenExecuteAfter(WARMUP, () -> {
                    var before = stamina(player);
                    var sword = player.getItemInHand(swordHand);
                    int wear = sword.getDamageValue();
                    incoming(player, enemy);
                    var guard = progress(player).getActiveCast().getSustained();
                    test.assertTrue(guard.getGuardContact() && guard.hasEndRequestedMillis(), "正面实挡未立即收势");
                    test.assertTrue(!guarding(player), "同刻仍可再挡");
                    if (swordHand == InteractionHand.OFF_HAND) {
                        test.assertTrue(sword.getDamageValue() == wear, "前臂承力却磨损副手剑");
                    } else {
                        test.assertTrue(sword.getDamageValue() > wear, "主手格剑没有磨损");
                    }
                    YunxiWorkReceipts.expectHitFee(test, player, before.cost(HIT_JOULES));
                    test.assertTrue(!cast(player, CLEAVE).hasBinding(), "收势前偷跑斜劈");
                })
                .thenExecuteAfter(RECOVERY + wait, () -> {
                    int skip = wait == WINDOW ? 0 : SKIP;
                    finishStrike(test, actor, enemy, skip);
                });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void strikingShieldDoesNotShortenCleave(GameTestHelper test) {
        var actor = trained(test, InteractionHand.OFF_HAND);
        var player = actor.player();
        var target = enemy(test, player);
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        target.setYRot(REAR);
        target.setYHeadRot(REAR);
        target.setYBodyRot(REAR);
        target.startUsingItem(InteractionHand.OFF_HAND);
        test.runAfterDelay(HOLD, () -> {
            test.assertTrue(target.isBlocking(), "对方未举稳原版盾");
            target.hurtServer(test.getLevel(), player.damageSources().playerAttack(player), 1);
            test.assertTrue(progress(player).getBlockedAttack().getSource() == BlockedAttackSource.SHIELD,
                    "本人的剑未产生攻方被盾挡事实");
            target.stopUsingItem();
            try (actor) {
                checkCleave(test, player, 0);
                test.succeed();
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void exhaustedParrySharesOrdinaryGuardLockout(GameTestHelper test) {
        var actor = actor(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        accepted(test, player, PARRY);
        test.startSequence()
                .thenExecuteAfter(WARMUP, () -> {
                    var bodies = new StoryBodies();
                    var body = bodies.read(player).orElseThrow();
                    var budget = HumanStamina.from(BodyProtobuf.fromMessage(body));
                    var drain = budget.cost(budget.availableJoules() - ALMOST_EMPTY);
                    var plan = BodyActivity.newBuilder().addAllTissueFatigue(drain).build();
                    test.assertTrue(bodies.apply(player, body, "meridian_story_test:fatigue", plan), "疲劳未写入");
                })
                .thenExecuteAfter(1, () -> {
                    var state = progress(player);
                    test.assertTrue(state.getActiveCast().getSustained().getExhausted(), "体力耗尽后仍在守");
                    test.assertTrue(state.containsGuardBreakEnds(GUARD), "没写通用守禁守期");
                })
                .thenExecuteAfter(RECOVERY + 1, () -> {
                    test.assertTrue(cast(player, GUARD).getReason().equals("meridian_human_host.guard.broken"),
                            "普通守绕过短格禁守");
                    actor.close();
                }).thenSucceed();
    }

    private static void finishStrike(GameTestHelper test, StoryPlayer actor, ObservedCow target, int skip) {
        var player = actor.player();
        // 当前身体时刻先交付已到期的收势，再从受理拍观察真实世界的伤害。
        TechniqueFabric.service().tick();
        checkCleave(test, player, skip);
        float health = target.getHealth();
        int tick = skip == 0 ? ORDINARY_HIT : RIPOSTE_HIT;
        test.startSequence()
                .thenExecuteAfter(tick - 1, () -> {
                    TechniqueFabric.service().tick();
                    near(test, target.getHealth(), health, "提前落剑");
                })
                .thenExecuteAfter(1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == 1 && target.getHealth() < health, "落剑拍未伤一人");
                        near(test, target.damage, IRON_CLEAVE, "铁剑入口剂量");
                    }
                }).thenSucceed();
    }

    private static void checkCleave(GameTestHelper test, ServerPlayer player, int skip) {
        double before = lumen(player);
        accepted(test, player, CLEAVE);
        var state = progress(player);
        var active = state.getActiveCast();
        test.assertTrue(active.getClipStartMillis() == skip, "斜劈错误起播 " + active.getClipStartMillis());
        test.assertTrue(active.getWarmupEndMillis() - active.getStartedMillis() == ORDINARY_HIT * TICK_MILLIS - skip,
                "斜劈起手时长错误");
        test.assertTrue(!state.getLastCast().getGuardContact(), "受理后还击资格仍可用");
        near(test, before - lumen(player), PRICE, "起手真元费用");
    }

    private static StoryPlayer actor(GameTestHelper test, InteractionHand hand) {
        var actor = StoryPlayer.join(test, UUID.randomUUID());
        var player = actor.player();
        YunxiGameTest.prepare(test, player);
        player.setItemInHand(hand, new ItemStack(Items.IRON_SWORD));
        return actor;
    }

    private static StoryPlayer trained(GameTestHelper test, InteractionHand hand) {
        var actor = actor(test, hand);
        var player = actor.player();
        YunxiPracticeBody.practitioner(player);
        TechniqueFabric.service().learn(player.getUUID(), CLEAVE);
        return actor;
    }

    private static ObservedCow enemy(GameTestHelper test, ServerPlayer player) {
        var cow = new ObservedCow(test.getLevel());
        cow.setPos(player.position().add(0, 0, TARGET_DISTANCE));
        test.assertTrue(test.getLevel().addFreshEntity(cow), "试剑目标未生成");
        cow.setNoAi(true /* noAi */);
        cow.setNoGravity(true /* noGravity */);
        return cow;
    }

    private static HumanStamina stamina(ServerPlayer player) {
        return HumanStamina.from(TechniqueFabric.service().observe(player.getUUID()).orElseThrow().body());
    }

    private static double lumen(ServerPlayer player) {
        var body = TechniqueFabric.service().observe(player.getUUID()).orElseThrow().body();
        return body.meridianStates().stream().flatMap(m -> m.lumenQi().entries().stream())
                .mapToDouble(Qi::amount).sum();
    }

    private static boolean guarding(ServerPlayer player) {
        return TechniqueFabric.service().activeGuard(player.getUUID()).isPresent();
    }

    private static void accepted(GameTestHelper test, ServerPlayer player, String form) {
        var result = cast(player, form);
        test.assertTrue(result.hasBinding(), "施放未受理 " + result);
    }

    private static void near(GameTestHelper test, double actual, double expected, String name) {
        test.assertTrue(Math.abs(actual - expected) < EPSILON, name + " 实际 " + actual + " 应为 " + expected);
    }
}
