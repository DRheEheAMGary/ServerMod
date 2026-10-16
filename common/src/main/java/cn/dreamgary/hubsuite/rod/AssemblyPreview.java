package cn.dreamgary.hubsuite.rod;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 装配台的**活预览**：竿 + 三个材料格里"如果应用"之后的结果（纯客户端演算，纯展示）。
 *
 * <p>规则代码（{@link AssemblyRules}）在 common 里、客户端也编译同一份，
 * 所以预览在客户端本地算，不需要服务器来回 —— 材料一进格，竿的外观立刻跟着变。
 */
public final class AssemblyPreview {

    private AssemblyPreview() {
    }

    /**
     * 预览堆：材料会改竿 → 返回应用后的**拷贝**；没变化 → 返回原堆；不是装配台 → null。
     */
    public static ItemStack previewStack(AbstractContainerMenu menu) {
        if (!(menu instanceof AssemblyMenu asm)) {
            return null;
        }
        ItemStack rod = asm.getSlot(AssemblyMenu.SLOT_ROD).getItem();
        if (rod.isEmpty()) {
            return ItemStack.EMPTY;
        }
        RodData data = RodData.of(rod);
        if (data == null) {
            data = RodData.defaults();
        }
        RodData next = data;
        next = apply(next, asm, AssemblyMenu.SLOT_ROD_MAT, RodMaterials.Part.ROD);
        next = apply(next, asm, AssemblyMenu.SLOT_LINE, RodMaterials.Part.LINE);
        next = apply(next, asm, AssemblyMenu.SLOT_HOOK, RodMaterials.Part.HOOK);
        if (next.equals(data)) {
            return rod;
        }
        ItemStack preview = rod.copy();
        next.writeTo(preview);
        return preview;
    }

    private static RodData apply(RodData data, AssemblyMenu asm, int slot, RodMaterials.Part part) {
        ItemStack stack = asm.getSlot(slot).getItem();
        if (stack.isEmpty()) {
            return data;
        }
        String material = RodMaterials.match(part, stack);
        if (material == null) {
            return data;
        }
        return switch (part) {
            case ROD -> AssemblyRules.applyRodMaterial(data, material);
            case LINE -> AssemblyRules.applyLine(data, material);
            case HOOK -> AssemblyRules.applyHook(data, material);
        };
    }
}
