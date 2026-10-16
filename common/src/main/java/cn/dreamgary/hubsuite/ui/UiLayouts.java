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

    /** 装配台：钓竿位（左中）+ 杆/线/钩三个部位格（右列）。 */
    public static final UiLayout ASSEMBLY = new UiLayout(
            Identifier.fromNamespaceAndPath("hubsuite", "assembly"),
            176, 166, "钓竿装配台",
            List.of(
                    new UiLayout.SlotFrame(0, 26, 20, "钓竿"),
                    new UiLayout.SlotFrame(1, 98, 20, "杆"),
                    new UiLayout.SlotFrame(2, 98, 40, "线"),
                    new UiLayout.SlotFrame(3, 98, 60, "钩")),
            "背包");

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
