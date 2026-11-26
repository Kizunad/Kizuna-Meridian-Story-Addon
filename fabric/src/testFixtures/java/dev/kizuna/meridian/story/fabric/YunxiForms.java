package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public final class YunxiForms {
    public static final String PARRY = "meridian_story:flash_parry";
    public static final String CLEAVE = "meridian_story:cloud_cleaver";
    public static final String GUARD = "meridian_basic_techniques:guard";
    public static final int WARMUP = 8;
    public static final int HOLD = 6;
    public static final int RECOVERY = 6;
    public static final int TICK_MILLIS = 50;
    public static final int ORDINARY_HIT = 9;
    public static final int RIPOSTE_HIT = 5;
    public static final int SKIP = (ORDINARY_HIT - RIPOSTE_HIT) * TICK_MILLIS;
    private static final double FRONT_DOSE = 1;

    private YunxiForms() { }

    public static CastOutcome cast(ServerPlayer player, String id) {
        var reference = TechniqueReference.newBuilder()
                .setId(id)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(player.getUUID(), request);
    }

    public static TechniqueProgress progress(ServerPlayer player) {
        var inspection = TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow().inspect();
        return TechniqueState.read(inspection.body());
    }

    public static void incoming(ServerPlayer player, LivingEntity enemy) {
        // 固定胸部和正面来源，让实际 Damage 处理重定向、体力费和守方回执。
        var source = player.damageSources().mobAttack(enemy);
        var damage = new VanillaDamageFabric.DirectDamage(player, source, "chest", FRONT_DOSE);
        if (VanillaDamageFabric.damageDirect(damage).isEmpty()) {
            throw new IllegalStateException("来击没有进入玩家身体");
        }
    }
}
