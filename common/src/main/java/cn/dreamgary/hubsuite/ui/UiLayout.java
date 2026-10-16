package cn.dreamgary.hubsuite.ui;

import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * 一个容器界面的**描述**（服务端与客户端共用一份）。
 *
 * <p>为什么这么做（[客户端契约](../docs/客户端契约.md) 第 8 节）：界面不写死在客户端代码里，
 * 而是"布局数据 + 通用渲染"。以后加新台子 = 服务端加一份 {@link UiLayout} + 一张底图，
 * **客户端一个字节都不用改**（也就没有"为了加个机器又要玩家更新客户端"）。
 *
 * <p>坐标是**面板内**的（相对界面左上角），和原版箱子的约定一致；
 * 菜单槽位的坐标也从这份布局里取（见 {@code AssemblyMenu}）——
 * 单一事实来源，画框和真实槽位不可能错位。
 */
public record UiLayout(
        Identifier id,
        int width,
        int height,
        String title,
        List<SlotFrame> frames,
        String playerInventoryHint) {

    /** 一个语义格：菜单里的槽位序号 + 面板内坐标 + 格子旁边的提示文字。 */
    public record SlotFrame(int slotIndex, int x, int y, String hint) {
    }

    /** 第 {@code index} 个语义格（越界就退回 0 号，别让界面崩）。 */
    public SlotFrame frame(int index) {
        return index >= 0 && index < frames.size() ? frames.get(index) : frames.get(0);
    }
}
