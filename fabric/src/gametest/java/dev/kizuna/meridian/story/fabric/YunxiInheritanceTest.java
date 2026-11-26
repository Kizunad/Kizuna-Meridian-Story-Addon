package dev.kizuna.meridian.story.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.kizuna.meridian.story.fabric.YunxiScrolls.*;

public final class YunxiInheritanceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_story_test");
    private static final int TIMEOUT = 800;
    private static final int SETTLE = 40;
    private static final float LETHAL = 10_000;
    private static final int MAX_HITS = 30;
    private static final double DROP_RADIUS = 3;
    private static final String ORDINARY = PhysiqueScene.ORDINARY;
    private static final String BOOK = "meridian_story:yunxi";
    private static final String CLEAVE = YunxiForms.CLEAVE;

    @GameTest(maxTicks = TIMEOUT)
    public void twoKeepersStartWithBothAndTransferredCopiesStaySpent(GameTestHelper test) {
        var first = StoryPlayer.join(test, StoryPlayer.offered(test, GraveVisits.ORIGIN));
        var second = join(test, GraveVisits.ORIGIN);
        var receiver = join(test, ORDINARY);
        var next = new StoryPlayer[1];
        var a = first.player();
        var b = second.player();
        var other = receiver.player();
        test.startSequence().thenWaitUntil(() -> test.assertTrue(first.packets()
                .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待本人出身候选"))
                .thenExecute(() -> {
                    a.setGameMode(GameType.SURVIVAL);
                    first.chooseOrigin(GraveVisits.ORIGIN);
                }).thenWaitUntil(() -> {
                    for (var keeper : List.of(a, b)) {
                        checkKeeperGift(test, keeper);
                    }
                    test.assertTrue(count(other, UPPER) == 0 && count(other, LOWER) == 0, "普通出身也发了卷");
                }).thenExecute(() -> {
                    studyGifts(test, a, b, other);
                    first.close();
                }).thenExecuteAfter(SETTLE, () -> next[0] = StoryPlayer.join(test, a.getUUID()))
                .thenExecuteAfter(SETTLE, () -> {
                    var restored = next[0].player();
                    test.assertTrue(count(restored, UPPER) == 0 && count(b, LOWER) == 0, "转赠后又领到卷");
                    test.assertTrue(count(restored, LOWER) == 1 && count(b, UPPER) == 1, "同冢串发卷");
                    next[0].close();
                    second.close();
                    receiver.close();
                }).thenSucceed();
    }

    private static void checkKeeperGift(GameTestHelper test, ServerPlayer keeper) {
        test.assertTrue(count(keeper, UPPER) == 1 && count(keeper, LOWER) == 1, "开局未取得两份实物");
        test.assertTrue(!record(keeper).getFirstSwept(), "开局取卷竟要先祭扫");
        var inventory = keeper.getInventory();
        for (var heirloom : GraveItems.GIFTS) {
            test.assertTrue(inventory.contains(stack -> stack.is(heirloom)), "开局缺少遗物 " + heirloom);
        }
    }

    private static void studyGifts(GameTestHelper test, ServerPlayer first, ServerPlayer second, ServerPlayer reader) {
        YunxiMortal.emptyQi(reader);
        var upper = take(first, UPPER);
        read(reader, upper);
        test.assertTrue(upper.isEmpty() && known(reader).contains(YunxiForms.PARRY), "赠上卷未学齐");
        var lower = take(second, LOWER);
        YunxiPracticeBody.practitioner(reader);
        read(reader, lower);
        test.assertTrue(lower.isEmpty() && known(reader).contains(CLEAVE), "普通出身不能学赠下卷");
        reader.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        test.assertTrue(YunxiForms.cast(reader, CLEAVE).hasBinding(), "持铁剑不能施展获赠的斜劈");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void learnBookVolumeExactAndSingleCheckRealmAndChannels(GameTestHelper test) {
        try (var actor = join(test, ORDINARY)) {
            var player = actor.player();
            var service = TechniqueFabric.service();
            var id = player.getUUID();
            var ref = TechniqueReference.newBuilder().setId(CLEAVE).setVersion(1).build();
            var initial = known(player);
            var gift = scroll(LOWER);
            test.assertTrue(read(player, gift) == InteractionResult.FAIL && gift.getCount() == 1, "凡人读卷被消耗");
            denied(test, () -> service.learn(id, CLEAVE), "指定单式误学");
            denied(test, () -> service.learn(id, ref), "精确版本误学");
            denied(test, () -> service.learn(id, LOWER), "游炁时学了下卷");
            denied(test, () -> service.learn(id, BOOK), "整书误学");
            test.assertTrue(known(player).equals(initial), "混合请求部分提交");
            YunxiPracticeBody.ascend(player);
            for (var hand : YunxiPracticeBody.HANDS) {
                denied(test, () -> service.learn(id, ref), "手经不足");
                YunxiPracticeBody.open(player, hand);
            }
            denied(test, () -> service.learn(id, BOOK), "缺足经仍整书成功");
            YunxiPracticeBody.open(player, YunxiPracticeBody.FOOT);
            YunxiPracticeBody.empty(player);
            read(player, gift);
            var afterLower = known(player);
            test.assertTrue(gift.isEmpty() && afterLower.contains(CLEAVE), "经脉齐全仍不能先读下卷");
            test.assertTrue(!afterLower.contains(YunxiForms.PARRY), "单读下卷误学上卷");
            var completed = service.learn(id, BOOK);
            test.assertTrue(known(player).contains(YunxiForms.PARRY) && completed.stream()
                    .filter(e -> CLEAVE.equals(e.technique().getId())).count() == 1, "整书未补齐或重复学式");
            test.succeed();
        }
    }

    private static void denied(GameTestHelper test, Runnable submit, String reason) {
        try {
            submit.run();
        } catch (TechniqueService.LearningDeniedException expected) {
            return;
        }
        test.fail(reason);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void legacyFullPackRecognizesExistingScrollAndRetriesOnlyMissingOne(GameTestHelper test) {
        var owner = join(test, GraveVisits.ORIGIN);
        var player = owner.player();
        var next = new StoryPlayer[1];
        test.startSequence().thenWaitUntil(() -> test.assertTrue(count(player, LOWER) == 1, "开局未交卷"))
                .thenExecute(() -> legacy(player))
                .thenExecuteAfter(SETTLE, () -> {
                    var grave = record(player);
                    test.assertTrue(grave.getLowerReceived() && !grave.getUpperReceived(), "满包老档未分别核销");
                    test.assertTrue(count(player, LOWER) == 1 && count(player, UPPER) == 0, "重复补发已有卷");
                    take(player, LOWER);
                    owner.close();
                }).thenExecuteAfter(SETTLE, () -> next[0] = StoryPlayer.join(test, player.getUUID()))
                .thenWaitUntil(() -> test.assertTrue(record(next[0].player()).getUpperReceived(), "重登未补缺卷"))
                .thenExecute(() -> {
                    var restored = next[0].player();
                    test.assertTrue(count(restored, LOWER) == 0 && count(restored, UPPER) == 1, "缺卷补发数量错");
                    take(restored, UPPER);
                }).thenExecuteAfter(SETTLE, () -> {
                    test.assertTrue(count(next[0].player(), UPPER) == 0, "老档补发了第二次");
                    next[0].close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fallenKeeperDropsOnlyTheVolumeStillCarried(GameTestHelper test) {
        var owner = join(test, GraveVisits.ORIGIN);
        var receiver = join(test, ORDINARY);
        var keeper = owner.player();
        test.startSequence().thenWaitUntil(() -> test.assertTrue(count(keeper, LOWER) == 1, "未取得初始剑谱"))
                .thenExecute(() -> {
                    try (owner; receiver) {
                        var feet = test.absolutePos(BlockPos.ZERO).above();
                        keeper.snapTo(Vec3.atBottomCenterOf(feet));
                        var other = receiver.player();
                        other.snapTo(keeper.position());
                        var readUpper = take(keeper, UPPER);
                        read(keeper, readUpper);
                        test.assertTrue(readUpper.isEmpty(), "上卷未读完");
                        defeatKeeper(test, keeper, other);
                        pickUpLower(test, keeper, other);
                    }
                }).thenSucceed();
    }

    private static void defeatKeeper(GameTestHelper test, ServerPlayer keeper, ServerPlayer other) {
        var level = keeper.level();
        var server = level.getServer();
        var rules = level.getGameRules();
        boolean previous = rules.get(GameRules.KEEP_INVENTORY);
        try {
            rules.set(GameRules.KEEP_INVENTORY, false /* value */, server);
            var source = keeper.damageSources().playerAttack(other);
            for (int hit = 0; hit < MAX_HITS && keeper.isAlive(); hit++) {
                keeper.invulnerableTime = 0;
                keeper.hurtServer(level, source, LETHAL);
            }
            test.assertTrue(!keeper.isAlive(), "普通玩家近战未击倒守墓人");
        } finally {
            rules.set(GameRules.KEEP_INVENTORY, previous, server);
        }
    }

    private static void pickUpLower(GameTestHelper test, ServerPlayer keeper, ServerPlayer other) {
        var box = keeper.getBoundingBox().inflate(DROP_RADIUS);
        var drops = keeper.level().getEntitiesOfClass(ItemEntity.class, box);
        var lower = scroll(LOWER).getItem();
        var upper = scroll(UPPER).getItem();
        var books = drops.stream().filter(drop -> drop.getItem().is(lower)).toList();
        test.assertTrue(books.size() == 1, "遗落下卷数量 " + books.size() + "，身上还有 " + count(keeper, LOWER));
        var book = books.getFirst();
        test.assertTrue(book.getItem().getCount() == 1, "掉落堆内有重复下卷");
        test.assertTrue(drops.stream().noneMatch(drop -> drop.getItem().is(upper)), "已读的上卷又生成了掉落");
        book.setNoPickUpDelay();
        book.playerTouch(other);
        test.assertTrue(count(other, LOWER) == 1 && book.isRemoved(), "未拾到原有的下卷实物");
    }

    @GameTest
    public void noLootMerchantOrTradeSuppliesEitherScroll(GameTestHelper test) throws IOException {
        var server = test.getLevel().getServer();
        var resources = server.getResourceManager();
        var forbidden = new HashSet<String>();
        for (String volume : List.of(UPPER, LOWER)) {
            var stack = scroll(volume);
            var item = (TechniqueScrollItem) stack.getItem();
            var entry = item.scroll().volume();
            test.assertTrue(entry.getScrollDropsCount() == 0, "在战利品注入里发现 " + volume);
            forbidden.add(volume + "_scroll");
            stack.typeHolder().tags().forEach(tag -> forbidden.add("#" + tag.location()));
        }
        var folders = List.of("loot_table", "villager_trade", "trade_set", "merchant_offers");
        for (String folder : folders) {
            var files = resources.listResources(folder, id -> id.getPath().endsWith(".json"));
            test.assertTrue(!files.isEmpty(), "测试装配缺少 " + folder);
            for (var entry : files.entrySet()) {
                try (var reader = entry.getValue().openAsReader()) {
                    var json = JsonParser.parseReader(reader);
                    test.assertTrue(!supplies(json, forbidden), "卷进入了来源 " + entry.getKey());
                }
            }
            LOGGER.info("云溪来源核查 {} {}", folder, files.size());
        }
        test.succeed();
    }

    private static boolean supplies(JsonElement json, Set<String> forbidden) {
        if (json.isJsonPrimitive()) {
            return json.getAsJsonPrimitive().isString() && forbidden.contains(json.getAsString());
        }
        if (json.isJsonObject()) {
            var object = json.getAsJsonObject();
            var type = object.get("type");
            if (type != null && type.isJsonPrimitive()
                    && type.getAsString().equals("minecraft:tag") && object.has("name")
                    && forbidden.contains("#" + object.get("name").getAsString())) {
                return true;
            }
            return object.entrySet().stream().anyMatch(entry -> supplies(entry.getValue(), forbidden));
        }
        return json.isJsonArray() && json.getAsJsonArray().asList().stream()
                .anyMatch(item -> supplies(item, forbidden));
    }

    private static void legacy(ServerPlayer player) {
        // 老版已交付旧剑、旧札和路引。两项卷轴凭据缺省，背包中已有一份可转赠的下卷。
        var pack = player.getInventory();
        var stone = Items.COBBLESTONE.getDefaultInstance();
        for (int slot = 0; slot < pack.getNonEquipmentItems().size(); slot++) {
            pack.setItem(slot, stone.copyWithCount(stone.getMaxStackSize()));
        }
        pack.setItem(0, scroll(LOWER));
        var bodies = new StoryBodies();
        var body = bodies.read(player).orElseThrow();
        var progress = StoryProgress.read(body);
        var old = progress.getGrave().toBuilder().clearUpperReceived().clearLowerReceived().build();
        if (!bodies.write(player, body, progress.toBuilder().setGrave(old).build())) {
            throw new IllegalStateException("老档夹具写入失败");
        }
    }

    private static StoryPlayer join(GameTestHelper test, String originId) {
        var owner = StoryPlayer.join(test, UUID.randomUUID());
        var player = owner.player();
        player.setGameMode(GameType.SURVIVAL);
        origin(player, originId);
        return owner;
    }
}
