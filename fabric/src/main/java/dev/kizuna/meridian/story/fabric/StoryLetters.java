package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Claim;
import java.util.List;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

record StoryLetters(String owner, String line, String reward, Claim claim) {
    static void deliverPending(ServerPlayer owner, StoryStore store, Progress progress) {
        progress.getLinesMap().forEach((line, state) -> state.getClaimsMap().forEach((id, claim) -> {
            if (claim.getAwaitingDelivery()) {
                var letter = new StoryLetters(owner.getStringUUID(), line, id, claim);
                letter.deliver(owner, store);
            }
        }));
    }

    private void deliver(ServerPlayer player, StoryStore store) {
        if (!claim.getSpent() && carried(player) == 0 && !player.getInventory().add(letter(1))) {
            return;
        }
        store.change(player, view -> {
            var progress = view.progress();
            var state = progress.getLinesOrThrow(line);
            if (!claim.equals(state.getClaimsMap().get(reward))) {
                return progress;
            }
            var delivered = claim.toBuilder().setAwaitingDelivery(false /* awaitingDelivery */).build();
            var updated = state.toBuilder().putClaims(reward, delivered).build();
            return progress.toBuilder().putLines(line, updated).build();
        });
    }

    private CustomData identity() {
        var tag = new CompoundTag();
        tag.putString("owner", owner);
        tag.putString("line", line);
        tag.putString("chapter", claim.getChapter());
        tag.putString("reward", reward);
        tag.putInt("encounter", claim.getEncounter());
        return CustomData.of(tag);
    }

    static String nameKey(String line, String reward) {
        return "letter." + line.replace(':', '.') + "." + reward;
    }

    ItemStack letter(int count) {
        var stack = new ItemStack(material(), count);
        String key = nameKey(line, reward);
        stack.set(DataComponents.ITEM_NAME, Component.translatable(key));
        stack.set(DataComponents.LORE, new ItemLore(List.of(lore(key))));
        stack.set(DataComponents.CUSTOM_DATA, identity());
        return stack;
    }

    private Component lore(String key) {
        if (line.equals(FateRoad.LINE) && reward.equals("slip") && claim.hasLoreX()) {
            return Component.translatable(key + ".lore", claim.getLoreX(), claim.getLoreY(), claim.getLoreZ());
        }
        return Component.translatable(key + ".lore");
    }

    boolean matches(ItemStack stack) {
        return stack.is(material()) && identity().equals(stack.get(DataComponents.CUSTOM_DATA));
    }

    private net.minecraft.world.item.Item material() {
        if (line.equals(FateRoad.LINE)) {
            return switch (reward) {
                case "notebook" -> Items.BOOK;
                case "cord" -> Items.STRING;
                default -> Items.PAPER;
            };
        }
        // 护送报酬可实际食用，投递仍按同一张具名领取凭据去重。
        if (line.equals(LoneStarRoad.LINE) && reward.equals("loaf")) {
            return Items.BREAD;
        }
        return Items.PAPER;
    }

    int carried(ServerPlayer player) {
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    void take(ServerPlayer player, int count) {
        int remaining = count;
        var inventory = player.getInventory();
        for (int slot = 0; remaining > 0 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack)) {
                int used = Math.min(remaining, stack.getCount());
                inventory.removeItem(slot, used);
                remaining -= used;
            }
        }
    }

    void drop(ServerPlayer player) {
        var dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(claim.getDimension()));
        var level = player.level().getServer().getLevel(dimension);
        BlockPos pos = BlockPos.containing(claim.getX(), claim.getY(), claim.getZ());
        if (level != null && level.hasChunkAt(pos)) {
            var entity = new ItemEntity(level, claim.getX(), claim.getY(), claim.getZ(), letter(1), 0, 0, 0);
            level.addFreshEntity(entity);
        }
    }
}
