package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.OriginPhysique;
import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.human.server.HumanPhysique;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/** 游戏客户端和服务端用同一笔非致死部位损耗跨越生命阈值。 */
public final class PhysiqueScene {
    public static final String ORDINARY = "meridian_story:ordinary";
    public static final String LONE = "meridian_story:lone_star";
    private static final String SOURCE = "meridian_story_test:part_injuries";
    private final StoryBodies bodies = new StoryBodies();
    private final ServerPlayer player;

    public PhysiqueScene(ServerPlayer player) {
        // 夹具只绑定对象，权威身体始终从正式宿主取。
        this.player = player;
    }

    public BodySnapshot body() {
        // 获取刚提交的身体以核对实际损耗。
        return bodies.read(player).orElseThrow();
    }

    public OriginPhysique physique() {
        // 登录和恢复都从持久字段取体质。
        return HumanPhysique.read(body());
    }

    public MobEffectInstance effect() {
        // 读取玩家实有的状态实例，覆盖等级和剩余在线时间。
        return player.getEffect(GrittedCounter.EFFECT);
    }

    public void heal() {
        // 经原版治疗入口补足全身缺口，再从同一快照设置下一道阈值。
        player.heal((float) HealingRules.partMaximum(body()));
    }

    public void injureTo(double fraction) {
        // 按每个部位的最大值等比压低血量，保留肌肉、器官、护盾和原版血条各自的账。
        var body = body();
        var resolution = DamageResolution.newBuilder().setLethal(false /* lethal */);
        for (var part : body.getStructure().getPartsList()) {
            var state = body.getPartStatesList().stream().filter(value -> value.getId().equals(part.getId()))
                    .findFirst().orElseThrow();
            double loss = state.getHp() - part.getMaxHp() * fraction;
            if (loss > 0) {
                var target = HealthTarget.newBuilder().setPartId(part.getId()).build();
                var dose = HpLoss.newBuilder()
                        .setTarget(target)
                        .setHp(loss)
                        .build();
                resolution.addLosses(dose);
            }
        }
        var damage = resolution.build();
        var activity = BodyActivity.newBuilder().setDamage(damage).build();
        if (!bodies.apply(player, body, SOURCE, activity)) {
            throw new IllegalStateException("部位损耗未提交");
        }
    }
}
