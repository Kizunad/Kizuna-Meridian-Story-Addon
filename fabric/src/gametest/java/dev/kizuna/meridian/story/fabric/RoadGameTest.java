package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadTravel;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class RoadGameTest {
    private static final int TIMEOUT = 900;
    private static final int SECOND = LoneStarRoad.SECOND;
    private static final int AWAY = 128;
    private static final int SPACING = 112;
    private static final int REJOIN = 5;

    @GameTest
    public void onlyWalkingNewSegmentsCounts(GameTestHelper test) {
        var position = Vec3.ZERO;
        var before = new LoneStarRoad.Step(position, 0, 0);
        var stationary = new LoneStarRoad.Step(position, 0, SECOND);
        test.assertTrue(!stationary.followed(before), "挂机在线被算作徒步");
        var moved = position.add(RoadScene.WALK, 0, 0);
        var jump = new LoneStarRoad.Step(moved, 0, SECOND);
        test.assertTrue(!jump.followed(before), "传送或骑行无徒步统计也计时");
        var walk = new LoneStarRoad.Step(moved, RoadScene.WALK * LoneStarRoad.CM, SECOND);
        test.assertTrue(walk.followed(before) && !walk.followed(null), "真实徒步与重登第一点混同");
        var road = RoadTravel.getDefaultInstance();
        for (int tile = 0; tile < LoneStarRoad.DRAW / SECOND; tile++) {
            road = LoneStarRoad.credit(road, tile);
        }
        test.assertTrue(road.getWalkingTicks() == LoneStarRoad.DRAW, "实际行路六分钟未满签");
        var back = LoneStarRoad.credit(road, 1);
        var circle = LoneStarRoad.credit(back, 0);
        test.assertTrue(circle.getWalkingTicks() == road.getWalkingTicks(), "原路折返刷出行路时长");
        test.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void roadYieldsToProtectionAndFullRaid(GameTestHelper test) {
        var run = new Journey(test, 0);
        run.start().thenExecute(() -> {
            var player = run.scene.player();
            test.assertTrue(!CrisisEncounters.requestRoad(player, LoneStarRoad.SCOUT, 1), "首两日提前来人");
            run.scene.camp();
            var campPos = player.blockPosition().east();
            var camp = RoadTravel.newBuilder().setCamp(campPos.asLong()).build();
            test.assertTrue(!LoneStarRoad.outside(player, camp), "营地内被算作在外行路");
            player.level().removeBlock(campPos, false /* moving */);
            test.assertTrue(LoneStarRoad.outside(player, camp), "拆掉营火仍算营地");
            StoryRaidFixture.heatDue(player);
            test.assertTrue(!CrisisEncounters.requestRoad(player, LoneStarRoad.SCOUT, 1), "轻场抢了到期完整招敌");
            run.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void roadEscapeAndLossReloadWithoutFullSettlement(GameTestHelper test) {
        var run = new Journey(test, 1);
        var sequence = run.start();
        run.encounter(sequence, LoneStarRoad.SCOUT);
        sequence.thenExecute(run.owner::close).thenExecuteAfter(REJOIN, run::rejoin)
                .thenWaitUntil(() -> run.outcome("escape")).thenExecute(() -> {
                    var record = run.scene.record();
                    test.assertTrue(record.getSettledAt() == 0 && record.getSettledCount() == 0,
                            "轻场写入了完整招敌静默时刻");
                    test.assertTrue(!record.hasGrudge() && record.getRoadCount() == 1 && record.getRoadSettledAt() > 0,
                            "轻场逃脱产生记仇者或未记自己的收场");
                    var player = run.scene.player();
                    player.setGameMode(GameType.SURVIVAL);
                    long ticket = record.getRoadRequest() + 1;
                    test.assertTrue(!CrisisEncounters.requestRoad(player, LoneStarRoad.SCOUT, ticket),
                            "重登刷新了两日冷却");
                });
        run.encounter(sequence, LoneStarRoad.SCOUT);
        sequence.thenExecute(() -> StoryRaidFixture.fallToLeader(run.scene.player(), run.scene.leader()))
                .thenExecute(() -> run.owner.close()).thenExecuteAfter(REJOIN, run::rejoin)
                .thenWaitUntil(() -> run.outcome("loss"))
                .thenExecute(() -> {
                    test.assertTrue(run.scene.record().getRoadCount() == 2 && run.scene.state().getClaimsCount() == 0,
                            "逃输冒领驿记或重登丢失战果");
                    run.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void escortReturnsWithFoodAfterPursuit(GameTestHelper test) {
        new Journey(test, 2).finish(Route.ESCORT);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void soloTradesOnlyOwnTokenForSafePassage(GameTestHelper test) {
        new Journey(test, 3).finish(Route.SOLO);
    }

    private enum Route { ESCORT, SOLO }

    private static final class Journey {
        private final GameTestHelper test;
        private final UUID id;
        private final BlockPos trail;
        private StoryPlayer owner;
        private RoadScene scene;
        private BlockPos camp;

        Journey(GameTestHelper test, int index) {
            this.test = test;
            id = StoryPlayer.offered(test, PhysiqueScene.LONE);
            trail = test.absolutePos(BlockPos.ZERO).offset(AWAY + index * SPACING, 0, AWAY);
            rejoin();
        }

        void rejoin() {
            owner = StoryPlayer.join(test, id);
            scene = new RoadScene(owner.player());
        }

        GameTestSequence start() {
            return PhysiqueGameTest.choose(test, owner, PhysiqueScene.LONE)
                    .thenWaitUntil(() -> test.assertTrue(scene.state().getChapter().equals("walk"), "尚未独行"))
                    .thenExecute(() -> scene.ground(trail));
        }

        void encounter(GameTestSequence sequence, String party) {
            encounter(sequence, party, trail);
        }

        void encounter(GameTestSequence sequence, String party, BlockPos destination) {
            sequence.thenExecute(() -> {
                scene.ground(destination);
                scene.readyRoad();
            }).thenIdle(SECOND * 2).thenWaitUntil(() -> {
                if (!scene.record().hasPending() && scene.player().level().getGameTime() % SECOND == 1) {
                    scene.walk();
                }
                var pending = scene.record().getPending();
                test.assertTrue(pending.getRoadRequest() > 0 && pending.getParty().equals(party),
                        "等待正式行路到场 " + party + "，实际 " + pending.getParty());
            });
        }

        void outcome(String expected) {
            var state = scene.state();
            test.assertTrue(state.getChapter().equals("walk") && expected.equals(state.getOutcomesMap().get("walk")),
                    "读档后等待独行战果 " + expected);
        }

        private StoryPlayer.Invitation invitation() {
            var guest = scene.visitor();
            test.assertTrue(guest != null, "等待本人的脚夫");
            test.assertTrue(guest.blockPosition().closerThan(owner.player().blockPosition(), SPACING / 2),
                    "等待脚夫回到同一地点");
            owner.player().setPos(guest.position());
            int before = owner.offers().size();
            StoryGameTest.use(owner, guest);
            var offers = owner.offers();
            test.assertTrue(offers.size() > before, "脚夫未在聊天栏给出选项");
            return offers.getLast();
        }

        void choose(int button) {
            owner.choose(invitation(), button);
        }

        private void handInWaybill(int button) {
            var receipt = Component.translatable("story.meridian_story.road.waybill_received")
                    .withStyle(ChatFormatting.GRAY);
            var offer = invitation();
            test.assertTrue(!owner.chat().contains(receipt), "打开留宿选项就收了驿记");
            owner.choose(offer, offer.buttons().size() - 1);
            test.assertTrue(scene.delivered("waybill") && !owner.chat().contains(receipt), "改日再谈却收了驿记");
            choose(button);
            test.assertTrue(owner.chat().getLast().equals(receipt), "交出驿记后未收到脚夫收件旁白");
        }

        void finish(Route route) {
            boolean escort = route == Route.ESCORT;
            String proof = escort ? "escort_stub" : "road_token";
            String prize = escort ? "loaf" : "pass";
            var sequence = start();
            encounter(sequence, LoneStarRoad.SCOUT);
            sequence.thenExecute(scene::win)
                    .thenWaitUntil(() -> test.assertTrue(scene.delivered("waybill"), "等待驿记"))
                    .thenExecute(() -> {
                        scene.camp();
                        camp = scene.player().blockPosition();
                    }).thenWaitUntil(() -> handInWaybill(escort ? 0 : 1))
                    .thenWaitUntil(() -> test.assertTrue(scene.delivered(proof), "未领本人凭据"))
                    .thenExecute(() -> {
                        test.assertTrue(party().equals(LoneStarRoad.SCOUT), "未开脉就能引来追兵");
                        scene.openMeridian();
                    });
            if (!escort) {
                sequence.thenExecute(() -> loseToken(proof))
                        .thenWaitUntil(() -> choose(0))
                        .thenWaitUntil(() -> test.assertTrue(scene.delivered(proof), "营地未补发本人的路签"));
            }
            encounter(sequence, LoneStarRoad.PURSUIT, trail.east(AWAY));
            sequence.thenWaitUntil(() -> choose(0))
                    .thenWaitUntil(() -> test.assertTrue(!scene.record().hasPending(), "换道未撤队"))
                    .thenExecute(() -> scene.ground(camp))
                    .thenWaitUntil(() -> choose(0))
                    .thenWaitUntil(() -> test.assertTrue(scene.delivered(prize), "等待终章奖励 " + prize))
                    .thenExecute(() -> {
                        String ending = escort ? "escorted" : "passed";
                        test.assertTrue(scene.state().getEnding().equals(ending), "结局串线");
                    });
            encounter(sequence, LoneStarRoad.SCOUT, trail.south(AWAY));
            sequence.thenExecute(owner::close).thenSucceed();
        }

        private String party() {
            var player = scene.player();
            var store = new StoryStore();
            store.ready(player, PhysiqueScene.LONE);
            return LoneStarRoad.party(player, store.read(player).orElseThrow());
        }

        private void loseToken(String proof) {
            var player = scene.player();
            var claim = scene.state().getClaimsOrThrow(proof);
            var mine = new StoryLetters(player.getStringUUID(), LoneStarRoad.LINE, proof, claim);
            mine.take(player, 1);
            var borrowed = new StoryLetters(UUID.randomUUID().toString(), LoneStarRoad.LINE, proof, claim);
            test.assertTrue(player.getInventory().add(borrowed.letter(1)), "未放入旁人的凭据");
            test.assertTrue(party().equals(LoneStarRoad.SCOUT), "凭旁人的路签引来了追兵");
        }
    }
}
