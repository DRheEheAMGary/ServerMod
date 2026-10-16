package cn.dreamgary.hubsuite.rod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 钓竿的物品提示（tooltip）文本 —— 放在共用层，两边同一份、自检能直接断言文字。
 *
 * <p><b>格式照匠魂（Tinkers' Construct）</b>：收起时先给耐久，再一行一个部件材料，
 * 然后是效果（能不能耐高温）、「按住 Shift」提示，最后是总览数值；
 * 按住 Shift 展开成一段一个部件，段内 {@code 属性: 值} 一行一个。
 *
 * <p><b>颜色</b>：部位标题用部位色（杆青 / 线金 / 钩绿 / 升级紫），
 * **材料名与它那一段的数值再用材料自己的颜色**（铁灰、钻石青、下界合金深灰、黑曜石紫……）。
 */
public final class RodTooltip {

    public static final String HINT = "按住 Shift 查看部件数值";

    private static final String C_ROD = "\u00A7b";
    private static final String C_LINE = "\u00A76";
    private static final String C_HOOK = "\u00A7a";
    private static final String C_UPGRADE = "\u00A7d";
    private static final String GRAY = "\u00A77";
    private static final String DARK = "\u00A78";

    private RodTooltip() {
    }

    /** 收起时显示：耐久 → 材料一览 → 效果 → Shift 提示 → 总览数值。 */
    public static List<Component> summary(RodData data) {
        List<Component> lines = new ArrayList<>();
        if (data == null) {
            return lines;
        }
        RodMaterials.RodMaterial rod = data.rodMaterial();
        RodMaterials.LineMaterial line = data.lineMaterial();
        RodMaterials.HookMaterial hook = data.hookMaterial();
        RodMaterials.Upgrade upgrade = data.upgradeMaterial();

        lines.add(Component.literal(GRAY + "耐久: " + "\u00A7f" + tenths(data.durability())
                + GRAY + " / " + "\u00A7f" + tenths(data.maxDurability())));
        if (data.broken()) {
            lines.add(Component.literal("\u00A7c已损坏 —— 回装配台放同种杆材料修补"));
        }

        lines.add(materialLine(C_ROD, "杆", rodLabel(rod.id()), rod.id()));
        lines.add(materialLine(C_LINE, "线", lineLabel(line.id()), line.id()));
        lines.add(materialLine(C_HOOK, "钩", hookLabel(hook.id()), hook.id()));
        lines.add(Component.literal(C_UPGRADE + "升级 " + DARK + "› "
                + (hasUpgrade(upgrade) ? color(upgrade.id()) + upgradeLabel(upgrade.id()) : GRAY + "无")));

        if (data.lavaCapable()) {
            lines.add(Component.literal("\u00A76耐高温"));
        }
        lines.add(Component.literal(DARK + HINT));
        lines.add(Component.literal(GRAY + "速度: " + "\u00A7f×" + tenths(data.speedTenths())));
        lines.add(Component.literal(GRAY + "幸运: " + "\u00A7f×" + thousandths(data.luckMilli())));
        lines.add(Component.literal(GRAY + "断线: " + "\u00A7f" + permille(data.breakPermille())));
        return lines;
    }

    /** 按住 Shift 展开：一段一个部件，段内一行一个属性（数值用材料色）。 */
    public static List<Component> details(RodData data) {
        List<Component> lines = new ArrayList<>();
        if (data == null) {
            return lines;
        }
        RodMaterials.RodMaterial rod = data.rodMaterial();
        RodMaterials.LineMaterial line = data.lineMaterial();
        RodMaterials.HookMaterial hook = data.hookMaterial();
        RodMaterials.Upgrade upgrade = data.upgradeMaterial();

        String cRod = color(rod.id());
        lines.add(Component.literal(C_ROD + "▍杆 " + cRod + rodLabel(rod.id())
                + " " + DARK + "(" + rod.item() + ")"));
        lines.add(stat(cRod, "基础耐久", String.valueOf(rod.baseDurability())));

        String cLine = color(line.id());
        lines.add(Component.literal(C_LINE + "▍线 " + cLine + lineLabel(line.id())
                + " " + DARK + "(" + line.item() + ")"));
        lines.add(stat(cLine, "断线", permille(line.breakPermille())));
        lines.add(stat(cLine, "速度", "×" + tenths(line.speedMul())));

        String cHook = color(hook.id());
        lines.add(Component.literal(C_HOOK + "▍钩 " + cHook + hookLabel(hook.id())
                + " " + DARK + "(" + hook.item() + ")"));
        lines.add(stat(cHook, "速度", "×" + tenths(hook.speedMul())));
        if (hook.lava()) {
            lines.add(Component.literal("   \u00A76耐高温"));
        }

        if (!hasUpgrade(upgrade)) {
            lines.add(Component.literal(C_UPGRADE + "▍升级 " + GRAY + "无 "
                    + DARK + "（铁砧上放 钻石 / 绿宝石 / 青金石 / 红石）"));
        } else {
            String cUp = color(upgrade.id());
            lines.add(Component.literal(C_UPGRADE + "▍升级 " + cUp + upgradeLabel(upgrade.id())
                    + " " + DARK + "(" + upgrade.item() + ")"));
            lines.add(stat(cUp, "耐久", "×" + tenths(upgrade.durabilityMul()) + add(upgrade.durabilityAdd())));
            lines.add(stat(cUp, "速度", "×" + tenths(upgrade.speedMul())));
            lines.add(stat(cUp, "幸运", "×" + tenths(upgrade.luckMul()) + add(upgrade.luckAdd())));
            if (upgrade.boostsEnchant()) {
                // 用户定的：青金石只提示"附魔++"，不展开细节
                lines.add(Component.literal("   \u00A7b附魔++"));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------

    /** 「部位 › 材料名」：部位用部位色，材料名用材料色。 */
    private static Component materialLine(String partColor, String part, String name, String id) {
        return Component.literal(partColor + part + " " + DARK + "› " + color(id) + name);
    }

    /** 段内一行：{@code 属性: 值}，值用材料色。 */
    private static Component stat(String valueColor, String label, String value) {
        return Component.literal("   " + GRAY + label + ": " + valueColor + value);
    }

    /** **材料自己的颜色**（用户要的"字的颜色随材料变化"）。 */
    private static String color(String id) {
        return switch (id == null ? "" : id) {
            case "wood" -> "\u00A76";          // 木：棕
            case "iron" -> "\u00A77";          // 铁：灰
            case "diamond" -> "\u00A7b";       // 钻石：青
            case "netherite" -> "\u00A78";     // 下界合金：深
            case "gold" -> "\u00A7e";
            case "string" -> "\u00A7f";        // 线：白
            case "nether_string" -> "\u00A7c"; // 地狱线：红
            case "vine" -> "\u00A7a";          // 藤：绿
            case "obsidian" -> "\u00A75";      // 黑曜石：深紫
            case "emerald" -> "\u00A7a";
            case "lapis" -> "\u00A79";
            case "redstone" -> "\u00A7c";
            default -> "\u00A7f";
        };
    }

    private static boolean hasUpgrade(RodMaterials.Upgrade upgrade) {
        return upgrade != null && upgrade.id() != null && !upgrade.id().isBlank();
    }

    private static String tenths(int value) {
        return (value / 10) + "." + Math.abs(value % 10);
    }

    private static String thousandths(int value) {
        return (value / 1000) + "." + String.format("%03d", Math.abs(value % 1000));
    }

    /** 千分比在 ×10 域里存着：{@code 7} → {@code 0.7%}。 */
    private static String permille(int value) {
        return tenths(value) + "%";
    }

    private static String add(int value) {
        return value == 0 ? "" : (value > 0 ? " +" + value : " " + value);
    }

    private static String rodLabel(String id) {
        return switch (id == null ? "" : id) {
            case "wood" -> "木杆";
            case "iron" -> "铁杆";
            case "diamond" -> "钻石杆";
            case "netherite" -> "下界合金杆";
            case "gold" -> "金杆";
            default -> id == null ? "?" : id;
        };
    }

    private static String lineLabel(String id) {
        return switch (id == null ? "" : id) {
            case "string" -> "线";
            case "nether_string" -> "地狱线";
            case "vine" -> "藤蔓";
            default -> id == null ? "?" : id;
        };
    }

    private static String hookLabel(String id) {
        return switch (id == null ? "" : id) {
            case "string" -> "线钩";
            case "iron" -> "铁钩";
            case "diamond" -> "钻石钩";
            case "obsidian" -> "黑曜石钩";
            case "netherite" -> "下界合金钩";
            case "gold" -> "金钩";
            default -> id == null ? "?" : id;
        };
    }

    private static String upgradeLabel(String id) {
        return switch (id == null ? "" : id) {
            case "diamond" -> "钻石";
            case "emerald" -> "绿宝石";
            case "lapis" -> "青金石";
            case "redstone" -> "红石";
            default -> id == null ? "?" : id;
        };
    }
}
