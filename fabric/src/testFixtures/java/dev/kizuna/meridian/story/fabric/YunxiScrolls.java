package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

final class YunxiScrolls {
    static final String UPPER = "meridian_story:yunxi_upper";
    static final String LOWER = "meridian_story:yunxi_lower";

    private YunxiScrolls() { }

    static GraveKeeper record(ServerPlayer player) {
        return StoryProgress.read(new StoryBodies().read(player).orElseThrow()).getGrave();
    }

    static void origin(ServerPlayer player, String origin) {
        var server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "meridian debug origin set " + player.getScoreboardName() + " " + origin);
    }

    static ItemStack scroll(String volume) {
        var id = Identifier.parse(volume + "_scroll");
        return new ItemStack(TechniqueScrollItems.scrolls().get(id));
    }

    static int count(ServerPlayer player, String volume) {
        return player.getInventory().countItem(scroll(volume).getItem());
    }

    static Set<String> known(ServerPlayer player) {
        return TechniqueFabric.service().known(player.getUUID()).stream()
                .map(TechniqueService.KnownEntry::technique)
                .map(ref -> ref.getId())
                .collect(Collectors.toSet());
    }

    static ItemStack take(ServerPlayer player, String volume) {
        var item = scroll(volume).getItem();
        var found = player.getInventory().getNonEquipmentItems().stream().filter(stack -> stack.is(item))
                .findFirst().orElseThrow();
        return found.split(1);
    }

    static InteractionResult read(ServerPlayer player, ItemStack held) {
        var hand = InteractionHand.MAIN_HAND;
        player.setItemInHand(hand, held);
        return player.gameMode.useItem(player, player.level(), held, hand);
    }
}
