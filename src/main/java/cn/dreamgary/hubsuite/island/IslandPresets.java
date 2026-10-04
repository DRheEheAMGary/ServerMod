package cn.dreamgary.hubsuite.island;

import java.util.List;

/**
 * 内置岛型预置。
 *
 * <p>目前提供两种（按需求）：
 * <ul>
 *   <li><b>classic 经典空岛</b> —— 草方块小岛 + 树 + 岩浆 + 冰 + 箱子；</li>
 *   <li><b>ocean 海岛</b> —— 沙子小岛 + 沉船风格箱子 + 少量水，更适合"海岛生存"。</li>
 * </ul>
 * 单方块空岛（oneblock）暂不实现。
 */
public final class IslandPresets {

    private IslandPresets() {
    }

    public static IslandConfig defaults() {
        IslandConfig config = new IslandConfig();
        config.types = List.of(classic(), ocean());
        config.normalize();
        return config;
    }

    public static IslandConfig.IslandType classic() {
        IslandConfig.IslandType type = new IslandConfig.IslandType();
        type.id = "classic";
        type.displayName = "\u00A7a经典空岛";
        type.description = "\u00A77草方块小岛，带一棵树、岩浆与冰，最经典的开局。";
        type.layers = List.of(
                "0:minecraft:grass_block",
                "-1:minecraft:dirt",
                "-2:minecraft:dirt",
                "-3:minecraft:stone");
        type.tree = true;
        type.chest = true;
        type.loot = List.of(
                "minecraft:ice*2",
                "minecraft:lava_bucket*1",
                "minecraft:oak_sapling*2",
                "minecraft:bone_meal*8",
                "minecraft:wheat_seeds*4",
                "minecraft:bread*4",
                "minecraft:torch*8");
        return type;
    }

    public static IslandConfig.IslandType ocean() {
        IslandConfig.IslandType type = new IslandConfig.IslandType();
        type.id = "ocean";
        type.displayName = "\u00A7b海岛";
        type.description = "\u00A77沙石小岛，周围有浅水，物资偏向渔猎与航海。";
        type.layers = List.of(
                "0:minecraft:sand",
                "-1:minecraft:sand",
                "-2:minecraft:sandstone",
                "-3:minecraft:stone");
        type.tree = false;
        type.chest = true;
        type.loot = List.of(
                "minecraft:fishing_rod*1",
                "minecraft:oak_boat*1",
                "minecraft:kelp*8",
                "minecraft:bone_meal*8",
                "minecraft:bread*4",
                "minecraft:torch*8",
                "minecraft:sugar_cane*4");
        return type;
    }
}
