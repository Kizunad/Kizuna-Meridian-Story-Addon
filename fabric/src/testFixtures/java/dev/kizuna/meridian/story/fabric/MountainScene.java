package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class MountainScene {
    enum Terrain { TRAIL, CLIFF }
    enum Lot { GOOD, BAD }
    private static final int TILE = 16;
    private static final int WIDTH = 6;
    private static final int BASE = 4;
    private static final int LENGTH = 10;
    private static final int RISE = 2;
    private static final int FIRST_STEP = 4;
    private static final int LAST_STEP = 6;
    private static final int GOOD_LIMIT = 50;
    private static final int BAD_FLOOR = 80;
    private static final long ELAPSED = 6 * MountainEncounters.DAY;
    private final ServerPlayer player;
    private final StoryStore store = new StoryStore();
    final MountainEncounters events = new MountainEncounters(store);
    final BlockPos feet;

    MountainScene(ServerPlayer player, BlockPos column, Terrain terrain, Lot lot) {
        this.player = player;
        store.ready(player, "");
        var level = player.level();
        long seed = FallenCultivator.seed(player);
        var start = column;
        while (!RoadsideRules.opens(seed, FallenCultivator.tile(start))) {
            start = start.south(TILE);
        }
        level.getChunkAt(start);
        feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start).above(BASE);
        prepare(terrain);
        player.setGameMode(GameType.SURVIVAL);
        move(feet);
        player.setYRot(0);
        player.setYHeadRot(0);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        long number = 1;
        int draw = RoadsideRules.roll(seed, number);
        while (lot == Lot.GOOD && draw >= GOOD_LIMIT || lot == Lot.BAD && draw < BAD_FLOOR) {
            draw = RoadsideRules.roll(seed, ++number);
        }
        long prior = number - 1;
        store.change(player, v -> {
            var road = v.progress().getRoadside().toBuilder().setCases(prior).build();
            return v.progress().toBuilder().setRoadside(road).build();
        });
    }

    private void prepare(Terrain terrain) {
        var level = player.level();
        for (int x = -WIDTH; x <= WIDTH; x++) {
            for (int z = -WIDTH; z <= LENGTH; z++) {
                int rise = 0;
                if (terrain == Terrain.CLIFF && z >= FIRST_STEP) {
                    rise = 1;
                    if (z >= LAST_STEP) {
                        rise = RISE;
                    }
                }
                var floor = feet.offset(x, -1, z);
                level.getChunkAt(floor);
                for (int up = 0; up <= rise; up++) {
                    level.setBlockAndUpdate(floor.above(up), Blocks.STONE.defaultBlockState());
                }
                var surface = Blocks.GRASS_BLOCK;
                if (terrain == Terrain.TRAIL && x == 0) {
                    surface = Blocks.DIRT_PATH;
                }
                level.setBlockAndUpdate(floor.above(rise), surface.defaultBlockState());
            }
        }
    }

    void move(BlockPos pos) {
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.level().getChunkSource().move(player);
        player.setOnGround(true /* onGround */);
    }

    Mob father() {
        return HumanoidVisitors.arrive(player.level(), feet.west(BASE), UUID.randomUUID(),
                Component.literal("沈石"), EntityTypes.VINDICATOR).orElseThrow();
    }

    void age() {
        long now = CrisisEncounters.current(player).orElseThrow().getOnlineTicks();
        StoryRaidFixture.roadClock(player, now + ELAPSED);
    }

    boolean enter() {
        return events.encounter(player, store.read(player).orElseThrow(), FallenCultivator.tile(feet));
    }

    Roadside road() {
        return store.read(player).orElseThrow().progress().getRoadside();
    }

    RoadsideCase current() {
        return road().getCurrent();
    }

    Mob actor(String id) {
        return MountainEncounters.actor(player, id).orElseThrow();
    }

    static ItemStack silver(int count) {
        var coin = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:silver_sycee"));
        return new ItemStack(coin, count);
    }

    void pick(RoadsideCase c) {
        var pos = BlockPos.of(c.getPlant());
        move(pos.west());
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
        UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, hit);
    }

    void tick() {
        var view = store.read(player).orElseThrow();
        events.tick(player, view, view.progress().getRoadside().getCurrent());
    }
}
