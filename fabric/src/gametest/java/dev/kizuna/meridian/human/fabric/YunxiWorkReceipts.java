package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** 读取玩家身体已经提交的做功命令，供剧情招式核对出手次数和肌肉剂量。 */
public final class YunxiWorkReceipts {
    private YunxiWorkReceipts() { }

    public static List<BodyCommand> read(ServerPlayer player) {
        // 组织活动和普通挥击使用不同命令，两种都计入实付笔数。
        var body = HumanTechniqueHost.state().find(player.getUUID());
        return body.receipts().stream()
                .map(receipt -> receipt.getCommand())
                .filter(command -> command.hasExertTissues() || command.getExecuteActivity().getEffortsCount() > 0)
                .toList();
    }
}
