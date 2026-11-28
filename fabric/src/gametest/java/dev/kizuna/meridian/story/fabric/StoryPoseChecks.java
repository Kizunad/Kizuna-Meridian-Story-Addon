package dev.kizuna.meridian.story.fabric;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** 剧情演员姿态的核对：只看本仓写的标签、座位和朝向；标签怎么变成动作由 PA-Mobs 自己的 GameTest 管。 */
final class StoryPoseChecks {
    private static final double SEAT_TOLERANCE = 0.01;
    private static final float FACING_TOLERANCE = 1;
    private static final int FIRE_SEARCH = 2;
    private static final float FACING_OFFSET = 90;

    private StoryPoseChecks() { }

    /** 演员被指定的姿态（去掉前缀；不含抱袖和一次性的交谈打断）。 */
    static Set<String> poses(Entity actor) {
        return actor.entityTags().stream()
                .filter(tag -> tag.startsWith(StoryPoses.PREFIX))
                .filter(tag -> !tag.equals(StoryPoses.SLEEVES) && !tag.equals(StoryPoses.TALK))
                .map(tag -> tag.substring(StoryPoses.PREFIX.length()))
                .collect(Collectors.toSet());
    }

    /** 坐在营火边：只指定了火边，中心离火 18 px，面朝火。 */
    static void seatedAtFire(GameTestHelper test, Mob actor) {
        var fire = BlockPos.findClosestMatch(actor.blockPosition(), FIRE_SEARCH, 1,
                pos -> actor.level().getBlockState(pos).is(BlockTags.CAMPFIRES));
        test.assertTrue(fire.isPresent(), "演员身边没有营火");
        var center = Vec3.atBottomCenterOf(fire.orElseThrow());
        double distance = actor.position().subtract(center).horizontalDistance();
        test.assertTrue(poses(actor).equals(Set.of(StoryPoses.CAMPFIRE)), "火边的演员姿态不对，实际 " + poses(actor));
        test.assertTrue(Math.abs(distance - StoryPoses.SEAT_DISTANCE) < SEAT_TOLERANCE,
                "离营火中心 " + distance + " 格，规格是 " + StoryPoses.SEAT_DISTANCE + " 格");
        facing(test, actor, center);
    }

    static void facing(GameTestHelper test, Mob actor, Vec3 target) {
        double dx = target.x - actor.getX();
        double dz = target.z - actor.getZ();
        float expected = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - FACING_OFFSET);
        float error = Math.abs(Mth.wrapDegrees(actor.getYRot() - expected));
        test.assertTrue(error < FACING_TOLERANCE, "没有面朝目标，偏了 " + error + "°");
    }
}
