package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/** 冢前试剑：陈恪起专用守架，应约人用月弧轻撩接连珠两剑破架才算赢；其余收场都不记胜。 */
public final class RivalDuelTest {
    private static final int TIMEOUT = 900;
    private static final BlockPos RIVAL_FEET = new BlockPos(3, 2, 5);
    private static final double REACH = 1.5;
    private static final double OUTSIDE = 12;
    private static final int GUARD_READY = 12;
    private static final int LIFT_HIT = 4;
    private static final int WINDOW_OPEN = 9;
    private static final int WINDOW_LATE = 22;
    /** 松架后他按正式收势与起手重架，新架成形后连珠首拍仍在起手窗内。 */
    private static final int AFTER_REGUARD = 18;
    private static final int FIRST_BEAT = 6;
    private static final int BEAT_GAP = 7;
    private static final int SETTLE = 4;
    /** 连珠收势加上它的冷却，重试前等够。 */
    private static final int COOLDOWN = 140;
    private static final int RELEASE_AT = 1;
    private static final double NEARLY_EMPTY = 30;
    private static final double LOW = 300;
    private static final List<RivalDuel.Ended> ended = new ArrayList<>();

    static {
        RivalDuel.ENDED.register(ended::add);
    }

    /** 一场的双方与场界。 */
    private record Scene(StoryPlayer seat, Mob rival, RivalDuel.Arena arena) {
        ServerPlayer player() {
            return seat.player();
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fullComboBreaksGuardAndWinsOnce(GameTestHelper test) {
        // 从成架到末拍逐刻记下陈恪的余力，写进日志供调校对照。
        var holder = new Scene[1];
        var ledger = new ArrayList<String>();
        var sequence = keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> {
                    guarding(test, holder[0]);
                    test.onEachTick(() -> ledger.add(String.format("%d:%.0f", test.getTick(),
                            available(holder[0].rival()))));
                });
        combo(sequence, holder, WINDOW_OPEN, Between.NOTHING)
                .thenExecuteAfter(SETTLE, () -> {
                    LoggerFactory.getLogger("meridian_story_test").info("陈恪余力逐刻 {}", ledger);
                    won(test, holder[0]);
                })
                .thenWaitUntil(() -> test.assertTrue(record(holder[0]).getRivalWon(), "胜场未写入已赢待传"))
                .thenExecuteAfter(SETTLE, () -> {
                    test.assertTrue(results(holder[0], RivalDuel.Result.WON) == 1, "胜场应只通知一次");
                    holder[0].seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void winSurvivesRelog(GameTestHelper test) {
        var holder = new Scene[1];
        var relogged = new StoryPlayer[1];
        var sequence = keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> guarding(test, holder[0]));
        combo(sequence, holder, WINDOW_OPEN, Between.NOTHING)
                .thenWaitUntil(() -> test.assertTrue(record(holder[0]).getRivalWon(), "胜场未写入已赢待传"))
                .thenExecute(() -> holder[0].seat().close())
                .thenExecuteAfter(SETTLE, () -> relogged[0] = StoryPlayer.join(test, holder[0].player().getUUID()))
                .thenWaitUntil(() -> test.assertTrue(StoryProgress.read(new PhysiqueScene(relogged[0].player()).body())
                        .getGrave().getRivalWon(), "重登后已赢待传丢失"))
                .thenExecute(() -> relogged[0].close())
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void unlearnedChallengerIsRefused(GameTestHelper test) {
        var holder = new Scene[1];
        keeper(test, holder, Learned.NONE)
                .thenExecute(() -> {
                    var scene = holder[0];
                    var refusal = RivalDuel.open(scene.player(), scene.rival(), scene.arena());
                    test.assertTrue(refusal.orElse(null) == RivalDuel.Refusal.UNLEARNED, "没学两式未给提示 " + refusal);
                    test.assertFalse(RivalDuel.active(scene.player().getUUID()), "没学两式却开了场");
                })
                .thenExecuteAfter(GUARD_READY, () -> {
                    var scene = holder[0];
                    test.assertTrue(TechniqueFabric.service().activeGuard(scene.rival().getUUID()).isEmpty(),
                            "没开场他却立了架");
                    scene.seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void brokenComboThenRetryWins(GameTestHelper test) {
        var holder = new Scene[1];
        var sequence = keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> guarding(test, holder[0]));
        combo(sequence, holder, WINDOW_LATE, Between.NOTHING)
                .thenExecuteAfter(SETTLE, () -> {
                    notWon(test, holder[0]);
                    guarding(test, holder[0]);
                })
                .thenIdle(COOLDOWN);
        combo(sequence, holder, WINDOW_OPEN, Between.NOTHING)
                .thenExecuteAfter(SETTLE, () -> won(test, holder[0]))
                .thenExecute(() -> holder[0].seat().close())
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void bystanderComboDoesNotWin(GameTestHelper test) {
        // 旁人用同一套连招打陈恪：一伤到他就算插手，散场，不记胜。
        var holder = new Scene[1];
        var bystander = new StoryPlayer[1];
        keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> {
                    opened(test, holder[0]);
                    bystander[0] = YunxiLowerTest.swordsman(test, InteractionHand.MAIN_HAND);
                    stand(bystander[0].player(), holder[0].rival());
                })
                .thenExecuteAfter(GUARD_READY, () -> {
                    guarding(test, holder[0]);
                    tickAndCast(bystander[0].player(), LIFT);
                })
                .thenExecuteAfter(LIFT_HIT + WINDOW_OPEN, () -> tickAndCast(bystander[0].player(), TWIN))
                .thenExecuteAfter(FIRST_BEAT + BEAT_GAP + SETTLE, () -> {
                    notWon(test, holder[0]);
                    test.assertTrue(results(holder[0], RivalDuel.Result.ABORTED) == 1, "旁人插手未散场");
                    test.assertFalse(record(holder[0]).getRivalWon(), "旁人连招被记成胜场");
                    bystander[0].close();
                    holder[0].seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void strangerOutcomeIsIgnored(GameTestHelper test) {
        // 连招回执的施剑者不是应约人：本场守式、破架都对得上，也不记胜。
        var holder = new Scene[1];
        keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> {
                    var scene = holder[0];
                    var guard = TechniqueFabric.service().activeGuard(scene.rival().getUUID()).orElseThrow();
                    var outcome = new YunxiCombo.Outcome(UUID.randomUUID(), scene.rival().getUUID(),
                            UUID.randomUUID().toString(), UUID.randomUUID().toString(), guard.castId(),
                            true /* broken */);
                    YunxiCombo.COMPLETED.invoker().completed(outcome);
                })
                .thenExecuteAfter(SETTLE, () -> {
                    notWon(test, holder[0]);
                    test.assertTrue(RivalDuel.active(holder[0].player().getUUID()), "旁人的回执让比试收了场");
                    holder[0].seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void reraisedGuardDoesNotWin(GameTestHelper test) {
        var holder = new Scene[1];
        var sequence = keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> guarding(test, holder[0]));
        combo(sequence, holder, AFTER_REGUARD, Between.RELEASE)
                .thenExecuteAfter(SETTLE, () -> {
                    notWon(test, holder[0]);
                    test.assertTrue(RivalDuel.active(holder[0].player().getUUID()), "松架再起后比试不该收场");
                    holder[0].seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void exhaustedGuardDoesNotWin(GameTestHelper test) {
        // 余力只剩几十焦耳，持守自己耗尽：守式没了，不记胜，比试照常等他重架。
        var holder = new Scene[1];
        keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> {
                    guarding(test, holder[0]);
                    drain(holder[0].rival(), NEARLY_EMPTY);
                })
                .thenWaitUntil(() -> test.assertTrue(TechniqueFabric.service()
                        .activeGuard(holder[0].rival().getUUID()).isEmpty(), "余力耗尽后仍在架上"))
                .thenExecuteAfter(SETTLE, () -> {
                    var scene = holder[0];
                    notWon(test, scene);
                    test.assertTrue(RivalDuel.active(scene.player().getUUID()), "自然耗尽不该散场");
                    scene.seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void plainSlashBreakDoesNotWin(GameTestHelper test) {
        // 余力压低后普通挥砍一下把架打掉：不是云溪连招，不记胜。
        var holder = new Scene[1];
        keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> {
                    guarding(test, holder[0]);
                    drain(holder[0].rival(), LOW);
                })
                .thenExecuteAfter(1, () -> holder[0].player().attack(holder[0].rival()))
                .thenWaitUntil(() -> test.assertTrue(TechniqueFabric.service()
                        .activeGuard(holder[0].rival().getUUID()).isEmpty(), "普通挥砍没把架打掉"))
                .thenExecuteAfter(SETTLE, () -> {
                    var scene = holder[0];
                    notWon(test, scene);
                    test.assertTrue(RivalDuel.active(scene.player().getUUID()), "普通挥砍破架不该散场");
                    scene.seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void swordSwapBetweenFormsDoesNotWin(GameTestHelper test) {
        var holder = new Scene[1];
        var sequence = keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> guarding(test, holder[0]));
        combo(sequence, holder, WINDOW_OPEN, Between.SWAP_SWORD)
                .thenExecuteAfter(SETTLE, () -> {
                    notWon(test, holder[0]);
                    holder[0].seat().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void leavingArenaAborts(GameTestHelper test) {
        var holder = new Scene[1];
        keeper(test, holder, Learned.BOTH)
                .thenExecute(() -> opened(test, holder[0]))
                .thenExecuteAfter(GUARD_READY, () -> {
                    guarding(test, holder[0]);
                    var player = holder[0].player();
                    player.setPos(player.position().add(0, 0, -OUTSIDE));
                })
                .thenExecuteAfter(SETTLE, () -> {
                    var scene = holder[0];
                    test.assertTrue(results(scene, RivalDuel.Result.ABORTED) == 1, "离开空坪未散场");
                    test.assertFalse(RivalDuel.active(scene.player().getUUID()), "散场后比试仍在");
                    test.assertTrue(scene.rival().isRemoved(), "散场后陈恪未离开");
                })
                .thenWaitUntil(() -> test.assertTrue(record(holder[0]).hasRivalSetback(), "散场未记冷却"))
                .thenExecute(() -> {
                    test.assertFalse(record(holder[0]).getRivalWon(), "散场被记成胜场");
                    holder[0].seat().close();
                })
                .thenSucceed();
    }

    private enum Learned { BOTH, NONE }

    private enum Between { NOTHING, RELEASE, SWAP_SWORD }

    private static GameTestSequence keeper(GameTestHelper test, Scene[] holder, Learned learned) {
        // 正式出身入口定下守墓人，等剧情记录建好；再按需学剑，最后请陈恪到场并站到他面前。
        var seat = StoryPlayer.join(test, StoryPlayer.offered(test, GraveVisits.ORIGIN));
        var player = seat.player();
        return test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(seat.packets()
                        .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待出身卡"))
                .thenExecute(() -> seat.chooseOrigin(GraveVisits.ORIGIN))
                .thenWaitUntil(() -> test.assertFalse(StoryProgress.read(new PhysiqueScene(player).body()).getGrave()
                        .getSwordSerial().isEmpty(), "等待守墓记录"))
                .thenExecute(() -> holder[0] = scene(test, seat, learned));
    }

    private static Scene scene(GameTestHelper test, StoryPlayer seat, Learned learned) {
        var player = seat.player();
        YunxiGameTest.prepare(test, player);
        if (learned == Learned.BOTH) {
            YunxiPracticeBody.practitioner(player);
            TechniqueFabric.service().learn(player.getUUID(), TWIN);
        } else {
            TechniqueFabric.service().known(player.getUUID()).forEach(entry -> test.assertFalse(
                    entry.technique().getId().equals(TWIN), "凡人不该会连珠"));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        var feet = test.absolutePos(RIVAL_FEET);
        test.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        var rival = RivalVisit.arrive(test.getLevel(), feet, RivalVisit.guest(player.getUUID())).orElseThrow();
        stand(player, rival);
        return new Scene(seat, rival, new RivalDuel.Arena(test.getLevel().dimension(), feet));
    }

    private static void stand(ServerPlayer player, Mob rival) {
        // 站在他正前方一步半，面朝他；悬空不落，位置不随刻数漂移。
        player.setNoGravity(true /* noGravity */);
        Vec3 spot = rival.position().add(0, 0, -REACH);
        player.snapTo(spot.x, spot.y, spot.z, 0, 0);
    }

    private static void opened(GameTestHelper test, Scene scene) {
        var refusal = RivalDuel.open(scene.player(), scene.rival(), scene.arena());
        test.assertTrue(refusal.isEmpty(), "应约被拒 " + refusal);
    }

    private static void guarding(GameTestHelper test, Scene scene) {
        test.assertTrue(TechniqueFabric.service().activeGuard(scene.rival().getUUID()).isPresent(), "陈恪未成架");
    }

    private static GameTestSequence combo(GameTestSequence sequence, Scene[] holder, int chainAfterHit,
                                          Between between) {
        // 轻撩命中后按计划插一步，再在指定刻起连珠；两拍走完再看结果。
        return sequence
                .thenExecute(() -> tickAndCast(holder[0].player(), LIFT))
                .thenExecuteAfter(LIFT_HIT + RELEASE_AT, () -> interpose(holder[0], between))
                .thenExecuteAfter(chainAfterHit - RELEASE_AT, () -> tickAndCast(holder[0].player(), TWIN))
                .thenIdle(FIRST_BEAT + BEAT_GAP);
    }

    private static void interpose(Scene scene, Between between) {
        TechniqueFabric.service().tick();
        switch (between) {
            case RELEASE -> {
                var service = TechniqueFabric.service();
                var guard = service.activeGuard(scene.rival().getUUID()).orElseThrow();
                var request = EndActiveRequest.newBuilder()
                        .setBodyId(guard.bodyId())
                        .setCastId(guard.castId())
                        .build();
                service.endActive(scene.rival().getUUID(), request);
            }
            case SWAP_SWORD -> scene.player().setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(Items.DIAMOND_SWORD));
            default -> { }
        }
    }

    private static void tickAndCast(ServerPlayer player, String form) {
        // 先交付已到期的打击，再起手；起手没受理时让用例直接失败。
        TechniqueFabric.service().tick();
        var result = cast(player, form);
        if (!result.hasBinding()) {
            throw new IllegalStateException("施放未受理 " + form + " " + result);
        }
    }

    private static void drain(Mob rival, double remaining) {
        // 与比试同一条路：经功法宿主写疲劳，把余力压到指定焦耳数。
        var host = TechniqueFabric.service().statusHost(rival.getUUID()).orElseThrow();
        var body = host.inspect().body();
        var stamina = HumanStamina.from(body);
        var activity = BodyActivity.newBuilder()
                .addAllTissueFatigue(stamina.cost(stamina.availableJoules() - remaining))
                .build();
        host.submit(new TechniqueHost.Submission(UUID.randomUUID().toString(), body.revision(), activity));
    }

    private static double available(Mob rival) {
        var body = TechniqueFabric.service().observe(rival.getUUID()).orElseThrow().body();
        return HumanStamina.from(body).availableJoules();
    }

    private static void won(GameTestHelper test, Scene scene) {
        test.assertTrue(results(scene, RivalDuel.Result.WON) == 1, "完整连招破架未记胜");
        test.assertTrue(TechniqueFabric.service().activeGuard(scene.rival().getUUID()).isEmpty(), "破架后仍在架上");
        test.assertFalse(RivalDuel.active(scene.player().getUUID()), "胜后比试仍在");
    }

    private static void notWon(GameTestHelper test, Scene scene) {
        test.assertTrue(results(scene, RivalDuel.Result.WON) == 0, "不该记胜");
    }

    private static long results(Scene scene, RivalDuel.Result result) {
        UUID challenger = scene.player().getUUID();
        return ended.stream()
                .filter(entry -> entry.challenger().equals(challenger) && entry.result() == result)
                .count();
    }

    private static GraveKeeper record(Scene scene) {
        return StoryProgress.read(new PhysiqueScene(scene.player()).body()).getGrave();
    }
}
