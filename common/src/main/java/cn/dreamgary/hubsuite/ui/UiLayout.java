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
        String playerInventoryHint,
        Preview preview,
        List<Button> buttons) {

    /** 兼容旧构造（没有按钮的台子）。 */
    public UiLayout(Identifier id, int width, int height, String title,
                    List<SlotFrame> frames, String playerInventoryHint, Preview preview) {
        this(id, width, height, title, frames, playerInventoryHint, preview, List.of());
    }

    /** 一个语义格：菜单里的槽位序号 + 面板内坐标 + 格子旁边的提示文字。 */
    public record SlotFrame(int slotIndex, int x, int y, String hint) {
    }

    /** 面板里的一个按钮：点了客户端会给服务端发 {@code UiActionPayload}（action = {@link #action()}）。 */
    public record Button(String action, int x, int y, int width, int height, String label) {
    }

    /**
     * **活预览**：把某个槽位里的真实物品渲染在面板里（可缩放）。
     *
     * <p>装配台用它渲染"当前的竿"—— 原版物品渲染会自动叠我们的分层模型，
     * 所以换杆/线/钩/升级的瞬间，面板里的竿**跟着变**，不用预画组合图。
     */
    public record Preview(int slotIndex, int x, int y, float scale) {
    }

    /** 第 {@code index} 个语义格（越界就退回 0 号，别让界面崩）。 */
    public SlotFrame frame(int index) {
        return index >= 0 && index < frames.size() ? frames.get(index) : frames.get(0);
    }
}
