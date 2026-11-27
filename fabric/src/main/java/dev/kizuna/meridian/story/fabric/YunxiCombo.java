package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStrikeScope;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStrikeScope.Strike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.server.GuardState;
import dev.kizuna.meridian.technique.server.TechniqueCastEndReason;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric.DamageEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 月弧轻撩接连珠两剑：轻撩被对方的持守正面接住后，在窗内起连珠，两拍都落在同一次持守上，
 * 末拍加收守方的命中体力费。记录只留在内存里，以施剑者为键，一人至多一条。
 */
public final class YunxiCombo {
    static final String LIFT = "meridian_story:moon_lift";
    static final String TWIN = "meridian_story:twin_pearls";
    private static final long TICK_MILLIS = 50;
    private static final long START_OPEN_TICKS = 9;
    private static final long START_CLOSE_TICKS = 20;
    private static final long FIRST_BEAT_TICKS = 6;
    /** 连珠首拍相对轻撩命中的最早、最晚时刻；按起手窗加上连珠自身的起手时长折算。 */
    private static final long FIRST_BEAT_OPEN_MILLIS = (START_OPEN_TICKS + FIRST_BEAT_TICKS) * TICK_MILLIS;
    private static final long FIRST_BEAT_CLOSE_MILLIS = (START_CLOSE_TICKS + FIRST_BEAT_TICKS) * TICK_MILLIS;
    private static final double FINAL_BEAT_COST_SCALE = 2.0;

    /** 整套连招走完后通知一次；末拍把对方的持守打到耗尽时 broken 为真。 */
    public static final Event<Listener> COMPLETED = EventFactory.createArrayBacked(Listener.class,
            listeners -> outcome -> {
                for (Listener listener : listeners) {
                    listener.completed(outcome);
                }
            });

    private static final Map<UUID, Opening> openings = new HashMap<>();
    private static final Map<UUID, Chain> chains = new HashMap<>();

    private YunxiCombo() {
        // 只登记监听，不持有世界对象。
    }

    @FunctionalInterface
    public interface Listener {
        void completed(Outcome outcome);
    }

    /** 一次走完的连招：攻守双方、轻撩与连珠的施放号、被打的那次持守，以及末拍是否打到耗尽。 */
    public record Outcome(UUID attacker, UUID defender, String liftCastId, String chainCastId,
                          String guardCastId, boolean broken) {
        public Outcome {
            Objects.requireNonNull(attacker);
            Objects.requireNonNull(defender);
            Objects.requireNonNull(liftCastId);
            Objects.requireNonNull(chainCastId);
            Objects.requireNonNull(guardCastId);
        }
    }

    /** 轻撩被主目标正面接住的事实；剑是命中当刻的副本。 */
    private record Opening(UUID defender, String guardCastId, String liftCastId, Hand hand, ItemStack sword,
                           long liftMillis) { }

    /** 连珠首拍已被同一次持守接住。 */
    private record Chain(Opening opening, String castId) { }

    static void register() {
        // 伤害提交后记事实，准备阶段只读；施剑者死亡、离线、换维和停服都清掉记录。
        VanillaDamageFabric.registerDamageObserver(YunxiCombo::observe);
        VanillaDamageFabric.registerGuardHitCostScale(YunxiCombo::scale);
        TechniqueFabric.CAST_ENDED.register(YunxiCombo::castEnded);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> clear(entity.getUUID()));
        ServerPlayerEvents.LEAVE.register(player -> clear(player.getUUID()));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                clear(player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            openings.clear();
            chains.clear();
        });
    }

    private static void observe(DamageEvent event) {
        // 只认本击自己的来源；附伤、反伤和普通挥砍没有施放身份。
        if (event.attacker() == null) {
            return;
        }
        Optional<Strike> current = TechniqueStrikeScope.current(event.source());
        if (current.isEmpty()) {
            return;
        }
        Strike strike = current.orElseThrow();
        String technique = strike.effect().getTechnique().getId();
        if (technique.equals(LIFT)) {
            lifted(event, strike);
        } else if (technique.equals(TWIN)) {
            chained(event, strike);
        }
    }

    private static void lifted(DamageEvent event, Strike strike) {
        // 扫中两人时只看先结算的主目标；主目标没有用持守接住，旧记录也一并作废。
        String target = event.target().getStringUUID();
        if (strike.targets().isEmpty() || !strike.targets().getFirst().equals(target)) {
            return;
        }
        UUID attacker = event.attacker().getUUID();
        clear(attacker);
        Optional<GuardState> guard = event.input().guarded();
        if (guard.isEmpty()) {
            return;
        }
        EffectIntent effect = strike.effect();
        Opening opening = new Opening(event.target().getUUID(), guard.orElseThrow().castId(), effect.getCastId(),
                effect.getSelectedHand(), strike.weapon(), effect.getScheduledMillis());
        openings.put(attacker, opening);
    }

    private static void chained(DamageEvent event, Strike strike) {
        // 首拍消费轻撩的记录，末拍消费首拍的记录；任一拍对不上就按普通连珠收场。
        UUID attacker = event.attacker().getUUID();
        EffectIntent effect = strike.effect();
        Optional<GuardState> guard = event.input().guarded();
        if (!effect.getWeaponStrike().getStrike().getLastStrike()) {
            Opening opening = openings.remove(attacker);
            chains.remove(attacker);
            if (opening != null && inWindow(opening, effect) && continues(opening, strike, event.target(), guard)) {
                chains.put(attacker, new Chain(opening, effect.getCastId()));
            }
            return;
        }
        Chain chain = chains.remove(attacker);
        if (chain == null || !chain.castId().equals(effect.getCastId())
                || !continues(chain.opening(), strike, event.target(), guard)) {
            return;
        }
        Opening opening = chain.opening();
        boolean broken = exhausted(event, opening.guardCastId());
        COMPLETED.invoker().completed(new Outcome(attacker, opening.defender(), opening.liftCastId(),
                chain.castId(), opening.guardCastId(), broken));
    }

    private static double scale(LivingEntity defender, DamageSource source, GuardState guard) {
        // 连珠末拍落在同一次持守上才加收；这里只读记录，本击提交后由观察方消费。
        Optional<Strike> current = TechniqueStrikeScope.current(source);
        if (current.isEmpty() || source.getEntity() == null) {
            return 1;
        }
        Strike strike = current.orElseThrow();
        EffectIntent effect = strike.effect();
        if (!effect.getTechnique().getId().equals(TWIN) || !effect.getWeaponStrike().getStrike().getLastStrike()) {
            return 1;
        }
        Chain chain = chains.get(source.getEntity().getUUID());
        if (chain != null && chain.castId().equals(effect.getCastId())
                && continues(chain.opening(), strike, defender, Optional.of(guard))) {
            return FINAL_BEAT_COST_SCALE;
        }
        return 1;
    }

    private static boolean inWindow(Opening opening, EffectIntent firstBeat) {
        // 两个时刻都取施剑者身体时钟上的权威生效时刻，两端都算在窗内。
        long elapsed = firstBeat.getScheduledMillis() - opening.liftMillis();
        return elapsed >= FIRST_BEAT_OPEN_MILLIS && elapsed <= FIRST_BEAT_CLOSE_MILLIS;
    }

    private static boolean continues(Opening opening, Strike strike, LivingEntity defender,
                                     Optional<GuardState> guard) {
        // 同一个人、同一次持守、同一只手、同一把剑。
        return opening.defender().equals(defender.getUUID())
                && guard.isPresent() && guard.orElseThrow().castId().equals(opening.guardCastId())
                && strike.effect().getSelectedHand() == opening.hand()
                && sameSword(opening.sword(), strike.weapon());
    }

    /** 施剑者这次连珠的首拍是否已接上轻撩、落在同一次持守上；末拍结算前一直成立。 */
    static boolean chained(UUID attacker, String chainCastId) {
        Chain chain = chains.get(attacker);
        return chain != null && chain.castId().equals(chainCastId);
    }

    static boolean sameSword(ItemStack recorded, ItemStack held) {
        // 每一式末击都会磨剑，比对时不计耐久；其余组件有一处不同就算换了剑。
        ItemStack first = recorded.copy();
        ItemStack second = held.copy();
        first.remove(DataComponents.DAMAGE);
        second.remove(DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(first, second);
    }

    private static boolean exhausted(DamageEvent event, String guardCastId) {
        // 本击之前这次持守还有效；提交后的身体写着同一次持守因耗尽收势，就是这一击打破的。
        var body = BodyProtobuf.fromMessage(event.result().body());
        var active = TechniqueState.read(body).getActiveCast();
        return active.getRequest().getCastId().equals(guardCastId) && active.getSustained().getExhausted();
    }

    private static void castEnded(TechniqueCastEnded event) {
        // 轻撩自己正常走完不影响记录；它被打断，或中间另起了别的式，连招就断了。
        UUID attacker = event.entityUuid();
        Opening opening = openings.get(attacker);
        Chain chain = chains.get(attacker);
        if (opening == null && chain == null) {
            return;
        }
        Opening recorded = opening;
        if (recorded == null) {
            recorded = chain.opening();
        }
        boolean liftFinished = event.castId().equals(recorded.liftCastId())
                && event.reason() == TechniqueCastEndReason.COMPLETED;
        if (!liftFinished) {
            clear(attacker);
        }
    }

    private static void clear(UUID attacker) {
        openings.remove(attacker);
        chains.remove(attacker);
    }
}
