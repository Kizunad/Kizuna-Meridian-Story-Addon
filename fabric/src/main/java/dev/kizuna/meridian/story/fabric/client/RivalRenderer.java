package dev.kizuna.meridian.story.fabric.client;

import dev.kizuna.meridian.story.fabric.RivalSwordsman;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/** 宽臂玩家模型（含外层袖、襟、裤）配陈恪的 64×64 皮肤；PA 接管时换成同一皮肤的 24 骨网格。 */
final class RivalRenderer
        extends HumanoidMobRenderer<RivalSwordsman, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    private static final Identifier SKIN =
            Identifier.fromNamespaceAndPath("meridian_story", "textures/entity/chen_ke.png");
    private static final float SHADOW_RADIUS = 0.5F;
    private static final int TEXTURE_SIZE = 64;

    RivalRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(StoryClient.RIVAL_LAYER)), SHADOW_RADIUS);
    }

    static LayerDefinition layer() {
        // 外层挂在各肢体下面，跟着两足骨架一起动。
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false /* slim */);
        return LayerDefinition.create(mesh, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return SKIN;
    }
}
