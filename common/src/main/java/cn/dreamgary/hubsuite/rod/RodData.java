package cn.dreamgary.hubsuite.rod;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

/**
 * 一根钓竿的全部自定义数据（见 docs/钓竿系统方案.md 第 2 节）。
 *
 * <p>写在**原版钓竿**的 {@code CUSTOM_DATA} 组件里 —— 不需要新物品、不需要 Polymer
 * 造物品，存档与背包同步都天然安全（和渔桶一个路子）。
 *
 * <p><b>整数编码</b>：耐久是 **×10** 整数（512.5 → 5125），乘数是 ×10、概率是千分比，
 * 全程整数运算、不取整；只有最后落到 ×10 时才会丢掉不到 0.1 的部分（方案第 2 节）。
 *
 * <p><b>只增不改</b>（客户端契约 v1 第 3.1 节）：NBT 里的键只允许追加，旧键永不改名 / 改型；
 * 读的时候缺键一律走默认值，认不出来的材质 id 由 {@link RodMaterials} 落回默认材质。
 * 结果：**旧客户端读到新服务端写的数据不会崩，只是显示回退**。
 */
public record RodData(String rod, String line, String hook, String upgrade,
                      int durability, int maxDurability) {

    /** NBT 结构版本（只增不改，将来要迁移时看它）。 */
    public static final int FORMAT = 1;

    /** 挂在物品 CUSTOM_DATA 下的根键。 */
    public static final String TAG_ROOT = "hubsuite_rod";

    private static final String KEY_FORMAT = "ver";
    private static final String KEY_ROD = "rod";
    private static final String KEY_LINE = "line";
    private static final String KEY_HOOK = "hook";
    private static final String KEY_UPGRADE = "up";
    private static final String KEY_DURABILITY = "dur";
    private static final String KEY_MAX = "max";

    /** 默认竿：木杆 + 线 + 线钩、无升级、满耐久。 */
    public static RodData defaults() {
        return new RodData(RodMaterials.DEFAULT_ROD, RodMaterials.DEFAULT_LINE,
                RodMaterials.DEFAULT_HOOK, "", 0, 0).refilled();
    }

    /** 满耐久：上限按当前部件与升级算，当前 = 上限。 */
    public RodData refilled() {
        int max = computedMaxDurability();
        return new RodData(rod, line, hook, upgrade, max, max);
    }

    /**
     * 换部件（杆 / 线 / 钩）：上限重算，**当前耐久保留**（夹进 {@code [0, 新上限]}）。
     *
     * <p>方案第 5 节："线 / 钩的替换不影响耐久" —— 这里只是把上限重算，
     * 当前耐久不动（除非已经超出新上限）。
     */
    public RodData withParts(String newRod, String newLine, String newHook) {
        RodData next = new RodData(newRod, newLine, newHook, upgrade, durability, maxDurability);
        return next.reclamped();
    }

    /** 换升级（只有一个槽，旧的直接顶掉）：上限重算 + 当前耐久夹住。 */
    public RodData withUpgrade(String newUpgrade) {
        RodData next = new RodData(rod, line, hook, newUpgrade == null ? "" : newUpgrade,
                durability, maxDurability);
        return next.reclamped();
    }

    private RodData reclamped() {
        int max = computedMaxDurability();
        return new RodData(rod, line, hook, upgrade, Math.max(0, Math.min(durability, max)), max);
    }

    // ------------------------------------------------------------------
    // 材质解析（未知 id 落回默认）
    // ------------------------------------------------------------------

    public RodMaterials.RodMaterial rodMaterial() {
        return RodMaterials.rod(rod);
    }

    public RodMaterials.LineMaterial lineMaterial() {
        return RodMaterials.line(line);
    }

    public RodMaterials.HookMaterial hookMaterial() {
        return RodMaterials.hook(hook);
    }

    public RodMaterials.Upgrade upgradeMaterial() {
        return RodMaterials.upgrade(upgrade);
    }

    // ------------------------------------------------------------------
    // 属性（方案第 2 节；全程整数）
    // ------------------------------------------------------------------

    /** 耐久上限（×10，下限夹到 1）。 */
    public int computedMaxDurability() {
        long value = (long) rodMaterial().baseDurability() * 10L;      // 杆：基础耐久 → ×10
        value = value * lineMaterial().durabilityMul() / 10L;          // 线：耐久乘数
        value = value * upgradeMaterial().durabilityMul() / 10L;       // 升级：耐久乘数
        value += (long) upgradeMaterial().durabilityAdd() * 10L;       // 升级：耐久加值
        return (int) Math.max(1L, value);
    }

    /** 存的上限与当前部件算出来的上限是否一致（装配台 / 自检用来发现漂移）。 */
    public boolean maxIsCurrent() {
        return maxDurability == computedMaxDurability();
    }

    /** 速度（×1000）。三个 ×10 乘数直接相乘就是 ×1000 的值（10×14×10 = 1400 = ×1.4）。 */
    public int speedMilli() {
        return lineMaterial().speedMul() * hookMaterial().speedMul() * upgradeMaterial().speedMul();
    }

    /** 速度（×10，用于显示：14 = ×1.4）。 */
    public int speedTenths() {
        return speedMilli() / 100;
    }

    /**
     * 幸运（×1000）：{@code (基础幸运 + 加值) × 乘数}。
     *
     * <p>基础幸运由玩法层给（海之眷顾之类），数据层默认 0 —— 所以"绿宝石 ×1.5 幸运"
     * 只有在有基础幸运时才看得出效果，这是公式本身的样子（方案第 2、4 节）。
     */
    public int luckMilli(int baseLuckMilli) {
        long value = baseLuckMilli + (long) upgradeMaterial().luckAdd() * 1000L;
        return (int) (value * upgradeMaterial().luckMul() / 10L);
    }

    /** 幸运（×1000，基础 0）。 */
    public int luckMilli() {
        return luckMilli(0);
    }

    /** 断线概率（千分比）—— 只看线，与升级 / 耐久无关。 */
    public int breakPermille() {
        return lineMaterial().breakPermille();
    }

    /** 能不能在岩浆里钓 —— 只看钩。 */
    public boolean lavaCapable() {
        return hookMaterial().lava();
    }

    /** 耐久见底：不能钓，但物品不消失（方案第 5 节）。 */
    public boolean broken() {
        return durability <= 0;
    }

    /** 耐久比例 0..1（原版耐久条是整数，按比例映射过去，只影响观感）。 */
    public float durabilityRatio() {
        int max = Math.max(1, maxDurability);
        return Math.max(0.0F, Math.min(1.0F, durability / (float) max));
    }

    /** 青金石升级才有的"附魔档次提升"（15 级 → 30 级 + 宝藏附魔）。 */
    public boolean boostsEnchant() {
        return upgradeMaterial().boostsEnchant();
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 写成一棵 NBT。
     *
     * <p><b>键的顺序不是线格式的一部分</b>：{@code CompoundTag} 内部是散列表，键序由它决定
     * （实测同一份数据每次编码都一样，但**新增一个键可能让顺序重排**）。NBT 是按名字读的，
     * 所以真正的契约是"**键名与类型只增不改**" —— 自检用键集合守着它，而不是字节顺序。
     */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_FORMAT, FORMAT);
        tag.putString(KEY_ROD, rod == null ? "" : rod);
        tag.putString(KEY_LINE, line == null ? "" : line);
        tag.putString(KEY_HOOK, hook == null ? "" : hook);
        tag.putString(KEY_UPGRADE, upgrade == null ? "" : upgrade);
        tag.putInt(KEY_DURABILITY, durability);
        tag.putInt(KEY_MAX, maxDurability);
        return tag;
    }

    /** 从 NBT 读；缺键走默认、认不出的材质留给解析层落回默认（只增不改）。 */
    public static RodData fromTag(CompoundTag tag) {
        if (tag == null) {
            return defaults();
        }
        RodData data = new RodData(
                tag.getStringOr(KEY_ROD, RodMaterials.DEFAULT_ROD),
                tag.getStringOr(KEY_LINE, RodMaterials.DEFAULT_LINE),
                tag.getStringOr(KEY_HOOK, RodMaterials.DEFAULT_HOOK),
                tag.getStringOr(KEY_UPGRADE, ""),
                tag.getIntOr(KEY_DURABILITY, 0),
                tag.getIntOr(KEY_MAX, 0));
        // 上限缺失（旧数据 / 手写数据）时按当前部件补算，当前耐久夹进去
        if (data.maxDurability() <= 0) {
            return data.reclamped();
        }
        return new RodData(data.rod(), data.line(), data.hook(), data.upgrade(),
                Math.max(0, data.durability()), data.maxDurability());
    }

    /** 读物品上的钓竿数据；**不是"配置过的钓竿"时返回 null**。 */
    public static RodData of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_ROOT)) {
            return null;
        }
        return fromTag(tag.getCompoundOrEmpty(TAG_ROOT));
    }

    /** 写到物品上（覆盖）：数据本体 + 客户端渲染用的材质提示。 */
    public void writeTo(ItemStack stack) {
        CompoundTag tag = toTag();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, custom -> custom.put(TAG_ROOT, tag));
        stack.set(DataComponents.CUSTOM_MODEL_DATA, modelHint());
    }

    /**
     * 渲染提示：把 4 个材质 id 依次放进 {@code custom_model_data} 的**字符串槽**。
     *
     * <p>客户端就用它分层：{@code assets/minecraft/items/fishing_rod.json} 里对
     * index 0/1/2/3 各做一次 {@code minecraft:select}，再 {@code minecraft:composite} 叠起来。
     * 认不出来的材质 id 在客户端会落到 {@code fallback}（占位图）—— 这就是契约里的
     * "未知即占位"，而且是原版模型系统自己实现的，不需要我们写渲染代码。
     *
     * <p>升级槽空着时放空串：客户端那一层的 fallback 是 {@code minecraft:empty}（不画）。
     */
    public CustomModelData modelHint() {
        return new CustomModelData(List.of(), List.of(), List.of(
                safe(rod), safe(line), safe(hook), upgrade == null ? "" : upgrade.trim()), List.of());
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** 抹掉钓竿数据（把竿还原成"没配置过的原版钓竿"）。 */
    public static void clear(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, custom -> custom.remove(TAG_ROOT));
        stack.remove(DataComponents.CUSTOM_MODEL_DATA);
    }
}
