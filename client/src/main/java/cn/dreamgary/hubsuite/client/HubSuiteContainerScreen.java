package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.ui.UiLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * **通用容器界面**（客户端）：一份 {@link UiLayout} 渲染出任意台子的界面。
 *
 * <p>这就是[客户端契约](../../../../../../docs/客户端契约.md) 第 8 节说的那件事：
 * 服务端给布局、客户端通用渲染。加新台子 = 服务端加一份 {@code UiLayout} + 一张底图，
 * **客户端不用改**（也就没有"为了加个机器又要全员更新客户端"）。
 *
 * <p>底图目前是纯色块占位（面板 + 语义格边框 + 格子边的提示文字），不依赖美术；
 * 将来换真图时在这里加一次 {@code blit} 就行，所有台子一起受益。
 */
public class HubSuiteContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    private static final int PANEL = 0xF01A1A1A;
    private static final int PANEL_EDGE = 0xFF6E6E6E;
    private static final int SLOT_BG = 0xFF2B2B2B;
    private static final int SLOT_EDGE = 0xFF8B8B8B;
    private static final int LABEL = 0xFFB0B0B0;

    private final UiLayout layout;

    public HubSuiteContainerScreen(T menu, Inventory inventory, Component title, UiLayout layout) {
        super(menu, inventory, title, layout.width(), layout.height());
        this.layout = layout;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = layout.height() - 93;   // 原版箱子面板（166）算下来是 73
    }

    public UiLayout layout() {
        return layout;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** 底图（占位）：面板 + 每个语义格的边框与提示文字，坐标全部来自布局。 */
    private void drawPanel(GuiGraphicsExtractor g) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.outline(x, y, imageWidth, imageHeight, PANEL_EDGE);

        // 活预览：把面板里的"当前的竿"画出来（原版物品渲染，自动叠分层模型）
        UiLayout.Preview preview = layout.preview();
        if (preview != null && preview.slotIndex() < menu.slots.size()) {
            var stack = menu.getSlot(preview.slotIndex()).getItem();
            if (!stack.isEmpty()) {
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x + preview.x(), y + preview.y());
                pose.scale(preview.scale(), preview.scale());
                g.fakeItem(stack, 0, 0);
                pose.popMatrix();
            }
        }

        for (UiLayout.SlotFrame frame : layout.frames()) {
            int sx = x + frame.x() - 1;
            int sy = y + frame.y() - 1;
            g.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
            g.outline(sx, sy, 18, 18, SLOT_EDGE);
            g.text(font, frame.hint(), sx + 20, sy + 5, LABEL);
        }
    }
}
