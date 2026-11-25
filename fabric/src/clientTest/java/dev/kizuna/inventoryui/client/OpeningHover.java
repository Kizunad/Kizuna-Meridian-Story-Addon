package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager;
import io.wispforest.owo.ui.core.UIComponent;

public final class OpeningHover {
    private OpeningHover() { }

    public static UIComponent diagram(UiWindowManager.WindowKey key) {
        // 工作台里的真实开脉控件持有服务端发来的连续维持提示。
        var root = InventoryUiClient.runtime().view(key).adapter.rootComponent;
        return root.childById(UIComponent.class, "opening-visual");
    }
}
