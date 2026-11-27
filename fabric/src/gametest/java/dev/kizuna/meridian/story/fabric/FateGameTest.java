package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import java.util.BitSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public final class FateGameTest {
    private static final int LIMIT = 1400;
    private static final int SECOND = 20;
    private static final int AWAY = 360;
    private static final int APART = 144;
    private static final int PRICE = 8;
    private static final int OUTSIDE_TALK_RANGE = 8;
    private static final int BEHIND_WALL = 3;
    private static final int READING_OPTIONS = 2;
    private static final int ESCORT_OPENING_ROW = 3;
    private static final double LOW = 0.34;
    private static final int SAMPLE_TICKETS = 512;

    @GameTest(maxTicks = LIMIT)
    public void ordinaryWalkerCanLodgeButCannotReceiveReading(GameTestHelper test) {
        var run = new Case(test, 0, PhysiqueScene.ORDINARY);
        var sequence = run.start();
        sequence.thenExecute(run.road::readyRoad);
        run.walkUntil(sequence, LoneStarRoad.SCOUT);
        sequence.thenExecute(() -> {
            run.road.win();
            test.assertTrue(!run.scene.progress().containsLines(FateRoad.LINE), "平常出身启动了批命");
            test.assertTrue(run.scene.progress().getRoad().getDraws() == 0, "普通人走进了孤星抽签");
        }).thenWaitUntil(() -> test.assertTrue(run.road.delivered("waybill"), "普通人没领到自己的驿记"))
                .thenExecute(run.owner::close).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void readingAndEscortOpenOnceWhenVisible(GameTestHelper test) {
        var run = new Case(test, 5, PhysiqueScene.LONE);
        var sequence = run.start();
        run.meet(sequence, "reading");
        sequence.thenExecute(() -> {
            var distant = run.scene.actor().position().add(0, 0, OUTSIDE_TALK_RANGE);
            run.owner.player().setPos(distant);
        })
                .thenExecuteAfter(SECOND, () -> {
                    test.assertTrue(run.owner.offers(FateScenes.ACTION).isEmpty(), "批命在范围外提前开场");
                    run.hideReadingBehindWall();
                }).thenExecuteAfter(SECOND, () -> {
                    test.assertTrue(run.owner.offers(FateScenes.ACTION).isEmpty(), "批命隔墙提前开场");
                    run.clearReadingWall();
                }).thenWaitUntil(() -> test.assertTrue(
                        !run.owner.offers(FateScenes.ACTION).isEmpty(), "批命未自动开场"));
        sequence.thenExecuteAfter(SECOND, () -> {
            run.checkOpening("reading", READING_OPTIONS, 1);
            run.choose(1);
        });
        run.approach(sequence, "escort", "escort.scene");
        sequence.thenExecuteAfter(SECOND, () -> {
            run.checkOpening("escort.scene", 1, ESCORT_OPENING_ROW);
            run.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void poorWalkerLeavesAndOnlyDeathStartsMourning(GameTestHelper test) {
        var run = new Case(test, 1, PhysiqueScene.LONE);
        var sequence = run.start();
        run.approach(sequence, "reading", "reading");
        sequence.thenExecute(() -> {
            run.talk(0);
            run.at("reading");
            run.talk(1);
        });
        run.joinEscort(sequence);
        sequence.thenExecute(() -> {
            var escort = run.scene.actor();
            var registry = escort.registryAccess();
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
            escort.saveWithoutId(output);
            run.saved = output.buildResult();
            escort.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        }).thenExecuteAfter(SECOND * 2, () -> {
            run.at("walking");
            test.assertTrue(run.scene.progress().getFate().getGrievingUntil() == 0, "仅卸载就记了丧伴");
            var level = run.scene.player().level();
            var restored = EntityTypes.VILLAGER.create(level, EntitySpawnReason.LOAD);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), run.saved));
            test.assertTrue(level.addFreshEntity(restored) && run.scene.actor() == restored, "原同行者未能重新绑定");
            restored.die(restored.damageSources().generic());
        }).thenWaitUntil(() -> {
            run.at("paid_hand");
            var fate = run.scene.progress().getFate();
            test.assertTrue(!fate.getDeceased().isEmpty()
                    && fate.getGrievingUntil() > run.road.record().getOnlineTicks()
                    && run.scene.player().hasEffect(GraveMood.EFFECT), "真死未登记长期低落");
        }).thenExecute(run.owner::close).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void strikeReadingAndProtectEscortAtGate(GameTestHelper test) {
        var run = new Case(test, 2, PhysiqueScene.LONE);
        var sequence = run.start();
        run.meet(sequence, "reading");
        sequence.thenExecute(() -> run.scene.strike(run.scene.actor()));
        run.joinEscort(sequence);
        sequence.thenWaitUntil(() -> {
            var progress = run.scene.progress();
            var state = FateRoad.state(progress);
            String chapter = state.getChapter();
            if (chapter.equals("walking")) {
                var fate = progress.getFate();
                test.assertTrue(fate.hasGate(), "护送未登记村口");
                var player = run.scene.player();
                var escort = FateScenes.actor(player, fate);
                test.assertTrue(escort != null && escort.isAlive(), "等待本场活着的镖师");
                var site = Vec3.atBottomCenterOf(BlockPos.of(fate.getGate()).south());
                escort.setPos(site);
                player.setPos(site);
            }
            test.assertTrue(chapter.equals("paid_hand"), "等候 paid_hand，实际 " + chapter);
            test.assertTrue(state.getOutcomesOrThrow("reading").equals("strike") && state.containsClaims("cord"),
                    "动手应答或活着到村口丢失");
        }).thenExecute(run.owner::close).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void drawingCurrentChangesOnlyAfterReadingAndChoice(GameTestHelper test) {
        new Case(test, 3, PhysiqueScene.LONE).finish(FateRoad.Grade.DRAWN);
    }

    @GameTest(maxTicks = LIMIT)
    public void stillingCurrentChangesOnlyAfterReadingAndChoice(GameTestHelper test) {
        new Case(test, 4, PhysiqueScene.LONE).finish(FateRoad.Grade.STILLED);
    }

    private static final class Case {
        final GameTestHelper test;
        final String origin;
        final StoryPlayer owner;
        final RoadScene road;
        final FateScene scene;
        final BlockPos trail;
        BlockPos gate;
        CompoundTag saved;
        double normalSpeed;
        int baseline;
        BitSet initialFate;
        BitSet initialFootman;

        Case(GameTestHelper test, int index, String origin) {
            this.test = test;
            this.origin = origin;
            owner = StoryPlayer.join(test, StoryPlayer.offered(test, origin));
            road = new RoadScene(owner.player());
            scene = new FateScene(owner.player());
            trail = test.absolutePos(BlockPos.ZERO).offset(AWAY + index * APART, 0, AWAY);
        }

        GameTestSequence start() {
            return PhysiqueGameTest.choose(test, owner, origin)
                    .thenWaitUntil(() -> test.assertTrue(
                            CrisisEncounters.current(owner.player()).isPresent(), "等待生人记录"))
                    .thenExecute(() -> gate = scene.prepare(trail))
                    .thenWaitUntil(() -> test.assertTrue(scene.registered(gate), "村口尚未认领"));
        }

        void at(String wanted) {
            test.assertTrue(scene.chapter().equals(wanted), "等候 " + wanted + "，实际 " + scene.chapter());
        }

        void meet(GameTestSequence sequence, String chapter) {
            sequence.thenWaitUntil(() -> {
                at(chapter);
                test.assertTrue(scene.actor() != null, "等待 " + chapter + " 现场演员");
            });
        }

        void approach(GameTestSequence sequence, String chapter, String prompt) {
            meet(sequence, chapter);
            sequence.thenExecute(() -> owner.player().setPos(scene.actor().position()))
                    .thenWaitUntil(() -> test.assertTrue(narrationCount(prompt) == 1, chapter + " 未自动开场"));
        }

        void hideReadingBehindWall() {
            var actor = scene.actor();
            var level = actor.level();
            var wall = actor.blockPosition().south();
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
            owner.player().setPos(actor.position().add(0, 0, BEHIND_WALL));
        }

        void clearReadingWall() {
            var actor = scene.actor();
            var level = actor.level();
            var wall = actor.blockPosition().south();
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(wall.above(), Blocks.AIR.defaultBlockState());
            owner.player().setPos(actor.position());
        }

        void checkOpening(String key, int optionCount, int rowCount) {
            var offers = owner.offers(FateScenes.ACTION);
            test.assertTrue(offers.size() == rowCount, key + " 开场应自动发送一行按钮");
            test.assertTrue(offers.getLast().buttons().size() == optionCount, key + " 开场按钮数量错误");
            StoryGameTest.use(owner, scene.actor());
            var repeatedOffers = owner.offers(FateScenes.ACTION);
            test.assertTrue(repeatedOffers.size() == rowCount + 1, key + " 潜行重谈应补发一行按钮");
            test.assertTrue(narrationCount(key) == 1, key + " 开场旁白应只发送一次");
        }

        long narrationCount(String key) {
            var prompt = Component.translatable(FateScenes.PREFIX + key);
            return owner.chat().stream().filter(message -> message.getContents().equals(prompt.getContents())).count();
        }

        void talk(int index) {
            var actor = scene.actor();
            owner.player().setPos(actor.position());
            StoryGameTest.use(owner, actor);
            choose(index);
        }

        void choose(int index) {
            var offers = owner.offers(FateScenes.ACTION);
            test.assertTrue(!offers.isEmpty(), "等待命格聊天按钮 " + scene.chapter());
            owner.choose(offers.getLast(), index);
        }

        void joinEscort(GameTestSequence sequence) {
            approach(sequence, "escort", "escort.scene");
            sequence.thenExecute(() -> talk(0)).thenWaitUntil(() -> at("walking"));
        }

        void walkUntil(GameTestSequence sequence, String party) {
            sequence.thenIdle(SECOND * 2).thenWaitUntil(() -> {
                if (!road.record().hasPending() && owner.player().level().getGameTime() % SECOND == 1) {
                    road.walk();
                }
                test.assertTrue(party.equals(road.record().getPending().getParty()), "等待正式到场 " + party);
            });
        }

        StoryStore.View view() {
            var store = new StoryStore();
            store.ready(owner.player(), origin);
            return store.read(owner.player()).orElseThrow();
        }

        int chance() {
            return FateRoad.chance(view());
        }

        BitSet tickets(LoneStarRoad.Track track) {
            var current = view();
            var accepted = new BitSet(SAMPLE_TICKETS);
            for (int draw = 1; draw <= SAMPLE_TICKETS; draw++) {
                if (LoneStarRoad.selected(owner.player(), draw, track, current)) {
                    accepted.set(draw);
                }
            }
            return accepted;
        }

        void finish(FateRoad.Grade expected) {
            var sequence = start();
            approach(sequence, "reading", "reading");
            sequence.thenExecute(() -> {
                Item cash = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:copper_cash"));
                owner.player().getInventory().add(new ItemStack(cash, PRICE));
                talk(0);
                test.assertTrue(owner.player().getInventory().countItem(cash) == 0, "卦钱未扣");
            });
            joinEscort(sequence);
            sequence.thenExecute(() -> talk(0)).thenWaitUntil(() -> at("paid_hand"));
            sequence.thenExecute(() -> {
                test.assertTrue(!FateRoad.state(scene.progress()).containsClaims("cord"), "遣走也领到旗绳");
                baseline = chance();
                initialFate = tickets(LoneStarRoad.Track.FATE);
                initialFootman = tickets(LoneStarRoad.Track.FOOTMAN);
                var wolf = EntityTypes.WOLF.create(owner.player().level(), EntitySpawnReason.EVENT);
                wolf.setPos(owner.player().position());
                owner.player().level().addFreshEntity(wolf);
                wolf.die(owner.player().damageSources().playerAttack(owner.player()));
                StoryRaidFixture.roadClock(owner.player(), road.record().getOnlineTicks() + LoneStarRoad.REST);
                test.assertTrue(CrisisEncounters.requestRoad(owner.player(), FateRoad.STRAY,
                        road.record().getRoadRequest() + 1), "普通散兵未到");
                road.win();
            }).thenExecuteAfter(SECOND * 2, () -> {
                at("paid_hand");
                test.assertTrue(!FateRoad.state(scene.progress()).containsClaims("slip"), "普通狼或散兵给了命帖");
                road.readyRoad(LoneStarRoad.Track.FATE);
            });
            walkUntil(sequence, FateRoad.HIRED);
            sequence.thenExecute(road::win).thenWaitUntil(() -> at("search"))
                    .thenExecute(() -> scene.fallSite("slip")).thenExecuteAfter(SECOND * 2, () -> choose(0))
                    .thenWaitUntil(() -> at("directions"))
                    .thenExecute(() -> {
                        scene.read("slip");
                        var temple = BlockPos.of(scene.progress().getFate().getTemple());
                        var slip = FateRoad.state(scene.progress()).getClaimsOrThrow("slip");
                        test.assertTrue(slip.hasLoreX() && slip.getLoreX() == temple.getX()
                                && slip.getLoreY() == temple.getY() && slip.getLoreZ() == temple.getZ(),
                                "命帖没有保存庙址悬停坐标");
                        var lore = owner.player().getMainHandItem().get(DataComponents.LORE);
                        test.assertTrue(lore != null && lore.lines().getFirst().getString()
                                .contains(Integer.toString(temple.getX())), "命帖悬停没有显示精确庙址");
                        test.assertTrue(owner.chat().stream().noneMatch(message -> message.getString()
                                .contains(Integer.toString(temple.getX()))), "聊天栏泄露庙址坐标");
                        choose(0);
                        owner.player().setPos(Vec3.atBottomCenterOf(temple.south()));
                    });
            meet(sequence, "temple");
            sequence.thenExecute(() -> {
                scene.strike(scene.actor());
                test.assertTrue(FateRoad.MASTER.equals(road.record().getPending().getParty()), "出手未开庙战");
                at("temple");
                road.win();
            }).thenWaitUntil(() -> at("victory")).thenExecute(() -> scene.fallSite("notebook"))
                    .thenExecuteAfter(SECOND * 2, () -> {
                        test.assertTrue(chance() == baseline, "打赢邪修就改变了行路签率");
                        test.assertTrue(tickets(LoneStarRoad.Track.FATE).equals(initialFate), "战胜就改变了同页路签");
                        choose(0);
                        at("unread");
                        test.assertTrue(chance() == baseline, "拾书改变了行路签率");
                        scene.read("notebook");
                        choose(2);
                        at("decision");
                        test.assertTrue(chance() == baseline, "暂缓改了签率");
                        test.assertTrue(tickets(LoneStarRoad.Track.FATE).equals(initialFate), "暂缓改变了同页路签");
                        scene.read("notebook");
                        choose(expected == FateRoad.Grade.DRAWN ? 0 : 1);
                        test.assertTrue(chance() == expected.chance, "结局未改变专属签率");
                        var after = tickets(LoneStarRoad.Track.FATE);
                        int change = after.cardinality() - initialFate.cardinality();
                        test.assertTrue(Integer.signum(change) == Integer.signum(expected.chance - baseline),
                                "同页孤星路签没有依结局增减");
                        test.assertTrue(tickets(LoneStarRoad.Track.FOOTMAN).equals(initialFootman), "结局改变了脚夫签");
                        normalSpeed = owner.player().getAttributeValue(Attributes.ATTACK_SPEED);
                        new PhysiqueScene(owner.player()).injureTo(LOW);
                    }).thenWaitUntil(() -> checkBody(expected)).thenExecute(owner::close).thenSucceed();
        }

        void checkBody(FateRoad.Grade expected) {
            var body = new PhysiqueScene(owner.player());
            var burst = body.effect();
            test.assertTrue(burst != null && burst.getAmplifier() == 1, "低血未爆发二级");
            test.assertTrue(body.physique().getArmBurst() == expected.power, "双臂档位错误");
            test.assertTrue(burst.getDuration() <= expected.seconds * SECOND
                    && burst.getDuration() > (expected.seconds - 1) * SECOND, "持续时间错误");
            PhysiqueGameTest.near(test, owner.player().getAttributeValue(Attributes.ATTACK_SPEED),
                    normalSpeed * (1 + expected.speed), "结局攻速");
        }
    }
}
