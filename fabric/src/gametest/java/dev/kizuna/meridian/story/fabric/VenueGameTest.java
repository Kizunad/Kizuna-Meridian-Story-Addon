package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public final class VenueGameTest {
    private static final BlockPos VENUES = new BlockPos(4096, 0, 4096);
    private static final int SPACING = 64;
    private static final int TIMEOUT = 600;
    private static final int THREE = 3;
    private static final double LETHAL = 10000;

    private record Run(GameTestHelper test, VenueScene scene, RoadsideCase c, StoryPlayer owner) {
        void expect(boolean ok, String message) {
            test.assertTrue(ok, message);
        }

        void enter() {
            scene.move(scene.role(c, "patron").blockPosition().north());
            expect(scene.click(c, VillageRing.ENTER), "不能从发赏人处上台");
        }

        void wager() {
            scene.move(scene.role(c, "house").blockPosition().south());
            expect(scene.click(c, VillageRing.BET), "两边足额却无法受注");
        }

        void hit(LivingEntity victim, LivingEntity attacker) {
            var source = victim.damageSources().mobAttack(attacker);
            var hit = new VanillaDamageFabric.DirectDamage(victim, source, "head", LETHAL);
            var before = VanillaDamageFabric.inspectBody(victim).orElseThrow();
            var receipt = VanillaDamageFabric.damageDirect(hit).orElseThrow();
            expect(receipt.deathPrevented() && !receipt.body().equals(before) && victim.isAlive(),
                    "重击未认输保命 " + victim.getName().getString() + " spared=" + receipt.deathPrevented()
                            + " alive=" + victim.isAlive());
        }
    }

    private static void run(GameTestHelper test, int lane, int roll, String kind, Consumer<Run> check) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        var prepared = new VenueScene[1];
        PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                test.assertTrue(CrisisEncounters.current(owner.player()).isPresent(), "等待身体"))
                .thenExecute(() -> {
                    var p = owner.player();
                    p.setGameMode(GameType.SURVIVAL);
                    var site = VENUES.east(lane * SPACING);
                    prepared[0] = new VenueScene(p, site, roll);
                }).thenIdle(LoneStarRoad.SECOND).thenExecute(() -> {
                    try (owner) {
                        var scene = prepared[0];
                        check.accept(new Run(test, scene, scene.open(kind), owner));
                    } catch (RuntimeException failed) {
                        test.fail(failed.toString());
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void originalCrateReturnedWithoutBanditShare(GameTestHelper test) {
        run(test, 1, VenueScene.BAD, EscortCart.KIND, r -> {
            var s = r.scene;
            s.strike(r.c, "chief");
            s.strike(r.c, "guard");
            r.expect(s.current().getStage().equals(EscortCart.GUARDS), "第二击改了阵营");
            s.retreat(r.c, "chief", "bandit");
            r.expect(s.current().getStage().equals(EscortCart.CLAIM), "原主未到场");
            var owner = s.role(s.current(), "owner");
            s.move(owner.blockPosition().south());
            var box = (Container) s.player.level().getBlockEntity(s.center.offset(RoadVenue.CRATE));
            var pack = MobInventories.open(owner);
            r.expect(s.click(r.c, EscortCart.RETURN), "货单未换回原箱");
            r.expect(box.isEmpty() && s.player.getInventory().countItem(Items.CHEST) == 0, "追赃又领了分赃");
            r.expect(pack.snapshot().stream().anyMatch(stack -> net.minecraft.world.item.ItemStack
                    .isSameItemSameComponents(stack, EscortCart.crate(r.c))), "失主未收到原箱");
            r.expect(!s.click(r.c, EscortCart.RETURN) && s.history(r.c).getEnding().equals("returned"),
                    "原箱重复领取");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void banditVictoryTransfersOnlyCartGoods(GameTestHelper test) {
        run(test, 2, VenueScene.GOOD, EscortCart.KIND, r -> {
            r.scene.strike(r.c, "guard");
            r.scene.retreat(r.c, "guard", "patron");
            r.expect(r.scene.history(r.c).getWinner().equals(EscortCart.BANDITS)
                    && r.scene.player.getInventory().countItem(Items.CHEST) == 1, "未分到车上的一箱货");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void bankWitnessClaimsOnlyAfterBattle(GameTestHelper test) {
        run(test, 3, VenueScene.GOOD, EscortCart.KIND, r -> {
            var s = r.scene;
            s.move(s.center.offset(RoadVenue.BANK));
            r.expect(s.click(r.c, EscortCart.WAIT), "未受理坡上观望");
            r.expect(s.player.getInventory().countItem(Items.CHEST) == 0, "战前领货");
            s.retreat(r.c, "chief", "bandit");
            r.expect(s.history(r.c).getEnding().equals("salvage"), "未给遗货");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void bookmakerCannotAcceptUncoveredStake(GameTestHelper test) {
        run(test, 4, VenueScene.GOOD, VillageRing.KIND, r -> {
            var s = r.scene;
            var house = s.role(r.c, "house");
            var pack = MobInventories.open(house);
            for (int slot = 0; slot < MobInventory.SIZE; slot++) {
                pack.extract(slot, Integer.MAX_VALUE);
            }
            var stake = VenueScene.coins("silver_fragment", 1);
            var item = stake.getItem();
            s.player.getInventory().add(stake);
            s.move(house.blockPosition().south());
            r.expect(!s.click(r.c, VillageRing.BET) && s.current().getStage().equals(RoadShow.OPEN),
                    "庄家没有足额押金也受注");
            r.expect(s.player.getInventory().countItem(item) == 1, "拒注却扣了玩家本金");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void masterCanYieldAliveAfterThreeRealBouts(GameTestHelper test) {
        run(test, 5, VenueScene.BAD, VillageRing.KIND, r -> {
            r.enter();
            var witness = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
            witness.snapTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(r.scene.center.above().south()));
            for (var role : List.of("white", "second", "master")) {
                r.hit(r.scene.role(r.c, role), r.scene.player);
            }
            var result = r.scene.history(r.c);
            r.expect(result.getBoutsCount() == THREE && result.getRenowned() && result.getEnding().equals("won"),
                    "三胜未领赏传名");
            var purse = new ScenePurse(r.scene.player, r.c);
            r.expect(purse.paid("award"), "未实付赏银");
            r.expect(!CrisisEncounters.ringVictory(r.scene.player, r.c.getNumber()), "同一轮重复传名");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void injuredChallengerYieldsToMaster(GameTestHelper test) {
        run(test, 6, VenueScene.BAD, VillageRing.KIND, r -> {
            r.enter();
            for (var role : List.of("white", "second")) {
                r.hit(r.scene.role(r.c, role), r.scene.player);
            }
            r.hit(r.scene.player, r.scene.role(r.c, "master"));
            var result = r.scene.history(r.c);
            r.expect(result.getWounded() && result.getEnding().equals("hurt"), "受伤败北未记回执");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void whiteSashPayoutUsesBothDeposits(GameTestHelper test) {
        run(test, 7, VenueScene.GOOD, VillageRing.KIND, r -> {
            var s = r.scene;
            s.player.getInventory().add(VenueScene.coins("silver_fragment", 1));
            r.wager();
            var fighter = s.role(r.c, "second");
            r.hit(fighter, s.role(r.c, "white"));
            r.expect(r.owner.packets().noneMatch(packet -> packet instanceof BubblePayload bubble
                    && bubble.entityId() == fighter.getId()), "擂台拳手受击冒出嘲讽气泡");
            var purse = new ScenePurse(s.player, r.c);
            r.expect(s.history(r.c).getEnding().equals("bet_good") && purse.paid("pay_cover")
                    && purse.paid("pay_stake") && !purse.paid("award"), "受注没有独立结清两笔实款");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void chosenBanditsCanLoseWithoutPayment(GameTestHelper test) {
        run(test, 8, VenueScene.GOOD, EscortCart.KIND, r -> {
            var s = r.scene;
            s.strike(r.c, "patron");
            s.retreat(r.c, "chief", "bandit");
            var paid = new ScenePurse(s.player, r.c);
            r.expect(!paid.paid("award") && paid.paid("refund_prize")
                    && s.history(r.c).getEnding().equals("withdraw"), "帮贼落败仍领赏");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void guardVictoryPaysAndKeepsOneEscort(GameTestHelper test) {
        run(test, 9, VenueScene.GOOD, EscortCart.KIND, r -> {
            var s = r.scene;
            s.strike(r.c, "bandit");
            s.retreat(r.c, "chief", "bandit");
            var favor = s.history(r.c);
            r.expect(favor.getEscortFavor() && new ScenePurse(s.player, r.c).paid("award"), "镖头未付银留人情");
            var next = s.prepareGround(s.center.north(SPACING));
            var feet = next.relative(s.player.getDirection().getOpposite(), RoadVenue.APPROACH);
            var level = s.player.level();
            level.setBlockAndUpdate(feet.below(), Blocks.DIRT_PATH.defaultBlockState());
            s.move(feet);
            var server = level.getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            var now = CrisisEncounters.current(s.player).orElseThrow().getOnlineTicks();
            StoryRaidFixture.roadClock(s.player, now + RoadShow.REST);
            r.expect(s.enterRoad() && s.current().getStage().equals("escort"), "下次同号山路未见镖师");
            var visit = s.current();
            r.expect(!s.history(r.c).getEscortFavor(), "照应未消耗");
            var fork = next.offset(RoadVenue.FORK);
            s.move(fork);
            s.tick();
            r.expect(s.current().equals(visit), "镖师未到路口就散场");
            s.role(visit, "guard").snapTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(fork));
            s.tick();
            r.expect(s.history(visit).getEnding().equals("fork"), "两人已到路口却未散场");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void bountyRequiresRealBanditVictory(GameTestHelper test) {
        run(test, 10, VenueScene.BAD, EscortCart.KIND, r -> {
            var s = r.scene;
            s.strike(r.c, "guard");
            s.retreat(r.c, "guard", "patron");
            var warrant = s.history(r.c);
            r.expect(warrant.getWarrants() == 2 && warrant.getReturnParty().equals(EscortCart.WARRANT),
                    "未挂两拨悬赏");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twoWarrantsArriveAndSettleSeparately(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        var holder = new Run[1];
        var player = owner.player();
        var sequence = PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                test.assertTrue(CrisisEncounters.current(player).isPresent(), "等待身体")).thenExecute(() -> {
            var scene = new VenueScene(player, VENUES, VenueScene.BAD);
            player.setGameMode(GameType.SURVIVAL);
            var c = scene.open(EscortCart.KIND);
            holder[0] = new Run(test, scene, c, owner);
            scene.strike(c, "guard");
            scene.retreat(c, "guard", "patron");
            new RoadScene(player).ground(scene.center.south(SPACING));
            var record = CrisisEncounters.current(player).orElseThrow();
            StoryRaidFixture.roadClock(player, record.getOnlineTicks() + LoneStarRoad.REST);
        });
        waitForWarrant(sequence, holder);
        sequence.thenWaitUntil(() -> {
            var r = holder[0];
            var c = r.scene.history(r.c);
            r.expect(c.getWarrants() == 1 && c.getWarrantOutcomesCount() == 1 && c.getTicket() == 0,
                    "第一拨未独立销签");
            long now = CrisisEncounters.current(r.scene.player).orElseThrow().getOnlineTicks();
            r.expect(c.getReturnAt() >= now + EscortCart.WARRANT_INTERVAL - LoneStarRoad.SECOND,
                    "第二拨未间隔两个在线日");
            StoryRaidFixture.roadClock(r.scene.player, c.getReturnAt());
        });
        waitForWarrant(sequence, holder);
        sequence.thenWaitUntil(() -> {
            var r = holder[0];
            var c = r.scene.history(r.c);
            r.expect(c.getWarrants() == 0 && c.getReturnParty().isEmpty()
                    && c.getWarrantOutcomesList().equals(List.of("escape", "escape")), "两拨未各记收场");
        }).thenExecute(owner::close).thenSucceed();
    }

    private static void waitForWarrant(GameTestSequence sequence, Run[] holder) {
        sequence.thenWaitUntil(() -> {
            var r = holder[0];
            var c = r.scene.history(r.c);
            r.expect(c.getShouted() && !c.getFollowupLeader().isEmpty(), "等本拨悬赏人到场");
            var leader = MountainEncounters.actor(r.scene.player, c.getFollowupLeader()).orElseThrow();
            r.expect(!leader.entityTags().contains("meridian.no_taunt"), "悬赏来犯头目误挂访客静音标签");
            r.expect(CrisisEncounters.leaveRoad(r.scene.player, c.getTicket()), "本拨到场签没有对应的轻场");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fixedBoutPaysHouseAfterWhiteSashYields(GameTestHelper test) {
        run(test, 11, VenueScene.BAD, VillageRing.KIND, r -> {
            var s = r.scene;
            s.player.getInventory().add(VenueScene.coins("silver_fragment", 1));
            r.wager();
            var level = s.player.level();
            var past = s.current().toBuilder().setArrivedAt(level.getGameTime() - TIMEOUT).build();
            s.events.update(s.player, s.current(), past);
            s.tick();
            var purse = new ScenePurse(s.player, r.c);
            r.expect(s.history(r.c).getEnding().equals("bet_bad") && purse.paid("pay_cover")
                    && purse.paid("pay_stake"), "串局未收回两份押金");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void exposedLotsReturnRealDepositBeforeThanks(GameTestHelper test) {
        run(test, 12, VenueScene.GOOD, VillageRing.KIND, r -> {
            r.scene.strike(r.c, "house");
            var purse = new ScenePurse(r.scene.player, r.c);
            r.expect(r.scene.history(r.c).getEnding().equals("exposed") && purse.paid("return_deposit")
                    && purse.paid("thanks") && !purse.paid("pay_stake"), "揭庄未按实退钱");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void leavingPlatformForfeitsThreeBoutPrize(GameTestHelper test) {
        run(test, 13, VenueScene.GOOD, VillageRing.KIND, r -> {
            r.enter();
            r.scene.move(r.scene.center.south(RoadVenue.APPROACH));
            r.scene.tick();
            r.expect(r.scene.history(r.c).getEnding().equals("left")
                    && !new ScenePurse(r.scene.player, r.c).paid("award"), "自己下台仍领赏");
        });
    }
}
