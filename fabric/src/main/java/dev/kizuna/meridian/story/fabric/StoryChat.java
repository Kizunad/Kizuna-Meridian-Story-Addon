package dev.kizuna.meridian.story.fabric;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public final class StoryChat {
    static final int LATER = -1;
    static final Identifier ACTION = Identifier.parse("meridian_story:dialogue");
    private static final int NONCE_LENGTH = 36;
    private static StoryDialogue dialogue;
    private static GraveForge forge;

    private StoryChat() { }

    static void register(StoryDialogue handler, GraveForge smith) {
        dialogue = handler;
        forge = smith;
    }

    record Selection(String nonce, long revision, int option) {
        private ClickEvent.Custom click(Identifier action) {
            var tag = new CompoundTag();
            tag.putString("session", nonce);
            tag.putLong("revision", revision);
            tag.putInt("option", option);
            return new ClickEvent.Custom(action, Optional.of(tag));
        }

        private static Optional<Selection> read(Optional<Tag> payload) {
            if (payload.orElse(null) instanceof CompoundTag tag
                    && tag.get("revision") instanceof LongTag revision && tag.get("option") instanceof IntTag option) {
                String nonce = tag.getStringOr("session", "");
                if (nonce.length() == NONCE_LENGTH) {
                    return Optional.of(new Selection(nonce, revision.value(), option.value()));
                }
            }
            return Optional.empty();
        }
    }

    /** 只接管剧情自己的点击，身份取自当前游戏连接。 */
    public static boolean clicked(ServerPlayer player, ServerboundCustomClickActionPacket packet) {
        boolean forgeClick = GraveForge.ACTION.equals(packet.id());
        if (!forgeClick && !ACTION.equals(packet.id())) {
            return false;
        }
        Component reply = Selection.read(packet.payload())
                .flatMap(selection -> {
                    if (forgeClick) {
                        return forge.choose(player, selection);
                    }
                    return dialogue.choose(player, selection);
                })
                .orElseGet(() -> Component.translatable("screen.meridian_story.expired"));
        player.sendSystemMessage(reply.copy().withStyle(ChatFormatting.GRAY));
        return true;
    }

    static void narrate(ServerPlayer player, String key) {
        if (!key.isEmpty()) {
            player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
    }

    static Component button(String label, Component cost, ChatFormatting color, Selection selection) {
        return button(label, cost, color, selection, ACTION);
    }

    static Component button(String label, Component cost, ChatFormatting color, Selection selection,
                            Identifier action) {
        return Component.literal("[")
                .append(Component.translatable(label))
                .append("]")
                .withStyle(style -> style
                        .withColor(color)
                        .withClickEvent(selection.click(action))
                        .withHoverEvent(new HoverEvent.ShowText(cost)));
    }
}
