package cn.dreamgary.hubsuite.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * 全部容器界面的布局表（共用；客户端按**菜单类型 id** 查它来决定怎么画）。
 *
 * <p>加新台子的完整流程：这里加一条 {@link UiLayout} → 配一个同 id 的 {@code MenuType}
 * → 客户端自动用通用界面渲染。**不需要新写 Screen，也不需要玩家更新客户端**。
 */
public final class UiLayouts {

    /**
     * 装配台：**以钓竿实物为中心**（用户定的稿子）。
     *
     * <p>面板尺寸沿用原版箱子（176×166）；面板里渲染**当前的竿**（活预览，槽位 0），
     * 四个语义格钉在竿的各个部件位置上：左上=钓竿位、竿身=杆位、竿梢=线位、钩该在的地方=钩位。
     */
    public static final UiLayout ASSEMBLY = new UiLayout(
            Identifier.fromNamespaceAndPath("hubsuite", "assembly"),
            176, 186, "钓竿装配台",
            List.of(
                    new UiLayout.SlotFrame(0, 22, 20, "钓竿"),
                    // 1/2/3 是**钉在竿的部件上**的（坐标按预览竿的斜线算出来的，见 Preview 注释）
                    new UiLayout.SlotFrame(1, 84, 30, "杆"),
                    new UiLayout.SlotFrame(2, 98, 16, "线"),
                    new UiLayout.SlotFrame(3, 67, 47, "钩"),
                    // 成品格：钩（最后一个输入）正下方
                    new UiLayout.SlotFrame(4, 67, 68, "成品")),
            "背包",
            /*
             * 活预览（用户定的稿）：
             * - 竿画大（5 倍）且**顶端超出面板**，但由界面按面板边框**裁剪**（不糊到外面去）；
             * - 1/2/3 号槽的坐标就是照这条 80x80 的斜线钉的：
             *   斜线从 (px+0.15W, py+0.85H) 到 (px+0.85W, py+0.15H)，
             *   t=0.70 -> 线、(0.45) -> 杆、t=0.15 -> 钩（中心减 9 得槽左上角）。
             */
            new UiLayout.Preview(0, 56, -4, 5.0F),
            List.of());

    /** 菜单类型 id → 布局。 */
    private static final Map<Identifier, UiLayout> BY_MENU = new LinkedHashMap<>();

    static {
        BY_MENU.put(ASSEMBLY.id(), ASSEMBLY);
    }

    private UiLayouts() {
    }

    /** 这个菜单类型有没有布局；没有就交回原版处理。 */
    public static UiLayout forMenu(Identifier menuId) {
        return menuId == null ? null : BY_MENU.get(menuId);
    }

    public static List<UiLayout> all() {
        return List.copyOf(BY_MENU.values());
    }
}
