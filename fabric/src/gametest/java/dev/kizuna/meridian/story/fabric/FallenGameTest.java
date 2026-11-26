package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** 倒在路边的修士：实际徒步开场，救护、夺卷、走开与同案追兵都走正式入口；好坏签用固定种子挑场次号。 */
public final class FallenGameTest {
    private static final int TIMEOUT = 1200;
    private static final int SECOND = LoneStarRoad.SECOND;
    private static final int AWAY = 160;
    private static final int SPACING = 1024;
    private static final int TILE = 16;
    private static final int FAR = 32;
    private static final int SEARCH = 48;
    private static final int PAVE = 5;
    private static final int RAISE = 3;
    private static final int STRANDED = 512;
    private static final String KEY = "story.meridian_story.fallen.";

    @GameTest(maxTicks = TIMEOUT)
    public void walkingOntoRoadsideOffersThreeWaysAndLeaving(GameTestHelper test) {
        var road = new Road(test, 0, true);
        road.open().thenExecute(() -> {
            var actor = road.actor();
            test.assertTrue(actor instanceof WanderingTrader && actor.getHealth() < actor.getMaxHealth(),
                    "伤者不是受了伤的流浪商人");
            test.assertTrue(road.current().getHeld() && road.originals() == 0, "原卷不在伤者怀里或已先造出实物");
            test.assertTrue(road.owner.chat().contains(narration("opening")), "开场旁白未播");
            var offer = road.owner.offers().getLast();
            test.assertTrue(offer.buttons().size() == 2, "带药时应有救护与走开两个可点选项");
            road.owner.choose(offer, FallenCultivator.LEAVE);
            var roadside = road.roadside();
            test.assertTrue(!roadside.hasCurrent() && roadside.getLastEnding().equals("left")
                    && roadside.getRestUntil() > 0, "走开未收场或未起冷却");
            test.assertTrue(road.potions() == 1 && road.originals() == 0 && !FallenCultivator.carries(
                    road.player(), road.case0), "走开却动了药瓶或卷");
            road.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void rescueGoodHandsOverTheOnlyScroll(GameTestHelper test) {
        var road = new Road(test, 1, true);
        road.open().thenExecute(() -> {
            var current = road.current();
            var actor = road.actor();
            float before = actor.getHealth();
            road.owner.choose(road.owner.offers().getLast(), FallenCultivator.RESCUE);
            test.assertTrue(actor.getHealth() > before, "宿主未确认实际治疗：" + before + " → " + actor.getHealth()
                    + "/" + actor.getMaxHealth() + " 距离 " + actor.distanceTo(road.player()) + " 药 " + road.potions()
                    + " " + road.roadside().getCurrent().getStage());
            test.assertTrue(road.potions() == 0 && road.player().getInventory().countItem(Items.GLASS_BOTTLE) == 1,
                    "确认治疗后未扣药或未还空瓶");
            test.assertTrue(FallenCultivator.carries(road.player(), current) && road.originals() == 1,
                    "伤者未把唯一那份原卷递过来");
            test.assertTrue(road.roadside().getLastEnding().equals("rescued")
                    && road.owner.chat().contains(said("rescue.good")), "救获台词或结局未落");
            actor.die(road.player().damageSources().playerAttack(road.player()));
        }).thenIdle(2).thenExecute(() -> {
            test.assertTrue(road.originals() == 1, "救获之后伤者死亡又掉出一份原卷：" + road.originals());
            road.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void someoneElseHealingKeepsThePotion(GameTestHelper test) {
        var road = new Road(test, 2, true);
        road.open().thenExecute(() -> {
            var actor = road.actor();
            var offer = road.owner.offers().getLast();
            actor.heal(actor.getMaxHealth());
            road.owner.choose(offer, FallenCultivator.RESCUE);
            test.assertTrue(road.potions() == 1 && road.player().getInventory().countItem(Items.GLASS_BOTTLE) == 0,
                    "旁人已治好、本人治疗未提交却扣了药瓶");
            var roadside = road.roadside();
            test.assertTrue(roadside.hasCurrent() && roadside.getCurrent().getStage().equals(FallenCultivator.OPEN)
                    && !roadside.getCurrent().getHealed(), "未提交的救护被登记");
            test.assertTrue(!FallenCultivator.carries(road.player(), road.current()), "未救护就拿到了卷");
            road.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void rescueBadEscortDiesMidway(GameTestHelper test) {
        var road = new Road(test, 3, false);
        var sequence = road.open();
        road.pursued(sequence, FallenCultivator.PURSUIT);
        sequence.thenExecute(() -> {
            test.assertTrue(road.owner.chat().contains(narration("rescue.bad")), "追兵到场未喝止");
            test.assertTrue(!road.owner.chat().contains(narration("escort.safe")), "到场即宣告脱险");
            road.actor().die(road.player().damageSources().generic());
        }).thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("died"), "等待失救登记"))
                .thenExecute(() -> {
                    var chat = road.owner.chat();
                    test.assertTrue(chat.contains(narration("escort.died")) && !chat.contains(narration("escort.lost")),
                            "失救句未替代失散句");
                    test.assertTrue(road.originals() == 1, "伤者死亡掉卷不止一份");
                    new RoadScene(road.player()).win();
                    road.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void rescueBadEscortSurvivesThePursuit(GameTestHelper test) {
        var road = new Road(test, 4, false);
        var sequence = road.open();
        road.pursued(sequence, FallenCultivator.PURSUIT);
        sequence.thenExecute(() -> new RoadScene(road.player()).win())
                .thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("escorted"), "等待护送收场"))
                .thenExecute(() -> {
                    test.assertTrue(road.owner.chat().contains(narration("escort.safe")), "护送成功句未播");
                    test.assertTrue(!FallenCultivator.carries(road.player(), road.case0) && road.originals() == 0,
                            "护送脱险却得了谢礼");
                    road.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void strikeWithoutGrabbingLeavesTheScroll(GameTestHelper test) {
        var road = new Road(test, 5, true);
        road.open().thenExecute(() -> {
            road.fillBag();
            road.strike();
            test.assertTrue(road.roadside().getCurrent().getStage().equals(FallenCultivator.WREST), "出手未锁夺卷");
            test.assertTrue(road.current().getHeld() && road.originals() == 0, "行囊满了卷却离了手");
            road.walkAway();
        }).thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("missed"), "等待未得手离场"))
                .thenExecute(() -> {
                    var chat = road.owner.chat();
                    test.assertTrue(chat.contains(narration("wrest.missed")) && !chat.contains(narration("wrest.good"))
                            && !chat.contains(narration("wrest.bad")), "未夺到却宣告得手");
                    test.assertTrue(!FallenCultivator.carries(road.player(), road.case0), "未夺到却转移了卷");
                    road.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void droppedScrollTakenByAnotherIsNoWin(GameTestHelper test) {
        var road = new Road(test, 6, true);
        road.open().thenExecute(() -> {
            road.fillBag();
            road.strike();
            road.actor().die(road.player().damageSources().playerAttack(road.player()));
        }).thenWaitUntil(() -> test.assertTrue(!road.dropped().isEmpty(), "等待伤者掉卷")).thenIdle(SECOND + 1)
                .thenExecute(() -> {
            var dropped = road.dropped();
            test.assertTrue(dropped.size() == 1 && !road.current().getHeld(), "伤者死亡未掉出唯一原卷：" + dropped.size());
            dropped.getFirst().discard();
            road.walkAway();
        }).thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("missed"), "等待失物收场"))
                .thenExecute(() -> {
                    test.assertTrue(!road.owner.chat().contains(narration("wrest.good")), "卷被旁人拿走仍播得手句");
                    road.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void wrestGoodKeepsScrollWithoutPursuit(GameTestHelper test) {
        var road = new Road(test, 7, true);
        road.open().thenExecute(() -> {
            // 夺到手的同一刻伤者断气：卷已离手，死亡不能再掉一份。
            road.strike();
            road.actor().die(road.player().damageSources().playerAttack(road.player()));
        }).thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("wrested"), "等待夺卷收场"))
                .thenExecute(() -> {
                    test.assertTrue(road.owner.chat().contains(narration("wrest.good"))
                            && road.roadside().getInquiryAt() == 0, "无证好签仍排了门人");
                    test.assertTrue(FallenCultivator.carries(road.player(), road.case0) && road.originals() == 1,
                            "夺到的不是那唯一一份：" + road.originals());
                    road.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void wrestBadBringsTheSchoolWithTheSeal(GameTestHelper test) {
        var road = new Road(test, 8, false);
        var sequence = road.open().thenExecute(road::strike)
                .thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("marked"), "等待坏签夺卷"))
                .thenExecute(() -> {
                    var roadside = road.roadside();
                    long now = new RoadScene(road.player()).record().getOnlineTicks();
                    long wait = roadside.getInquiryAt() - now;
                    test.assertTrue(road.owner.chat().contains(narration("wrest.bad"))
                            && wait >= 3 * CrisisRules.DAY_TICKS
                            && wait <= 5 * CrisisRules.DAY_TICKS,
                            "朱印未现或门人不在三到五个在线日后");
                    StoryRaidFixture.roadClock(road.player(), roadside.getInquiryAt());
                });
        road.pursued(sequence, FallenCultivator.SCHOOL);
        sequence.thenWaitUntil(() -> test.assertTrue(road.owner.chat().contains(narration("school")), "门人未亮朱印"))
                .thenExecute(() -> {
                    new RoadScene(road.player()).win();
                    road.owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT * 2)
    public void strandedActorIsReleasedWhenHisChunkReloads(GameTestHelper test) {
        // 主人下线期间伤者随区块卸下；重登远处收场时他不在场，等区块再载入才按已收的案放他走。
        var road = new Road(test, 9, true);
        var actorId = new UUID[1];
        road.open().thenExecute(() -> {
            // 同一刻挪远再下线：剧情还没来得及按距离收场，重登就落在远处。
            actorId[0] = UUID.fromString(road.case0.getActor());
            road.scene().ground(BlockPos.of(road.case0.getSite()).east(STRANDED));
            road.owner.close();
        }).thenWaitUntil(() -> test.assertTrue(test.getLevel().getEntity(actorId[0]) == null, "等待伤者随区块卸下"))
                .thenExecute(road::rejoin)
                .thenWaitUntil(() -> test.assertTrue(road.roadside().getLastEnding().equals("passed"),
                        "等待远处收场"))
                .thenExecute(() -> road.scene().ground(BlockPos.of(road.case0.getSite())))
                .thenWaitUntil(() -> {
                    var actor = test.getLevel().getEntity(actorId[0]);
                    test.assertTrue(actor instanceof Mob mob && !mob.isNoAi()
                            && !mob.entityTags().contains(FallenCultivator.GUEST), "伤者载回后仍是无 AI 的常驻来客");
                }).thenExecute(() -> road.owner.close()).thenSucceed();
    }

    static Component narration(String key) {
        return Component.translatable(KEY + key).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    static Component said(String key) {
        return Component.translatable(KEY + key).withStyle(ChatFormatting.ITALIC).copy()
                .withStyle(ChatFormatting.GRAY);
    }

    /** 一名普通出身玩家走上一段必然开场的路肩；场次号按要的好坏签从固定种子里挑。 */
    static final class Road {
        private final GameTestHelper test;
        private final boolean good;
        private final BlockPos trail;
        StoryPlayer owner;
        private RoadScene scene;
        RoadsideCase case0;
        private int walks;

        Road(GameTestHelper test, int index, boolean good) {
            this.test = test;
            this.good = good;
            owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
            // 同批用例的原点彼此只隔几十格，各用例的路段按序号拉开，免得铺地、证人与伤者互相串场。
            trail = test.absolutePos(BlockPos.ZERO).offset(-AWAY, 0, -AWAY - index * SPACING);
        }

        void rejoin() {
            var id = owner.player().getUUID();
            owner = StoryPlayer.join(test, id);
            scene = new RoadScene(owner.player());
            owner.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        }

        RoadScene scene() {
            return scene;
        }

        ServerPlayer player() {
            return owner.player();
        }

        Roadside roadside() {
            return new StoryBodies().read(player()).map(StoryProgress::read).orElseThrow().getRoadside();
        }

        RoadsideCase current() {
            return roadside().getCurrent();
        }

        Mob actor() {
            return FallenCultivator.actor(player(), case0).orElseThrow();
        }

        GameTestSequence open() {
            return PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                    test.assertTrue(CrisisEncounters.current(player()).isPresent(), "等待来犯记录")).thenExecute(() -> {
                scene = new RoadScene(player());
                prepare();
                var start = column();
                scene.ground(start);
                pave();
                scene.ground(start);
                player().setYRot(0);
                var server = player().level().getServer();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                player().getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            }).thenWaitUntil(() -> {
                if (!roadside().hasCurrent() && player().level().getGameTime() % SECOND == 1) {
                    pace();
                }
                var player = player();
                test.assertTrue(roadside().hasCurrent() && !owner.offers().isEmpty(), "等待路肩开场 site="
                        + FallenCultivator.site(player.level(), player.blockPosition(), player.getDirection())
                                .isPresent() + " tiles=" + roadside().getTilesCount() + " water=" + player.isInWater()
                        + " ground=" + player.onGround() + " walks=" + walks + " pending=" + scene.record().hasPending()
                        + " offers=" + owner.offers().size() + " current=" + roadside().hasCurrent() + " actor="
                        + (roadside().hasCurrent() ? String.valueOf(player.level().getEntity(UUID.fromString(
                                roadside().getCurrent().getActor())) instanceof Mob mob ? mob.entityTags() + " t="
                                + mob.tickCount + " hp=" + mob.getHealth() + " alive=" + mob.isAlive() : "none") : "-"));
                case0 = current();
                test.assertTrue(RoadsideRules.good(case0.getRoll()) == good, "固定种子挑错了签");
            });
        }

        void pursued(GameTestSequence sequence, String party) {
            if (party.equals(FallenCultivator.PURSUIT)) {
                sequence.thenExecute(() -> owner.choose(owner.offers().getLast(), FallenCultivator.RESCUE))
                        .thenExecute(() -> test.assertTrue(potions() == 0
                                && current().getStage().equals(FallenCultivator.ESCORT), "坏签救护未转护送"));
            }
            sequence.thenWaitUntil(() -> {
                var pending = scene.record().getPending();
                test.assertTrue(pending.getArrived() && pending.getParty().equals(party), "等待 " + party + " 到场");
            }).thenIdle(SECOND + 1);
        }

        private void prepare() {
            // 开场前两日轻场静默照常；场次号从固定种子里挑到所要的好坏签。
            var bodies = new StoryBodies();
            var body = bodies.read(player()).orElseThrow();
            var progress = StoryProgress.read(body);
            long seed = FallenCultivator.seed(player());
            long number = 1;
            while (RoadsideRules.good(RoadsideRules.roll(seed, number)) != good) {
                number++;
            }
            var roadside = progress.getRoadside().toBuilder().setCases(number - 1).build();
            test.assertTrue(bodies.write(player(), body, progress.toBuilder().setRoadside(roadside).build()),
                    "未能预置场次号");
            StoryRaidFixture.roadClock(player(), scene.record().getOnlineTicks() + LoneStarRoad.REST);
        }

        private BlockPos column() {
            // 只按固定种子挑一个必开签网格，网格位置与地形无关。
            long seed = FallenCultivator.seed(player());
            for (int step = 0; ; step++) {
                // 起点贴着网格西缘，基线前后的两步都还在同一格里，不受采样相位影响。
                var shifted = trail.east(step * TILE);
                var column = shifted.west(Math.floorMod(shifted.getX(), TILE) - 1);
                if (RoadsideRules.opens(seed, FallenCultivator.tile(column))) {
                    return column;
                }
            }
        }

        private void pave() {
            // 在地表上方垫高铺一段原版草地路肩，避开水流；前方与脚程都留出净空。
            var level = player().level();
            var feet = player().blockPosition().above(RAISE);
            for (int x = -PAVE; x <= 2 * PAVE; x++) {
                for (int z = -PAVE; z <= 2 * PAVE; z++) {
                    var floor = feet.offset(x, -1, z);
                    level.setBlockAndUpdate(floor, Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int up = 1; up <= PAVE; up++) {
                        level.setBlockAndUpdate(floor.above(up), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        private void pace() {
            // 在同一必开网格内来回踱步，每秒一段真实脚程，开场后就停步。
            var player = player();
            int sign = walks++ % 2 == 0 ? 1 : -1;
            player.setPos(player.position().add(sign * RoadScene.WALK, 0, 0));
            player.awardStat(Stats.WALK_ONE_CM, RoadScene.WALK * LoneStarRoad.CM);
            player.setOnGround(true /* onGround */);
        }

        void strike() {
            var player = player();
            AttackEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                    actor(), null);
        }

        void fillBag() {
            var inventory = player().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    inventory.setItem(slot, new ItemStack(Items.DIRT, Items.DIRT.getDefaultMaxStackSize()));
                }
            }
        }

        void walkAway() {
            var site = BlockPos.of(case0.getSite());
            scene.ground(site.west(FAR));
        }

        int potions() {
            return FallenCultivator.potion(player()) < 0 ? 0 : 1;
        }

        List<ItemEntity> dropped() {
            var site = BlockPos.of(case0.getSite());
            return player().level().getEntitiesOfClass(ItemEntity.class, new AABB(site).inflate(SEARCH),
                    item -> FallenCultivator.original(player(), case0, item.getItem()));
        }

        int originals() {
            // 行囊与地上的原卷实物合起来至多一份；伤者怀里那份只在交出、被夺或掉落时才造出来。
            var inventory = player().getInventory();
            int count = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (FallenCultivator.original(player(), case0, inventory.getItem(slot))) {
                    count += inventory.getItem(slot).getCount();
                }
            }
            return count + dropped().stream().mapToInt(item -> item.getItem().getCount()).sum();
        }
    }
}
