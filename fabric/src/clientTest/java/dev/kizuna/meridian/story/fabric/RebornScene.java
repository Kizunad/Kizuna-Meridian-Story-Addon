package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.server.StoryProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 从实际征兆、落物和证人处取景。案卷、选项和结局都经正式数据线产生。 */
public final class RebornScene {
    public static final String NOTE = "item.meridian_humanoid.crisis_note";
    public static final String FRAGMENT = "letter.meridian_story.reborn.fragment";
    public static final String TAIL = "letter.meridian_story.reborn.public_letter";
    private static final String PARTY = "meridian_humanoid:old_register";
    private static final String ORIGIN = "meridian_humanoid:reborn";
    private static final String LINE = "meridian_story:reborn";
    private static final int SEARCH = 14;
    private static final int FLOOR = 6;
    private static final int WITNESS_DISTANCE = 4;
    private static final float FACE_NORTH = 180.0F;
    private static final double CENTER = 0.5;
    private final ServerPlayer player;
    private final BlockPos ground;
    private Villager witness;

    public RebornScene(ServerPlayer player) {
        this.player = player;
        var level = player.level();
        ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
        for (var pos : BlockPos.betweenClosed(ground.offset(-FLOOR, -1, -FLOOR), ground.offset(FLOOR, -1, FLOOR))) {
            level.setBlockAndUpdate(pos, Blocks.COARSE_DIRT.defaultBlockState());
        }
        player.teleportTo(ground.getX() + CENTER, ground.getY(), ground.getZ() + CENTER);
        player.setGameMode(GameType.SURVIVAL);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "meridian debug origin set "
                + player.getScoreboardName() + " " + ORIGIN);
    }

    public boolean watching(String chapter) {
        return StoryRaidFixture.watching(player, chapter, ORIGIN + "#0");
    }

    public void schedule() {
        StoryRaidFixture.due(player);
    }

    public Vec3 fallSite() {
        return Vec3.atBottomCenterOf(ground.south(3));
    }

    public void win() {
        var mob = EntityTypes.PILLAGER.create(player.level(), EntitySpawnReason.COMMAND);
        if (mob == null) {
            throw new IllegalStateException("头目未生成");
        }
        mob.setNoAi(true /* noAi */);
        mob.setPos(fallSite());
        player.level().addFreshEntity(mob);
        StoryRaidFixture.arrive(player, mob, ORIGIN + "#0", PARTY);
        mob.die(player.damageSources().playerAttack(player));
    }

    public boolean dropped(String key) {
        return findDrop(key) != null;
    }

    public boolean pickUp(String key) {
        ItemEntity item = findDrop(key);
        if (item == null) {
            if (key.equals(NOTE)) {
                StoryRaidFixture.nextOmen(player);
            }
            return false;
        }
        item.setNoPickUpDelay();
        item.playerTouch(player);
        return !item.isAlive();
    }

    private ItemEntity findDrop(String key) {
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(SEARCH),
                item -> item.getItem().getHoverName().equals(net.minecraft.network.chat.Component.translatable(key)))
                .stream().findFirst().orElse(null);
    }

    public void speak() {
        if (witness == null) {
            witness = EntityTypes.VILLAGER.create(player.level(), EntitySpawnReason.COMMAND);
            if (witness == null) {
                throw new IllegalStateException("证人未生成");
            }
            witness.setNoAi(true /* noAi */);
            witness.setPos(Vec3.atBottomCenterOf(ground.south(WITNESS_DISTANCE)));
            witness.setYRot(FACE_NORTH);
            witness.setYBodyRot(FACE_NORTH);
            witness.setYHeadRot(FACE_NORTH);
            player.level().addFreshEntity(witness);
        }
        DialogueScene.reopen(player, witness.getUUID());
    }

    public Vec3 witnessEyes() {
        return witness.getEyePosition();
    }

    public void placeBell() {
        player.level().setBlockAndUpdate(ground.south(1).east(1), Blocks.BELL.defaultBlockState());
    }

    public boolean claimBell() {
        var pos = ground.south(1).east(1);
        var level = player.level();
        if (!level.isVillage(pos)) {
            level.getPoiManager().take(type -> type.is(PoiTypes.MEETING), (type, at) -> at.equals(pos), pos, 1);
        }
        return level.isVillage(pos);
    }

    public boolean delivered(String name) {
        var state = new StoryBodies().read(player).map(StoryProgress::read)
                .map(progress -> progress.getLinesOrDefault(LINE, LineState.getDefaultInstance())).orElseThrow();
        var claim = state.getClaimsMap().get(name);
        return claim != null && !claim.getAwaitingDelivery()
                && new StoryLetters(player.getStringUUID(), LINE, name, claim).carried(player) == 1;
    }
}
