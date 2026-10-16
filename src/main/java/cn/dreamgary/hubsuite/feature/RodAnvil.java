package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.rod.RodData;
import cn.dreamgary.hubsuite.rod.RodMaterials;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * 铁砧升级（见 docs/钓竿系统方案.md 第 4 节）。
 *
 * <p>规则：「钓竿 + 升级物」→ 输出打了该升级的竿；**只有一个升级槽**，
 * 再来一个直接替换（旧的消失、不退还）；**原版累计惩罚**（Prior Work Cost）原样生效 ——
 * 每次升级给产物的 {@code REPAIR_COST} 记一笔，下一次就更贵。
 *
 * <p>这里是**纯逻辑**（自检直接测）：不是我们的组合一律返回 {@link Outcome#EMPTY}，
 * 让原版结果原样留着（合并两把竿、命名、附魔书……全都照旧）。
 */
public final class RodAnvil {

    /** 产物的等级花费 = 基础 1 级 + 已有的累计惩罚。 */
    public static final int BASE_COST = 1;

    /** 组合结果；{@code stack} 为空表示"不归我们管，保留原版结果"。 */
    public record Outcome(ItemStack stack, int cost) {
        public static final Outcome EMPTY = new Outcome(ItemStack.EMPTY, 0);

        public boolean isEmpty() {
            return stack.isEmpty();
        }
    }

    private RodAnvil() {
    }

    /**
     * 算「左格 + 右格」的结果。
     *
     * @param left  钓竿（带我们数据的）
     * @param right 升级物（钻石 / 绿宝石 / 青金石 / 红石）
     */
    public static Outcome combine(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return Outcome.EMPTY;
        }
        if (!left.is(net.minecraft.world.item.Items.FISHING_ROD)) {
            return Outcome.EMPTY;   // 不是钓竿 → 原版铁砧逻辑照旧
        }
        RodData data = RodData.of(left);
        if (data == null) {
            data = RodData.defaults();   // 新竿还没数据 → 按默认木竿对待（用户实测：无数据时升级无效）
        }
        String upgrade = RodMaterials.matchUpgrade(right);
        if (upgrade == null) {
            return Outcome.EMPTY;   // 不是升级物 → 原版
        }

        RodData next = data.withUpgrade(upgrade);   // 单槽：旧的直接被顶掉（不退还）
        ItemStack result = left.copy();
        result.setCount(1);
        next.writeTo(result);
        next.applyVanillaBar(result);

        int priorWork = priorWork(left);
        result.set(DataComponents.REPAIR_COST, priorWork + 1);   // 原版累计惩罚
        return new Outcome(result, BASE_COST + priorWork);
    }

    /** 原版累计惩罚（REPAIR_COST 组件）。 */
    public static int priorWork(ItemStack stack) {
        Integer value = stack == null ? null : stack.get(DataComponents.REPAIR_COST);
        return value == null ? 0 : Math.max(0, value);
    }
}
