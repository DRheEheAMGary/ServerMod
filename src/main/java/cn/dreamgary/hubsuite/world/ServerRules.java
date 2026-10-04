package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个子服的"规则集"。
 *
 * <p><b>为什么需要它：</b>26.1 的 gamerule 不再保存在 {@code level.dat} 里，
 * 而是跟着 {@code MinecraftServer} 走（全局一份，存在 {@code <world>/data/}）。
 * 原版 {@code ServerLevel.getGameRules()} 直接返回那一份全局对象，
 * 所以多个维度天生共享规则。
 *
 * <p>本项目为每个子服各建一份 {@link GameRules}，并用
 * {@link cn.dreamgary.hubsuite.mixin.ServerLevelGameRulesMixin} 让
 * {@code ServerLevel.getGameRules()} 返回本子服的那一份。因为原版所有游戏逻辑
 * （命令、进阶、重生、刷怪、伤害、死亡掉落…）都通过 {@code level.getGameRules()} 取规则，
 * 所以这一个改动点就实现了完整的规则隔离。
 *
 * <p>规则会持久化到 {@code config/hubsuite/rules/<子服id>.json}，
 * 管理员用 {@code /gamerule} 改过的值重启后依然有效。
 */
public final class ServerRules {

    private final String ownerId;
    private final HubSuiteConfig.ServerBehaviour behaviour;
    private final GameRules gameRules;

    public ServerRules(String ownerId, HubSuiteConfig.ServerBehaviour behaviour) {
        this.ownerId = ownerId;
        this.behaviour = behaviour == null ? new HubSuiteConfig.ServerBehaviour() : behaviour;
        this.gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
    }

    /**
     * 按"配置 → mod 规则"的顺序完成初始化。
     *
     * @param configuredRules 配置里显式写的 gamerule（优先级最高，覆盖存档值）
     */
    public void initialize(Map<String, String> configuredRules) {
        // 1) 先读回上次保存的规则（管理员用 /gamerule 改过的）
        Map<String, String> saved = RulesStorage.load(ownerId);
        if (saved != null) {
            saved.forEach((name, value) -> GameRuleNames.apply(gameRules, name, value));
        }
        // 2) 配置里的值覆盖存档值 —— 配置是运维的唯一真相来源
        if (configuredRules != null) {
            configuredRules.forEach((name, value) -> {
                if (!GameRuleNames.apply(gameRules, name, value)) {
                    HubSuite.logger().warn("子服 '{}' 的 gamerule '{}' 未能应用。", ownerId, name);
                }
            });
        }
        // 3) 把 mod 行为开关同步成对应 gamerule，保证两者不打架
        mirrorBehaviourRules();
    }

    /** 把 mod 自有的行为开关同步成对应的原版 gamerule。 */
    private void mirrorBehaviourRules() {
        setBoolean("PVP", behaviour.pvp);
        setBoolean("FALL_DAMAGE", behaviour.fallDamage);
        // 26.1 起火焰蔓延是"半径"整数：0 = 不蔓延
        setInt("FIRE_SPREAD_RADIUS_AROUND_PLAYER", behaviour.fireSpread ? 1 : 0);
        setBoolean("MOB_GRIEFING", behaviour.mobGriefing);
        setBoolean("TNT_EXPLODES", behaviour.tntIgnition);
        setBoolean("BLOCK_EXPLOSION_DROP_DECAY", behaviour.explosionBlockDamage);
        setBoolean("MOB_EXPLOSION_DROP_DECAY", behaviour.explosionBlockDamage);
    }

    private void setBoolean(String name, boolean value) {
        if (GameRuleNames.find(name) != null) {
            GameRuleNames.apply(gameRules, name, Boolean.toString(value));
        }
    }

    private void setInt(String name, int value) {
        if (GameRuleNames.find(name) != null) {
            GameRuleNames.apply(gameRules, name, Integer.toString(value));
        }
    }

    /** 把当前全部规则写盘（服务端关闭时、以及 /hub save 时调用）。 */
    public void persist() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        gameRules.availableRules().forEach(rule -> {
            String name = rule.id();
            // 规则在注册表里的 id 形如 minecraft:keep_inventory，转成配置用的常量名
            snapshot.put(GameRuleNames.toConstantName(name), readAsString(rule));
        });
        RulesStorage.save(ownerId, snapshot);
    }

    @SuppressWarnings("unchecked")
    private String readAsString(GameRule<?> rule) {
        try {
            return gameRules.getAsString((GameRule<Object>) rule);
        } catch (Exception e) {
            return "";
        }
    }

    public String ownerId() {
        return ownerId;
    }

    public GameRules gameRules() {
        return gameRules;
    }

    public HubSuiteConfig.ServerBehaviour behaviour() {
        return behaviour;
    }

    // ------------------------------------------------------------------
    // mod 级规则（原版 gamerule 表达不了的）
    // ------------------------------------------------------------------

    public boolean invulnerable() {
        return behaviour.invulnerable;
    }

    public boolean allowFlight() {
        return behaviour.allowFlight;
    }

    public boolean allowItemDrop() {
        return behaviour.allowItemDrop;
    }

    public boolean clearInventoryOnEnter() {
        return behaviour.clearInventoryOnEnter;
    }

    public boolean keepHungerFull() {
        return behaviour.keepHungerFull;
    }

    public double worldBorderRadius() {
        return behaviour.worldBorderRadius;
    }

    /** PvP 同时看 mod 规则与原版 gamerule，任意一边禁用即不允许。 */
    public boolean pvpAllowed() {
        Object value = readRule("PVP");
        if (value instanceof Boolean b && !b) {
            return false;
        }
        return behaviour.pvp;
    }

    @SuppressWarnings("unchecked")
    private Object readRule(String name) {
        GameRule<?> rule = GameRuleNames.find(name);
        if (rule == null) {
            return null;
        }
        try {
            return gameRules.get((GameRule<Object>) rule);
        } catch (Exception e) {
            return null;
        }
    }
}
