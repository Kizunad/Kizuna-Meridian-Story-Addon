package dev.kizuna.meridian.story.fabric.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder;
import net.minecraft.client.gui.components.ChatComponent.ChatMethod;
import net.minecraft.client.gui.components.ChatComponent.DisplayMode;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 从原版收件与文字命中区域找到最新邀请的第一项，以普通左键选中。 */
final class ChatChoices {
    private static final Identifier ACTION = Identifier.parse("meridian_story:dialogue");
    private static final int CHAT_LEFT_SAMPLE = 8;
    private static final int SAMPLE_STEP = 3;
    private ClickEvent.Custom first;
    private int x;
    private int y;
    private final List<Component> messages = new ArrayList<>();

    void listen() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                messages.add(message);
                message.getSiblings().stream().map(part -> part.getStyle().getClickEvent())
                        .filter(ClickEvent.Custom.class::isInstance)
                        .map(ClickEvent.Custom.class::cast)
                        .filter(click -> ACTION.equals(click.id()))
                        .findFirst().ifPresent(click -> first = click);
            }
        });
    }

    boolean received(Component expected) {
        return messages.contains(expected);
    }

    boolean locate(Minecraft client) {
        if (first == null) {
            return false;
        }
        var chat = client.gui.hud.getChat();
        if (!(client.gui.screen() instanceof ChatScreen)) {
            chat.openScreen(ChatMethod.MESSAGE, ChatScreen::new);
            return false;
        }
        int height = client.getWindow().getGuiScaledHeight();
        int time = client.gui.hud.getGuiTicks();
        for (int row = height - 1; row > 0; row -= SAMPLE_STEP) {
            var finder = new ClickableStyleFinder(client.font, CHAT_LEFT_SAMPLE, row);
            chat.captureClickableText(finder, height, time, DisplayMode.FOREGROUND);
            var style = finder.result();
            if (style != null && first.equals(style.getClickEvent())
                    && style.getHoverEvent() instanceof HoverEvent.ShowText) {
                x = CHAT_LEFT_SAMPLE;
                y = row;
                return true;
            }
        }
        return false;
    }

    void hover(Minecraft client) {
        long window = client.getWindow().handle();
        double scale = client.getWindow().getGuiScale();
        var callback = GLFW.glfwSetCursorPosCallback(window, null);
        GLFW.glfwSetCursorPosCallback(window, callback);
        callback.invoke(window, x * scale, y * scale);
    }

    void click(Minecraft client) {
        var button = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        var event = new MouseButtonEvent(x, y, button);
        if (!(client.gui.screen() instanceof ChatScreen screen)
                || !screen.mouseClicked(event, false /* doubleClick */)) {
            throw new IllegalStateException("聊天选项没有收到鼠标点击");
        }
        first = null;
    }
}
