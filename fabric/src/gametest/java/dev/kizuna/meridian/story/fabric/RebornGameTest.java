package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;

public final class RebornGameTest {
    private static final int TIMEOUT = 800;
    private static final int REJOIN = 3;
    private static final int REFRESH = 25;
    private static final int NEAR_DROP = 3;
    private static final int GOODWILL = 5;
    private static final String ORIGIN = "meridian_humanoid:reborn";
    private static final String FEUD = ORIGIN + "#0";
    private static final String PARTY = "meridian_humanoid:old_register";
    private static final String LINE = "meridian_story:reborn";
    private static final BlockPos BELL = new BlockPos(1, 1, 1);

    @GameTest(maxTicks = TIMEOUT)
    public void onlyRebornFeudLeavesItsPageAcrossRelog(GameTestHelper test) {
        var scene = new Case(test);
        scene.start().thenExecute(() -> {
            scene.win("");
            test.assertTrue(CrisisEncounters.current(scene.owner.player()).orElseThrow().getReceiptsCount() == 0,
                    "同队伍的通用场串了重生线");
            scene.win(FEUD);
            test.assertTrue(CrisisEncounters.current(scene.owner.player()).orElseThrow().getReceiptsCount() == 1,
                    "重生仇家胜场没有留下自己的残页回执");
            scene.owner.close();
        }).thenExecuteAfter(REJOIN, scene::rejoin)
                .thenWaitUntil(() -> scene.chapter("seal"))
                .thenExecute(() -> {
                    var letter = scene.letter("fragment");
                    test.assertTrue(letter.claim().getEncounter() == 2, "领了另一场的残页");
                    var fell = BlockPos.containing(letter.claim().getX(), letter.claim().getY(), letter.claim().getZ());
                    var center = net.minecraft.world.phys.Vec3.atCenterOf(fell);
                    var bounds = net.minecraft.world.phys.AABB.ofSize(center, NEAR_DROP, NEAR_DROP, NEAR_DROP);
                    var drops = test.getLevel().getEntitiesOfClass(ItemEntity.class, bounds,
                            item -> letter.matches(item.getItem()));
                    test.assertTrue(drops.size() == 1, "重登未从头目落点取到唯一残页");
                    scene.talkAfterPickup(drops.getFirst());
                }).thenWaitUntil(() -> scene.watching("keep_page")).thenExecute(() -> {
                    test.assertTrue(scene.letter("fragment").carried(scene.owner.player()) == 1, "自留残页却被收走");
                    scene.win(FEUD);
                }).thenWaitUntil(() -> scene.chapter("ledger")).thenExecute(() -> {
                    test.assertTrue(scene.state().getOutcomesOrThrow("keep_page").equals("self_proved"),
                            "没用下拨副印补上自证");
                    scene.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void noVillageSettlesPrivatelyAndOnlyFinalVictoryGivesALetter(GameTestHelper test) {
        var scene = new Case(test);
        var sequence = scene.start();
        for (String next : java.util.List.of("seal", "ledger", "sign")) {
            sequence.thenExecute(() -> scene.win(FEUD)).thenWaitUntil(() -> scene.watching(next))
                    .thenExecute(() -> test.assertTrue(!scene.state().containsClaims("private_letter")
                            && !scene.state().containsClaims("public_letter"), "未到终章就发了尾信"));
        }
        sequence.thenExecute(() -> scene.win(FEUD))
                .thenWaitUntil(() -> scene.delivered("private_letter"))
                .thenExecute(() -> {
                    var state = scene.state();
                    test.assertTrue(state.getEnding().equals("sealed") && state.getOutcomesOrThrow("seal")
                            .equals("self_proved") && state.getOutcomesOrThrow("ledger").equals("copied"),
                            "无村时未用副印、抄本自证封印");
                    StoryRaidFixture.due(scene.owner.player());
                }).thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(scene.owner.player()).orElseThrow()
                        .getPending().getFeud().equals(FEUD), "结局之后没再排本线仇家"))
                .thenExecute(() -> scene.win(FEUD))
                .thenExecuteAfter(REFRESH, () -> {
                    test.assertTrue(scene.letter("private_letter").carried(scene.owner.player()) == 1
                            && scene.state().getLastEncounter() == Case.FINAL_WIN, "结案后胜场重发了尾信");
                    scene.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void oldOriginAttestsAtVillageBellAndPublishesOnce(GameTestHelper test) {
        var scene = new Case(test);
        var villager = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        villager.setNoAi(true /* noAi */);
        var sequence = scene.start().thenExecute(() -> {
            StoryRaidFixture.oldOrigin(scene.owner.player());
            var bodies = new StoryBodies();
            var body = bodies.read(scene.owner.player()).orElseThrow();
            var empty = StoryProgress.read(dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot
                    .getDefaultInstance());
            test.assertTrue(bodies.write(scene.owner.player(), body, empty), "无法模拟升级前的身体");
            scene.owner.close();
        }).thenExecuteAfter(REJOIN, scene::rejoin)
                .thenWaitUntil(() -> scene.watching("roll"))
                .thenExecute(() -> scene.win(FEUD)).thenWaitUntil(() -> scene.chapter("seal"));
        witness(test, scene, villager, sequence);
    }

    private static void witness(GameTestHelper test, Case scene, Villager villager, GameTestSequence sequence) {
        sequence.thenExecute(() -> {
            scene.talk(villager, 0);
            if (scene.state().getChapter().equals("seal")) {
                scene.talk(villager, 0);
            }
        }).thenWaitUntil(() -> scene.delivered("impression")).thenExecute(() -> {
            int before = scene.owner.offers().size();
            StoryGameTest.use(scene.owner, villager);
            test.assertTrue(scene.owner.offers().size() == before, "远离村钟也能查村册");
            test.setBlock(BELL.below(), Blocks.STONE);
            test.setBlock(BELL, Blocks.BELL);
        }).thenWaitUntil(() -> {
            BlockPos at = test.absolutePos(BELL);
            var level = test.getLevel();
            if (!level.isVillage(at)) {
                level.getPoiManager().take(type -> type.is(PoiTypes.MEETING), (type, pos) -> pos.equals(at), at, 1);
            }
            test.assertTrue(level.isVillage(at), "等待村钟登记");
        }).thenExecute(() -> scene.talk(villager, 0))
                .thenWaitUntil(() -> scene.delivered("testimony"));
        publish(test, scene, villager, sequence);
    }

    private static void publish(GameTestHelper test, Case scene, Villager villager, GameTestSequence sequence) {
        sequence.thenExecute(() -> {
            StoryGameTest.use(scene.owner, villager);
            var promise = scene.owner.offers().getLast();
            var leader = test.spawn(EntityTypes.PILLAGER, 1, 1, 1);
            StoryRaidFixture.arrive(scene.owner.player(), leader, FEUD, PARTY);
            scene.owner.choose(promise);
            test.assertTrue(scene.state().getEnding().isEmpty(), "来人已经到场仍可交证立遗书");
            scene.owner.close();
        }).thenExecuteAfter(REJOIN, scene::rejoin)
                .thenWaitUntil(() -> scene.watching("sign")).thenExecute(() -> {
                    scene.talk(villager, 0);
                    scene.owner.choose(scene.owner.offers().getLast());
                }).thenWaitUntil(() -> scene.delivered("public_letter")).thenExecute(() -> {
                    test.assertTrue(scene.state().getEnding().equals("published")
                            && scene.state().getOutcomesOrThrow("ledger").equals("archived"), "村册未留旁证");
                    int gossip = villager.getGossips().getReputation(scene.id,
                            type -> type == GossipType.MINOR_POSITIVE);
                    test.assertTrue(gossip == GOODWILL && scene.letter("public_letter").carried(scene.owner.player())
                            == 1 && !scene.state().containsClaims("private_letter"), "重发尾信或重复传好话");
                    scene.owner.close();
                }).thenSucceed();
    }

    private static final class Case {
        private static final int FINAL_WIN = 4;
        private final GameTestHelper test;
        private final UUID id;
        private StoryPlayer owner;

        Case(GameTestHelper test) {
            this.test = test;
            id = StoryPlayer.offered(test, ORIGIN);
            rejoin();
        }

        void rejoin() {
            owner = StoryPlayer.join(test, id);
        }

        GameTestSequence start() {
            return test.startSequence().thenWaitUntil(() -> test.assertTrue(owner.packets()
                    .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待重生出身候选"))
                    .thenExecute(() -> owner.chooseOrigin(ORIGIN)).thenWaitUntil(() -> watching("roll"));
        }

        LineState state() {
            return owner.state(LINE);
        }

        void chapter(String name) {
            test.assertTrue(state().getChapter().equals(name), "等待章节 " + name);
        }

        void watching(String name) {
            chapter(name);
            test.assertTrue(StoryRaidFixture.watching(owner.player(), name, FEUD), "等待仇家和章节订阅 " + name);
        }

        void win(String feud) {
            var leader = test.spawn(EntityTypes.PILLAGER, 1, 1, 1);
            StoryRaidFixture.arrive(owner.player(), leader, feud, PARTY);
            leader.die(owner.player().damageSources().playerAttack(owner.player()));
        }

        void talkAfterPickup(ItemEntity drop) {
            drop.setNoPickUpDelay();
            drop.playerTouch(owner.player());
            var actor = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
            talk(actor, 1);
        }

        void talk(Villager actor, int index) {
            owner.player().setPos(actor.position());
            StoryGameTest.use(owner, actor);
            owner.choose(owner.offers().getLast(), index);
        }

        StoryLetters letter(String name) {
            return new StoryLetters(id.toString(), LINE, name, state().getClaimsOrThrow(name));
        }

        void delivered(String name) {
            var claim = state().getClaimsMap().get(name);
            test.assertTrue(claim != null && !claim.getAwaitingDelivery() && letter(name).carried(owner.player()) == 1,
                    "等待签收 " + name);
        }
    }
}
