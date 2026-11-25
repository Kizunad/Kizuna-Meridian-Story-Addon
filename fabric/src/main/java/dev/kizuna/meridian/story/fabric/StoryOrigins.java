package dev.kizuna.meridian.story.fabric;

import com.mojang.serialization.MapCodec;
import dev.kizuna.meridian.origin.fabric.Origin;
import dev.kizuna.meridian.origin.fabric.OriginEntrypoint;
import dev.kizuna.meridian.origin.fabric.OriginRegistrar;
import dev.kizuna.meridian.origin.fabric.OriginTalentType;
import dev.kizuna.meridian.origin.fabric.OriginThreatType;
import java.util.Optional;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;

public final class StoryOrigins implements OriginEntrypoint {
    private static final com.mojang.serialization.Codec<Unit> FLAG = MapCodec.unit(Unit.INSTANCE).codec();
    private static final OriginTalentType<Unit> ENDURANCE = OriginTalentType.standing(
            Identifier.parse("meridian_story:enduring_limbs"), FLAG);
    private static final OriginTalentType<Unit> COUNTER = OriginTalentType.standing(
            Identifier.parse("meridian_story:gritted_counter"), FLAG);
    private static final OriginThreatType<Unit> OPENING = new OriginThreatType<>(
            Identifier.parse("meridian_story:slow_opening"), FLAG);
    private static final OriginThreatType<Unit> ROAD = new OriginThreatType<>(
            Identifier.parse("meridian_story:road_trouble"), FLAG);

    @Override
    public void register(OriginRegistrar registrar) {
        // 出身卡的行数和顺序直接取自正式的人物定义。
        registrar.talent(ENDURANCE);
        registrar.talent(COUNTER);
        registrar.threat(OPENING);
        registrar.threat(ROAD);
    }

    enum Trait { ENDURING_LIMBS, GRITTED_COUNTER, SLOW_OPENING }

    static Set<Trait> traits(Optional<Origin> origin) {
        // 从当前数据包读取，支持换出身及重载移除某项特质。
        var traits = EnumSet.noneOf(Trait.class);
        origin.ifPresent(value -> {
            if (!value.talents(ENDURANCE).isEmpty()) {
                traits.add(Trait.ENDURING_LIMBS);
            }
            if (!value.talents(COUNTER).isEmpty()) {
                traits.add(Trait.GRITTED_COUNTER);
            }
            if (!value.threats(OPENING).isEmpty()) {
                traits.add(Trait.SLOW_OPENING);
            }
        });
        return Set.copyOf(traits);
    }
}
