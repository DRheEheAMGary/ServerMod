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

    /** 底图（占位）：面板 + 活预览 + 语义格 + 按钮，坐标全部来自布局。 */
    private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.outline(x, y, imageWidth, imageHeight, PANEL_EDGE);

        // 活预览：装配台会按材料格演算"如果应用"的结果（AssemblyPreview），
        // 材料一进格竿的外观立刻跟着变；其它台子就画槽位原物
        UiLayout.Preview preview = layout.preview();
        if (preview != null && preview.slotIndex() < menu.slots.size()) {
            var stack = cn.dreamgary.hubsuite.rod.AssemblyPreview.previewStack(menu);
            if (stack == null) {
                stack = menu.getSlot(preview.slotIndex()).getItem();
            }
            if (!stack.isEmpty()) {
                // 用户要求：竿画大、顶端可以超出，但**超出的部分要被面板边框裁掉**
                g.enableScissor(x, y, x + imageWidth, y + imageHeight);
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x + preview.x(), y + preview.y());
                pose.scale(preview.scale(), preview.scale());
                g.fakeItem(stack, 0, 0);
                pose.popMatrix();
                g.disableScissor();

                // 悬浮在竿上直接看它的 tooltip（按住 Shift 展开 —— 之前只有槽位能看，预览看不了）
                int boxX = x + preview.x();
                int boxY = y + preview.y();
                int boxSize = (int) (16 * preview.scale());
                if (mouseX >= boxX && mouseX < boxX + boxSize
                        && mouseY >= boxY && mouseY < boxY + boxSize) {
                    g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                }
            }
        }

        for (UiLayout.SlotFrame frame : layout.frames()) {
            int sx = x + frame.x() - 1;
            int sy = y + frame.y() - 1;
            g.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
            g.outline(sx, sy, 18, 18, SLOT_EDGE);
            g.text(font, frame.hint(), sx + 20, sy + 5, LABEL);
        }

        /*
         * 玩家背包（以及任何"没有语义框的槽位"）也要画框。
         *
         * 原版容器界面是靠**底图**画出槽位背景的，我们这套是纯色占位面板、不 blit 任何图，
         * 所以不补这一步的话，背包区看起来就是"一片空白"（用户实测截图里就是这样）。
         */
        for (int i = layout.frames().size(); i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            int bx2 = x + slot.x - 1;
            int by2 = y + slot.y - 1;
            g.fill(bx2, by2, bx2 + 18, by2 + 18, SLOT_BG);
            g.outline(bx2, by2, 18, 18, SLOT_EDGE);
        }

        for (UiLayout.Button b : layout.buttons()) {
            int bx = x + b.x();
            int by = y + b.y();
            boolean hover = mouseX >= bx && mouseX < bx + b.width() && mouseY >= by && mouseY < by + b.height();
            g.fill(bx, by, bx + b.width(), by + b.height(), hover ? 0xFF4A5A7E : 0xFF33334A);
            g.outline(bx, by, b.width(), b.height(), hover ? 0xFFB0C0E0 : SLOT_EDGE);
            int tw = font.width(b.label());
            g.text(font, b.label(), bx + (b.width() - tw) / 2, by + (b.height() - 7) / 2,
                    hover ? 0xFFFFFFFF : 0xFFE0E0E0);
        }
    }
}
