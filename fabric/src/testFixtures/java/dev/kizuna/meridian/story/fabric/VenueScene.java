package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

final class VenueScene {
    static final int GOOD = 15;
    static final int BAD = 90;
    private static final int WIDTH = 12;
    private static final int LIFT = 4;
    private final StoryStore store = new StoryStore();
    final MountainEncounters events = new MountainEncounters(store);
    final ServerPlayer player;
    final BlockPos center;

    VenueScene(ServerPlayer player, BlockPos column, int desiredRoll) {
        this.player = player;
        store.ready(player, "");
        center = prepareGround(column);
        move(center.south(RoadVenue.APPROACH));
        long number = 1;
        long seed = FallenCultivator.seed(player);
        while (RoadsideRules.roll(seed, number) != desiredRoll) {
            number++;
        }
        long before = number - 1;
        store.change(player, v -> {
            var road = v.progress().getRoadside().toBuilder().setCases(before).build();
            return v.progress().toBuilder().setRoadside(road).build();
        });
    }

    BlockPos prepareGround(BlockPos column) {
        var level = player.level();
        level.getChunkAt(column);
        var base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).above(LIFT);
        for (var pos : BlockPos.betweenClosed(base.offset(-WIDTH, -1, -WIDTH), base.offset(WIDTH, -1, WIDTH))) {
            level.getChunkAt(pos);
            level.setBlockAndUpdate(pos, Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= LIFT; y++) {
                level.setBlockAndUpdate(pos.above(y), Blocks.AIR.defaultBlockState());
            }
        }
        return base;
    }

    boolean enterRoad() {
        var view = store.read(player).orElseThrow();
        return events.encounter(player, view, FallenCultivator.tile(player.blockPosition()));
    }

    void move(BlockPos pos) {
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.level().getChunkSource().move(player);
        player.setOnGround(true /* onGround */);
    }

    RoadsideCase open(String kind) {
        var view = store.read(player).orElseThrow();
        RoadShow show = events.cart;
        if (kind.equals(VillageRing.KIND)) {
            show = events.ring;
        }
        if (!show.open(player, view.progress().getRoadside(), center)) {
            throw new IllegalStateException("实景未开场 " + kind + " " + center);
        }
        return current();
    }

    RoadsideCase current() {
        return store.read(player).orElseThrow().progress().getRoadside().getCurrent();
    }

    RoadsideCase history(RoadsideCase c) {
        return store.read(player).orElseThrow().progress().getRoadside().getMountainHistoryOrThrow(c.getActor());
    }

    Mob role(RoadsideCase c, String name) {
        return RoadShow.actor(player, c, name).orElseThrow();
    }

    void strike(RoadsideCase c, String name) {
        var actor = role(c, name);
        move(actor.blockPosition().north());
        AttackEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, actor, null);
    }

    void retreat(RoadsideCase c, String first, String second) {
        var away = center.east(MountainEncounters.AWAY);
        role(c, first).snapTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(away));
        role(c, second).snapTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(away.south()));
        tick();
    }

    boolean click(RoadsideCase c, int choice) {
        return events.choose(player, new StoryChat.Selection(c.getActor(), c.getNumber(), choice)).isPresent();
    }

    void tick() {
        var view = store.read(player).orElseThrow();
        events.tick(player, view, view.progress().getRoadside().getCurrent());
    }

    static ItemStack coins(String id, int count) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("meridian_currency", id));
        return new ItemStack(item, count);
    }
}
