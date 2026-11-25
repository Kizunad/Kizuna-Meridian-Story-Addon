package dev.kizuna.meridian.story.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

/** 在正式背包中悬停指定物品，供剧情实机验收取图。 */
public final class StoryInventory extends InventoryScreen {
    private static final int SLOT_CENTER = 8;

    public StoryInventory(Player player) {
        super(player);
    }

    public boolean hover(Minecraft client, String key) {
        var title = Component.translatable(key);
        for (var slot : getMenu().slots) {
            if (slot.getItem().getHoverName().equals(title)) {
                long handle = client.getWindow().handle();
                double scale = client.getWindow().getGuiScale();
                double x = (leftPos + slot.x + SLOT_CENTER) * scale;
                double y = (topPos + slot.y + 1) * scale;
                var callback = GLFW.glfwSetCursorPosCallback(handle, null);
                GLFW.glfwSetCursorPosCallback(handle, callback);
                if (callback == null) {
                    GLFW.glfwSetCursorPos(handle, x, y);
                } else {
                    callback.invoke(handle, x, y);
                }
                return hoveredSlot == slot;
            }
        }
        return false;
    }
}
