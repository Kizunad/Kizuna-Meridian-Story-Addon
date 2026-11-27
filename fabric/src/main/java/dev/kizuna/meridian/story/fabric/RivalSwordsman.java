package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpeciesDefinitions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;

/**
 * 陈恪：玩家体型的剧情来客，用玩家模型和专画的皮肤。不寻路、不被击退、不会被打死；
 * 身体与功法照常由生人宿主承担，站位、朝向与起守都由比试控制器驱动。
 */
public final class RivalSwordsman extends Mob {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_story", "chen_ke");
    private static final String SPECIES_RESOURCE = "/meridian-story-species.toml";
    private static final float WIDTH = 0.6F;
    private static final float HEIGHT = 1.8F;
    private static final int TRACKING_RANGE_CHUNKS = 10;
    /** 原版生命值的下限；身体部位打空时原版投影仍保持存活。 */
    private static final float MINIMUM_HEALTH = Float.MIN_NORMAL;
    private static EntityType<RivalSwordsman> type;

    public RivalSwordsman(EntityType<? extends RivalSwordsman> type, Level level) {
        super(type, level);
    }

    static void register() {
        // 实体类型先于物种表登记；生人按类型注册名认领，之后的出生、存档和受击都走正式宿主。
        EntityType.Builder<RivalSwordsman> builder = EntityType.Builder.of(RivalSwordsman::new, MobCategory.MISC);
        builder.sized(WIDTH, HEIGHT);
        builder.clientTrackingRange(TRACKING_RANGE_CHUNKS);
        type = Registry.register(BuiltInRegistries.ENTITY_TYPE, ID,
                builder.build(ResourceKey.create(Registries.ENTITY_TYPE, ID)));
        FabricDefaultAttributeRegistry.register(type, Mob.createMobAttributes());
        HumanoidSpeciesDefinitions.registerAll(speciesTable());
    }

    public static EntityType<RivalSwordsman> type() {
        return type;
    }

    private static String speciesTable() {
        // 物种表是 JAR 内固定资源，缺失或不可读都是打包错误。
        try (InputStream input = RivalSwordsman.class.getResourceAsStream(SPECIES_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少陈恪物种表 " + SPECIES_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取陈恪物种表 " + SPECIES_RESOURCE, failure);
        }
    }

    @Override
    public void knockback(double power, double xd, double zd, DamageSource source, float damage,
                          boolean comesFromEffect) {
        // 比试里他站定不退，击退整体忽略；推挤走碰撞路径，不经过这里。
    }

    @Override
    public void setHealth(float health) {
        // Healing 按部位总 HP 比例投影原版生命值；比例为零时停在下限，不进入原版死亡。
        super.setHealth(Math.max(health, MINIMUM_HEALTH));
    }

    @Override
    public boolean removeWhenFarAway(double distanceSquared) {
        return false;
    }
}
