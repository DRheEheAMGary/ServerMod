package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度 → 规则集 的映射表。
 *
 * <p>原版 {@code ServerLevel.getGameRules()} 返回的是全局 {@code MinecraftServer.gameRules}，
 * 于是所有维度共享规则。{@link cn.dreamgary.hubsuite.mixin.ServerLevelGameRulesMixin}
 * 会在这里查表：查到就用该维度自己的规则，查不到就保持原版行为。
 *
 * <p>规则对象直接来自各子服自己 {@code level.dat} 里的 {@code PrimaryLevelData}，
 * 因此 {@code /gamerule} 命令改的是"当前子服的规则"，并且会随该子服的存档一起保存。
 */
public final class RulesManager {

    private static final Map<ResourceKey<Level>, ServerRules> BY_LEVEL = new ConcurrentHashMap<>();

    private RulesManager() {
    }

    public static void register(ResourceKey<Level> dimension, ServerRules rules) {
        BY_LEVEL.put(dimension, rules);
        HubSuite.logger().debug("已为维度 {} 注册独立规则集。", dimension.identifier());
    }

    public static void unregister(ResourceKey<Level> dimension) {
        BY_LEVEL.remove(dimension);
    }

    public static void clear() {
        BY_LEVEL.clear();
    }

    /** 该维度是否有独立规则集。 */
    public static ServerRules rulesFor(ResourceKey<Level> dimension) {
        return BY_LEVEL.get(dimension);
    }

    /**
     * 供 Mixin 调用：返回该维度应该使用的 {@link GameRules}。
     *
     * @return 独立规则集里的对象；没有注册过则返回 {@code null}（Mixin 会放行给原版逻辑）
     */
    public static GameRules gameRulesFor(ServerLevel level) {
        ServerRules rules = BY_LEVEL.get(level.dimension());
        return rules == null ? null : rules.gameRules();
    }

    /** 给调试命令用。 */
    public static Map<ResourceKey<Level>, ServerRules> snapshot() {
        return Map.copyOf(BY_LEVEL);
    }
}
