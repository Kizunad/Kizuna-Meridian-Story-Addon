package dev.kizuna.meridian.story.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginChoice;
import dev.kizuna.meridian.origin.server.OriginDraw;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

record StoryPlayer(ServerPlayer player, RecordingConnection connection) implements AutoCloseable {
    static final String ORIGIN = "meridian_story_test:witness";
    static final String LINE = "meridian_story_test:register";
    private static final int NAME_LENGTH = 8;
    private static final int OFFER_COUNT = 3;
    private static final int DRAW_LIMIT = 10_000;

    static UUID offered(GameTestHelper test) {
        return offered(test, ORIGIN);
    }

    static UUID offered(GameTestHelper test, String... wanted) {
        // 同一组正式随机候选可以包含多张指定卡，便于并列核对。
        var candidates = Origins.all().values().stream()
                .map(origin -> new OriginDraw.Candidate(origin.id().toString(), origin.weight(), origin.group()))
                .toList();
        long world = test.getLevel().getServer().overworld().getSeed();
        for (int attempt = 0; attempt < DRAW_LIMIT; attempt++) {
            UUID id = UUID.randomUUID();
            if (OriginDraw.draw(candidates, OFFER_COUNT, OriginDraw.seed(world, id, 0)).containsAll(List.of(wanted))) {
                return id;
            }
        }
        throw new IllegalStateException("未抽到测试出身");
    }

    static StoryPlayer join(GameTestHelper test, UUID id) {
        var profile = new GameProfile(id, "story_" + id.toString().substring(0, NAME_LENGTH));
        var level = test.getLevel();
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var channels = Stream.of("meridian:body_view", "meridian:gathering_response", "meridian:opening_response",
                "meridian_human:control", "meridian_progression:event", "kizuna_players_animation:play",
                "kizuna_players_animation:stop", "meridian_origin:offer",
                "meridian_humanoid_taunts:bubble").map(Identifier::parse).toList();
        var connection = new RecordingConnection(channels);
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.CREATIVE);
        return new StoryPlayer(player, connection);
    }

    void tickConnection() {
        // 模拟原版客户端对本刻心跳的应答，慢速整服测试也能维持在线。
        if (!connection.isConnected()) {
            return;
        }
        var packets = connection.packets();
        int first = packets.size();
        connection.tick();
        int end = packets.size();
        for (int index = first; index < end; index++) {
            if (packets.get(index) instanceof ClientboundKeepAlivePacket ping) {
                new ServerboundKeepAlivePacket(ping.getId()).handle(player.connection);
            }
        }
    }

    void chooseOrigin() {
        chooseOrigin(ORIGIN);
    }

    void chooseOrigin(String origin) {
        var choice = OriginChoice.newBuilder().setId(origin).build();
        send(OriginNetworking.CHOOSE, choice.toByteArray());
    }

    record Invitation(List<Component> buttons) {
        ClickEvent.Custom click(int index) {
            return (ClickEvent.Custom) buttons.get(index).getStyle().getClickEvent();
        }
    }

    void choose(Invitation offer) {
        choose(offer, 0);
    }

    void choose(Invitation offer, int index) {
        var click = offer.click(index);
        new ServerboundCustomClickActionPacket(click.id(), click.payload()).handle(player.connection);
    }

    private void send(ProtobufChannel channel, byte[] bytes) {
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(channel.payload(bytes)));
    }

    Stream<CustomPacketPayload> packets() {
        return connection.packets().stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload());
    }

    List<Component> chat() {
        return connection.packets().stream()
                .filter(ClientboundSystemChatPacket.class::isInstance)
                .map(ClientboundSystemChatPacket.class::cast)
                .map(ClientboundSystemChatPacket::content)
                .toList();
    }

    List<Invitation> offers() {
        return chat().stream().map(message -> message.getSiblings().stream()
                        .filter(part -> part.getStyle().getClickEvent() instanceof ClickEvent.Custom click
                                && click.id().equals(StoryChat.ACTION))
                        .toList())
                .filter(parts -> !parts.isEmpty())
                .map(Invitation::new)
                .toList();
    }

    long bubbles(int speaker) {
        return packets().filter(BubblePayload.class::isInstance).map(BubblePayload.class::cast)
                .filter(bubble -> bubble.entityId() == speaker && bubble.line().equals(
                        net.minecraft.network.chat.Component.translatable("story.test.received"))).count();
    }

    LineState state() {
        return state(LINE);
    }

    LineState state(String line) {
        return new StoryBodies().read(player).map(StoryProgress::read)
                .map(progress -> progress.getLinesOrDefault(line, LineState.getDefaultInstance()))
                .orElseGet(LineState::getDefaultInstance);
    }

    @Override
    public void close() {
        connection.disconnectFromClient();
    }
}
