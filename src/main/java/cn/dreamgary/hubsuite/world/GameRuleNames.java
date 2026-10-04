package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 26.1 的 gamerule 是注册表对象（{@link GameRule}），常量名是 {@code UPPER_SNAKE_CASE}
 * （例如 {@code KEEP_INVENTORY}、{@code PVP}）。配置文件里用常量名，这里做名字 → 对象的映射。
 *
 * <p>用反射遍历 {@link GameRules} 的静态字段而不是手写一张表，
 * 好处是：以后 MC 增删 gamerule 时，只要名字没变就自动适配，不用改代码。
 */
public final class GameRuleNames {

    private static final Map<String, GameRule<?>> BY_NAME;

    static {
        Map<String, GameRule<?>> map = new LinkedHashMap<>();
        for (Field field : GameRules.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            if (!GameRule.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                map.put(field.getName(), (GameRule<?>) field.get(null));
            } catch (ReflectiveOperationException e) {
                HubSuite.logger().warn("无法读取 gamerule 常量 {}", field.getName(), e);
            }
        }
        BY_NAME = Collections.unmodifiableMap(map);
        HubSuite.logger().debug("已加载 {} 条 gamerule 定义。", BY_NAME.size());
    }

    private GameRuleNames() {
    }

    /** 按常量名查；找不到返回 null。 */
    public static GameRule<?> find(String constantName) {
        return constantName == null ? null : BY_NAME.get(constantName.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public static Map<String, GameRule<?>> all() {
        return BY_NAME;
    }

    /**
     * 注册表 id（{@code minecraft:keep_inventory}）→ 常量名（{@code KEEP_INVENTORY}）。
     * 找不到对应常量时返回大写形式，保证不会丢数据。
     */
    public static String toConstantName(String registryId) {
        if (registryId == null) {
            return "";
        }
        String path = registryId;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        return path.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 把字符串值写进一份 {@link GameRules}。类型不匹配或名字未知时只告警不抛异常，
     * 保证一条写错的配置不会让服务器启动失败。
     *
     * @return 是否写入成功
     */
    @SuppressWarnings("unchecked")
    public static <T> boolean apply(GameRules rules, String constantName, String rawValue) {
        GameRule<?> rule = find(constantName);
        if (rule == null) {
            HubSuite.logger().warn("未知 gamerule '{}'，已忽略。", constantName);
            return false;
        }
        GameRule<T> typed = (GameRule<T>) rule;
        try {
            T value = typed.deserialize(rawValue).getOrThrow();
            rules.set(typed, value, null);
            return true;
        } catch (Exception e) {
            HubSuite.logger().warn("gamerule '{}' 的值 '{}' 非法（应为 {}），已忽略。",
                    constantName, rawValue, typed.valueClass().getSimpleName());
            return false;
        }
    }

    /** 读当前值的字符串形式（用于 /hub info 展示）。 */
    public static String read(GameRules rules, String constantName) {
        GameRule<?> rule = find(constantName);
        if (rule == null) {
            return "?";
        }
        try {
            return rules.getAsString((GameRule<Object>) rule);
        } catch (Exception e) {
            return "?";
        }
    }
}
