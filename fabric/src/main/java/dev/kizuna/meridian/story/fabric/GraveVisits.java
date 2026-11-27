package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class GraveVisits {
    static final String ORIGIN = "meridian_story:grave_keeper";
    static final String LINE = "meridian_story:grave_friend";
    static final int VIGIL = 20 * 20;
    static final int DRAW = 30 * 20;
    private static final int RADIUS = 24;
    private static final int ELEVATION = 8;
    private static final long VISIT_SALT = 0x9E3779B9L;
    private static final int VISITOR_PERCENT = 35;
    private static final int PERCENT = 100;
    private static final long ABSENCE = 5 * CrisisRules.DAY_TICKS;
    private record Stay(long firstTick, long enteredOnline, long lastOnlineTick) { }

    private final Map<UUID, Stay> arrived = new HashMap<>();
    private final StoryStore store;
    private final StoryBodies bodies = new StoryBodies();

    GraveVisits(StoryStore store) {
        this.store = store;
    }

    void trackPosition(ServerPlayer player) {
        if (!arrived.containsKey(player.getUUID())) {
            return;
        }
        var anchor = GraveSite.get(player.level()).anchor();
        if (!player.isAlive() || anchor.isEmpty()
                || !inside(player.level().dimension(), player.blockPosition(), anchor.get())) {
            leave(player);
        }
    }

    void leave(ServerPlayer player) {
        var stay = arrived.remove(player.getUUID());
        if (stay != null && player.level().getGameTime() - stay.firstTick() >= VIGIL) {
            store.change(player, view -> {
                var progress = view.progress();
                var grave = progress.getGrave();
                if (!grave.getFirstSwept() || grave.getLastVigil() >= stay.lastOnlineTick()) {
                    return progress;
                }
                var departed = grave.toBuilder().setLastVigil(stay.lastOnlineTick()).build();
                return progress.toBuilder().setGrave(departed).build();
            });
        }
        var level = player.level().getServer().overworld();
        var site = level.getDataStorage().get(GraveSite.TYPE);
        if (site != null) {
            site.release(level, player.getUUID());
        }
    }

    void tick(ServerPlayer player, StoryStore.View view) {
        var progress = view.progress();
        if (!ORIGIN.equals(view.facts().origin()) || !player.isAlive() || player.isSpectator()) {
            if (progress.hasGrave()) {
                leave(player);
            }
            if (progress.hasGrave() || progress.getFate().getGrievingUntil() > 0) {
                reconcileMood(player, view, GraveMood.Mood.AT_PEACE);
            }
            return;
        }
        var level = player.level().getServer().overworld();
        var site = GraveSite.get(level);
        site.advance(level);
        StoryStore.View registered;
        if (progress.hasGrave()) {
            registered = view;
        } else {
            var initial = GraveKeeper.newBuilder()
                    .setSwordSerial(UUID.randomUUID().toString())
                    .setLastVigil(view.facts().ticks())
                    .build();
            if (!put(player, view, initial)) {
                return;
            }
            registered = store.read(player).orElseThrow();
        }
        var supplied = deliver(player, registered);
        site.anchor().ifPresent(stone -> keepVigil(player, supplied, site, stone));
    }

    private void keepVigil(ServerPlayer player, StoryStore.View view, GraveSite site, BlockPos stone) {
        var grave = view.progress().getGrave();
        long now = view.facts().ticks();
        var mood = GraveMood.Mood.AT_PEACE;
        var location = player.getUUID();
        var previous = arrived.get(location);
        long clock = player.level().getGameTime();
        if (now - grave.getLastVigil() >= ABSENCE && (previous == null || clock - previous.firstTick() < VIGIL)) {
            mood = GraveMood.Mood.HOMESICK;
        }
        reconcileMood(player, view, mood);
        if (!inside(player.level().dimension(), player.blockPosition(), stone)) {
            leave(player);
            return;
        }
        long entered = clock;
        long enteredOnline = now;
        if (previous != null) {
            enteredOnline = previous.enteredOnline();
            entered = previous.firstTick();
        }
        arrived.put(location, new Stay(entered, enteredOnline, now));
        long stay = clock - entered;
        if (stay >= VIGIL && (!grave.getFirstSwept() || grave.getLastVigil() < enteredOnline
                || mood == GraveMood.Mood.HOMESICK)) {
            var swept = grave.toBuilder()
                    .setFirstSwept(true /* firstSwept */)
                    .setLastVigil(now)
                    .build();
            if (put(player, view, swept)) {
                StoryChat.narrate(player, "story.meridian_story.tomb.swept");
            }
            return;
        }
        visit(player, view, site, stay, enteredOnline);
    }

    private void reconcileMood(ServerPlayer player, StoryStore.View view, GraveMood.Mood grave) {
        var mood = grave;
        if (grave == GraveMood.Mood.AT_PEACE && view.progress().getFate().getGrievingUntil() > view.facts().ticks()) {
            mood = GraveMood.Mood.BEREAVED;
        }
        GraveMood.reconcile(player, bodies, view.body(), mood);
    }

    static boolean inside(net.minecraft.resources.ResourceKey<Level> dimension, BlockPos feet, BlockPos stone) {
        long dx = (long) feet.getX() - stone.getX();
        long dz = (long) feet.getZ() - stone.getZ();
        return dimension.equals(Level.OVERWORLD) && dx * dx + dz * dz <= RADIUS * RADIUS
                && Math.abs(feet.getY() - stone.getY()) <= ELEVATION;
    }

    private StoryStore.View deliver(ServerPlayer player, StoryStore.View view) {
        // 初选与重登共用同一份领取进度。旧档投递到路引为止，继续核对两份独立卷据。
        var grave = view.progress().getGrave();
        if (grave.getDelivered() >= GraveItems.GIFTS.size() && grave.getUpperReceived() && grave.getLowerReceived()) {
            return view;
        }
        var equipped = deliverHeirlooms(player, view);
        return deliverScrolls(player, equipped);
    }

    private StoryStore.View deliverHeirlooms(ServerPlayer player, StoryStore.View view) {
        var grave = view.progress().getGrave();
        var current = view;
        String serial = grave.getSwordSerial();
        var inventory = player.getInventory();
        for (int index = grave.getDelivered(); index < GraveItems.GIFTS.size(); index++) {
            var item = GraveItems.GIFTS.get(index);
            var stack = GraveItems.gift(item, serial);
            var next = current.progress().getGrave().toBuilder().setDelivered(index + 1).build();
            boolean present = inventory.contains(held -> GraveItems.bound(held, item, serial));
            if (!issue(player, current, next, stack, present ? Delivery.CARRIED : Delivery.MISSING)) {
                break;
            }
            current = store.read(player).orElseThrow();
        }
        return current;
    }

    private StoryStore.View deliverScrolls(ServerPlayer player, StoryStore.View view) {
        var current = view;
        var inventory = player.getInventory();
        for (var scroll : GraveItems.Scroll.values()) {
            var grave = current.progress().getGrave();
            if (scroll.received(grave)) {
                continue;
            }
            var item = scroll.item();
            boolean present = inventory.contains(stack -> stack.is(item));
            var delivery = present ? Delivery.CARRIED : Delivery.MISSING;
            if (issue(player, current, scroll.mark(grave), item.getDefaultInstance(), delivery)) {
                current = store.read(player).orElseThrow();
            }
        }
        return current;
    }

    private enum Delivery { CARRIED, MISSING }

    private boolean issue(ServerPlayer player, StoryStore.View view, GraveKeeper next,
                          ItemStack stack, Delivery delivery) {
        // 单个主线程回调内核销个人凭据并放入已确认的空格，满包和提交失败都可重试。
        var inventory = player.getInventory();
        int slot = inventory.getFreeSlot();
        if (delivery == Delivery.MISSING && slot < 0 || !put(player, view, next)) {
            return false;
        }
        if (delivery == Delivery.MISSING) {
            inventory.setItem(slot, stack);
        }
        return true;
    }

    private boolean put(ServerPlayer player, StoryStore.View before, GraveKeeper next) {
        var expected = before.progress().getGrave();
        return store.change(player, current -> {
            var progress = current.progress();
            if (!ORIGIN.equals(current.facts().origin()) || !progress.getGrave().equals(expected)) {
                return progress;
            }
            return progress.toBuilder().setGrave(next).build();
        });
    }

    private void visit(ServerPlayer player, StoryStore.View view, GraveSite site, long stay, long enteredAt) {
        var grave = view.progress().getGrave();
        var level = player.level();
        var state = view.progress().getLinesMap().get(LINE);
        if (state != null && !state.getEnding().isEmpty()) {
            site.release(level, player.getUUID());
            if (state.getEnding().equals("welcomed") && grave.getLot().equals("friend")
                    && grave.getLastFragment() < grave.getVisits()) {
                awardFragment(player, view, grave);
                return;
            }
        }
        if (grave.getLot().equals("friend") && state != null && state.getEnding().isEmpty()) {
            var guest = visitor(player, grave);
            if (site.receive(level, player.getUUID(), guest)) {
                HumanoidVisitors.arrive(level, site.guest(), guest,
                        Component.translatable("story.meridian_story.friend.name"));
            }
            return;
        }
        long now = view.facts().ticks();
        if (stay < DRAW || grave.getVisits() > 0 && (grave.getLastDraw() >= enteredAt
                || now - grave.getLastDraw() < CrisisRules.DAY_TICKS)) {
            return;
        }
        int visit = Math.incrementExact(grave.getVisits());
        String lot = lot(player, site, visit);
        var next = grave.toBuilder()
                .setVisits(visit)
                .setLastDraw(now)
                .setLot(lot)
                .build();
        store.change(player, current -> draw(current, grave, next));
    }

    private void awardFragment(ServerPlayer player, StoryStore.View view, GraveKeeper grave) {
        String claim = visitor(player, grave).toString();
        var inventory = player.getInventory();
        if (inventory.contains(stack -> GraveItems.bound(stack, GraveItems.FRAGMENT, claim))
                || inventory.getFreeSlot() >= 0 && inventory.add(GraveItems.gift(GraveItems.FRAGMENT, claim))) {
            put(player, view, grave.toBuilder().setLastFragment(grave.getVisits()).build());
        }
    }

    static String lot(ServerPlayer player, GraveSite site, int visit) {
        var owner = player.getUUID();
        long seed = player.level().getSeed() ^ site.guest().asLong() ^ owner.getMostSignificantBits()
                ^ owner.getLeastSignificantBits() ^ Integer.toUnsignedLong(visit) * VISIT_SALT;
        return lot(RandomSource.create(seed).nextInt(PERCENT));
    }

    static String lot(int roll) {
        if (roll < VISITOR_PERCENT) {
            return "friend";
        }
        return "quiet";
    }

    private static Progress draw(StoryStore.View view, GraveKeeper before, GraveKeeper next) {
        var progress = view.progress();
        if (!before.equals(progress.getGrave())) {
            return progress;
        }
        var update = progress.toBuilder().setGrave(next);
        if (next.getLot().equals("friend")) {
            long now = view.facts().ticks();
            var meeting = LineState.newBuilder()
                    .setStartedAt(now)
                    .setEnteredAt(now)
                    .setRevision(next.getVisits())
                    .setChapter("meeting")
                    .build();
            update.putLines(LINE, meeting);
        }
        return update.build();
    }

    static boolean recipient(StoryStore.View view, ServerPlayer player, LivingEntity actor) {
        var grave = view.progress().getGrave();
        var site = GraveSite.get(player.level());
        return grave.getLot().equals("friend") && actor.getUUID().equals(visitor(player, grave))
                && site.anchor().filter(pos -> inside(player.level().dimension(), actor.blockPosition(), pos))
                        .isPresent();
    }

    static UUID visitor(ServerPlayer player, GraveKeeper grave) {
        String id = ORIGIN + "/" + player.getStringUUID() + "/" + grave.getVisits();
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
    }
}
