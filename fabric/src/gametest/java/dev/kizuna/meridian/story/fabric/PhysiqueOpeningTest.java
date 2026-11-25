package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.OpeningRuntime;
import dev.kizuna.meridian.core.OpeningTask;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.human.server.HumanOpeningViews;
import dev.kizuna.meridian.human.server.HumanPhysique;
import dev.kizuna.meridian.protocol.ActionProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class PhysiqueOpeningTest {
    private static final String TARGET = "left_lung_meridian";
    private static final double DENSITY = 0.01;
    private static final int FOUR_OPEN = 4;
    private static final long FIRST = 15_000;
    private static final long FIFTH = 35_000;
    private static final long SLOW_FIRST = 18_750;
    private static final long SLOW_FIFTH = 43_750;
    private static final long BREATH_INTERVAL = 5_000;
    private static final long SECOND = 1_000;
    private static final int REJOIN_TICKS = 6;
    private static final double FEE_PER_SECOND = 0.1;

    @GameTest
    public void solitaryAndOrdinaryHoldAndPayUntilRealCompletion(GameTestHelper test) {
        // 同一组规则还要走一次在线玩家开脉、断线暂停和重新登录。
        var id = StoryPlayer.offered(test, PhysiqueScene.LONE);
        var lone = StoryPlayer.join(test, id);
        var rejoined = new StoryPlayer[1];
        var ordinary = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        PhysiqueGameTest.choose(test, lone, PhysiqueScene.LONE)
                .thenExecute(() -> ordinary.chooseOrigin(PhysiqueScene.ORDINARY))
                .thenWaitUntil(() -> PhysiqueGameTest.selected(test, ordinary, PhysiqueScene.ORDINARY))
                .thenExecute(() -> {
                    var base = new PhysiqueScene(ordinary.player()).body();
                    var physique = new PhysiqueScene(lone.player()).body().getAddonProgressOrThrow(HumanPhysique.KEY);
                    var sameBody = base.toBuilder().putAddonProgress(HumanPhysique.KEY, physique).build();
                    check(test, full(base, 0), FIRST);
                    check(test, full(sameBody, 0), SLOW_FIRST);
                    check(test, full(base, FOUR_OPEN), FIFTH);
                    check(test, full(sameBody, FOUR_OPEN), SLOW_FIFTH);
                    ordinary.close();
                    startOnline(test, lone);
                    lone.close();
                }).thenExecuteAfter(REJOIN_TICKS, () -> rejoined[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> PhysiqueGameTest.selected(test, rejoined[0], PhysiqueScene.LONE))
                .thenExecute(() -> {
                    var saved = new PhysiqueScene(rejoined[0].player()).body().getOpeningTask();
                    test.assertTrue(saved.getStatus() == BodyMessages.OpeningTaskStatus.OPENING_PAUSED
                            && saved.getRequiredMillis() == SLOW_FIRST, "重登没有保留暂停任务和受理时长");
                    rejoined[0].close();
                }).thenSucceed();
    }

    private static void startOnline(GameTestHelper test, StoryPlayer actor) {
        // 解锁炁脉札记并补足内腔后，发起开脉仍使用玩家实际权限。
        var player = actor.player();
        var server = player.level().getServer();
        String asPlayer = "execute as " + player.getScoreboardName() + " run meridian ";
        var commands = server.getCommands();
        var admin = server.createCommandSourceStack();
        commands.performPrefixedCommand(admin, asPlayer + "progression unlock");
        commands.performPrefixedCommand(admin, asPlayer + "qi fill");
        commands.performPrefixedCommand(player.createCommandSourceStack(), "meridian opening start " + TARGET);
        var task = new PhysiqueScene(player).body().getOpeningTask();
        test.assertTrue(task.getRequiredMillis() == SLOW_FIRST, "玩家开脉指令未受理孤星时长");
    }

    private static BodyMessages.BodySnapshot full(BodyMessages.BodySnapshot source, int opened) {
        // 在同一个体上只预置满腔和先前已开条数，当前目标仍由真实策略受理和计费。
        var body = source.toBuilder();
        int prior = 0;
        for (var state : body.getMeridianStatesBuilderList()) {
            var definition = source.getStructure().getMeridiansList().stream()
                    .filter(value -> value.getId().equals(state.getId())).findFirst().orElseThrow();
            var qi = BodyMessages.Qi.newBuilder()
                    .setAttribute("meridian:none")
                    .setDensity(DENSITY)
                    .setAmount(definition.getLumenVolume() * DENSITY)
                    .build();
            var inventory = BodyMessages.QiInventory.newBuilder().addEntries(qi).build();
            state.setLumenQi(inventory);
            if (prior < opened && !definition.getId().equals(TARGET)) {
                state.getOpeningBuilder()
                        .setStage(BodyMessages.MeridianOpeningStage.OPENED)
                        .setProgress(1);
                prior++;
            }
        }
        return body.build();
    }

    private static void check(GameTestHelper test, BodyMessages.BodySnapshot source, long expected) {
        var bundle = HumanContent.bundle();
        var rule = bundle.getOpeningRulesList().stream().filter(value -> value.getTargetMeridianId().equals(TARGET))
                .findFirst().orElseThrow();
        var action = bundle.getActionsList().stream().filter(value -> value.getId().equals(HumanOpening.ACTION_ID))
                .findFirst().orElseThrow();
        var start = OpeningRuntime.start(BodyProtobuf.fromMessage(source), OpeningProtobuf.fromRule(rule),
                ActionProtobuf.fromDefinition(action));
        var card = HumanOpeningViews.update(source, List.of(rule)).getOperations(0);
        var hint = card.getMetersList().stream().filter(meter -> meter.getId().equals("hold"))
                .findFirst().orElseThrow();
        test.assertTrue(hint.getHint().contains(Double.toString((double) expected / SECOND)), "开脉卡秒数 " + hint);
        // 存盘读回时即使开脉特质已经改变，也必须沿用受理时冻结的目标。
        var saved = BodyProtobuf.toMessage(start).toBuilder().removeAddonProgress(HumanPhysique.KEY).build();
        var loaded = BodyProtobuf.decodeSnapshot(saved.toByteArray());
        var paused = OpeningRuntime.pause(loaded, new Anatomy.MeridianId(TARGET)).body();
        var almost = hold(loaded, expected - 1);
        test.assertTrue(almost.openingTask().status() == OpeningTask.Status.HOLDING,
                "真实完成时间提前 " + expected);
        var complete = hold(almost, 1);
        test.assertTrue(complete.openingTask().status() == OpeningTask.Status.COMPLETED, "真实开脉没有按时完成");
        test.assertTrue(start.openingTask().requiredMillis() == expected, "开脉真实受理时长错误 " + expected);
        test.assertTrue(paused.openingTask().requiredMillis() == expected, "暂停丢了冻结目标");
        double spent = loaded.totalQi() - complete.totalQi();
        PhysiqueGameTest.near(test, spent, expected * FEE_PER_SECOND / SECOND, "实际维持元数");
        org.slf4j.LoggerFactory.getLogger("meridian_story_test").info("开脉实际 {} ms，账户支出 {} 元", expected, spent);
    }

    private static BodySnapshot hold(BodySnapshot start, long duration) {
        // 夹具按双肺收据的公开入口供气，扣元、疲劳、连续计时和完成均由权威框架推进。
        long end = start.settledAt().elapsedMillis() + duration;
        BodySnapshot current = start;
        while (current.settledAt().elapsedMillis() < end) {
            var withAir = OpeningRuntime.recordIntake(current, new Anatomy.OrganId("left_lung"), 1);
            var time = withAir.settledAt();
            long nextMillis = Math.min(end, time.elapsedMillis() + BREATH_INTERVAL);
            var next = new TimeCursor(time.domain(), time.epoch(), nextMillis);
            var plan = new BodyTimeAdvancement.Plan(next, 0, BodyTimeAdvancement.Mode.ACTIVE);
            current = BodyTimeAdvancement.apply(withAir, plan).body();
        }
        return current;
    }
}
