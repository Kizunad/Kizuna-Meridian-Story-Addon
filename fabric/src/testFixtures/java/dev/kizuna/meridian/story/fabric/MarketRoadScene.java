package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.story.server.StoryProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 在能抽中指定事件的网格铺路，村口使用真实的已占用集合点。 */
final class MarketRoadScene {
    private static final int TILE = 16;
    private static final int RADIUS = 12;
    private static final int HEIGHT = 4;
    private static final int OFFSET = 256;
    private static final int STEP = 2;
    private static final int CENTER = TILE / 2;
    private static final int MONEY = 20;
    private final ServerPlayer player;
    private final String kind;
    private BlockPos start;
    private BlockPos bell;
    private boolean claimed;
    private int steps;

    MarketRoadScene(ServerPlayer player, String kind) {
        this.player = player;
        this.kind = kind;
    }

    void prepare(BlockPos anchor, int roll) {
        long seed = FallenCultivator.seed(player);
        var column = anchor.east(OFFSET);
        while (!opens(seed, column)) {
            column = column.east(TILE);
        }
        var middle = column.offset(CENTER - Math.floorMod(column.getX(), TILE), 0, 0);
        var road = new RoadScene(player);
        road.ground(middle);
        var level = player.level();
        start = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, middle).above(HEIGHT);
        clearSite();
        if (kind.equals(FranticCultivator.KIND)) {
            village();
        }
        var feet = Vec3.atBottomCenterOf(start);
        player.teleportTo(feet.x, feet.y, feet.z);
        player.setYRot(0);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        var bodies = new StoryBodies();
        var body = bodies.read(player).orElseThrow();
        var progress = StoryProgress.read(body);
        long number = progress.getRoadside().getCases() + 1;
        while (RoadsideRules.roll(seed, number) != roll) {
            number++;
        }
        var next = progress.getRoadside().toBuilder().setCases(number - 1).build();
        if (!bodies.write(player, body, progress.toBuilder().setRoadside(next).build())) {
            throw new IllegalStateException("写入场次失败");
        }
        StoryRaidFixture.roadClock(player, road.record().getOnlineTicks() + LoneStarRoad.REST);
    }

    private void clearSite() {
        var level = player.level();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                var floor = start.offset(x, -1, z);
                level.setBlockAndUpdate(floor, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = 1; y <= HEIGHT + 1; y++) {
                    level.setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private void village() {
        var level = player.level();
        bell = start.west(STEP);
        level.setBlockAndUpdate(start.south(HEIGHT).below().west(), Blocks.DIRT_PATH.defaultBlockState());
        level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
        var resident = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        resident.setPos(Vec3.atBottomCenterOf(start.south(RADIUS / 2)));
        resident.setNoAi(true /* noAi */);
        level.addFreshEntity(resident);
        player.getInventory().add(BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK));
    }

    private boolean opens(long seed, BlockPos pos) {
        long tile = FallenCultivator.tile(pos);
        if (kind.equals(ManualSeller.KIND)) {
            return RoadsideRules.opensStall(seed, tile);
        }
        return RoadsideRules.opens(seed, tile);
    }

    void walk() {
        if (bell != null && !claimed) {
            var poi = player.level().getPoiManager();
            claimed = poi.take(type -> type.is(PoiTypes.MEETING), (type, at) -> at.equals(bell), bell, 1).isPresent();
        }
        if (bell != null && (!claimed || !player.level().isVillage(start.south(HEIGHT)))) {
            return;
        }
        if (road().hasCurrent() || player.level().getGameTime() % LoneStarRoad.SECOND != 1) {
            return;
        }
        int shift = 0;
        if (++steps % STEP != 0) {
            shift = STEP;
        }
        var pos = Vec3.atBottomCenterOf(start.east(shift));
        player.teleportTo(pos.x, pos.y, pos.z);
        player.setOnGround(true /* onGround */);
        player.awardStat(Stats.WALK_ONE_CM, STEP * LoneStarRoad.CM);
    }

    String location() {
        return "bell=" + claimed + " village=" + player.level().isVillage(start.south(HEIGHT))
                + " feet=" + player.blockPosition() + " steps=" + steps;
    }

    Roadside road() {
        return new StoryBodies().read(player).map(StoryProgress::read).orElseThrow().getRoadside();
    }

    void fund() {
        var coin = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:silver_fragment"));
        player.getInventory().add(new ItemStack(coin, MONEY));
    }
}
