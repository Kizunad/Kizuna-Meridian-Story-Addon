package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;

public final class GraveGameTest {
    private static final int TIMEOUT = 2600;
    private static final int DAY_COUNT = 5;
    private static final int SETTLE = 40;
    private static final int DISTANT = 100;
    private static final int SEARCH = 2000;
    private static final double LETHAL_HP = 10_000;
    private static final String ORIGIN = GraveVisits.ORIGIN;

    @GameTest(maxTicks = TIMEOUT)
    public void deathAndRelogRetainAbsenceAndSwordSerial(GameTestHelper test) {
        var owner = keeper(test);
        var first = owner.player();
        var serial = new String[1];
        var rejoined = new StoryPlayer[1];
        selected(test, owner).thenWaitUntil(() -> test.assertTrue(
                record(owner).getDelivered() == GraveItems.GIFTS.size(), "等待遗物交付")).thenExecute(() -> {
                    first.setGameMode(GameType.SURVIVAL);
                    serial[0] = record(owner).getSwordSerial();
                    move(owner, GraveSite.get(first.level()).guest().east(DISTANT));
                    for (int day = 0; day < DAY_COUNT; day++) {
                        StoryRaidFixture.passDay(first);
                    }
                }).thenWaitUntil(() -> test.assertTrue(first.hasEffect(GraveMood.EFFECT), "等待离冢状态"))
                .thenExecute(() -> respawn(test, first))
                .thenWaitUntil(() -> {
                    var player = first.level().getServer().getPlayerList().getPlayer(first.getUUID());
                    test.assertTrue(player != null, "复活玩家没有登记");
                    test.assertTrue(player != first, "旧死亡实体未替换");
                    // 内存连接没有网络轮询，推进正式玩家网络刻，让 Healing 将复活身体同步到红心。
                    player.doTick();
                    test.assertTrue(player.isAlive(), "新实体血量 " + player.getHealth() + "，位置 " + player.position());
                    test.assertTrue(!player.isSpectator(), "复活玩家进入了旁观模式");
                    test.assertTrue(Origins.chosen(player).isPresent(), "复活后缺少出身");
                    var clock = CrisisEncounters.current(player).orElseThrow().getOnlineTicks();
                    var grave = StoryProgress.read(new PhysiqueScene(player).body()).getGrave();
                    test.assertTrue(grave.getSwordSerial().equals(serial[0]), "复活丢失剑号");
                    test.assertTrue(player.hasEffect(GraveMood.EFFECT),
                            "复活未续离冢状态，在线钟 " + clock + "，上次守候 " + grave.getLastVigil());
                })
                .thenExecute(owner::close)
                .thenExecuteAfter(SETTLE, () -> rejoined[0] = StoryPlayer.join(test, first.getUUID()))
                .thenWaitUntil(() -> {
                    var grave = record(rejoined[0]);
                    test.assertTrue(grave.getSwordSerial().equals(serial[0]), "重登改变剑号");
                    test.assertTrue(grave.getDelivered() == GraveItems.GIFTS.size(), "重登刷新领取权");
                    test.assertTrue(rejoined[0].player().hasEffect(GraveMood.EFFECT), "重登刷新了离冢钟");
                }).thenExecute(() -> rejoined[0].close()).thenSucceed();
    }

    private static void respawn(GameTestHelper test, ServerPlayer first) {
        var hit = new VanillaDamageFabric.DirectDamage(first, first.damageSources().genericKill(), "head", LETHAL_HP);
        test.assertTrue(VanillaDamageFabric.damageDirect(hit).isPresent(), "致死部位伤害未提交");
        test.assertTrue(!first.isAlive() && new PhysiqueScene(first).body().getVitality().getDead(), "旧身体未死亡");
        first.connection.handleClientCommand(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        var revived = first.level().getServer().getPlayerList().getPlayer(first.getUUID());
        test.assertTrue(revived != null && revived != first, "服务端没有换出新实体");
        revived.setNoGravity(true /* noGravity */);
        revived.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        test.assertTrue(!new PhysiqueScene(revived).body().getVitality().getDead(), "重生后的权威身体仍是死态");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twoKeepersReceiveOnlyTheirOwnInheritance(GameTestHelper test) {
        var a = keeper(test);
        var b = keeper(test);
        selected(test, a, b).thenWaitUntil(() -> {
            test.assertTrue(record(a).getDelivered() == GraveItems.GIFTS.size(), "首人未收齐遗物");
            test.assertTrue(record(b).getDelivered() == GraveItems.GIFTS.size(), "后人未收齐遗物");
        }).thenExecute(() -> {
            var stone = GraveSite.get(a.player().level()).anchor().orElseThrow();
            var item = a.player().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.is(GraveItems.SWORD)).findFirst().orElseThrow();
            for (var owner : List.of(a, b)) {
                GraveItems.GUIDE.use(owner.player().level(), owner.player(), InteractionHand.MAIN_HAND);
                var directions = Component.translatable("story.meridian_story.tomb.directions",
                        stone.getX(), stone.getY(), stone.getZ());
                test.assertTrue(owner.chat().contains(directions), "路引未指向同一登记碑心");
            }
            var iron = new ItemStack(Items.IRON_SWORD);
            test.assertTrue(item.getMaxDamage() == iron.getMaxDamage() * 3 / 4, "旧剑耐久");
            var baseline = iron.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers();
            var stats = item.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers();
            test.assertTrue(stats.size() == baseline.size(), "旧剑面板数");
            for (int index = 0; index < stats.size(); index++) {
                var entry = stats.get(index);
                double expected = baseline.get(index).modifier().amount();
                if (entry.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
                    expected -= 1;
                }
                test.assertTrue(entry.modifier().amount() == expected, "旧剑伤害或攻速不符");
            }
            a.close();
            b.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void dimensionAndUnbrokenVigilControlHomesickness(GameTestHelper test) {
        var owner = keeper(test);
        var player = owner.player();
        selected(test, owner).thenWaitUntil(() -> test.assertTrue(
                record(owner).getDelivered() == GraveItems.GIFTS.size(),
                "等待已成冢与开局")).thenExecute(() -> {
            var stone = GraveSite.get(player.level()).anchor().orElseThrow();
            test.assertTrue(!GraveVisits.inside(Level.NETHER, stone, stone), "地狱同坐标竟算归冢");
            move(owner, stone.east(DISTANT));
            for (int day = 0; day < DAY_COUNT; day++) {
                StoryRaidFixture.passDay(player);
            }
        }).thenWaitUntil(() -> test.assertTrue(player.hasEffect(GraveMood.EFFECT)
                && new PhysiqueScene(player).body().getModifiersList().stream()
                        .anyMatch(mod -> mod.getTissueId().equals("attention_network") && mod.getRecoveryPeak() == 0.8),
                "离冢五日未挂熄灯与注意恢复修正")).thenExecute(() -> {
            player.removeEffect(GraveMood.EFFECT);
        }).thenWaitUntil(() -> test.assertTrue(player.hasEffect(GraveMood.EFFECT), "清药后未恢复"))
                .thenExecute(() -> {
                    player.setNoGravity(true /* noGravity */);
                    var nether = player.level().getServer().getLevel(Level.NETHER);
                    travel(player, nether, GraveSite.get(player.level()).guest());
                })
                .thenExecuteAfter(GraveVisits.VIGIL + SETTLE, () -> {
                    test.assertTrue(player.level().dimension() == Level.NETHER && player.hasEffect(GraveMood.EFFECT),
                            "异维同坐标守候清除了离冢状态");
                    var overworld = player.level().getServer().overworld();
                    travel(player, overworld, GraveSite.get(overworld).guest());
                })
                .thenExecuteAfter(GraveVisits.VIGIL - SETTLE, () -> {
                    test.assertTrue(player.hasEffect(GraveMood.EFFECT), "守候不足二十秒提前消退");
                    move(owner, GraveSite.get(player.level()).guest().east(DISTANT));
                }).thenExecuteAfter(SETTLE, () -> move(owner, GraveSite.get(player.level()).guest()))
                .thenWaitUntil(() -> test.assertTrue(GraveInheritance.sweptAtGrave(player)
                        && !player.hasEffect(GraveMood.EFFECT), "连续守候后未解或祭扫凭据缺失"))
                .thenExecute(owner::close).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void steppingBackCannotRedrawTheLot(GameTestHelper test) {
        var owner = keeper(test);
        var site = GraveSite.get(owner.player().level());
        var visits = new int[1];
        selected(test, owner).thenWaitUntil(() -> test.assertTrue(
                record(owner).getDelivered() == GraveItems.GIFTS.size(),
                "等待祖冢")).thenExecute(() -> {
                    selectLot(owner, site, "quiet");
                    StoryRaidFixture.passDay(owner.player());
                    visits[0] = record(owner).getVisits() + 1;
                    move(owner, site.guest().east(1));
                })
                .thenWaitUntil(() -> test.assertTrue(record(owner).getVisits() == visits[0], "三十秒未落签"))
                .thenExecute(() -> move(owner, site.guest().east(DISTANT)))
                .thenExecuteAfter(SETTLE, () -> move(owner, site.guest().east(1)))
                .thenExecuteAfter(GraveVisits.DRAW + SETTLE, () -> {
                    test.assertTrue(record(owner).getVisits() == visits[0], "当天离圈重进换了签");
                    test.assertTrue(GraveVisits.lot(34).equals("friend") && GraveVisits.lot(35).equals("quiet"),
                            "三成半概率边界错误");
                    owner.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void oldFriendSpeaksAndTakesBothAnswers(GameTestHelper test) {
        var owner = keeper(test);
        var player = owner.player();
        var site = GraveSite.get(player.level());
        var sequence = selected(test, owner).thenWaitUntil(() ->
                test.assertTrue(record(owner).getDelivered() == GraveItems.GIFTS.size(), "等待开局"));
        for (int choice = 0; choice < 2; choice++) {
            int index = choice;
            sequence.thenExecute(() -> {
                move(owner, site.guest().east(DISTANT));
                selectLot(owner, site, "friend");
                StoryRaidFixture.passDay(player);
            }).thenExecuteAfter(SETTLE, () -> move(owner, site.guest().east(1)))
                    .thenWaitUntil(() -> test.assertTrue(actor(owner) != null, "旧识未到场"))
                    .thenExecute(() -> answer(test, owner, index))
                    .thenWaitUntil(() -> test.assertTrue(actor(owner) == null, "结局后旧识未退场"));
        }
        sequence.thenExecute(owner::close).thenSucceed();
    }

    private static void answer(GameTestHelper test, StoryPlayer owner, int index) {
        var guest = actor(owner);
        move(owner, guest.blockPosition().east(1));
        StoryGameTest.use(owner, guest);
        owner.choose(owner.offers().getLast(), index);
        var key = List.of("thanks", "hurt").get(index);
        var expected = Component.translatable("story.meridian_story.friend." + key);
        test.assertTrue(owner.packets().filter(BubblePayload.class::isInstance).map(BubblePayload.class::cast)
                .anyMatch(bubble -> bubble.entityId() == guest.getId() && expected.equals(bubble.line())),
                "气泡说话者不是本场旧识");
        String ending = List.of("welcomed", "turned_away").get(index);
        test.assertTrue(owner.state(GraveVisits.LINE).getEnding().equals(ending), "本次祭拜结局错误");
    }

    private static LivingEntity actor(StoryPlayer owner) {
        var player = owner.player();
        return (LivingEntity) player.level().getEntity(GraveVisits.visitor(player, record(owner)));
    }

    private static void selectLot(StoryPlayer owner, GraveSite site, String wanted) {
        var player = owner.player();
        var body = new PhysiqueScene(player).body();
        var progress = StoryProgress.read(body);
        var grave = progress.getGrave();
        for (int visit = grave.getVisits() + 1; visit < SEARCH; visit++) {
            if (GraveVisits.lot(player, site, visit).equals(wanted)) {
                var prepared = grave.toBuilder()
                        .setVisits(visit - 1)
                        .setLastFragment(visit - 1)
                        .build();
                var next = progress.toBuilder().setGrave(prepared).build();
                if (new StoryBodies().write(player, body, next)) {
                    return;
                }
            }
        }
        throw new IllegalStateException("未找到访客签");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fullPackRelogAndForgePreserveEntitlement(GameTestHelper test) {
        var owner = keeper(test);
        var inventory = owner.player().getInventory();
        var id = owner.player().getUUID();
        var next = new StoryPlayer[1];
        test.startSequence().thenWaitUntil(() -> test.assertTrue(owner.packets()
                .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待登录与候选"))
                .thenExecute(() -> {
                    owner.player().setGameMode(GameType.SURVIVAL);
                    var cobble = Items.COBBLESTONE.getDefaultInstance();
                    for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
                        inventory.setItem(slot, cobble.copyWithCount(cobble.getMaxStackSize()));
                    }
                    owner.chooseOrigin(ORIGIN);
                }).thenWaitUntil(() -> test.assertTrue(!record(owner).getSwordSerial().isEmpty(),
                "满包时未登记剑号")).thenExecuteAfter(SETTLE, () -> {
                    test.assertTrue(record(owner).getDelivered() == 0, "满包吞掉遗物");
                    for (int slot = 0; slot < GraveItems.GIFTS.size(); slot++) {
                        inventory.setItem(slot, ItemStack.EMPTY);
                    }
                }).thenWaitUntil(() -> test.assertTrue(
                record(owner).getDelivered() == GraveItems.GIFTS.size(),
                        "腾空后未投递")).thenExecute(owner::close)
                .thenExecuteAfter(SETTLE, () -> next[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> {
                    var player = next[0].player();
                    test.assertTrue(record(next[0]).getDelivered() == GraveItems.GIFTS.size(), "重登丢失继承记录");
                    test.assertTrue(Origins.chosen(player).isPresent() && CrisisEncounters.current(player).isPresent(),
                            "等待重登的出身和在线时钟");
                }).thenWaitUntil(() -> test.assertTrue(GraveSite.get(next[0].player().level()).anchor().isPresent(),
                        "等待碑前重铸位置")).thenExecute(() -> verifyForge(test, next[0])).thenSucceed();
    }

    private static void verifyForge(GameTestHelper test, StoryPlayer owner) {
        var player = owner.player();
        var inventory = player.getInventory();
        var serial = record(owner).getSwordSerial();
        var sword = GraveItems.gift(GraveItems.SWORD, serial);
        var stone = GraveSite.get(player.level()).anchor().orElseThrow();
        move(owner, stone.south(1));
        inventory.setSelectedSlot(0);
        inventory.setItem(0, sword);
        inventory.setItem(1, new ItemStack(GraveItems.FRAGMENT, 2));
        inventory.setItem(2, new ItemStack(Items.IRON_INGOT));
        sword.setDamageValue(sword.getMaxDamage() - 1);
        int before = sword.getDamageValue();
        forge(test, owner, stone);
        test.assertTrue(sword.getDamageValue() == before - sword.getMaxDamage() / 4, "碑前修剑未扣碎片补耐久");
        inventory.setItem(0, new ItemStack(GraveItems.GUIDE));
        forge(test, owner, stone);
        test.assertTrue(inventory.contains(stack -> stack.is(GraveItems.SWORD)), "重铸后未收到旧剑");
        test.assertTrue(!inventory.contains(stack -> stack.is(GraveItems.FRAGMENT) || stack.is(Items.IRON_INGOT)),
                "重铸未收材料");
        owner.close();
    }

    private static void forge(GameTestHelper test, StoryPlayer owner, BlockPos stone) {
        var player = owner.player();
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(stone), net.minecraft.core.Direction.UP,
                stone, false /* inside */);
        player.setShiftKeyDown(true /* sneaking */);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker()
                .interact(player, player.level(), InteractionHand.MAIN_HAND, hit);
        player.setShiftKeyDown(false /* sneaking */);
        var messages = owner.chat();
        test.assertTrue(!messages.isEmpty(), "碑前未收到修剑确认");
        var event = messages.getLast().getStyle().getClickEvent();
        test.assertTrue(event instanceof net.minecraft.network.chat.ClickEvent.Custom, "碑前确认缺少按钮");
        var click = (net.minecraft.network.chat.ClickEvent.Custom) event;
        new net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(click.id(), click.payload())
                .handle(player.connection);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void reservedSiteResumesWithoutReplacingPlayerBlocks(GameTestHelper test) throws Exception {
        var level = test.getLevel().getServer().overworld();
        var remote = test.absolutePos(BlockPos.ZERO).east(SEARCH);
        level.getChunkAt(remote);
        var ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, remote).below();
        var site = new GraveSite();
        var blocked = ground.above();
        level.setBlockAndUpdate(blocked, Blocks.CHEST.defaultBlockState());
        test.assertTrue(!site.reserve(level, ground), "在旧箱子上预留祖冢");
        level.setBlockAndUpdate(blocked, Blocks.AIR.defaultBlockState());
        test.assertTrue(site.reserve(level, ground), "空地未能预留 " + ground + " "
                + level.getBlockState(ground) + " 支撑 " + level.getBlockState(ground.below())
                + " 地表归类 " + level.getBlockState(ground).is(net.minecraft.tags.BlockTags.DIRT)
                + " 界内 " + level.getWorldBorder().isWithinBounds(ground)
                + " 结构 " + level.structureManager().getAllStructuresAt(ground));
        test.assertTrue(!site.reserve(level, ground.east(DISTANT)), "同时预留第二座");
        site.advance(level);
        var stone = ground.offset(GraveSite.STONE);
        level.setBlockAndUpdate(stone, Blocks.CHEST.defaultBlockState());
        var folder = Files.createTempDirectory("story-grave");
        try (var storage = new SavedDataStorage(folder, level.getServer().getFixerUpper(), level.registryAccess())) {
            storage.set(GraveSite.TYPE, site);
            storage.saveAndJoin();
        }
        var restored = load(level, folder);
        test.startSequence().thenExecuteFor(SETTLE, () -> {
            restored.advance(level);
            test.assertTrue(restored.anchor().isEmpty(), "施工冲突时提前登记了完成");
            test.assertTrue(level.getBlockState(stone).is(Blocks.CHEST), "续建覆盖了旧箱子");
        }).thenExecute(() -> level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState())).thenWaitUntil(() -> {
            restored.advance(level);
            test.assertTrue(restored.anchor().isPresent(), "重读预留未续建");
        }).thenExecute(() -> {
            test.assertTrue(restored.anchor().orElseThrow().equals(stone), "重读后换了冢址");
            level.setBlockAndUpdate(stone, Blocks.BARREL.defaultBlockState());
            restored.advance(level);
            test.assertTrue(level.getBlockState(stone).is(Blocks.BARREL), "竣工后覆盖了玩家修建");
        }).thenSucceed();
    }

    private static GraveSite load(ServerLevel level, java.nio.file.Path folder) {
        try (var storage = new SavedDataStorage(folder, level.getServer().getFixerUpper(), level.registryAccess())) {
            return storage.get(GraveSite.TYPE);
        }
    }

    private static StoryPlayer keeper(GameTestHelper test) {
        return StoryPlayer.join(test, StoryPlayer.offered(test, ORIGIN));
    }

    private static GameTestSequence selected(GameTestHelper test, StoryPlayer... players) {
        var sequence = test.startSequence();
        for (var actor : players) {
            sequence.thenWaitUntil(() -> test.assertTrue(actor.packets()
                    .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待出身卡"))
                    .thenExecute(() -> actor.chooseOrigin(ORIGIN));
        }
        // 开局遗物可以先领，来访、磨剑和守候另等祖冢建好。
        return sequence.thenWaitUntil(() -> test.assertTrue(GraveSite.get(players[0].player().level())
                .anchor().isPresent(), "等待祖冢登记完成"));
    }

    private static GraveKeeper record(StoryPlayer owner) {
        return StoryProgress.read(new PhysiqueScene(owner.player()).body()).getGrave();
    }

    private static void move(StoryPlayer owner, BlockPos pos) {
        owner.player().setPos(Vec3.atBottomCenterOf(pos));
    }

    private static void travel(ServerPlayer player, ServerLevel level, BlockPos pos) {
        var feet = Vec3.atBottomCenterOf(pos);
        player.teleportTo(level, feet.x, feet.y, feet.z, Set.of(), 0, 0, true /* resetCamera */);
    }
}
