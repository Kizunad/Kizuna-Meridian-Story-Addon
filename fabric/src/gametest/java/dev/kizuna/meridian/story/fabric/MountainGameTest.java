package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MountainGameTest {
    private static final int TIMEOUT = 600;
    private static final int SPACING = 96;
    private static final int DISTANT = 240;
    private static final int FLOWERS = 2;
    private static final int VANILLA_STACK = 64;
    private static final int TRAINING_DAYS = 30;
    private static final int FIRST_BLOW = 3;
    private static final int SPARE_SITE = 12;
    private static final int DISARM_HITS = 8;

    private record Run(GameTestHelper test, StoryPlayer owner, MountainScene scene) {
        ServerPlayer player() {
            return owner.player();
        }

        void killFather() {
            var father = scene.father();
            father.die(player().damageSources().playerAttack(player()));
            var record = CrisisEncounters.current(player()).orElseThrow();
            test.assertTrue(record.getSlainAdultsList().stream()
                    .anyMatch(dead -> dead.getVictim().equals(father.getStringUUID()) && dead.getName().equals("沈石")),
                    "死亡记录未保存死者身份和当时的名字");
        }

        RoadsideCase enter(String kind) {
            scene.age();
            boolean opened = scene.enter();
            var level = player().level();
            test.assertTrue(opened && scene.current().getKind().equals(kind), "未开场 " + kind
                    + " 崖面 " + HerbLedge.find(level, player().blockPosition(), player().getDirection())
                    + " 时间 " + level.getOverworldClockTime() + " 脚下 " + player().blockPosition()
                    + " 朝向 " + player().getDirection());
            return scene.current();
        }

        void click(int slot) {
            owner.choose(owner.offers().getLast(), slot);
        }

        RoadsideCase history(RoadsideCase c) {
            return scene.road().getMountainHistoryOrThrow(c.getActor());
        }
    }

    private static void run(GameTestHelper test, int index, MountainScene.Terrain terrain,
                            MountainScene.Lot lot, Consumer<Run> check) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        var fixture = new Run[1];
        PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                test.assertTrue(CrisisEncounters.current(owner.player()).isPresent(), "等待本人身体记录"))
                .thenExecute(() -> {
                    var at = test.absolutePos(BlockPos.ZERO).offset(DISTANT + index * SPACING, 0, DISTANT);
                    var scene = new MountainScene(owner.player(), at, terrain, lot);
                    fixture[0] = new Run(test, owner, scene);
                }).thenIdle(LoneStarRoad.SECOND).thenExecute(() -> {
                    try (owner) {
                        fixture[0].scene.move(fixture[0].scene.feet);
                        check.accept(fixture[0]);
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void emptyKillRecordCannotOpenDebt(GameTestHelper test) {
        run(test, 0, MountainScene.Terrain.TRAIL, MountainScene.Lot.GOOD, r -> {
            r.scene.age();
            test.assertTrue(!r.scene.enter() && !r.scene.road().hasCurrent(), "没杀过成年生人也触发了寻仇");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void compensationClosesOnlyAfterRealPayment(GameTestHelper test) {
        run(test, 1, MountainScene.Terrain.TRAIL, MountainScene.Lot.GOOD, r -> {
            r.killFather();
            test.assertTrue(!r.scene.enter(), "杀人还没过五在线日就有少年上门");
            var c = r.enter(FathersDebt.KIND);
            var offer = r.owner.offers().getLast();
            test.assertTrue(offer.buttons().size() == 1, "先手未知却允许讲实情");
            r.click(0);
            test.assertTrue(r.scene.road().hasCurrent(), "不足额也算赔银");
            var coin = MountainScene.silver(1);
            var youth = r.scene.actor(c.getActor());
            int before = count(youth, coin.getItem());
            r.player().getInventory().add(coin.copy());
            r.click(0);
            var paid = r.history(c);
            test.assertTrue(paid.getEnding().equals("paid_good") && paid.getReturnParty().isEmpty()
                    && !paid.getPayment().isEmpty(), "未按好签清债");
            r.owner.choose(offer, 0);
            test.assertTrue(count(youth, coin.getItem()) == before + 1, "重放赔银按钮改变了实收");
            r.scene.age();
            r.scene.age();
            test.assertTrue(!r.scene.enter(), "同一死者重复立案");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void paidBadKeepsMoneyAndSchedulesSameYouth(GameTestHelper test) {
        run(test, 2, MountainScene.Terrain.TRAIL, MountainScene.Lot.BAD, r -> {
            r.killFather();
            var c = r.enter(FathersDebt.KIND);
            r.player().getInventory().add(MountainScene.silver(1));
            r.click(0);
            var paid = r.history(c);
            long now = CrisisEncounters.current(r.player()).orElseThrow().getOnlineTicks();
            test.assertTrue(paid.getReturnParty().equals(FathersDebt.RETURN) && paid.getReturnAt() - now
                    == TRAINING_DAYS * MountainEncounters.DAY && paid.getActor().equals(c.getActor()),
                    "学成重返的身份或期日不对");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void slainYouthSchedulesMaster(GameTestHelper test) {
        run(test, 3, MountainScene.Terrain.TRAIL, MountainScene.Lot.BAD, r -> {
            r.killFather();
            var c = r.enter(FathersDebt.KIND);
            r.scene.actor(c.getActor()).die(r.player().damageSources().playerAttack(r.player()));
            test.assertTrue(r.history(c).getReturnParty().equals(FathersDebt.MASTER), "本人杀徒没有留下师父的新债");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sparedYouthReturnsStrongerAndSettlesOnce(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        var player = owner.player();
        var run = new Run[1];
        var initial = new RoadsideCase[1];
        PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenExecute(() -> {
            var column = test.absolutePos(BlockPos.ZERO).east(SPARE_SITE * SPACING);
            var scene = new MountainScene(player, column, MountainScene.Terrain.TRAIL, MountainScene.Lot.GOOD);
            run[0] = new Run(test, owner, scene);
        }).thenIdle(LoneStarRoad.SECOND).thenExecute(() -> {
            var r = run[0];
            r.scene.move(r.scene.feet);
            r.killFather();
            initial[0] = r.enter(FathersDebt.KIND);
        }).thenIdle(LoneStarRoad.SECOND).thenExecute(() -> disarm(run[0], initial[0]))
                .thenWaitUntil(() -> test.assertTrue(run[0].scene.road().getLastEnding().equals("spared"),
                        "受伤后存活收手应放行"))
                .thenExecute(() -> {
                    var r = run[0];
                    new RoadScene(player).ground(r.scene.feet);
                    StoryRaidFixture.roadClock(player, r.history(initial[0]).getReturnAt());
                }).thenWaitUntil(() -> test.assertTrue(run[0].history(initial[0]).getShouted(), "等待少年真正重返"))
                .thenExecute(() -> {
                    var r = run[0];
                    var record = CrisisEncounters.current(player).orElseThrow();
                    var leader = r.scene.actor(r.history(initial[0]).getFollowupLeader());
                    test.assertTrue(record.getPending().getLeaderTier().equals("true_qi") && leader.entityTags()
                            .contains("meridian_story.case." + initial[0].getActor()), "升档或人物身份不对");
                    leader.die(player.damageSources().playerAttack(player));
                }).thenWaitUntil(() -> test.assertTrue(run[0].history(initial[0]).getFollowupOutcome().equals("win")
                        && run[0].history(initial[0]).getReturnParty().isEmpty(), "重返胜局未销签"))
                .thenExecute(owner::close).thenSucceed();
    }

    private static void disarm(Run r, RoadsideCase c) {
        var player = r.player();
        var youth = r.scene.actor(c.getActor());
        var attack = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker();
        for (int hit = 0; hit < DISARM_HITS && !r.scene.current().getStage().equals("down"); hit++) {
            attack.interact(player, player.level(), net.minecraft.world.InteractionHand.MAIN_HAND, youth, null);
            var blow = new VanillaDamageFabric.DirectDamage(youth, player.damageSources().playerAttack(player),
                    "left_forearm", FIRST_BLOW);
            r.test.assertTrue(VanillaDamageFabric.damageDirect(blow).isPresent(), "前臂实伤未提交");
        }
        r.test.assertTrue(youth.isAlive() && r.scene.current().getStage().equals("down"), "伤及前臂未活着倒地");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void truthfulAccountCanCloseDebt(GameTestHelper test) {
        run(test, 8, MountainScene.Terrain.TRAIL, MountainScene.Lot.GOOD, r -> truth(r, MountainScene.Lot.GOOD));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void rejectedTruthIsSpentAndFleeingSchedulesOnce(GameTestHelper test) {
        run(test, 9, MountainScene.Terrain.TRAIL, MountainScene.Lot.BAD, r -> truth(r, MountainScene.Lot.BAD));
    }

    private static void truth(Run r, MountainScene.Lot lot) {
        var player = r.player();
        var father = r.scene.father();
        var hit = new VanillaDamageFabric.DirectDamage(player, player.damageSources().mobAttack(father),
                "left_forearm", FIRST_BLOW);
        r.test.assertTrue(VanillaDamageFabric.damageDirect(hit).isPresent(), "父亲首击没有提交");
        father.die(player.damageSources().playerAttack(player));
        var c = r.enter(FathersDebt.KIND);
        var offer = r.owner.offers().getLast();
        r.test.assertTrue(offer.buttons().size() == 2, "有父亲先手证据却没有实情按钮");
        r.owner.choose(offer, 1);
        if (lot == MountainScene.Lot.GOOD) {
            r.test.assertTrue(r.history(c).getEnding().equals("truth_good"), "实情好签未了结");
        } else {
            r.owner.choose(offer, 1);
            r.test.assertTrue(r.scene.current().getAccountHeard() && r.owner.offers().getLast().buttons().size() == 1,
                    "拒听后仍可再说一次");
            r.scene.move(r.scene.feet.west(DISTANT));
            r.scene.tick();
            r.test.assertTrue(r.history(c).getReturnParty().equals(FathersDebt.RETRY), "失散后没有同案重邀");
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void solitaryHarvestAmbushWaitsForDescent(GameTestHelper test) {
        run(test, 10, MountainScene.Terrain.CLIFF, MountainScene.Lot.BAD, r -> {
            var c = r.enter(HerbHarvest.KIND);
            r.scene.pick(c);
            r.test.assertTrue(r.scene.current().getStage().equals("descent")
                    && r.player().getInventory().countItem(Items.WITHER_ROSE) == FLOWERS, "独采未实收两朵");
            r.scene.tick();
            r.test.assertTrue(r.scene.road().hasCurrent(), "还在崖上就触发了伏击");
            r.scene.move(r.scene.feet);
            r.scene.tick();
            r.test.assertTrue(r.history(c).getReturnParty().equals(HerbHarvest.AMBUSH), "返程坏签未安排截路");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void reservationSurvivesSaveAndSecondPicker(GameTestHelper test) {
        run(test, 11, MountainScene.Terrain.CLIFF, MountainScene.Lot.GOOD, r -> {
            var c = r.enter(HerbHarvest.KIND);
            var player = r.player();
            var level = player.level();
            var flower = BlockPos.of(c.getPlant());
            var friend = StoryPlayer.join(test, java.util.UUID.randomUUID());
            try (friend) {
                var other = friend.player();
                other.teleportTo(flower.getX(), flower.getY(), flower.getZ());
                var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(flower),
                        net.minecraft.core.Direction.UP, flower, false /* inside */);
                net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(other, level,
                        net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                r.test.assertTrue(other.getInventory().countItem(Items.WITHER_ROSE) == 0
                        && r.scene.current().getRemaining() == FLOWERS, "旁观者抢走本场药株");
            }
            var ops = net.minecraft.nbt.NbtOps.INSTANCE;
            var saved = HerbLedge.CODEC.encodeStart(ops, HerbLedge.get(level)).getOrThrow();
            var copy = HerbLedge.CODEC.parse(ops, saved).getOrThrow();
            r.test.assertTrue(c.getActor().equals(copy.owner(flower)), "存档往返丢失成熟批次");
            r.scene.pick(c);
            r.scene.move(r.scene.feet);
            r.scene.tick();
            r.test.assertTrue(r.history(c).getEnding().equals("solo_good"), "安全下崖没有保住两朵");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sharedGoodDividesOneRealBatch(GameTestHelper test) {
        run(test, 4, MountainScene.Terrain.CLIFF, MountainScene.Lot.GOOD, r -> {
            var c = r.enter(HerbHarvest.KIND);
            r.click(HerbHarvest.SHARE);
            var partner = r.scene.actor(c.getActor());
            test.assertTrue(r.scene.current().getRemaining() == FLOWERS, "刚约分采就先给了药");
            r.scene.pick(c);
            r.scene.pick(c);
            test.assertTrue(r.player().getInventory().countItem(Items.WITHER_ROSE) == 1
                    && count(partner, Items.WITHER_ROSE) == 1, "两次采收后不是双方各一朵");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void betrayedGathererLosesOnlyThisRose(GameTestHelper test) {
        run(test, 5, MountainScene.Terrain.CLIFF, MountainScene.Lot.BAD, r -> {
            var c = r.enter(HerbHarvest.KIND);
            var partner = r.scene.actor(c.getActor());
            r.player().getInventory().add(new ItemStack(Items.WITHER_ROSE));
            r.click(HerbHarvest.SHARE);
            r.scene.pick(c);
            test.assertTrue(count(partner, Items.WITHER_ROSE) == 1 && partner.getTarget() == r.player(),
                    "坏签同伴没有扣住一朵并翻脸");
            r.scene.move(r.scene.feet.west(DISTANT));
            r.scene.tick();
            test.assertTrue(count(partner, Items.WITHER_ROSE) == FLOWERS
                    && r.player().getInventory().countItem(Items.WITHER_ROSE) == 1
                    && r.history(c).getEnding().equals("lost"), "合采坏签败退没有扣掉本案的花");
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sellingTrailDebitsBuyerAndRetiresPlant(GameTestHelper test) {
        run(test, 6, MountainScene.Terrain.CLIFF, MountainScene.Lot.GOOD, r -> sale(r, "sold_good"));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void paidBuyerCanTurnHostileWithoutAnotherSale(GameTestHelper test) {
        run(test, 7, MountainScene.Terrain.CLIFF, MountainScene.Lot.BAD, r -> sale(r, "sold_bad"));
    }

    private static void sale(Run r, String ending) {
        var c = r.enter(HerbHarvest.KIND);
        var buyer = r.scene.actor(c.getBuyer());
        var coin = MountainScene.silver(FLOWERS);
        CurrencyService.get(r.player().level().getServer()).merchant(buyer);
        var bag = MobInventories.open(buyer);
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            bag.extract(slot, VANILLA_STACK);
        }
        r.click(HerbHarvest.INQUIRE);
        r.test.assertTrue(r.scene.current().getStage().equals(MountainEncounters.OPEN), "买家没钱仍报价");
        bag.insert(coin.copy());
        r.click(HerbHarvest.INQUIRE);
        r.test.assertTrue(r.scene.current().getRemaining() == FLOWERS && count(buyer, coin.getItem()) == FLOWERS,
                "询价就转走钱药");
        var quote = r.owner.offers().getLast();
        r.owner.choose(quote, 0);
        r.owner.choose(quote, 0);
        r.scene.pick(c);
        r.test.assertTrue(count(buyer, coin.getItem()) == 0
                && r.player().getInventory().countItem(coin.getItem()) == FLOWERS
                && count(buyer, Items.MAP) == 1 && count(buyer, Items.WITHER_ROSE) == FLOWERS
                && r.player().getInventory().countItem(Items.WITHER_ROSE) == 0, "成交钱图药权或折返采收不对");
        if (ending.equals("sold_bad")) {
            r.test.assertTrue(buyer.getTarget() == r.player() && r.scene.current().getStage().equals("sold"),
                    "买家付清后没有拦路");
        } else {
            r.test.assertTrue(r.history(c).getEnding().equals(ending), "卖采路图好签未结案");
        }
    }

    private static int count(Mob mob, Item item) {
        return MobInventories.open(mob).snapshot().stream().filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount).sum();
    }
}
