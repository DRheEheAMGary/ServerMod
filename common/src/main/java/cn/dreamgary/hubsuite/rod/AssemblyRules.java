package cn.dreamgary.hubsuite.rod;

/**
 * 装配台的**纯规则**（不碰菜单、不碰玩家，方便自检直接测）。
 *
 * <p>规则来自 docs/钓竿系统方案.md 第 3.2 节：
 * <ul>
 *   <li><b>杆位</b>：同种材料 + 没满耐久 → 修满；不同种 → 换底（基数换掉、耐久回满）；</li>
 *   <li><b>线位 / 钩位</b>：只换属性，**当前耐久不动**；</li>
 *   <li>耐久已满 + 同种材料 → 不消耗、不变化（调用方据此拒绝）。</li>
 * </ul>
 */
public final class AssemblyRules {

    private AssemblyRules() {
    }

    /** 杆位：返回换了杆材之后的竿；材料非法或"已满耐久还塞同种材料"时原样返回。 */
    public static RodData applyRodMaterial(RodData data, String materialId) {
        if (data == null || materialId == null || !RodMaterials.rods().stream()
                .anyMatch(m -> m.id().equals(materialId))) {
            return data;
        }
        if (materialId.equals(data.rod())) {
            // 同种：修补 —— 已经满了就什么都不做（不浪费材料）
            return data.durability() >= data.maxDurability() ? data : data.refilled();
        }
        // 不同种：换底 —— 全新（方案第 3.2 节明确"当前耐久也回满"）
        return data.withParts(materialId, data.line(), data.hook()).refilled();
    }

    /** 线位：只换线，耐久不动。 */
    public static RodData applyLine(RodData data, String materialId) {
        if (data == null || materialId == null || !RodMaterials.lines().stream()
                .anyMatch(m -> m.id().equals(materialId))) {
            return data;
        }
        return materialId.equals(data.line()) ? data : data.withParts(data.rod(), materialId, data.hook());
    }

    /** 钩位：只换钩，耐久不动。 */
    public static RodData applyHook(RodData data, String materialId) {
        if (data == null || materialId == null || !RodMaterials.hooks().stream()
                .anyMatch(m -> m.id().equals(materialId))) {
            return data;
        }
        return materialId.equals(data.hook()) ? data : data.withParts(data.rod(), data.line(), materialId);
    }
}
