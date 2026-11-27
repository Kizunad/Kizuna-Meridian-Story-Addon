package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/**
 * 陈恪胜后口授《寻隙剑谱》：同一次回冢接着问，退出再进直接续传；凡人先学第一式，升到真炁再补；
 * 旁人点胜者的按钮学不到；重复点、重发的包和重登都不多学。胜场本身由比试用例核过，这里直接写进记录。
 * 冢址全世界只有一处，每条用例单独一批。
 */
public final class RivalLessonTest {
    private static final int TIMEOUT = 2600;
    private static final String PREFIX = "story.meridian_story.rival.";
    private static final int FIRST_ONLY = 0b001;
    private static final int ALL_THREE = 0b111;
    private static final int NEAR = 2;
    private static final int DISTANT = 100;
    private static final int SETTLE = 40;
    private static final int STAY_ON = 60;

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:rival_lesson_resume")
    public void winnerIsAskedThenResumesAfterRelog(GameTestHelper test) {
        UUID id = StoryPlayer.offered(test, GraveVisits.ORIGIN);
        var owner = StoryPlayer.join(test, id);
        var relogged = new AtomicReference<StoryPlayer>();
        RivalVisitTest.settled(test, owner)
                .thenExecute(() -> {
                    // 学齐两式、记首次祭扫后回冢，等他约剑。
                    var player = owner.player();
                    YunxiPracticeBody.practitioner(player);
                    var service = TechniqueFabric.service();
                    service.learn(player.getUUID(), LIFT);
                    service.learn(player.getUUID(), TWIN);
                    RivalVisitTest.sweep(test, owner);
                    RivalVisitTest.approach(owner);
                })
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.said(owner, "invite"),
                        "陈恪未约剑 " + RivalVisitTest.diagnose(owner)))
                .thenExecute(() -> win(test, owner))
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.said(owner, "teach_offer"),
                        "胜后没问学不学 " + RivalVisitTest.diagnose(owner) + " present="
                                + (RivalVisitTest.rival(owner) != null) + " offers=" + owner.offers().size()))
                .thenExecute(owner::close)
                .thenExecuteAfter(SETTLE, () -> relogged.set(StoryPlayer.join(test, id)))
                .thenExecute(() -> RivalVisitTest.approach(relogged.get()))
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.said(relogged.get(), "resume")
                        && RivalVisitTest.narrated(relogged.get(), "resume_scene"), "重登后陈恪未来续传"))
                .thenExecute(() -> {
                    var again = relogged.get();
                    again.choose(again.offers().getLast(), 0);
                    test.assertTrue(forms(again.player()).containsAll(RivalLessons.FORMS), "续传没学齐三式");
                    finished(test, again);
                })
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.rival(relogged.get()) == null, "授齐后陈恪未走"))
                .thenExecute(() -> {
                    var grave = RivalVisitTest.record(relogged.get());
                    test.assertTrue(grave.getRivalWon(), "授齐后胜场丢了");
                    relogged.get().close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:rival_lesson_mortal")
    public void mortalLearnsFirstFormThenRestAfterTrueQi(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, GraveVisits.ORIGIN));
        var invites = new AtomicInteger();
        var taughtFirst = RivalVisitTest.settled(test, owner)
                .thenExecute(() -> {
                    win(test, owner);
                    RivalVisitTest.approach(owner);
                })
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.said(owner, "resume"),
                        "赢过的凡人回冢陈恪未来 " + RivalVisitTest.diagnose(owner)))
                .thenExecute(() -> {
                    owner.choose(owner.offers().getLast(), 0);
                    var known = forms(owner.player());
                    test.assertTrue(known.equals(Set.of(SIDE_STEP)), "凡人口授所得不对 " + known);
                    var grave = RivalVisitTest.record(owner);
                    test.assertTrue(grave.getRivalTaught() == FIRST_ONLY && grave.hasRivalRefusal(),
                            "待传未记 " + grave);
                    test.assertTrue(RivalVisitTest.said(owner, "teach_pending"), "后两式被拒他没交代");
                    test.assertTrue(RivalVisitTest.narrated(owner, "taught"), "学成第一式没有灰字");
                    invites.set(owner.offers().size());
                });
        var unchanged = revisit(taughtFirst, owner)
                .thenExecuteAfter(STAY_ON, () -> {
                    test.assertTrue(RivalVisitTest.rival(owner) == null, "境界没变他又来了");
                    YunxiPracticeBody.practitioner(owner.player());
                });
        revisit(unchanged, owner)
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.rival(owner) instanceof RivalSwordsman
                        && owner.offers().size() > invites.get(), "升到真炁后陈恪未来补授"))
                .thenExecute(() -> {
                    owner.choose(owner.offers().getLast(), 0);
                    test.assertTrue(forms(owner.player()).containsAll(RivalLessons.FORMS), "补授没学齐三式");
                    finished(test, owner);
                    owner.close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:rival_lesson_bystander")
    public void bystanderCannotLearnFromWinnersInvite(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, GraveVisits.ORIGIN));
        RivalVisitTest.settled(test, owner)
                .thenExecute(() -> {
                    YunxiPracticeBody.practitioner(owner.player());
                    win(test, owner);
                    RivalVisitTest.approach(owner);
                })
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.said(owner, "resume"),
                        "陈恪未来续传 " + RivalVisitTest.diagnose(owner)))
                .thenExecute(() -> {
                    // 围观的人境界够，站到陈恪身旁，点胜者聊天里的同一枚 [请教剑法]。
                    var invitation = owner.offers().getLast();
                    var other = StoryPlayer.join(test, UUID.randomUUID());
                    YunxiPracticeBody.practitioner(other.player());
                    RivalVisitTest.move(other, GraveSite.get(other.player().level()).guest().west(NEAR));
                    other.choose(invitation, 0);
                    test.assertTrue(forms(other.player()).isEmpty(), "围观者学到了 " + forms(other.player()));
                    test.assertTrue(forms(owner.player()).isEmpty(), "围观者一点胜者就学了");
                    test.assertTrue(RivalVisitTest.rival(owner) instanceof RivalSwordsman, "围观者一点陈恪就走了");
                    owner.choose(invitation, 0);
                    test.assertTrue(forms(owner.player()).containsAll(RivalLessons.FORMS), "胜者自己点学不到");
                    other.close();
                    owner.close();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:rival_lesson_repeat")
    public void repeatedClicksAndPacketsTeachOnce(GameTestHelper test) {
        UUID id = StoryPlayer.offered(test, GraveVisits.ORIGIN);
        var owner = StoryPlayer.join(test, id);
        var changes = new AtomicInteger();
        Consumer<TechniqueService.KnownChanged> counter = event -> {
            if (event.entityUuid().equals(id)) {
                changes.incrementAndGet();
            }
        };
        var relogged = new AtomicReference<StoryPlayer>();
        RivalVisitTest.settled(test, owner)
                .thenExecute(() -> {
                    YunxiPracticeBody.practitioner(owner.player());
                    win(test, owner);
                    RivalVisitTest.approach(owner);
                })
                .thenWaitUntil(() -> test.assertTrue(RivalVisitTest.said(owner, "resume"),
                        "陈恪未来续传 " + RivalVisitTest.diagnose(owner)))
                .thenExecute(() -> {
                    // 同一枚按钮点一次，再按原样重发两次包。
                    var invitation = owner.offers().getLast();
                    TechniqueFabric.service().addKnownChangedListener(counter);
                    owner.choose(invitation, 0);
                    owner.choose(invitation, 0);
                    owner.choose(invitation, 0);
                    test.assertTrue(forms(owner.player()).containsAll(RivalLessons.FORMS), "没学齐三式");
                    test.assertTrue(changes.get() == RivalLessons.FORMS.size(), "学会不是每式一次 " + changes.get());
                    test.assertTrue(spoken(owner, "teach_done") == 1 && replied(owner, "taught") == 1,
                            "重复点又教了一遍");
                    owner.close();
                })
                .thenExecuteAfter(SETTLE, () -> relogged.set(StoryPlayer.join(test, id)))
                .thenExecute(() -> RivalVisitTest.approach(relogged.get()))
                .thenExecuteAfter(STAY_ON, () -> {
                    TechniqueFabric.service().removeKnownChangedListener(counter);
                    test.assertTrue(RivalVisitTest.rival(relogged.get()) == null, "授齐重登后他又来了");
                    test.assertTrue(changes.get() == RivalLessons.FORMS.size(), "重登后又学了 " + changes.get());
                    relogged.get().close();
                })
                .thenSucceed();
    }

    private static void win(GameTestHelper test, StoryPlayer owner) {
        // 记下首次祭扫和已赢待传。
        var player = owner.player();
        var body = new PhysiqueScene(player).body();
        var progress = StoryProgress.read(body);
        var grave = progress.getGrave().toBuilder()
                .setFirstSwept(true /* firstSwept */)
                .setRivalWon(true /* rivalWon */)
                .build();
        var next = progress.toBuilder().setGrave(grave).build();
        test.assertTrue(new StoryBodies().write(player, body, next), "胜场未写入");
    }

    private static void finished(GameTestHelper test, StoryPlayer owner) {
        var grave = RivalVisitTest.record(owner);
        test.assertTrue(grave.getRivalTaught() == ALL_THREE && !grave.hasRivalRefusal(), "授齐未记全 " + grave);
        test.assertTrue(RivalVisitTest.narrated(owner, "taught"), "学成没有灰字");
        test.assertTrue(RivalVisitTest.said(owner, "teach_done"), "授齐他没告辞");
    }

    private static GameTestSequence revisit(GameTestSequence sequence, StoryPlayer owner) {
        // 走远再回冢，算新的一次回冢。
        return sequence
                .thenExecute(() -> RivalVisitTest.move(owner,
                        GraveSite.get(owner.player().level()).guest().east(DISTANT)))
                .thenExecuteAfter(SETTLE, () -> RivalVisitTest.approach(owner));
    }

    private static Set<String> forms(ServerPlayer player) {
        return TechniqueFabric.service().known(player.getUUID()).stream()
                .map(entry -> entry.technique().getId())
                .filter(RivalLessons.FORMS::contains)
                .collect(Collectors.toSet());
    }

    private static long spoken(StoryPlayer owner, String line) {
        var expected = Component.translatable(PREFIX + line);
        return owner.packets().filter(BubblePayload.class::isInstance).map(BubblePayload.class::cast)
                .filter(bubble -> expected.equals(bubble.line()))
                .count();
    }

    private static long replied(StoryPlayer owner, String line) {
        return owner.chat().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents text
                        && text.getKey().equals(PREFIX + line))
                .count();
    }
}
