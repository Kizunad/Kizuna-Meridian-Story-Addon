package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.story.server.StoryProgress;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;

final class GraveMood extends MobEffect {
    private static final String ID = StoryProgress.KEY + "/homesick";
    private static final int COLOR = 0x524755;
    private static final double RECOVERY = 0.8;
    private static final long DURATION = Math.min(180_000,
            dev.kizuna.meridian.core.CapabilityModifier.MAX_CURVE_DURATION_MILLIS);
    private static final int REFRESH_DIVISOR = 2;
    static final Holder<MobEffect> EFFECT = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
            Identifier.parse("meridian_story:homesick"), new GraveMood());

    static void initialize() {
        // 触发静态注册；原版在模组初始化结束后冻结注册表。
    }

    private GraveMood() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }

    static void reconcile(ServerPlayer player, StoryBodies bodies, BodySnapshot body, Mood mood) {
        var current = body.getModifiersList().stream().filter(mod -> ID.equals(mod.getId())).findFirst();
        long now = body.getSettledAt().getElapsedMillis();
        var plan = BodyActivity.newBuilder();
        if (mood != Mood.AT_PEACE) {
            if (!player.hasEffect(EFFECT)) {
                player.addEffect(new MobEffectInstance(EFFECT, MobEffectInstance.INFINITE_DURATION));
                if (mood == Mood.HOMESICK) {
                    StoryChat.narrate(player, "story.meridian_story.tomb.sad");
                }
            }
            if (current.isPresent() && now - current.get().getStartMillis() < DURATION / REFRESH_DIVISOR) {
                return;
            }
            plan.addAddModifiers(modifier(now));
        } else {
            player.removeEffect(EFFECT);
            if (current.isEmpty()) {
                return;
            }
        }
        current.ifPresent(old -> plan.addRemoveModifiers(old.getId()));
        bodies.apply(player, body, StoryProgress.KEY, plan.build());
    }

    enum Mood { AT_PEACE, HOMESICK, BEREAVED }

    private static CapabilityModifier modifier(long now) {
        var start = CurvePoint.newBuilder().setValue(1).build();
        var end = CurvePoint.newBuilder()
                .setOffsetMillis(DURATION)
                .setValue(1)
                .build();
        return CapabilityModifier.newBuilder()
                .setId(ID)
                .setSource(StoryProgress.KEY)
                .setTissueId("attention_network")
                .setStartMillis(now)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setTempoPeak(1)
                .setRecoveryPeak(RECOVERY)
                .addCurve(start)
                .addCurve(end)
                .build();
    }
}
