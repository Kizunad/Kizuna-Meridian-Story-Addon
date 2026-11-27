package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.origin.server.OriginDraw;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

public final class MarketRoadGameTest {
    private static final int LIMIT = 400;
    private static final int ESCAPE_CHUNK_RADIUS = 1;
    private static final int SPACING = 384;
    private static final int FAKE = 30;
    private static final int SPY = 75;
    private static final int TRUE = 95;
    private static final int SILVER = 20;
    private static final int COPPER_PER_FRAGMENT = 100;
    private static final double HIT = 1.0;
    private static final long SEED = 20261127L;
    private static final int OFFER_COUNT = 3;

    @GameTest(maxTicks = LIMIT)
    public void counterfeitSaleDeliversOnlyMarkedFakeAndPracticeIsExplicit(GameTestHelper test) {
        var f = new Fixture(test, ManualSeller.KIND, FAKE, 0);
        f.open().thenExecute(() -> {
            var player = f.owner.player();
            var offer = f.owner.offers().getLast();
            f.owner.choose(offer);
            test.assertTrue(f.current().getPayment().isEmpty(), "没钱也成交");
            f.scene.fund();
            var bag = MobInventories.open(f.actor());
            int before = silver(bag.snapshot());
            f.owner.choose(offer);
            var c = f.current();
            test.assertTrue(!c.getPayment().isEmpty() && silver(bag.snapshot()) == before + SILVER, "摊主未实收");
            var money = CurrencyService.get(player.level().getServer());
            var receipt = money.receipt(UUID.fromString(c.getPayment())).orElseThrow();
            test.assertTrue(receipt.parties().payee().equals(money.merchant(f.actor()).id()), "回执收款人错");
            var book = player.getInventory().findSlotMatchingItem(ManualSeller.book(player, c));
            test.assertTrue(book >= 0 && player.getInventory().getItem(book).is(Items.BOOK), "假书批交付了真卷");
            test.assertTrue(!player.hasEffect(MobEffects.WEAKNESS), "识破伪卷就岔气");
            f.owner.choose(offer);
            test.assertTrue(silver(bag.snapshot()) == before + SILVER, "重放多收钱");
            f.owner.choose(f.owner.offers().getLast());
            test.assertTrue(f.current().getPracticed() && player.hasEffect(MobEffects.WEAKNESS), "主动试练未岔气");
            var seller = f.actor();
            f.strike();
            var fallen = player.level().getEntitiesOfClass(ItemEntity.class, seller.getBoundingBox().inflate(1))
                    .stream().map(ItemEntity::getItem).toList();
            test.assertTrue(f.scene.road().getLastCaseOrThrow(ManualSeller.KIND).getPurseDropped()
                    && bag.snapshot().stream().allMatch(ItemStack::isEmpty) && silver(fallen) == before + SILVER,
                    "砸摊没有从原背囊掉出刚收的钱");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void genuineSaleTransfersOnePublicScroll(GameTestHelper test) {
        var f = new Fixture(test, ManualSeller.KIND, TRUE, 1);
        f.open().thenExecute(() -> {
            var player = f.owner.player();
            var cash = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:copper_cash"));
            var silver = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:silver_fragment"));
            player.getInventory().add(new ItemStack(cash, COPPER_PER_FRAGMENT));
            player.getInventory().add(new ItemStack(silver, SILVER - 1));
            var c = f.current();
            f.owner.choose(f.owner.offers().getLast());
            var book = ManualSeller.book(player, c);
            test.assertTrue(book.is(FallenCultivator.SCROLLS) && ManualSeller.carries(player, c)
                    && player.getInventory().countItem(cash) == 0, "铜银合付未交出真卷");
            f.strike();
            test.assertTrue(f.scene.road().getInquiryParty().equals(ManualSeller.ASSOCIATES), "真摊砸出空书");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void leavingStallKeepsBothInventories(GameTestHelper test) {
        var f = new Fixture(test, ManualSeller.KIND, SPY, 2);
        f.open().thenExecute(() -> {
            var c = f.current();
            f.scene.fund();
            f.owner.choose(f.owner.offers().getLast(), 1);
            test.assertTrue(f.scene.road().getLastEnding().equals("passed_stall")
                    && !ManualSeller.carries(f.owner.player(), c), "绕过摊子仍交付了卷");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void downedCultivatorSurvivesAndPaysOnlyAfterPlayerStops(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, FAKE, 3);
        var sequence = f.open().thenExecute(f::down);
        sequence.thenExecute(() -> test.assertTrue(f.current().getStage().equals(FranticCultivator.DOWN)
                && !f.current().getKilledByOwner(), "活着倒地却按杀人结算"));
        sequence.thenWaitUntil(() -> test.assertTrue(f.scene.road().getLastEnding().equals("spared"), "等收手赔礼"));
        sequence.thenExecute(() -> {
            test.assertTrue(!f.scene.road().getLastCaseOrThrow(FranticCultivator.KIND).getKilledByOwner(), "收手算成致死");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void witnessingDiscipleRemembersTheActualKiller(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, SPY, 4);
        f.open().thenExecute(() -> {
            f.strike();
            var player = f.owner.player();
            f.actor().hurtServer(player.level(), player.damageSources().playerAttack(player), Float.MAX_VALUE);
            var c = f.scene.road().getLastCaseOrThrow(FranticCultivator.KIND);
            test.assertTrue(c.getKilledByOwner() && c.getWitnessed() && !c.getDisciple().isEmpty(), "亲眼目击未记仇");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void thirdPartyDeathInterruptsEvenAfterOwnerHit(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, TRUE, 5);
        f.open().thenExecute(() -> {
            var actor = f.actor();
            f.down();
            actor.kill(f.owner.player().level());
            test.assertTrue(f.scene.road().getLastEnding().equals("interrupted")
                    && f.scene.road().getInquiryParty().isEmpty(), "旁人致死归给本人");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void pasteCalmsOnlyAfterHealingDoseIsInstalled(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, FAKE, 6);
        f.open().thenExecute(() -> {
            f.owner.choose(f.owner.offers().getLast());
            var c = f.scene.road().getLastCaseOrThrow(FranticCultivator.KIND);
            test.assertTrue(c.getHealed() && !c.getDose().isEmpty() && f.scene.road().getLastEnding().equals("calmed"),
                    "同门没有喂下真实苦黄膏");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void relapseConsumesOneDoseAndRecordsActualInjuredResident(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, SPY, 7);
        f.open().thenExecute(() -> {
            var offer = f.owner.offers().getLast();
            f.owner.choose(offer);
            test.assertTrue(f.current().getBrokeFree() && f.current().getHealed(), "坏签未复发");
            f.owner.choose(offer);
            var resident = VillageEncounters.actor(f.owner.player(), f.current().getResident()).orElseThrow();
            var hit = new VanillaDamageFabric.DirectDamage(resident, resident.damageSources().mobAttack(f.actor()),
                    "left_forearm", HIT);
            VanillaDamageFabric.damageDirect(hit).orElseThrow();
            test.assertTrue(f.scene.road().getLastCaseOrThrow(FranticCultivator.KIND).getVictim()
                    .equals(resident.getStringUUID()), "实际伤及村民未记回执");
            f.owner.close();
        }).thenSucceed();
    }

    private static int silver(java.util.List<ItemStack> stacks) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.parse("meridian_currency:silver_fragment"));
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @GameTest(maxTicks = LIMIT)
    public void closingFakeBookCostsNoStrengthAndAnotherBuyerCannotUseOffer(GameTestHelper test) {
        var f = new Fixture(test, ManualSeller.KIND, FAKE, 8);
        f.open().thenExecute(() -> {
            var player = f.owner.player();
            var other = StoryPlayer.join(test, UUID.randomUUID());
            other.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            other.player().teleportTo(player.getX(), player.getY(), player.getZ());
            other.player().getInventory().add(ManualSeller.book(player, f.current()));
            var invitation = f.owner.offers().getLast();
            other.choose(invitation);
            test.assertTrue(f.current().getHeld() && f.current().getPayment().isEmpty(), "旁人用本人的报价成交");
            f.scene.fund();
            f.owner.choose(invitation);
            var read = f.owner.offers().getLast();
            other.choose(read);
            f.owner.choose(read, 1);
            f.owner.choose(read);
            test.assertTrue(!player.hasEffect(MobEffects.WEAKNESS) && !other.player().hasEffect(MobEffects.WEAKNESS)
                    && !f.current().getPracticed(), "合书、旁人或重放导致岔气");
            other.close();
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void livingResidentThanksKillerWhenDiscipleCannotWitness(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, SPY, 9);
        f.open().thenExecute(() -> {
            var player = f.owner.player();
            VillageEncounters.actor(player, f.current().getDisciple()).orElseThrow().discard();
            f.strike();
            f.actor().hurtServer(player.level(), player.damageSources().playerAttack(player), Float.MAX_VALUE);
            var c = f.scene.road().getLastCaseOrThrow(FranticCultivator.KIND);
            test.assertTrue(c.getKilledByOwner() && !c.getWitnessed() && f.scene.road().getInquiryParty().isEmpty()
                    && f.scene.road().getLastEnding().equals("thanked"), "无目击同门仍立仇或漏掉活村民");
            f.owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void secondRestraintProtectsEscapingVillagerWithoutGift(GameTestHelper test) {
        var f = new Fixture(test, FranticCultivator.KIND, SPY, 10);
        var level = test.getLevel();
        var forcedChunks = new HashSet<ChunkPos>();
        var sequence = f.open().thenExecute(f::down);
        sequence.thenWaitUntil(() -> test.assertTrue(f.current().getBrokeFree(), "等第一次挣脱"));
        sequence.thenExecute(() -> {
            f.down();
            var c = f.current();
            var resident = VillageEncounters.actor(f.owner.player(), c.getResident()).orElseThrow();
            // 恢复 AI 后村民可能先横穿区块边界；测试村口的邻块也要推进实体刻。
            for (var chunk : ChunkPos.rangeClosed(resident.chunkPosition(), ESCAPE_CHUNK_RADIUS).toList()) {
                if (level.setChunkForced(chunk.x(), chunk.z(), true /* forced */)) {
                    forcedChunks.add(chunk);
                }
            }
            resident.setNoAi(false /* noAi */);
        });
        sequence.thenWaitUntil(() -> {
            var c = f.current();
            var resident = VillageEncounters.actor(f.owner.player(), c.getResident());
            test.assertTrue(f.scene.road().getLastEnding().equals("protected"),
                    "等村民实际走完退路 " + c.getStage() + " " + resident.map(mob -> mob.blockPosition()
                            + "/" + mob.tickCount + "/" + mob.getNavigation().getPath()
                            + "/" + mob.getMoveControl().getSpeedModifier()) + " -> " + BlockPos.of(c.getRefuge()));
        });
        sequence.thenExecute(() -> {
            forcedChunks.forEach(chunk -> level.setChunkForced(chunk.x(), chunk.z(), false /* forced */));
            var settled = f.scene.road().getLastCaseOrThrow(FranticCultivator.KIND);
            f.owner.close();
            test.assertTrue(settled.getBrokeFree() && settled.getGiftItem().isEmpty(), "二次制止不应交付谢礼");
        }).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT, environment = "meridian_story_test:market_night")
    public void sellerAssociatesArriveAtNightAndSettleOnlyTheirTicket(GameTestHelper test) {
        var f = new Fixture(test, ManualSeller.KIND, SPY, 11);
        var sequence = f.open().thenExecute(f::strike).thenIdle(LoneStarRoad.SECOND + 1);
        sequence.thenExecute(() -> {
            test.assertTrue(f.scene.road().getInquiryTicket() == 0, "白天同伙已到");
            var server = f.owner.player().level().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight");
        }).thenWaitUntil(() -> test.assertTrue(f.scene.road().getInquiryShown(), "等夜间同伙实际到场"));
        sequence.thenExecute(() -> {
            var road = f.scene.road();
            var c = road.getLastCaseOrThrow(ManualSeller.KIND);
            test.assertTrue(!c.getFollowupLeader().isEmpty()
                    && CrisisEncounters.leaveRoad(f.owner.player(), road.getInquiryTicket()), "本场没有可甩脱的同伙");
        }).thenWaitUntil(() -> test.assertTrue(f.scene.road().getLastCaseOrThrow(ManualSeller.KIND)
                .getFollowupOutcome().equals("escape"), "等轻场逃脱回执"));
        sequence.thenExecute(f.owner::close).thenSucceed();
    }

    @GameTest(maxTicks = LIMIT)
    public void returningBuyerRetainsOriginalBookReceiptAndLot(GameTestHelper test) {
        var f = new Fixture(test, ManualSeller.KIND, FAKE, 12);
        var paid = new RoadsideCase[1];
        var returned = new StoryPlayer[1];
        var sequence = f.open().thenExecute(() -> {
            f.scene.fund();
            f.owner.choose(f.owner.offers().getLast());
            paid[0] = f.current();
            f.owner.close();
        });
        sequence.thenIdle(1).thenExecute(() -> returned[0] = StoryPlayer.join(test, f.owner.player().getUUID()))
                .thenExecute(() -> returned[0].player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL))
                .thenWaitUntil(() -> {
                    var player = returned[0].player();
                    var stored = new MarketRoadScene(player, ManualSeller.KIND).road().getCurrent();
                    test.assertTrue(stored.equals(paid[0]) && ManualSeller.carries(player, stored),
                            "重登重抽货批或丢了原书");
                    var receipt = CurrencyService.get(player.level().getServer()).receipt(
                            UUID.fromString(stored.getPayment()));
                    test.assertTrue(receipt.isPresent(), "重登无法重查原笔成交");
                }).thenExecute(() -> returned[0].close()).thenSucceed();
    }

    private static UUID playerId(GameTestHelper test, int index) {
        var candidates = Origins.all().values().stream()
                .map(o -> new OriginDraw.Candidate(o.id().toString(), o.weight(), o.group())).toList();
        long world = test.getLevel().getServer().overworld().getSeed();
        var ids = new java.util.SplittableRandom(SEED + index);
        while (true) {
            long high = ids.nextLong();
            var id = new UUID(high, high ^ world ^ (SEED + index));
            if (OriginDraw.draw(candidates, OFFER_COUNT, OriginDraw.seed(world, id, 0))
                    .contains(PhysiqueScene.ORDINARY)) {
                return id;
            }
        }
    }

    private static final class Fixture {
        final GameTestHelper test;
        final StoryPlayer owner;
        final MarketRoadScene scene;
        final BlockPos anchor;
        final String kind;
        final int roll;

        Fixture(GameTestHelper test, String kind, int roll, int index) {
            this.test = test;
            this.kind = kind;
            this.roll = roll;
            owner = StoryPlayer.join(test, playerId(test, index));
            scene = new MarketRoadScene(owner.player(), kind);
            anchor = test.absolutePos(BlockPos.ZERO).north(SPACING * (index + 1));
        }

        GameTestSequence open() {
            return PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                    test.assertTrue(CrisisEncounters.current(owner.player()).isPresent(), "等来犯宿主"))
                    .thenExecute(() -> scene.prepare(anchor, roll)).thenWaitUntil(() -> {
                        scene.walk();
                        var c = current();
                        String stage = c.getStage();
                        test.assertTrue(c.getKind().equals(kind) && !owner.offers().isEmpty()
                                && stage.equals(VillageEncounters.OPEN),
                                "等开场 " + c.getKind() + "/" + stage + " " + scene.location());
                    });
        }

        RoadsideCase current() {
            return scene.road().getCurrent();
        }

        Mob actor() {
            return VillageEncounters.actor(owner.player(), current().getActor()).orElseThrow();
        }

        void strike() {
            var player = owner.player();
            AttackEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                    actor(), null);
        }

        void down() {
            strike();
            var player = owner.player();
            var hit = new VanillaDamageFabric.DirectDamage(actor(), player.damageSources().playerAttack(player),
                    "left_forearm", HIT);
            VanillaDamageFabric.damageDirect(hit).orElseThrow();
        }
    }
}
