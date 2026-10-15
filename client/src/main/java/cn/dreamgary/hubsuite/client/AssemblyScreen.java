package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.rod.AssemblyMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 装配台界面（客户端）。
 *
 * <p>布局照 docs/钓竿系统方案.md 第 3.3 节：左边是**大钓竿位**，右列三个部位格（杆 / 线 / 钩），
 * 每个格子底下画一圈边框当"部位提示"（方案里定了：**不做虚影**，边框就画在底图上）。
 *
 * <p>底图仍然是**占位**：这里先用纯色块画出来（不依赖美术），等真图好了换成 blit 一张 PNG。
 */
public class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {

    private static final int PANEL = 0xF01A1A1A;
    private static final int PANEL_EDGE = 0xFF6E6E6E;
    private static final int SLOT_BG = 0xFF2B2B2B;
    private static final int SLOT_EDGE = 0xFF8B8B8B;
    private static final int LABEL = 0xFFB0B0B0;

    public AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166);
        this.titleLabelX = 26;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 73;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** 底图（占位）：面板 + 四个语义格的边框与部位标签。 */
    private void drawPanel(GuiGraphicsExtractor g) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.outline(x, y, imageWidth, imageHeight, PANEL_EDGE);

        String[] labels = {"钓竿", "杆", "线", "钩"};
        for (int i = 0; i < AssemblyMenu.SLOT_POSITIONS.length; i++) {
            int sx = x + AssemblyMenu.SLOT_POSITIONS[i][0] - 1;
            int sy = y + AssemblyMenu.SLOT_POSITIONS[i][1] - 1;
            g.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
            g.outline(sx, sy, 18, 18, SLOT_EDGE);
            g.text(font, labels[i], sx + 20, sy + 5, LABEL);
        }
    }
}
