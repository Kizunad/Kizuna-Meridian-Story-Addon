package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

final class GraveForge {
    static final Identifier ACTION = Identifier.parse("meridian_story:grave_forge");
    private static final int LIFETIME = 600;
    private static final int RADIUS = 5;
    private static final int REPAIR_DIVISOR = 4;
    private static final String PREFIX = "story.meridian_story.tomb.";
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final StoryStore store;

    private enum Work {
        MEND("repair", "repaired"), REFORGE("reforge", "reforged");
        final String offer;
        final String done;
        Work(String offer, String done) {
            this.offer = offer;
            this.done = done;
        }
    }

    private record Offer(String nonce, Work work, String serial, long until) { }

    GraveForge(StoryStore store) {
        this.store = store;
        UseBlockCallback.EVENT.register((reader, level, hand, hit) -> {
            if (reader instanceof ServerPlayer player && hand == InteractionHand.MAIN_HAND
                    && player.isShiftKeyDown() && offer(player, hit.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    void leave(ServerPlayer player) {
        offers.remove(player.getUUID());
    }

    private boolean offer(ServerPlayer player, BlockPos target) {
        var view = store.read(player);
        if (view.isEmpty() || !GraveVisits.ORIGIN.equals(view.get().facts().origin()) || !near(player, target)) {
            return false;
        }
        var grave = view.get().progress().getGrave();
        Work work = Work.MEND;
        if (player.getMainHandItem().is(GraveItems.GUIDE)) {
            work = Work.REFORGE;
        }
        if (!materials(player, grave, work)) {
            return false;
        }
        String nonce = UUID.randomUUID().toString();
        long until = player.level().getGameTime() + LIFETIME;
        offers.put(player.getUUID(), new Offer(nonce, work, grave.getSwordSerial(), until));
        var cost = Component.translatable(PREFIX + work.offer + "_cost");
        var selection = new StoryChat.Selection(nonce, 1, 0);
        player.sendSystemMessage(StoryChat.button(PREFIX + work.offer, cost, ChatFormatting.GOLD, selection, ACTION));
        return true;
    }

    private static boolean near(ServerPlayer player, BlockPos target) {
        if (player.level().dimension() != Level.OVERWORLD || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        var stone = GraveSite.get(player.level()).anchor();
        return stone.isPresent() && stone.get().equals(target)
                && player.blockPosition().closerThan(target, RADIUS);
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection selection) {
        var offer = offers.get(player.getUUID());
        if (offer == null || !offer.nonce().equals(selection.nonce())) {
            return Optional.empty();
        }
        leave(player);
        var anchor = GraveSite.get(player.level()).anchor();
        if (selection.option() != 0 || selection.revision() != 1 || player.level().getGameTime() >= offer.until()
                || anchor.isEmpty() || !near(player, anchor.get())) {
            return Optional.empty();
        }
        var view = store.read(player);
        if (view.isEmpty() || !valid(player, view.get(), offer)) {
            return Optional.empty();
        }
        var fragment = ingredient(player, GraveItems.FRAGMENT);
        if (offer.work() == Work.MEND) {
            var sword = player.getMainHandItem();
            int repair = sword.getMaxDamage() / REPAIR_DIVISOR;
            sword.setDamageValue(Math.max(0, sword.getDamageValue() - repair));
        } else {
            var iron = ingredient(player, Items.IRON_INGOT);
            String serial = UUID.randomUUID().toString();
            if (!store.change(player, current -> {
                if (!valid(player, current, offer)) {
                    return current.progress();
                }
                var updated = current.progress().getGrave().toBuilder().setSwordSerial(serial).build();
                return current.progress().toBuilder().setGrave(updated).build();
            })) {
                return Optional.empty();
            }
            iron.shrink(1);
            player.getInventory().placeItemBackInInventory(GraveItems.gift(GraveItems.SWORD, serial));
        }
        fragment.shrink(1);
        return Optional.of(Component.translatable(PREFIX + offer.work().done));
    }

    private static boolean valid(ServerPlayer player, StoryStore.View view, Offer offer) {
        var grave = view.progress().getGrave();
        return GraveVisits.ORIGIN.equals(view.facts().origin()) && grave.getSwordSerial().equals(offer.serial())
                && materials(player, grave, offer.work());
    }

    private static boolean materials(ServerPlayer player, GraveKeeper grave, Work work) {
        if (grave.getDelivered() < GraveItems.GIFTS.size() || ingredient(player, GraveItems.FRAGMENT).isEmpty()) {
            return false;
        }
        var hand = player.getMainHandItem();
        if (work == Work.MEND) {
            return GraveItems.bound(hand, GraveItems.SWORD, grave.getSwordSerial()) && hand.isDamaged();
        }
        return hand.is(GraveItems.GUIDE) && !ingredient(player, Items.IRON_INGOT).isEmpty();
    }

    private static ItemStack ingredient(ServerPlayer player, Item item) {
        return player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(item))
                .findFirst()
                .orElse(ItemStack.EMPTY);
    }
}
