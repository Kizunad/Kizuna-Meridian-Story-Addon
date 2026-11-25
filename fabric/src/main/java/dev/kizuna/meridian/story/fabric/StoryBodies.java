package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.server.StoryProgress;
import com.google.protobuf.Any;
import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyActivityHost;
import dev.kizuna.meridian.server.BodyHealthHost;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * 剧情记录经 Framework 的两个身体宿主入口读写：健康宿主读身体，活动宿主原子地改附属进度。
 * 服务端先读修订号，写入冲突由调用方重新取身体和章节做判断。
 */
final class StoryBodies {
    private final List<BodyHealthHost> healthHosts;
    private final List<BodyActivityHost> activityHosts;

    StoryBodies() {
        // 身体宿主是必装的；没有就启动失败，不退回别的存法。
        healthHosts = FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class);
        activityHosts = FabricLoader.getInstance().getEntrypoints("meridian_activity_host", BodyActivityHost.class);
        if (healthHosts.isEmpty() || activityHosts.isEmpty()) {
            throw new IllegalStateException("剧情缺少必需的身体宿主");
        }
    }

    Optional<BodySnapshot> read(ServerPlayer player) {
        // 身体还没建好、宿主暂停、宿主拒绝，都当成「这一刻读不到」，调用方下一 tick 再来。
        String requestId = UUID.randomUUID().toString();
        byte[] payload = HealthHostProtobuf.encodeRequest(HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setSubjectId(player.getStringUUID())
                .setInspect(true /* inspect */)
                .build());
        for (BodyHealthHost host : healthHosts) {
            HealthHostResponse response = HealthHostProtobuf.decodeResponse(host.exchange(payload));
            if (response.getRequestId().equals(requestId) && !response.hasFailure() && response.hasBody()) {
                return Optional.of(response.getBody());
            }
        }
        return Optional.empty();
    }

    boolean write(ServerPlayer player, BodySnapshot body, Progress record) {
        // 带上读到的修订号提交：中间身体被别人改过就失败，不拿旧记录盖新身体。
        return write(player, body, StoryProgress.KEY, Any.pack(record));
    }

    boolean write(ServerPlayer player, BodySnapshot body, String key, Any state) {
        // 只替换所属字段，章节推进和体质触发可各自重读冲突的修订号。
        var activity = BodyActivity.newBuilder().putProgress(key, state).build();
        return apply(player, body, key, activity);
    }

    boolean apply(ServerPlayer player, BodySnapshot body, String key, BodyActivity activity) {
        // 调用身份和身体修订号一起交给活动宿主。
        var command = BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setExecuteActivity(activity)
                .build();
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        byte[] payload = ActivityHostProtobuf.encodeRequest(ActivityHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(envelope.getRequestId())
                .setSubjectId(player.getStringUUID())
                .setActivity(envelope)
                .build());
        for (BodyActivityHost host : activityHosts) {
            HealthHostResponse response = HealthHostProtobuf.decodeResponse(host.exchange(key, payload));
            if (!response.getRequestId().equals(envelope.getRequestId())) {
                throw new IllegalStateException("身体宿主响应身份不匹配");
            }
            if (response.hasFailure()) {
                return false;
            }
            if (response.hasBody()) {
                return true;
            }
        }
        throw new IllegalStateException("已绑定身体没有对应的活动宿主");
    }
}
