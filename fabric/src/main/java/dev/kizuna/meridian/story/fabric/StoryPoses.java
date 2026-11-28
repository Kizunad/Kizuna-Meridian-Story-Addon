package dev.kizuna.meridian.story.fabric;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * 剧情演员的日常姿态：给 PA-Mobs 的姿态控制写实体标签（本仓不引用那边的类），火边的人按规格的距离坐到营火前。
 * 标签的意思见 PA-Mobs 的 MobIdlePoses：idle.&lt;姿态&gt; 只摆这几种，idle.sleeves 抱袖，idle.talk 一次性的交谈打断。
 */
final class StoryPoses {
    static final String AKIMBO = "akimbo";
    static final String ARMS_CROSSED = "arms_crossed";
    static final String HANDS_BEHIND = "hands_behind";
    static final String LEAN_COUNTER = "lean_counter";
    static final String CAMPFIRE = "campfire";
    static final String PREFIX = "kizuna_players_animation_mobs.idle.";
    static final String SLEEVES = PREFIX + "sleeves";
    static final String TALK = PREFIX + "talk";
    // 规格：火堆近面在演员中心前 10 px，营火方块中心再往前半格，共 18 px。
    static final double SEAT_DISTANCE = 18.0 / 16;
    private static final float DEGREES_PER_RADIAN = (float) (180 / Math.PI);
    private static final float FACING_OFFSET = 90;

    private StoryPoses() { }

    static void pose(Entity actor, String... poses) {
        for (String pose : poses) {
            actor.addTag(PREFIX + pose);
        }
    }

    static void drop(Entity actor, String pose) {
        actor.removeTag(PREFIX + pose);
    }

    /** 摊主面朝柜台站着，靠柜台或叉腰。 */
    static void tend(Mob seller, BlockPos counter) {
        face(seller, Vec3.atBottomCenterOf(counter));
        pose(seller, LEAN_COUNTER, AKIMBO);
    }

    /** 坐在火边的把手收进袖里，之后一直抱着袖。 */
    static void sleeves(Entity actor) {
        actor.addTag(SLEEVES);
    }

    /** 玩家搭话打断姿态：站着的退出，坐着的收手入袖。没摆姿态的演员不留标签。 */
    static void talk(Entity actor) {
        if (actor.entityTags().stream().anyMatch(tag -> tag.startsWith(PREFIX) && !tag.equals(TALK))) {
            actor.addTag(TALK);
        }
    }

    /** 坐到营火四邻里离演员最近、能落脚的一格，中心离火 18 px，面朝火；四邻都落不了脚就原地坐。 */
    static void seat(ServerLevel level, Mob actor, BlockPos fire) {
        var center = Vec3.atBottomCenterOf(fire);
        Optional<Direction> side = Direction.Plane.HORIZONTAL.stream()
                .filter(direction -> FateSites.standable(level, fire.relative(direction)))
                .min(Comparator.comparingDouble(direction ->
                        actor.position().distanceToSqr(Vec3.atBottomCenterOf(fire.relative(direction)))));
        if (side.isPresent()) {
            var step = side.get();
            actor.setPos(center.x + step.getStepX() * SEAT_DISTANCE, fire.getY(),
                    center.z + step.getStepZ() * SEAT_DISTANCE);
        }
        face(actor, center);
        pose(actor, CAMPFIRE);
    }

    private static void face(Mob actor, Vec3 target) {
        // 关了 AI 的来客不会自己转头，按水平方向同时设身、头和朝向。
        double dx = target.x - actor.getX();
        double dz = target.z - actor.getZ();
        float yaw = Mth.wrapDegrees((float) (Math.atan2(dz, dx) * DEGREES_PER_RADIAN) - FACING_OFFSET);
        actor.setYRot(yaw);
        actor.setYBodyRot(yaw);
        actor.setYHeadRot(yaw);
        actor.setXRot(0);
    }
}
