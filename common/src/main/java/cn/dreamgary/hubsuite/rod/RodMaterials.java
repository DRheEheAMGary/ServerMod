package cn.dreamgary.hubsuite.rod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * 钓竿部件 / 升级的材质表（见 docs/钓竿系统方案.md 第 1、4 节）。
 *
 * <p>所有**乘数都是 ×10 整数**（{@code 10} = ×1.0、{@code 15} = ×1.5），概率是**千分比**
 * （{@code 5} = 0.5%），这样全程整数运算、没有浮点误差。
 *
 * <p>本文件在 {@code common/} 下 —— 服务端与客户端编译同一份：客户端渲染"这根竿是什么材质"
 * 与服务端算属性用的是同一张表，不可能对不上。
 *
 * <p><b>宽容原则</b>：认不出来的材质 id 一律落回默认材质，绝不抛异常
 * （客户端契约 v1 第 3.2 节「未知即占位」在数据层的体现）。
 */
public final class RodMaterials {

    /** 部位。同一材料出现在多个部位时（铁 / 钻石 / 下界合金）靠部位区分。 */
    public enum Part {
        ROD, LINE, HOOK
    }

    /** 杆：决定耐久基础（整数）。 */
    public record RodMaterial(String id, String item, int baseDurability) {
    }

    /** 线：耐久乘数（×10）、断线概率（‰）、速度乘数（×10）。 */
    public record LineMaterial(String id, String item, int durabilityMul, int breakPermille, int speedMul) {
    }

    /** 钩：速度乘数（×10）、能不能在岩浆里钓。 */
    public record HookMaterial(String id, String item, int speedMul, boolean lava) {
    }

    /** 升级：耐久乘数（×10）/ 耐久加值（整数）/ 速度乘数（×10）/ 幸运乘数（×10）/ 幸运加值 / 是否提升附魔档次。 */
    public record Upgrade(String id, String item, int durabilityMul, int durabilityAdd,
                          int speedMul, int luckMul, int luckAdd, boolean boostsEnchant) {

        /** 没有升级时的中性值（全是恒等元）。 */
        public static final Upgrade NONE = new Upgrade("", "", 10, 0, 10, 10, 0, false);
    }

    /** 默认材质：木杆 + 线 + 线钩（原版钓竿开箱即用的样子）。 */
    public static final String DEFAULT_ROD = "wood";
    public static final String DEFAULT_LINE = "string";
    public static final String DEFAULT_HOOK = "string";

    private static final Map<String, RodMaterial> RODS = new LinkedHashMap<>();
    private static final Map<String, LineMaterial> LINES = new LinkedHashMap<>();
    private static final Map<String, HookMaterial> HOOKS = new LinkedHashMap<>();
    private static final Map<String, Upgrade> UPGRADES = new LinkedHashMap<>();
    private static final Map<String, String> ROD_ITEMS = new LinkedHashMap<>();
    private static final Map<String, String> LINE_ITEMS = new LinkedHashMap<>();
    private static final Map<String, String> HOOK_ITEMS = new LinkedHashMap<>();
    private static final Map<String, String> UPGRADE_ITEMS = new LinkedHashMap<>();

    static {
        // ── 杆 ──
        rod("wood", "minecraft:stick", 64);
        rod("iron", "minecraft:iron_ingot", 384);
        rod("diamond", "minecraft:diamond", 1024);
        rod("netherite", "minecraft:netherite_ingot", 2048);

        // ── 线 ──
        line("string", "minecraft:string", 10, 5, 10);
        line("nether_string", "hubsuite:nether_string", 20, 7, 12);
        line("vine", "minecraft:vine", 15, 2, 8);
        // 藤蔓三种都算同一种材质（方案第 1 节）
        alias(LINE_ITEMS, "minecraft:twisting_vines", "vine");
        alias(LINE_ITEMS, "minecraft:weeping_vines", "vine");

        // ── 钩 ──
        hook("string", "minecraft:string", 10, false);
        hook("iron", "minecraft:iron_ingot", 12, false);
        hook("diamond", "minecraft:diamond", 14, false);
        hook("obsidian", "minecraft:obsidian", 11, true);
        hook("netherite", "minecraft:netherite_ingot", 16, true);

        // ── 升级 ──
        upgrade("diamond", "minecraft:diamond", 12, 0, 15, 10, 2, false);
        upgrade("emerald", "minecraft:emerald", 10, 768, 10, 15, 0, false);
        upgrade("lapis", "minecraft:lapis_lazuli", 10, -192, 10, 25, 0, true);
        upgrade("redstone", "minecraft:redstone", 8, 0, 30, 10, 0, false);
    }

    private RodMaterials() {
    }

    private static void rod(String id, String item, int durability) {
        RODS.put(id, new RodMaterial(id, item, durability));
        ROD_ITEMS.put(item, id);
    }

    private static void line(String id, String item, int durMul, int breakPermille, int speedMul) {
        LINES.put(id, new LineMaterial(id, item, durMul, breakPermille, speedMul));
        LINE_ITEMS.put(item, id);
    }

    private static void hook(String id, String item, int speedMul, boolean lava) {
        HOOKS.put(id, new HookMaterial(id, item, speedMul, lava));
        HOOK_ITEMS.put(item, id);
    }

    private static void upgrade(String id, String item, int durMul, int durAdd, int speedMul,
                                int luckMul, int luckAdd, boolean boostsEnchant) {
        UPGRADES.put(id, new Upgrade(id, item, durMul, durAdd, speedMul, luckMul, luckAdd, boostsEnchant));
        UPGRADE_ITEMS.put(item, id);
    }

    private static void alias(Map<String, String> table, String item, String id) {
        table.put(item, id);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public static List<RodMaterial> rods() {
        return List.copyOf(RODS.values());
    }

    public static List<LineMaterial> lines() {
        return List.copyOf(LINES.values());
    }

    public static List<HookMaterial> hooks() {
        return List.copyOf(HOOKS.values());
    }

    public static List<Upgrade> upgrades() {
        return List.copyOf(UPGRADES.values());
    }

    /** 认不出来的 id 落回默认材质（宽容）。 */
    public static RodMaterial rod(String id) {
        RodMaterial found = id == null ? null : RODS.get(id);
        return found != null ? found : RODS.get(DEFAULT_ROD);
    }

    public static LineMaterial line(String id) {
        LineMaterial found = id == null ? null : LINES.get(id);
        return found != null ? found : LINES.get(DEFAULT_LINE);
    }

    public static HookMaterial hook(String id) {
        HookMaterial found = id == null ? null : HOOKS.get(id);
        return found != null ? found : HOOKS.get(DEFAULT_HOOK);
    }

    /** 认不出来的升级 = 没有升级（中性值）。 */
    public static Upgrade upgrade(String id) {
        Upgrade found = id == null || id.isBlank() ? null : UPGRADES.get(id);
        return found != null ? found : Upgrade.NONE;
    }

    /** 这个物品属于哪个部位？不属于任何部位时返回 null。 */
    public static String match(Part part, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return matchItem(part, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    public static String matchItem(Part part, String itemId) {
        Map<String, String> table = switch (part) {
            case ROD -> ROD_ITEMS;
            case LINE -> LINE_ITEMS;
            case HOOK -> HOOK_ITEMS;
        };
        return itemId == null ? null : table.get(itemId);
    }

    /** 这个物品是哪种升级？不是升级物时返回 null。 */
    public static String matchUpgrade(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return UPGRADE_ITEMS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }
}
