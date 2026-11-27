package dev.kizuna.meridian.story.fabric.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.story.fabric.RivalSwordsman;
import dev.kizuna.playeranimation.api.PlayerAnimationHost;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationResponse;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.RegisterGeometry;
import java.io.IOException;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** 客户端入口：陈恪用玩家模型和专画皮肤，PA 骨架借玩家的 24 骨几何，守架与剑式动作因此照常播放。 */
public final class StoryClient implements ClientModInitializer {
    static final ModelLayerLocation RIVAL_LAYER = new ModelLayerLocation(RivalSwordsman.ID, "main");
    private static final Identifier GEOMETRY_LISTENER =
            Identifier.fromNamespaceAndPath("meridian_story", "rival_geometry");
    private static final Identifier PLAYER_GEOMETRY =
            Identifier.fromNamespaceAndPath("kizuna_players_animation", "geo/player_skeleton.geo.json");
    private static final String CLASSIC = "geometry.player.classic";

    @Override
    public void onInitializeClient() {
        // 渲染器登记到原版表；几何在每次资源重载后重新交给 PA，与 PA 自己的玩家骨架同源。
        ModelLayerRegistry.registerModelLayer(RIVAL_LAYER, RivalRenderer::layer);
        EntityRenderers.register(RivalSwordsman.type(), RivalRenderer::new);
        var host = host();
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(GEOMETRY_LISTENER,
                (ResourceManagerReloadListener) manager -> registerGeometry(host, manager));
    }

    private static PlayerAnimationHost host() {
        List<PlayerAnimationHost> hosts = FabricLoader.getInstance()
                .getEntrypoints(PlayerAnimationHost.ENTRYPOINT, PlayerAnimationHost.class);
        if (hosts.size() != 1) {
            throw new IllegalStateException("必须发现唯一的 PA 字节入口，实际数量: " + hosts.size());
        }
        return hosts.getFirst();
    }

    private static void registerGeometry(PlayerAnimationHost host, ResourceManager manager) {
        // 玩家骨架是 PA 包内固定资源，读不到或被拒都是装配错误。
        byte[] geometry;
        try (var stream = manager.getResourceOrThrow(PLAYER_GEOMETRY).open()) {
            geometry = stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取玩家骨架 " + PLAYER_GEOMETRY, failure);
        }
        var registration = RegisterGeometry.newBuilder()
                .setGeometryId(CLASSIC)
                .setGeometryJson(ByteString.copyFrom(geometry))
                .addEntityTypes(RivalSwordsman.ID.toString())
                .build();
        var request = EntityAnimationRequest.newBuilder()
                .setRequestId("register:" + RivalSwordsman.ID)
                .setRegisterGeometry(registration)
                .build();
        EntityAnimationResponse response;
        try {
            response = EntityAnimationResponse.parseFrom(host.exchange(request.toByteArray()));
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("PA 返回了无法解码的几何回执", failure);
        }
        if (response.getCode() != EntityAnimationResponse.Code.OK) {
            throw new IllegalStateException("PA 拒绝陈恪的几何：" + response.getDetail());
        }
    }
}
