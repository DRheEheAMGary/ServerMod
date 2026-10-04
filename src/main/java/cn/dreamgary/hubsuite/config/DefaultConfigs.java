package cn.dreamgary.hubsuite.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首次启动时写出的默认配置：大厅 + 生存 / 创造 / 空岛 三个子服。
 *
 * <p>这里刻意把"你实际会想改的东西"都放进来并写了默认值，
 * 管理员不改配置也能直接跑出一个可用的三服结构。
 */
public final class DefaultConfigs {

    private DefaultConfigs() {
    }

    public static HubSuiteConfig create() {
        HubSuiteConfig config = new HubSuiteConfig();

        // 注意：这里必须用 overworld，不能用 the_end（末地会强制生成黑曜石平台 + 末影龙）
        config.lobby.dimensionType = "minecraft:overworld";
        config.lobby.voidGenerator = true;
        config.lobby.spawnX = 0.5;
        config.lobby.spawnY = 100.0;

        // 大厅引导假人：只放一个，点击打开服务器选择界面
        config.lobby.menuNpc = new HubSuiteConfig.NpcConfig();
        config.lobby.menuNpc.enabled = true;
        config.lobby.menuNpc.x = 0.5;
        config.lobby.menuNpc.y = 100.0;
        config.lobby.menuNpc.z = 5.5;
        config.lobby.menuNpc.yaw = 180.0F;
        config.lobby.menuNpc.name = "\u00A7a\u00A7l点击传送";
        config.lobby.menuNpc.skin = "auto";
        config.lobby.spawnZ = 0.5;
        config.lobby.buildPlatform = true;
        config.lobby.platformRadius = 10;

        List<HubSuiteConfig.SubServerConfig> servers = new ArrayList<>();
        servers.add(survival());
        servers.add(creative());
        servers.add(skyblock());
        config.servers = servers;

        config.normalize();
        return config;
    }

    private static HubSuiteConfig.SubServerConfig survival() {
        HubSuiteConfig.SubServerConfig s = new HubSuiteConfig.SubServerConfig();
        s.id = "survival";
        s.displayName = "\u00A7a生存服";
        s.order = 0;
        s.worldKind = "normal";
        s.generateNether = false;
        s.generateEnd = false;
        // 正常世界交给原版算地表高度，不硬写 Y（硬写会悬空）
        s.useWorldSpawn = true;
        s.gameMode = "survival";
        s.difficulty = "normal";
        s.forceGameMode = true;
        s.allowGameModeCommand = false;
        s.rememberLastLocation = true;
        s.permissionGroup = "hubsuite.survival";

        s.gameRules.put("KEEP_INVENTORY", "false");
        s.gameRules.put("SHOW_DEATH_MESSAGES", "true");
        s.gameRules.put("SPAWN_MONSTERS", "true");

        s.behaviour.pvp = true;               // 允许 PvP（可用 /gamerule PVP false 关掉）
        s.behaviour.fallDamage = true;
        s.behaviour.explosionBlockDamage = true;
        s.behaviour.fireSpread = true;
        s.behaviour.mobGriefing = true;
        s.behaviour.allowFlight = false;
        s.behaviour.worldBorderRadius = 5000.0;

        s.npc.enabled = false;   // 改为统一用大厅的菜单假人
        s.npc.x = -3.5;
        s.npc.y = 100.0;
        s.npc.z = 5.5;
        s.npc.yaw = 180.0F;
        s.npc.name = "\u00A7a生存服";
        s.npc.skin = "auto";
        return s;
    }

    private static HubSuiteConfig.SubServerConfig creative() {
        HubSuiteConfig.SubServerConfig s = new HubSuiteConfig.SubServerConfig();
        s.id = "creative";
        s.displayName = "\u00A7e创造服";
        s.order = 1;
        // 与生存服一样用正常地形：创造模式在平地上没什么可玩的，
        // 有地形才方便试建。出生点交给原版算（它会落到真实地表）。
        s.worldKind = "normal";
        s.useWorldSpawn = true;
        s.gameMode = "creative";
        s.difficulty = "peaceful";
        s.forceGameMode = true;
        s.allowGameModeCommand = true;
        s.rememberLastLocation = true;
        s.permissionGroup = "hubsuite.creative";
        s.huskhomesTeleport = false;   // 创造服不让 /home 之类的指令把人传走

        s.gameRules.put("KEEP_INVENTORY", "true");
        s.gameRules.put("SPAWN_MOBS", "false");
        s.gameRules.put("SPAWN_MONSTERS", "false");
        s.gameRules.put("ADVANCE_WEATHER", "false");
        s.gameRules.put("SHOW_DEATH_MESSAGES", "false");

        s.behaviour.pvp = false;
        s.behaviour.invulnerable = true;
        s.behaviour.fallDamage = false;
        s.behaviour.allowFlight = true;
        s.behaviour.explosionBlockDamage = false;
        s.behaviour.fireSpread = false;
        s.behaviour.mobGriefing = false;
        s.behaviour.tntIgnition = false;
        s.behaviour.keepHungerFull = true;
        s.behaviour.worldBorderRadius = 2000.0;

        s.npc.enabled = false;   // 改为统一用大厅的菜单假人
        s.npc.x = 0.5;
        s.npc.y = 100.0;
        s.npc.z = 5.5;
        s.npc.yaw = 180.0F;
        s.npc.name = "\u00A7e创造服";
        s.npc.skin = "auto";
        return s;
    }

    private static HubSuiteConfig.SubServerConfig skyblock() {
        HubSuiteConfig.SubServerConfig s = new HubSuiteConfig.SubServerConfig();
        s.id = "skyblock";
        s.displayName = "\u00A7b空岛服";
        s.order = 2;
        s.worldKind = "void";
        // 虚空世界没有地形，玩家由空岛系统送到自己的岛，这里只是兜底坐标。
        // Y=101：岛面（草方块）在 Y=100，站上去就是 101。
        s.useWorldSpawn = false;
        s.spawnX = 0.5;
        s.spawnY = 101.0;
        s.spawnZ = 0.5;
        s.gameMode = "survival";
        s.difficulty = "normal";
        s.forceGameMode = true;
        s.allowGameModeCommand = false;
        s.rememberLastLocation = true;
        s.permissionGroup = "hubsuite.skyblock";
        s.huskhomesTeleport = false;   // 空岛服必须关，否则 /home 会把人传出自己的岛

        s.gameRules.put("KEEP_INVENTORY", "false");
        s.gameRules.put("SPAWN_MOBS", "false");
        s.gameRules.put("SPAWN_MONSTERS", "false");
        s.gameRules.put("SPAWN_PATROLS", "false");
        s.gameRules.put("SPAWN_PHANTOMS", "false");
        s.gameRules.put("SPAWN_WANDERING_TRADERS", "false");
        s.gameRules.put("ADVANCE_WEATHER", "false");
        s.gameRules.put("MOB_GRIEFING", "false");
        s.gameRules.put("RESPAWN_RADIUS", "0");

        s.behaviour.pvp = false;
        s.behaviour.fallDamage = false;       // 掉虚空由插件送回岛，摔落伤害单独关闭避免误伤
        s.behaviour.allowFlight = false;
        s.behaviour.keepHungerFull = false;

        s.npc.enabled = false;   // 改为统一用大厅的菜单假人
        s.npc.x = 4.5;
        s.npc.y = 100.0;
        s.npc.z = 5.5;
        s.npc.yaw = 180.0F;
        s.npc.name = "\u00A7b空岛服";
        s.npc.skin = "auto";
        return s;
    }
}
