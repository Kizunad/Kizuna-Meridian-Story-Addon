package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.server.StoryProgress;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Blocks;

record FateScene(ServerPlayer player) {
    private static final int GATE = 22;
    private static final int SITE_RADIUS = 49;

    BlockPos prepare(BlockPos column) {
        var trail = new RoadScene(player);
        trail.ground(column);
        var feet = player.blockPosition();
        var level = player.level();
        for (int x = -SITE_RADIUS; x <= SITE_RADIUS; x++) {
            for (int z = -SITE_RADIUS; z <= SITE_RADIUS; z++) {
                level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        var gate = feet.east(GATE);
        level.setBlockAndUpdate(gate.below(), Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(gate, Blocks.BELL.defaultBlockState());
        level.setBlockAndUpdate(feet.north().below(), Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(feet.north(), Blocks.CAMPFIRE.defaultBlockState());
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
        return gate;
    }

    boolean registered(BlockPos gate) {
        var level = player.level();
        if (!level.isVillage(gate)) {
            level.getPoiManager().take(type -> type.is(PoiTypes.MEETING), (t, p) -> p.equals(gate), gate, 1);
        }
        return level.isVillage(gate);
    }

    Progress progress() {
        return StoryProgress.read(new StoryBodies().read(player).orElseThrow());
    }

    Mob actor() {
        return FateScenes.actor(player, progress().getFate());
    }

    String chapter() {
        return FateRoad.state(progress()).getChapter();
    }

    void strike(Mob mob) {
        player.setPos(mob.position());
        AttackEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, mob, null);
    }

    void read(String reward) {
        var claim = FateRoad.state(progress()).getClaimsOrThrow(reward);
        var item = new StoryLetters(player.getStringUUID(), FateRoad.LINE, reward, claim);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (item.matches(inventory.getItem(slot))) {
                var previous = player.getMainHandItem();
                var found = inventory.getItem(slot);
                inventory.setItem(slot, previous);
                player.setItemInHand(InteractionHand.MAIN_HAND, found);
                UseItemCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND);
                return;
            }
        }
        throw new IllegalStateException("背包没有本人的 " + reward);
    }

    void fallSite(String reward) {
        var claim = FateRoad.state(progress()).getClaimsOrThrow(reward);
        player.setPos(claim.getX(), claim.getY(), claim.getZ());
    }
}
