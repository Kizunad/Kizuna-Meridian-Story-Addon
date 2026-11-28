package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

final class BequestGoods {
    static final String FORM = "meridian_story:side_step";
    enum Gift {
        JADE(Items.EMERALD, "jade"), NOTES(Items.PAPER, "notes"), BUNDLE(Items.BUNDLE, "bundle");
        final Item item;
        final String name;

        Gift(Item item, String name) {
            this.item = item;
            this.name = name;
        }

        ItemStack stack(RoadsideCase c) {
            var stack = new ItemStack(item);
            if (this == BUNDLE) {
                var supplies = java.util.List.of(new ItemStackTemplate(Items.BREAD),
                        new ItemStackTemplate(Items.STRING));
                stack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(supplies));
            }
            stack.set(DataComponents.CUSTOM_NAME, LastRequest.text(name));
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.putString("bequest", c.getActor());
                if (this == JADE) {
                    tag.putInt("serial", c.getBequest().getSerial());
                }
            });
            return stack;
        }
    }

    static boolean stock(Mob actor, RoadsideCase c) {
        var bag = MobInventories.open(actor);
        for (var gift : Gift.values()) {
            if (bag.insert(gift.stack(c)) != 1) {
                return false;
            }
        }
        return true;
    }

    static int slot(ServerPlayer player, ItemStack sample) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (ItemStack.isSameItemSameComponents(sample, inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    static boolean carries(Mob actor, ItemStack sample) {
        return MobInventories.open(actor).snapshot().stream()
                .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, sample));
    }

    static ItemStack take(Mob actor, ItemStack sample) {
        var bag = MobInventories.open(actor);
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            if (ItemStack.isSameItemSameComponents(bag.peek(slot), sample)) {
                return bag.extract(slot, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    static boolean give(ServerPlayer player, Mob elder, ItemStack sample) {
        if (slot(player, sample) >= 0) {
            return true;
        }
        var inventory = player.getInventory();
        int vacant = inventory.getFreeSlot();
        if (vacant < 0) {
            return false;
        }
        var real = take(elder, sample);
        if (real.isEmpty()) {
            return false;
        }
        inventory.setItem(vacant, real);
        return true;
    }

    private BequestGoods() { }
}
