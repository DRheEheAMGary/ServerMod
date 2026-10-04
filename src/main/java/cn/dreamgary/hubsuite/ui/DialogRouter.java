package cn.dreamgary.hubsuite.ui;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.auth.AuthGui;
import cn.dreamgary.hubsuite.auth.AuthManager;
import cn.dreamgary.hubsuite.auth.AuthService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 对话框动作路由表。
 *
 * <p>客户端点击带 {@code dynamic/custom} 动作的按钮时，会把**所有输入框的值
 * 按 key** 合并进 NBT 负载发回来。这里按动作 id 分派给对应的处理函数，
 * 并把负载里的字符串取出来。
 *
 * <p>扩展方式：调用 {@link #register(String, BiConsumer)} 注册新的动作 id 即可，
 * 不需要动 Mixin。
 */
public final class DialogRouter {

    /** 处理函数：玩家 + 该动作负载里的字段读取器。 */
    @FunctionalInterface
    public interface Handler {
        void handle(ServerPlayer player, Payload payload);
    }

    /** 对 NBT 负载的一层薄封装，避免到处写空判断。 */
    public record Payload(Optional<Tag> raw, String suffix) {

        public Payload(Optional<Tag> raw) {
            this(raw, "");
        }

        /** 读字符串字段，缺失返回 ""。 */
        public String str(String key) {
            return raw.filter(t -> t instanceof CompoundTag)
                    .map(t -> (CompoundTag) t)
                    .map(tag -> tag.getStringOr(key, ""))
                    .orElse("");
        }

        public String str(String key, String fallback) {
            String v = str(key);
            return v.isEmpty() ? fallback : v;
        }

        public int size() {
            return raw.filter(t -> t instanceof CompoundTag)
                    .map(t -> ((CompoundTag) t).size())
                    .orElse(0);
        }
    }

    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();
    /** 前缀路由：{@code lobby/join/<子服id>} 这类带参数的动作用它。 */
    private static final Map<String, Handler> PREFIX_HANDLERS = new ConcurrentHashMap<>();

    private static AuthManager authManager;
    private static AuthService authService;
    private static cn.dreamgary.hubsuite.world.WorldsManager worldsManager;

    private DialogRouter() {
    }

    /** 由主入口注入子系统（避免静态初始化顺序问题）。 */
    public static void bind(AuthManager manager, AuthService service,
                            cn.dreamgary.hubsuite.world.WorldsManager worlds) {
        authManager = manager;
        authService = service;
        worldsManager = worlds;
        registerAuthActions();
        registerLobbyActions();
    }

    /** 大厅交互：确认框里的"进入"按钮。 */
    private static void registerLobbyActions() {
        registerPrefix(cn.dreamgary.hubsuite.lobby.ServerJoinDialog.ACTION_JOIN_PREFIX,
                (player, payload) -> {
                    if (worldsManager == null) {
                        return;
                    }
                    DialogKit.close(player);
                    cn.dreamgary.hubsuite.lobby.ServerJoinDialog.joinById(player, payload.suffix(), worldsManager);
                });

        register(cn.dreamgary.hubsuite.lobby.ServerJoinDialog.ACTION_CANCEL, (player, payload) ->
                DialogKit.close(player));
    }

    public static void register(String actionPath, Handler handler) {
        HANDLERS.put(actionPath, handler);
    }

    /** 注册前缀动作，处理函数收到的 {@link Payload} 里 {@code __suffix} 是前缀之后的部分。 */
    public static void registerPrefix(String prefix, Handler handler) {
        PREFIX_HANDLERS.put(prefix, handler);
    }

    /**
     * 处理一个自定义点击动作。
     *
     * @return 是否由我们处理了
     */
    public static boolean route(ServerCommonPacketListener listener, ServerboundCustomClickActionPacket packet) {
        String id = packet.id().toString();
        String path = packet.id().getNamespace().equals("hubsuite")
                ? packet.id().getPath()
                : id;
        Handler handler = HANDLERS.get(path);
        if (handler == null) {
            // 前缀匹配（lobby/join/survival 之类）
            for (var entry : PREFIX_HANDLERS.entrySet()) {
                if (path.startsWith(entry.getKey())) {
                    handler = entry.getValue();
                    String suffix = path.substring(entry.getKey().length());
                    if (!(listener instanceof ServerGamePacketListenerImpl game0)) {
                        return false;
                    }
                    if (game0.player == null) {
                        return false;
                    }
                    entry.getValue().handle(game0.player, new Payload(packet.payload(), suffix));
                    return true;
                }
            }
        }
        if (handler == null) {
            return false;
        }
        if (!(listener instanceof ServerGamePacketListenerImpl game)) {
            // 配置阶段（还没进世界）暂时不处理
            return false;
        }
        ServerPlayer player = game.player;
        if (player == null) {
            return false;
        }
        HubSuite.logger().debug("收到对话框动作：{} <- {}", id, player.getName().getString());
        handler.handle(player, new Payload(packet.payload()));
        return true;
    }

    // ------------------------------------------------------------------
    // 内置：认证相关动作
    // ------------------------------------------------------------------

    private static void registerAuthActions() {
        register(AuthGui.ACTION_OPEN_LOGIN, (player, payload) -> {
            if (authService == null) {
                return;
            }
            AuthGui.openLogin(player, authService, null);
        });

        register(AuthGui.ACTION_OPEN_REGISTER, (player, payload) -> {
            if (authService == null) {
                return;
            }
            AuthGui.openRegister(player, authService, null);
        });

        register("auth/quit", (player, payload) -> {
            DialogKit.close(player);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "\u00A7e对话框已关闭。\u00A77你可以随时用 \u00A7f/login <密码> \u00A77或 \u00A7f/register <密码> <确认密码> \u00A77完成认证。"));
        });

        register(AuthGui.ACTION_LOGIN, (player, payload) -> {
            if (authManager == null || authService == null) {
                return;
            }
            String username = player.getName().getString();
            String password = payload.str(AuthGui.KEY_PASSWORD);
            if (password.isEmpty()) {
                AuthGui.openLogin(player, authService, "请输入密码。");
                return;
            }
            // 放到后台线程做 PBKDF2，结果再回主线程（避免卡 tick）
            authService.loginAsync(username, player.getUUID(), password,
                    AuthService.ipOf(player.connection.getRemoteAddress()),
                    result -> {
                        if (result.ok()) {
                            DialogKit.close(player);
                            AuthGui.forget(player.getUUID());
                            authManager.authenticated(player);
                        } else {
                            HubSuite.logger().info("玩家 {} 登录失败：{}", username, result.status());
                            AuthGui.openLogin(player, authService, result.message());
                        }
                    });
        });

        register(AuthGui.ACTION_REGISTER, (player, payload) -> {
            if (authManager == null || authService == null) {
                return;
            }
            String username = player.getName().getString();
            String password = payload.str(AuthGui.KEY_PASSWORD);
            String confirm = payload.str(AuthGui.KEY_CONFIRM);
            authService.registerAsync(username, player.getUUID(), password, confirm,
                    AuthService.ipOf(player.connection.getRemoteAddress()),
                    result -> {
                        if (result.ok()) {
                            DialogKit.close(player);
                            AuthGui.forget(player.getUUID());
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00A7a✔ 注册成功！\u00A77你的账号已创建，下次进入请用同一个密码登录。"));
                            authManager.authenticated(player);
                        } else {
                            HubSuite.logger().info("玩家 {} 注册失败：{}", username, result.status());
                            AuthGui.openRegister(player, authService, result.message());
                        }
                    });
        });
    }

    /** 供自检用：列出已注册的动作 id。 */
    public static java.util.Set<String> registeredActions() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(HANDLERS.keySet());
        PREFIX_HANDLERS.keySet().forEach(prefix -> all.add(prefix + "*"));
        return java.util.Set.copyOf(all);
    }
}
