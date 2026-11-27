package dev.kizuna.meridian.story.fabric;

import com.google.protobuf.Any;
import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.OriginPhysique;
import dev.kizuna.meridian.human.server.HumanPhysique;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.story.fabric.StoryOrigins.Trait;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class GrittedCounter extends MobEffect {
    private static final Identifier ID = Identifier.parse("meridian_story:gritted_counter");
    private static final int COLOR = 0xb54522;
    private static final int LEVEL = 2;
    private static final double INJURED = 0.35;
    private static final double RECOVERED = 0.50;
    private static final int SECOND = 20;
    private static final int COOLDOWN = 90 * SECOND;
    static final Holder<MobEffect> EFFECT = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, ID,
            new GrittedCounter());
    private static Players players;

    private GrittedCounter() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }

    static void initialize() {
        // 注册表冻结前登记可见效果，再接玩家生命周期。
        players = new Players();
        players.register();
    }

    public static void removed(ServerPlayer player, Collection<MobEffectInstance> removed) {
        // 牛奶、命令清效和自然到期共用原版移除回调，同一刻收回双臂的短时功率。
        if (players != null && removed.stream().anyMatch(effect -> effect.getEffect().equals(EFFECT))) {
            players.stop(player);
        }
    }

    private static final class Players {
        private final StoryBodies bodies = new StoryBodies();
        private final Map<UUID, Set<Trait>> tracked = new HashMap<>();
        private final Set<UUID> active = new HashSet<>();

        void register() {
            // 登录、换出身和重载重新同步常态；伤后取已提交的身体，每秒也复核治疗和直接改血。
            Origins.READY.register((player, origin) -> refresh(player, StoryOrigins.traits(origin)));
            Origins.CHOSEN.register((player, origin) -> refresh(player, StoryOrigins.traits(Optional.of(origin))));
            ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(Identifier.parse("meridian_story:after_crisis"),
                    (server, resources, success) -> {
                        if (success) {
                            server.getPlayerList().getPlayers().forEach(player ->
                                    refresh(player, StoryOrigins.traits(Origins.chosen(player))));
                        }
                    });
            ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, base, taken, blocked) -> {
                if (target instanceof ServerPlayer player) {
                    reconcile(player);
                }
            });
            ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
                if (entity instanceof ServerPlayer player) {
                    player.removeEffect(EFFECT);
                    reconcile(player);
                }
            });
            ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> reconcile(newPlayer));
            ServerTickEvents.END_SERVER_TICK.register(server ->
                    server.getPlayerList().getPlayers().forEach(this::tick));
            ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player.getUUID()));
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                tracked.clear();
                active.clear();
            });
        }

        private void forget(UUID id) {
            // 权威截止时刻随身体落盘，连接缓存只用于减少无变化时的查询。
            tracked.remove(id);
            active.remove(id);
        }

        private void refresh(ServerPlayer player, Set<Trait> traits) {
            // 恢复旧档或换出身都要先读取身体，即使新出身没有特质。
            tracked.put(player.getUUID(), traits);
            reconcile(player);
        }

        private void tick(ServerPlayer player) {
            // 爆发中逐刻检查治疗，常态下在受伤事件和每秒时读取身体。
            if (active.contains(player.getUUID()) || player.tickCount % SECOND == 0) {
                reconcile(player);
            }
        }

        private void stop(ServerPlayer player) {
            player.getAttribute(Attributes.ATTACK_SPEED).removeModifier(ID);
            // 只清截止时刻；冷却与回半血的门槛继续随身体保存。
            var id = player.getUUID();
            if (active.contains(id)) {
                bodies.read(player).ifPresent(body -> {
                    var rested = HumanPhysique.read(body).toBuilder().clearBurstUntilTick().build();
                    if (bodies.write(player, body, HumanPhysique.KEY, Any.pack(rested))) {
                        active.remove(id);
                    }
                });
            }
        }

        private void reconcile(ServerPlayer player) {
            var id = player.getUUID();
            var traits = tracked.get(id);
            if (traits == null) {
                return;
            }
            bodies.read(player).ifPresent(body -> {
                var before = HumanPhysique.read(body);
                long online = now(player);
                var next = decide(player, traits, body, before, online);
                if (!before.equals(next) && !bodies.write(player, body, HumanPhysique.KEY, Any.pack(next))) {
                    return;
                }
                // 写成以后才授效，修订冲突留到下一次复核。
                if (next.getBurstUntilTick() > online) {
                    active.add(id);
                    if (before.getBurstUntilTick() == 0) {
                        int ticks = Math.toIntExact(next.getBurstUntilTick() - online);
                        player.addEffect(new MobEffectInstance(EFFECT, ticks, LEVEL - 1, false /* ambient */,
                                false /* visible */, true /* showIcon */));
                    }
                    var speed = player.getAttribute(Attributes.ATTACK_SPEED);
                    var modifier = new AttributeModifier(ID, FateRoad.grade(StoryProgress.read(body)).speed,
                            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                    if (!modifier.equals(speed.getModifier(ID))) {
                        speed.removeModifier(ID);
                        speed.addTransientModifier(modifier);
                    }
                } else {
                    active.remove(id);
                    player.getAttribute(Attributes.ATTACK_SPEED).removeModifier(ID);
                    player.removeEffect(EFFECT);
                }
                if (traits.isEmpty()) {
                    forget(id);
                }
            });
        }
    }

    private static long now(ServerPlayer player) {
        // PLAY_TIME 只计实际在线刻，跨死亡和退出保存，离线时间不会抵消冷却。
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    private static OriginPhysique decide(ServerPlayer player, Set<Trait> traits, BodySnapshot body,
                                          OriginPhysique before, long now) {
        // 新玩家和平常出身无需体质记录；曾有特质者须把撤销写回原键。
        if (traits.isEmpty() && before.equals(OriginPhysique.getDefaultInstance())) {
            return before;
        }
        var state = before.toBuilder()
                .setEnduringLimbs(traits.contains(Trait.ENDURING_LIMBS))
                .setSlowOpening(traits.contains(Trait.SLOW_OPENING));
        if (!traits.contains(Trait.GRITTED_COUNTER)) {
            return state.clearArmBurst().clearBurstUntilTick().clearReadyAtTick().clearAwaitingRecovery().build();
        }
        var grade = FateRoad.grade(StoryProgress.read(body));
        state.setArmBurst(grade.power);
        if (!player.isAlive() || body.getVitality().getDead()) {
            return state.clearBurstUntilTick().build();
        }
        // 真身的部位损耗不计入局部黄心；35% 和 50% 各包括等于阈值的一刻。
        double maximum = HealingRules.partMaximum(body);
        double hp = HealingRules.partHp(body);
        if (hp >= maximum * RECOVERED) {
            return state.clearBurstUntilTick().clearAwaitingRecovery().build();
        }
        if (before.getBurstUntilTick() != 0) {
            if (now >= before.getBurstUntilTick() || !player.hasEffect(EFFECT)) {
                state.clearBurstUntilTick();
            }
        } else if (hp <= maximum * INJURED && !before.getAwaitingRecovery() && now >= before.getReadyAtTick()) {
            state.setBurstUntilTick(now + grade.seconds * SECOND)
                    .setReadyAtTick(now + COOLDOWN)
                    .setAwaitingRecovery(true /* awaitingRecovery */);
        }
        return state.build();
    }
}
