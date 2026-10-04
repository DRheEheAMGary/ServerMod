package cn.dreamgary.hubsuite.integration;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 占位符服务：把 HubSuite 的信息暴露成占位符，并统一解析
 * HuskHomes 与自己的两套语法。
 *
 * <h2>支持的写法</h2>
 * <table border="1">
 *   <tr><th>写法</th><th>来源</th><th>说明</th></tr>
 *   <tr><td>{@code %hubsuite_server%}</td><td>本模组</td>
 *       <td>下划线语法（兼容 HuskHomes/PlaceholderAPI 用户的习惯）</td></tr>
 *   <tr><td>{@code %hubsuite:player/server%}</td><td>本模组</td>
 *       <td>Text Placeholder API 的命名空间语法</td></tr>
 *   <tr><td>{@code %huskhomes_homes_count%}</td><td>HuskHomes</td>
 *       <td>由 HuskHomes 自己注册（依赖 Text Placeholder API）</td></tr>
 *   <tr><td>{@code %huskhomes:player/homes_count%}</td><td>HuskHomes</td>
 *       <td>Text Placeholder API 语法</td></tr>
 * </table>
 *
 * <h2>本模组提供的占位符</h2>
 * <ul>
 *   <li>{@code %hubsuite_server%} —— 当前所在场所 id</li>
 *   <li>{@code %hubsuite_server_name%} —— 当前场所显示名</li>
 *   <li>{@code %hubsuite_player%} —— 玩家名</li>
 *   <li>{@code %hubsuite_online%} —— 当前场所在线人数</li>
 *   <li>{@code %hubsuite_total_online%} —— 全服在线人数</li>
 *   <li>{@code %hubsuite_servers%} —— 子服列表</li>
 *   <li>{@code %hubsuite_authed%} —— 是否已登录（是/否）</li>
 *   <li>{@code %hubsuite_island%} —— 空岛方格坐标</li>
 *   <li>{@code %hubsuite_island_type%} —— 空岛类型</li>
 *   <li>{@code %hubsuite_homes%} —— 家数量（经 HuskHomes）</li>
 * </ul>
 */
public final class PlaceholderService {

    private static final String MOD_ID = "placeholder-api";

    private final WorldsManager worlds;
    private final HuskHomesIntegration huskHomes;
    private final boolean textPlaceholderApiPresent;
    /** 下划线语法 → 取值函数。参数是"上下文玩家"（可能为 null）。 */
    private final Map<String, Function<ServerPlayer, String>> placeholders = new LinkedHashMap<>();

    /** 最近一次解析时的"查看者所在世界"，用于没有玩家上下文时仍能解析 %hubsuite_server%。 */
    private static final ThreadLocal<net.minecraft.server.level.ServerLevel> VIEWER_LEVEL = new ThreadLocal<>();

    public PlaceholderService(WorldsManager worlds, HuskHomesIntegration huskHomes) {
        this.worlds = worlds;
        this.huskHomes = huskHomes;
        this.textPlaceholderApiPresent = FabricLoader.getInstance().isModLoaded(MOD_ID);
        registerBuiltins();
    }

    // ------------------------------------------------------------------
    // 内置占位符
    // ------------------------------------------------------------------

    private void registerBuiltins() {
        placeholders.put("server", player -> worldOfOrViewer(player).map(PlayableWorld::id).orElse("-"));
        placeholders.put("server_name", player -> worldOfOrViewer(player)
                .map(w -> stripColors(w.displayName())).orElse("-"));
        placeholders.put("player", player -> player == null ? "-" : player.getName().getString());
        placeholders.put("online", player -> worldOfOrViewer(player)
                .map(w -> String.valueOf(w.level().players().size()))
                .orElseGet(() -> String.valueOf(serverPlayerCount())));
        placeholders.put("total_online", player -> String.valueOf(serverPlayerCount()));
        placeholders.put("servers", player -> worlds.subServers().stream()
                .map(SubServer::id)
                .reduce((a, b) -> a + ", " + b)
                .orElse("-"));
        placeholders.put("authed", player -> {
            var manager = HubSuite.authManager();
            if (manager == null || player == null) {
                return "否";
            }
            return manager.isAuthenticated(player) ? "是" : "否";
        });
        placeholders.put("island", player -> islandInfo(player, 0));
        placeholders.put("island_type", player -> islandInfo(player, 1));
        placeholders.put("homes", this::homeCount);
    }

    private String islandInfo(ServerPlayer player, int field) {
        var engine = HubSuite.islands();
        if (engine == null || player == null) {
            return "-";
        }
        var island = engine.manager().islandOf(player.getUUID()).orElse(null);
        if (island == null) {
            return field == 0 ? "无" : "-";
        }
        return field == 0 ? (island.plotX + ", " + island.plotZ) : island.type;
    }

    private String homeCount(ServerPlayer player) {
        if (player == null) {
            return "0";
        }
        Optional<Integer> count = huskHomes.homeCount(player);
        return count.map(String::valueOf).orElseGet(() -> huskHomes.present() ? "?" : "0");
    }

    private Optional<PlayableWorld> worldOf(ServerPlayer player) {
        return player == null ? Optional.empty() : worlds.worldOf(player);
    }

    /**
     * 解析"当前服务器"时优先用上下文玩家；没有玩家（例如渲染 MOTD、
     * 玩家列表头部、状态栏）就回落到 {@link #VIEWER_LEVEL} 记录的世界。
     */
    private Optional<PlayableWorld> worldOfOrViewer(ServerPlayer player) {
        Optional<PlayableWorld> byPlayer = worldOf(player);
        if (byPlayer.isPresent()) {
            return byPlayer;
        }
        var level = VIEWER_LEVEL.get();
        return level == null ? Optional.empty() : worldByLevel(level);
    }

    private Optional<PlayableWorld> worldByLevel(net.minecraft.server.level.ServerLevel level) {
        for (PlayableWorld world : worlds.allWorlds()) {
            if (world.level().dimension().equals(level.dimension())) {
                return Optional.of(world);
            }
        }
        return Optional.empty();
    }

    private int serverPlayerCount() {
        var server = worlds.server();
        return server == null ? 0 : server.getPlayerList().getPlayers().size();
    }

    private static String stripColors(String text) {
        return text == null ? "" : text.replaceAll("[&\u00A7][0-9a-fk-orA-FK-OR]", "");
    }

    // ------------------------------------------------------------------
    // 注册到 Text Placeholder API
    // ------------------------------------------------------------------

    /** 在服务端启动后调用：向 Text Placeholder API 注册 {@code hubsuite} 命名空间。 */
    public void registerWithTextPlaceholderApi() {
        if (!textPlaceholderApiPresent) {
            HubSuite.logger().warn("未检测到 Text Placeholder API（placeholder-api）："
                    + "本模组的占位符仍可在自己的消息里使用，但不会被其它模组的文本解析器识别。");
            return;
        }
        try {
            Class<?> placeholdersClass = Class.forName("eu.pb4.placeholders.api.Placeholders");
            Class<?> serverContextClass = Class.forName("eu.pb4.placeholders.api.ServerPlaceholderContext");
            Class<?> handlerClass = Class.forName("eu.pb4.placeholders.api.Placeholder$Handler");
            Class<?> resultClass = Class.forName("eu.pb4.placeholders.api.PlaceholderResult");

            Method registerServer = placeholdersClass.getMethod("registerServer",
                    Identifier.class, handlerClass);
            Method resultValue = resultClass.getMethod("value", String.class);
            // ServerPlaceholderContext 继承 PlaceholderContext，player() 定义在父接口上
            Method contextPlayer = Class.forName("eu.pb4.placeholders.api.PlaceholderContext")
                    .getMethod("player");

            int registered = 0;
            for (var entry : placeholders.entrySet()) {
                Identifier id = Identifier.fromNamespaceAndPath("hubsuite", entry.getKey());
                Object handler = java.lang.reflect.Proxy.newProxyInstance(
                        handlerClass.getClassLoader(),
                        new Class<?>[]{handlerClass},
                        (proxy, method, args) -> {
                            if ("onPlaceholderRequest".equals(method.getName())) {
                                ServerPlayer player = null;
                                try {
                                    Object ctx = args[0];
                                    if (ctx != null) {
                                        Object p = contextPlayer.invoke(ctx);
                                        if (p instanceof ServerPlayer sp) {
                                            player = sp;
                                            VIEWER_LEVEL.set(sp.level());
                                        } else {
                                            // 没有玩家：可能是在渲染某个世界的文本，取上下文里的世界
                                            Method levelMethod = Class
                                                    .forName("eu.pb4.placeholders.api.PlaceholderContext")
                                                    .getMethod("level");
                                            Object lvl = levelMethod.invoke(ctx);
                                            if (lvl instanceof net.minecraft.server.level.ServerLevel sl) {
                                                VIEWER_LEVEL.set(sl);
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                    // 没有上下文时按"服务端级"占位符处理
                                }
                                try {
                                    String value = entry.getValue().apply(player);
                                    return resultValue.invoke(null, value == null ? "" : value);
                                } finally {
                                    VIEWER_LEVEL.remove();
                                }
                            }
                            return switch (method.getName()) {
                                case "toString" -> "HubSuitePlaceholder[" + entry.getKey() + "]";
                                case "hashCode" -> entry.getKey().hashCode();
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        });
                registerServer.invoke(null, id, handler);
                registered++;
            }
            HubSuite.logger().info("已向 Text Placeholder API 注册 {} 个 hubsuite 占位符"
                    + "（语法 %hubsuite:player/<名字>%）。", registered);
        } catch (Throwable t) {
            HubSuite.logger().warn("注册占位符到 Text Placeholder API 失败：{}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析一段文本里的占位符。
     *
     * <p>同时支持 {@code %hubsuite_server%} 与 {@code %hubsuite:player/server%}。
     * 不认识的占位符原样保留（方便排查），HuskHomes 的占位符交给它自己。
     */
    public String parse(String text, ServerPlayer player) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('%', i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            int end = text.indexOf('%', start + 1);
            if (end < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            String token = text.substring(start + 1, end);
            String resolved = resolveToken(token, player);
            out.append(resolved != null ? resolved : "%" + token + "%");
            i = end + 1;
        }
        return out.toString();
    }

    /** 解析单个占位符（不含百分号）。无法解析返回 null。 */
    public String resolveToken(String token, ServerPlayer player) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String body = token;
        // Text Placeholder API 语法：namespace:path/arg
        if (body.startsWith("hubsuite:")) {
            body = body.substring("hubsuite:".length());
            int slash = body.lastIndexOf('/');
            if (slash >= 0) {
                body = body.substring(slash + 1);
            }
        } else if (body.startsWith("hubsuite_")) {
            body = body.substring("hubsuite_".length());
        } else {
            // 不是我们的占位符：如果装了 Text Placeholder API，交给它尝试解析
            return resolveForeign(token, player);
        }
        Function<ServerPlayer, String> fn = placeholders.get(body);
        return fn == null ? null : fn.apply(player);
    }

    /** 交给 Text Placeholder API 解析（例如 HuskHomes 注册的那些）。 */
    private String resolveForeign(String token, ServerPlayer player) {
        if (!textPlaceholderApiPresent) {
            return null;
        }
        try {
            Class<?> placeholdersClass = Class.forName("eu.pb4.placeholders.api.Placeholders");
            Class<?> serverContextClass = Class.forName("eu.pb4.placeholders.api.ServerPlaceholderContext");
            Class<?> resultClass = Class.forName("eu.pb4.placeholders.api.PlaceholderResult");

            Object context = player != null
                    ? serverContextClass.getMethod("of", ServerPlayer.class).invoke(null, player)
                    : serverContextClass.getMethod("of", net.minecraft.server.MinecraftServer.class)
                            .invoke(null, worlds.server());

            // 支持 "huskhomes_homes_count" 与 "huskhomes:player/homes_count" 两种写法
            String normalized = token.replace('_', ':');
            int colon = normalized.indexOf(':');
            Identifier id = colon > 0
                    ? Identifier.tryParse(normalized.substring(0, colon) + ":" + normalized.substring(colon + 1))
                    : Identifier.tryParse("hubsuite:" + normalized);
            if (id == null) {
                return null;
            }
            Method parse = placeholdersClass.getMethod("parseServerPlaceholder",
                    Identifier.class, String.class, serverContextClass);
            Object result = parse.invoke(null, id, "", context);
            Method isValid = resultClass.getMethod("isValid");
            if (Boolean.TRUE.equals(isValid.invoke(result))) {
                Method component = resultClass.getMethod("component");
                Object comp = component.invoke(result);
                return comp instanceof Component c ? c.getString() : String.valueOf(comp);
            }
        } catch (Throwable t) {
            HubSuite.logger().debug("外部占位符解析失败（{}）：{}", token, t.toString());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    public boolean textPlaceholderApiPresent() {
        return textPlaceholderApiPresent;
    }

    /** 供 /hub info 与 /hub placeholders 展示。 */
    public List<String> describe() {
        return placeholders.keySet().stream()
                .map(key -> "%hubsuite_" + key + "%  或  %hubsuite:player/" + key + "%")
                .toList();
    }

    public String statusText() {
        return textPlaceholderApiPresent
                ? "已注册到 Text Placeholder API（" + placeholders.size() + " 个）"
                : "未安装 Text Placeholder API（仅本模组内部可用）";
    }

    /** 启动时打印适配报告。 */
    public void logReport() {
        HubSuite.logger().info("占位符适配：{}", statusText());
        if (huskHomes.present()) {
            HubSuite.logger().info("  HuskHomes 占位符：%huskhomes_homes_count% 等由它自己注册，"
                    + "本模组会在解析时自动转发。");
        }
    }

    /** 便捷方法：把带占位符的文本转成 Component。 */
    public Component component(String text, ServerPlayer player) {
        return Component.literal(parse(text, player));
    }
}
