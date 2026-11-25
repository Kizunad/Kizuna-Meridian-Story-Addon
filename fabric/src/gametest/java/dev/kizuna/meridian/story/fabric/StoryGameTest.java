package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.story.fabric.StoryPlayer.Invitation;
import net.minecraft.network.chat.Component;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidReceipt;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Claim;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class StoryGameTest {
    private static final int TIMEOUT = 500;
    private static final int REJOIN_DELAY = 3;
    private static final int FAR = 20;
    private static final String FEUD = StoryPlayer.ORIGIN + "#0";

    static GameTestSequence chosen(GameTestHelper test, StoryPlayer owner) {
        return test.startSequence().thenWaitUntil(() -> test.assertTrue(owner.packets()
                        .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待正式出身候选"))
                .thenExecute(owner::chooseOrigin)
                .thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(owner.player())
                        .filter(record -> record.getWatchesCount() == 1)
                        .filter(record -> record.getFeudsList().stream().anyMatch(feud -> feud.getKey().equals(FEUD)))
                        .isPresent(), "等待引擎接上出身仇家和剧情订阅"));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void victoryLetterSurvivesRelogAndRedeemsOnce(GameTestHelper test) {
        UUID id = StoryPlayer.offered(test);
        var first = StoryPlayer.join(test, id);
        var observer = StoryPlayer.join(test, UUID.randomUUID());
        var witness = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        witness.setNoAi(true /* noAi */);
        var leader = test.spawn(EntityTypes.PILLAGER, 1, 1, 1);
        var sequence = chosen(test, first);
        var next = new StoryPlayer[1];
        sequence.thenExecute(() -> {
            StoryRaidFixture.arrive(first.player(), leader, FEUD);
            leader.die(first.player().damageSources().playerAttack(first.player()));
            test.assertTrue(CrisisEncounters.current(first.player()).orElseThrow().getReceiptsCount() == 1,
                    "战果没有和收场一起留下");
            first.close();
        }).thenExecuteAfter(REJOIN_DELAY, () -> next[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> test.assertTrue(next[0].state().getChapter().equals("witness"), "旧玩家未续上胜局"))
                .thenExecute(() -> {
                    var owner = next[0];
                    owner.player().setPos(witness.position());
                    observer.player().setPos(witness.position());
                    var claim = owner.state().getClaimsOrThrow("register");
                    var letter = new StoryLetters(id.toString(), StoryPlayer.LINE, "register", claim);
                    var drops = test.getLevel().getEntitiesOfClass(ItemEntity.class, leader.getBoundingBox().inflate(1),
                            item -> letter.matches(item.getItem()));
                    test.assertTrue(drops.size() == 1, "头目落点没有唯一凭据信");
                    drops.getFirst().discard();
                    use(owner, witness);
                    test.assertTrue(owner.offers().isEmpty(), "刚入章就越过了在线日或风声条件");
                    StoryRaidFixture.passDay(owner.player());
                    verifyHandIn(test, owner, observer, witness);
                    owner.close();
                    observer.close();
                }).thenSucceed();
    }

    private static void verifyHandIn(GameTestHelper test, StoryPlayer owner, StoryPlayer observer, LivingEntity actor) {
        owner.player().getInventory().add(new ItemStack(Items.PAPER));
        use(owner, actor);
        var replace = owner.offers().getLast();
        test.assertTrue(replace.buttons().getFirst().getSiblings()
                .contains(Component.translatable("screen.meridian_story.replace")), "普通纸冒充了信物");
        var claim = owner.state().getClaimsOrThrow("register");
        var foreign = new StoryLetters(observer.player().getStringUUID(), StoryPlayer.LINE, "register", claim);
        owner.player().getInventory().add(foreign.letter(1));
        owner.choose(replace);
        use(owner, actor);
        var offer = owner.offers().getLast();
        var own = new StoryLetters(owner.player().getStringUUID(), StoryPlayer.LINE, "register", claim);
        own.take(owner.player(), 1);
        owner.choose(offer);
        test.assertTrue(!owner.state().getClaimsOrThrow("register").getSpent(), "按钮提交时未查实际信物");
        use(owner, actor);
        owner.choose(owner.offers().getLast());
        use(owner, actor);
        var confirmed = owner.offers().getLast();
        observer.choose(confirmed);
        test.assertTrue(!owner.state().getClaimsOrThrow("register").getSpent(), "旁人代交了信");
        test.assertTrue(observer.offers().isEmpty(), "旁人收到选择按钮");
        owner.choose(confirmed);
        owner.choose(confirmed);
        test.assertTrue(own.carried(owner.player()) == 0 && foreign.carried(owner.player()) == 1, "收错了信");
        var state = owner.state();
        test.assertTrue(state.getEnding().equals("attested") && state.getClaimsOrThrow("register").getSpent(),
                "交物未结章");
        test.assertTrue(owner.bubbles(actor.getId()) == 1 && observer.bubbles(actor.getId()) == 1,
                "当事人与旁人应各见一次同一村民的气泡");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void staleButtonsLoseToDistanceAndDirectAttack(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test));
        var actor = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        chosen(test, owner).thenExecute(() -> {
            owner.player().setPos(actor.position());
            UseEntityCallback.EVENT.invoker().interact(owner.player(), owner.player().level(),
                    InteractionHand.MAIN_HAND, actor, null);
            test.assertTrue(owner.offers().isEmpty(), "普通村民交互被剧情占用");
            use(owner, actor);
            var old = owner.offers().getLast();
            owner.player().setPos(actor.position().add(FAR, 0, 0));
            owner.choose(old);
            test.assertTrue(owner.state().getEnding().isEmpty(), "隔远仍能承诺");
            owner.player().setPos(actor.position());
            use(owner, actor);
            Invitation again = owner.offers().getLast();
            test.assertTrue(!again.equals(old), "重谈没有换凭据");
            AttackEntityCallback.EVENT.invoker().interact(owner.player(), test.getLevel(),
                    InteractionHand.MAIN_HAND, actor, null);
            owner.choose(again);
            test.assertTrue(owner.state().getEnding().equals("attacked"), "按钮覆盖了服务端的出手分支");
            owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void committedChapterAcknowledgesReplayWithoutAnotherLetter(GameTestHelper test) {
        UUID id = StoryPlayer.offered(test);
        var owner = StoryPlayer.join(test, id);
        var leader = test.spawn(EntityTypes.PILLAGER, 1, 1, 1);
        chosen(test, owner).thenExecute(() -> {
            StoryRaidFixture.arrive(owner.player(), leader, FEUD);
            leader.die(owner.player().damageSources().playerAttack(owner.player()));
            var crisis = CrisisEncounters.current(owner.player()).orElseThrow();
            var receipt = crisis.getReceiptsMap().values().iterator().next();
            var bodies = new StoryBodies();
            var body = bodies.read(owner.player()).orElseThrow();
            var progress = StoryProgress.read(body);
            var initial = progress.getLinesOrThrow(StoryPlayer.LINE);
            var claim = claim(initial, receipt);
            var committed = initial.toBuilder()
                    .setChapter("witness")
                    .setRevision(initial.getRevision() + 1)
                    .setEnteredAt(receipt.getOnlineTicks())
                    .setLastEncounter(receipt.getEncounter())
                    .putOutcomes("visitors", "win")
                    .putClaims("register", claim)
                    .build();
            var saved = progress.toBuilder().putLines(StoryPlayer.LINE, committed).build();
            test.assertTrue(bodies.write(owner.player(), body, saved), "无法保存确认前的章节");
        }).thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(owner.player()).orElseThrow()
                .getReceiptsCount() == 0, "已提交的同场仍未确认"))
                .thenExecute(() -> {
                    var claim = owner.state().getClaimsOrThrow("register");
                    var letter = new StoryLetters(id.toString(), StoryPlayer.LINE, "register", claim);
                    var copies = test.getLevel().getEntitiesOfClass(ItemEntity.class,
                            leader.getBoundingBox().inflate(1), item -> letter.matches(item.getItem()));
                    test.assertTrue(copies.isEmpty() && !claim.getSpent(), "重放又给了信或吞了补领权");
                    owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void conflictRereadsBodyAndCommitsComputedProgress(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test));
        chosen(test, owner).thenExecute(() -> {
            var store = new StoryStore();
            var bodies = new StoryBodies();
            store.ready(owner.player(), StoryPlayer.ORIGIN);
            int[] attempts = {0};
            boolean success = store.change(owner.player(), current -> {
                attempts[0]++;
                var progress = current.progress();
                if (attempts[0] == 1) {
                    var other = progress.getLinesOrThrow(StoryPlayer.LINE).toBuilder().setRevision(2).build();
                    var competing = progress.toBuilder().putLines(StoryPlayer.LINE, other).build();
                    test.assertTrue(bodies.write(owner.player(), current.body(), competing), "模拟并发提交失败");
                }
                var before = progress.getLinesOrThrow(StoryPlayer.LINE);
                var after = before.toBuilder().setRevision(before.getRevision() + 1).build();
                return progress.toBuilder().putLines(StoryPlayer.LINE, after).build();
            });
            test.assertTrue(success && attempts[0] == 2 && owner.state().getRevision() == 3, "冲突后没有重算");
            owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void readyStartsOldOriginAndReloadExpiresItsOpenOffer(GameTestHelper test) {
        UUID id = StoryPlayer.offered(test);
        var first = StoryPlayer.join(test, id);
        var actor = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        actor.setNoAi(true /* noAi */);
        var next = new StoryPlayer[1];
        var offered = new Invitation[1];
        var reload = new AtomicReference<CompletableFuture<Void>>();
        chosen(test, first).thenExecute(() -> {
            var bodies = new StoryBodies();
            var body = bodies.read(first.player()).orElseThrow();
            var empty = StoryProgress.read(BodySnapshot.getDefaultInstance());
            test.assertTrue(bodies.write(first.player(), body, empty), "未能建立选过出身但还没有线的旧档");
            first.close();
        }).thenExecuteAfter(REJOIN_DELAY, () -> next[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> test.assertTrue(next[0].state().getChapter().equals("visitors"), "READY 未建线"))
                .thenExecute(() -> {
                    var owner = next[0];
                    owner.player().setPos(actor.position());
                    use(owner, actor);
                    offered[0] = owner.offers().getLast();
                    var server = test.getLevel().getServer();
                    reload.set(server.reloadResources(server.getPackRepository().getSelectedIds()));
                }).thenWaitUntil(() -> test.assertTrue(reload.get().isDone(), "等待数据包重载"))
                .thenExecute(() -> {
                    reload.get().join();
                    var owner = next[0];
                    owner.choose(offered[0]);
                    test.assertTrue(owner.state().getEnding().isEmpty(), "重载前的窗口仍然有效");
                    use(owner, actor);
                    owner.choose(owner.offers().getLast());
                    test.assertTrue(owner.state().getEnding().equals("declined"), "重载后无法重新交谈");
                    owner.close();
                }).thenSucceed();
    }

    private static Claim claim(LineState initial, RaidReceipt receipt) {
        var fall = receipt.getFall();
        return Claim.newBuilder()
                .setChapter(initial.getChapter())
                .setEncounter(receipt.getEncounter())
                .setDimension(fall.getDimension())
                .setX(fall.getX())
                .setY(fall.getY())
                .setZ(fall.getZ())
                .build();
    }

    static void use(StoryPlayer owner, LivingEntity actor) {
        var player = owner.player();
        player.setShiftKeyDown(true /* sneaking */);
        UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, actor, null);
        player.setShiftKeyDown(false /* sneaking */);
    }
}
