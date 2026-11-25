package dev.kizuna.meridian.story.fabric;

import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;

final class GraveItems {
    private static final float SWORD_DAMAGE = 2;
    private static final float SWORD_SPEED = -2.4F;
    private static final int DURABILITY_NUMERATOR = 3;
    private static final int DURABILITY_DENOMINATOR = 4;
    static final Item MARKER = register("grave_marker", Item::new);
    static final Item SWORD = register("grave_sword", properties -> new Item(properties
            .sword(ToolMaterial.IRON, SWORD_DAMAGE, SWORD_SPEED)
            .durability(ToolMaterial.IRON.durability() * DURABILITY_NUMERATOR / DURABILITY_DENOMINATOR)));
    static final Item NOTES = register("yunxi_notes", properties -> {
        Component page = Component.translatable("book.meridian_story.yunxi_notes.page");
        var book = new WrittenBookContent(Filterable.passThrough("云溪旧札"), "养父", 0,
                List.of(Filterable.passThrough(page)), true /* resolved */);
        return new WrittenBookItem(properties.stacksTo(1).component(DataComponents.WRITTEN_BOOK_CONTENT, book));
    });
    static final Item FRAGMENT = register("grave_fragment", Item::new);
    static final Item GUIDE = register("grave_guide", Guide::new);
    static final List<Item> GIFTS = List.of(SWORD, NOTES, GUIDE);

    private GraveItems() { }

    static void initialize() {
        var tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.parse("minecraft:tools_and_utilities"));
        CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
            output.accept(MARKER);
            output.accept(FRAGMENT);
            GIFTS.forEach(output::accept);
        });
    }

    private static Item register(String name, Function<Item.Properties, Item> factory) {
        var id = Identifier.fromNamespaceAndPath("meridian_story", name);
        var key = ResourceKey.create(Registries.ITEM, id);
        var lore = new ItemLore(List.of(Component.translatable("item.meridian_story." + name + ".lore")));
        var properties = new Item.Properties().setId(key).component(DataComponents.LORE, lore);
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties));
    }

    static ItemStack gift(Item item, String serial) {
        var stack = new ItemStack(item);
        var tag = new CompoundTag();
        tag.putString("inheritance", serial);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    static boolean bound(ItemStack stack, Item item, String serial) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return stack.is(item) && data != null && !serial.isEmpty()
                && serial.equals(data.copyTag().getStringOr("inheritance", ""));
    }

    private static final class Guide extends Item {
        Guide(Properties properties) {
            super(properties.stacksTo(1));
        }

        @Override
        public InteractionResult use(Level level, Player reader, InteractionHand hand) {
            if (reader instanceof ServerPlayer player) {
                var pos = GraveSite.get(player.level()).anchor();
                var message = pos.<Component>map(p -> Component.translatable("story.meridian_story.tomb.directions",
                        p.getX(), p.getY(), p.getZ())).orElseGet(() -> Component.translatable(
                                "story.meridian_story.tomb.wait"));
                player.sendSystemMessage(message);
            }
            return InteractionResult.SUCCESS;
        }
    }
}
