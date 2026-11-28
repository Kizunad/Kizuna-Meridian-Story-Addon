package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

final class BequestScene {
    final ServerPlayer player;
    final StoryStore store = new StoryStore();
    final MountainEncounters events = new MountainEncounters(store);
    final MountainRetreats.Site site;
    final BlockPos bell;
    private static final int BACK = 19;
    private static final int GATE = 22;
    private static final int PATH_WIDTH = 2;
    private static final int CREST = 5;
    private static final int RIDGE = 8;

    BequestScene(ServerPlayer player, BlockPos column) {
        this(player, column, Rotation.NONE);
    }

    BequestScene(ServerPlayer player, BlockPos column, Rotation rotation) {
        this.player = player;
        store.ready(player, "");
        new RoadScene(player).ground(column);
        var mouth = player.blockPosition();
        site = new MountainRetreats.Site(mouth, rotation, 1, "");
        bell = mouth.east(GATE);
        buildSlope(mouth);
        markGate();
        move(site.at(MountainRetreats.ELDER).east());
        clock();
    }

    private void buildSlope(BlockPos mouth) {
        var level = player.level();
        for (int x = -RIDGE; x <= RIDGE; x++) {
            for (int z = 0; z <= RIDGE; z++) {
                int top = Math.min(CREST, Math.min(RIDGE - Math.abs(x), RIDGE - z));
                for (int y = 0; y < top; y++) {
                    var stone = new BlockPos(x, y, BACK + z).rotate(site.direction());
                    level.setBlockAndUpdate(mouth.offset(stone), Blocks.STONE.defaultBlockState());
                }
                var topsoil = new BlockPos(x, top, BACK + z).rotate(site.direction());
                level.setBlockAndUpdate(mouth.offset(topsoil), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
    }

    private void markGate() {
        var level = player.level();
        for (int x = -PATH_WIDTH; x <= PATH_WIDTH; x++) {
            for (int z = -RIDGE; z <= RIDGE; z++) {
                level.setBlockAndUpdate(bell.offset(x, -1, z), Blocks.GRAVEL.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(bell.below(), Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
        for (var post : java.util.List.of(bell.west(PATH_WIDTH), bell.east(PATH_WIDTH))) {
            level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(post.above(), Blocks.LANTERN.defaultBlockState());
        }
    }

    void clock() {
        var server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "time set " + LastRequest.DUSK);
    }

    boolean place() {
        return MountainRetreats.get(player.level()).place(player, site);
    }

    boolean gate() {
        return new FateScene(player).registered(bell);
    }

    boolean meet() {
        clock();
        return events.bequest.approach(player, view());
    }

    void move(BlockPos pos) {
        var feet = Vec3.atBottomCenterOf(pos);
        player.teleportTo(feet.x, feet.y, feet.z);
        player.level().getChunkSource().move(player);
        player.setOnGround(true /* onGround */);
    }

    StoryStore.View view() {
        return store.read(player).orElseThrow();
    }

    RoadsideCase current() {
        return view().progress().getRoadside().getCurrent();
    }

    RoadsideCase history(String id) {
        return view().progress().getRoadside().getMountainHistoryOrThrow(id);
    }

    void roll(int signature) {
        var c = current();
        if (!events.update(player, c, c.toBuilder().setRoll(signature).build())) {
            throw new IllegalStateException("未能固定分支签");
        }
    }

    void advance(long tick) {
        StoryRaidFixture.roadClock(player, tick);
        events.bequest.promise.tick(player, view());
    }

    boolean choose(RoadsideCase c, int index) {
        long revision = c.getNumber();
        if (index >= LastRequest.DELIVER) {
            revision = c.getBequest().getSerial();
        }
        return events.choose(player, new StoryChat.Selection(c.getActor(), revision, index)).isPresent();
    }
}
