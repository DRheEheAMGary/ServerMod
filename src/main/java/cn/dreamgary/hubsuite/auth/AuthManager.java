package cn.dreamgary.hubsuite.auth;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录状态管理 + 未登录玩家的限制。
 *
 * <p>未登录玩家会被"钉"在服务端出生点：不能移动、不能交互、不能聊天、不能受伤，
 * 只在对话框里完成注册或登录。所有限制都在这里集中实现，方便审查安全边界。
 */
public final class AuthManager {

    /** 一个玩家的认证状态。 */
    public static final class AuthState {
        final UUID uuid;
        final String username;
        final String ip;
        volatile boolean authenticated;
        volatile boolean registering;
        volatile long deadline;

        AuthState(UUID uuid, String username, String ip, boolean authenticated, long deadline) {
            this.uuid = uuid;
            this.username = username;
            this.ip = ip;
            this.authenticated = authenticated;
            this.deadline = deadline;
        }

        public boolean authenticated() {
            return authenticated;
        }

        public String username() {
            return username;
        }

        public boolean registering() {
            return registering;
        }
    }

    private final AuthService service;
    private final HubSuiteConfig.AuthConfig config;
    private final WorldsManager worlds;
    private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();

    public AuthManager(AuthService service, WorldsManager worlds) {
        this.service = service;
        this.config = service.config();
        this.worlds = worlds;
    }

    // ------------------------------------------------------------------
    // 注册事件
    // ------------------------------------------------------------------

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onQuit(handler.getPlayer()));

        // 未登录禁止受伤（含掉虚空、窒息等）
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allowDamage);
        // 未登录玩家死亡时直接满血复位（避免出现"死在虚空里"的怪状态）
        ServerPlayerEvents.AFTER_RESPAWN.register((player, level, alive) -> {
            if (!isAuthenticated(player)) {
                player.setHealth(player.getMaxHealth());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    // ------------------------------------------------------------------
    // 进入 / 退出
    // ------------------------------------------------------------------

    private void onJoin(ServerPlayer player) {
        if (!config.enabled) {
            return;
        }
        long t0 = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        String ip = AuthService.ipOf(player.connection.getRemoteAddress());
        long now = System.currentTimeMillis();
        String username = player.getName().getString();

        boolean session = service.sessionValid(uuid, ip, now);
        AuthState state = new AuthState(uuid, username, ip, session,
                now + config.loginTimeoutSeconds * 1000L);
        states.put(uuid, state);

        long tSession = System.currentTimeMillis();
        if (session) {
            HubSuite.logger().info("玩家 {} 使用有效会话直接进入（会话判定 {} ms）。",
                    username, tSession - t0);
            authenticated(player);
            HubSuite.logger().info("玩家 {} 进入流程完成，共 {} ms。",
                    username, System.currentTimeMillis() - t0);
            return;
        }

        // 未认证：送到大厅等待区并弹窗
        freezeInLobby(player);
        long tLobby = System.currentTimeMillis();
        AuthGui.openFor(player, service, this);
        HubSuite.logger().info("玩家 {} 到达大厅耗时 {} ms（会话判定 {} ms），已请求弹出表单。",
                username, tLobby - t0, tSession - t0);
    }

    private void onQuit(ServerPlayer player) {
        states.remove(player.getUUID());
        // 玩家在某个子服的当前状态会随玩家数据落盘到那个子服的存档里，
        // 所以内存暂存可以清掉（下次进入会重新建立）。
        cn.dreamgary.hubsuite.world.PlayerStateStash.forget(player.getUUID());
    }

    // ------------------------------------------------------------------
    // 限制
    // ------------------------------------------------------------------

    /** 未登录玩家统一放到大厅（避免在小游戏/子服里挂机）。 */
    private void freezeInLobby(ServerPlayer player) {
        var lobby = worlds.lobby().orElse(null);
        if (lobby == null) {
            HubSuite.logger().warn("大厅未加载，无法把未登录玩家 {} 送入等待区。", player.getName().getString());
            return;
        }
        // 每次有玩家进入都补一次：确保大厅始终是白天且不下雨
        cn.dreamgary.hubsuite.world.LobbyEnvironment.makeEternalDay(lobby.level());
        if (!player.level().dimension().equals(lobby.level().dimension())) {
            PlayerRouter.sendTo(player, lobby);
        }
    }

    private boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (entity instanceof ServerPlayer player) {
            AuthState state = states.get(player.getUUID());
            if (state != null && !state.authenticated) {
                return false;
            }
        }
        return true;
    }

    /** 每个 tick 检查超时，并持续压制未登录玩家的位置与状态。 */
    private void tick() {
        if (states.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (AuthState state : states.values()) {
            if (state.authenticated) {
                continue;
            }
            ServerPlayer player = playerOf(state.uuid);
            if (player == null) {
                continue;
            }
            if (now > state.deadline) {
                player.connection.disconnect(Component.literal(
                        "\u00A7c登录超时\u00A7r\n\u00A77请在 " + config.loginTimeoutSeconds + " 秒内完成注册或登录。"));
                continue;
            }
            hold(player);
        }
    }

    /** 把未登录玩家"按"在原地：清速度、关飞行、保持满血饱食。 */
    private void hold(ServerPlayer player) {
        player.setDeltaMovement(0, 0, 0);
        player.hurtMarked = true;
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        if (player.getFoodData().getFoodLevel() < 20) {
            player.getFoodData().setFoodLevel(20);
        }
    }

    // ------------------------------------------------------------------
    // 状态查询 / 变更
    // ------------------------------------------------------------------

    public boolean isAuthenticated(ServerPlayer player) {
        AuthState state = states.get(player.getUUID());
        // 没记录 = 登录系统关闭，视为已认证
        return state == null || state.authenticated;
    }

    public Optional<AuthState> state(ServerPlayer player) {
        return Optional.ofNullable(states.get(player.getUUID()));
    }

    /** 认证通过：解除限制、送到大厅、给出提示。 */
    public void authenticated(ServerPlayer player) {
        AuthState state = states.get(player.getUUID());
        if (state == null) {
            state = new AuthState(player.getUUID(), player.getName().getString(), "", true, Long.MAX_VALUE);
            states.put(player.getUUID(), state);
        } else {
            state.authenticated = true;
        }

        var lobby = worlds.lobby().orElse(null);
        if (lobby != null) {
            PlayerRouter.sendTo(player, lobby);
        }
        player.sendSystemMessage(Component.literal("\u00A7a✔ 认证成功，欢迎回来，\u00A7f" + player.getName().getString() + "\u00A7a！"));
        player.sendSystemMessage(Component.literal("\u00A77使用 \u00A7f/hub list \u00A77查看可用服务器，或直接点击大厅里的假人。"));
    }

    private ServerPlayer playerOf(UUID uuid) {
        var server = worlds.server();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(uuid);
    }

    /** 给命令用的提示文本。 */
    public String statusText(ServerPlayer player) {
        AuthState state = states.get(player.getUUID());
        if (state == null) {
            return "登录系统未启用";
        }
        return state.authenticated ? "已登录" : "未登录";
    }

    public AuthService service() {
        return service;
    }

    /** 便于自检：直接标记为已认证（不经过对话框）。 */
    public void forceAuthenticate(ServerPlayer player) {
        authenticated(player);
    }
}
