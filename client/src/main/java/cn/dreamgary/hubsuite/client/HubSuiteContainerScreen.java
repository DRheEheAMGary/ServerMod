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
 * <p>配色走**原版容器**那一套（浅灰面板 #C6C6C6、槽位 #8B8B8B + 深/白立体边、
 * 字 #404040），这样和原版 GUI 放一起不会割裂（用户反馈之前深色板太跳）。
 *
 * <p>加新台子 = 服务端加一份 {@code UiLayout} + 一张底图，客户端不用改。
 */
public class HubSuiteContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    // 原版容器配色
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_BORDER = 0xFF000000;
    private static final int HIGHLIGHT = 0xFF8B8B8B;
    private static final int SHADOW = 0xFF373737;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int LABEL = 0xFF404040;
    private static final int BUTTON = 0xFF8B8B8B;

    private final UiLayout layout;

    public HubSuiteContainerScreen(T menu, Inventory inventory, Component title, UiLayout layout) {
        super(menu, inventory, title, layout.width(), layout.height());
        this.layout = layout;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = layout.height() - 93;
    }

    public UiLayout layout() {
        return layout;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** 点中布局里的按钮 → 给服务端发 {@code UiActionPayload}（装配 = 消耗材料生效）。 */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();

        for (UiLayout.Button b : layout.buttons()) {
            int bx = leftPos + b.x();
            int by = topPos + b.y();
            if (mouseX >= bx && mouseX < bx + b.width() && mouseY >= by && mouseY < by + b.height()) {
                if (event.button() == 0) {
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new cn.dreamgary.hubsuite.net.UiActionPayload.UiAction(
                                    layout.id().toString(), b.action()));
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /** 原版风格的槽位：灰底 + 左上深边 + 右下白边。 */
    private void drawSlot(GuiGraphicsExtractor g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
        g.fill(sx, sy, sx + 18, sy + 1, SHADOW);
        g.fill(sx, sy, sx + 1, sy + 18, SHADOW);
        g.fill(sx + 17, sy, sx + 18, sy + 18, 0xFFFFFFFF);
        g.fill(sx, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
    }

    private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // 面板：原版浅灰 + 黑外框 + 白高光 / 灰阴影
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.outline(x, y, imageWidth, imageHeight, PANEL_BORDER);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + 2, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 2, y + imageHeight - 1, 0xFFFFFFFF);
        g.fill(x + 1, y + imageHeight - 2, x + imageWidth - 1, y + imageHeight - 1, HIGHLIGHT);
        g.fill(x + imageWidth - 2, y + 1, x + imageWidth - 1, y + imageHeight - 1, HIGHLIGHT);

        // 活预览（装配台会按材料格演算"如果应用"的结果；其它台子画槽位原物）
        UiLayout.Preview preview = layout.preview();
        if (preview != null && preview.slotIndex() < menu.slots.size()) {
            var stack = cn.dreamgary.hubsuite.rod.AssemblyPreview.previewStack(menu);
            if (stack == null) {
                stack = menu.getSlot(preview.slotIndex()).getItem();
            }
            if (!stack.isEmpty()) {
                g.enableScissor(x, y, x + imageWidth, y + imageHeight);
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x + preview.x(), y + preview.y());
                pose.scale(preview.scale(), preview.scale());
                g.fakeItem(stack, 0, 0);
                pose.popMatrix();
                g.disableScissor();

                int boxX = x + preview.x();
                int boxY = y + preview.y();
                int boxSize = (int) (16 * preview.scale());
                if (mouseX >= boxX && mouseX < boxX + boxSize
                        && mouseY >= boxY && mouseY < boxY + boxSize) {
                    g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                }
            }
        }

        // 语义格 + 提示
        for (UiLayout.SlotFrame frame : layout.frames()) {
            int sx = x + frame.x() - 1;
            int sy = y + frame.y() - 1;
            drawSlot(g, sx, sy);
            g.text(font, frame.hint(), sx + 20, sy + 5, LABEL);
        }

        // 背包等"没有语义框的槽位"也补上原版槽位背景
        for (int i = layout.frames().size(); i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            drawSlot(g, x + slot.x - 1, y + slot.y - 1);
        }

        // 按钮：原版按钮配色（灰底 + 立体边）
        for (UiLayout.Button b : layout.buttons()) {
            int bx = x + b.x();
            int by = y + b.y();
            boolean hover = mouseX >= bx && mouseX < bx + b.width() && mouseY >= by && mouseY < by + b.height();
            g.fill(bx, by, bx + b.width(), by + b.height(), hover ? 0xFFA8A8A8 : BUTTON);
            g.fill(bx, by, bx + b.width(), by + 1, 0xFFFFFFFF);
            g.fill(bx, by, bx + 1, by + b.height(), 0xFFFFFFFF);
            g.fill(bx, by + b.height() - 1, bx + b.width(), by + b.height(), SHADOW);
            g.fill(bx + b.width() - 1, by, bx + b.width(), by + b.height(), SHADOW);
            int tw = font.width(b.label());
            g.text(font, b.label(), bx + (b.width() - tw) / 2, by + (b.height() - 7) / 2, 0xFF404040);
        }
    }
}
