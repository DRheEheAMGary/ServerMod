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

    /**
     * 未认证玩家禁止聊天。
     *
     * <p>代码与文档一直声称未登录"不能聊天"，但实际上没有任何聊天钩子 ——
     * 任何能连上服务器的人都能向全服广播（钓鱼链接、冒充管理、刷屏）。
     * 这里补上。
     */
    private void onChat(ServerPlayer player, String message) {
        if (isAuthenticated(player)) {
            return;
        }
        player.sendSystemMessage(Component.literal(
                "\u00A7c请先完成注册或登录才能聊天。\u00A77（对话框应该已经弹出了）"));
    }

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

        // 未认证禁止聊天。
        // 代码/文档一直声称未登录"不能聊天"，但此前没有任何聊天钩子 ——
        // 任何能连上服务器的人都能向全服广播钓鱼链接/冒充管理/刷屏。
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(
                (message, sender, bound) -> isAuthenticated(sender));
    }

    // ------------------------------------------------------------------
    // 进入 / 退出
    // ------------------------------------------------------------------

    /**
     * 假人（NPC）不参与认证流程。
     *
     * <p><b>踩过的坑：</b>大厅/空岛服的引导假人是真正的 {@code ServerPlayer}，
     * 所以会触发 {@code ServerPlayConnectionEvents.JOIN}。没有这道豁免时
     * 认证系统会把它们当成"未登录玩家"：传送回大厅、弹注册表单、
     * 每 tick 冻结 —— 结果是**假人根本不在它该在的维度里**
     * （日志实证：{@code 切服请求：hub_island -> lobby}），
     * 而且被反复拉来拉去。
     */
    private static boolean isFake(ServerPlayer player) {
        return player instanceof cn.dreamgary.hubsuite.fake.MarkedFakePlayer;
    }

    private void onJoin(ServerPlayer player) {
        if (isFake(player)) {
            return;   // 假人不注册账号、不弹表单、不钉位置
        }
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
        // 连同"下次读档该用哪个存档"的登记一起清掉：
        // 留着的话，下次登录会按上一次会话的目标去读，可能读到别的子服的旧数据。
        cn.dreamgary.hubsuite.world.PlayerDataRouter.forget(player.getUUID());
        // 菜单状态也要清：不清的话玩家 ESC 关界面/断线后会永久留下
        // 一条 UUID→Open，其中 container 与回调闭包（捕获 ServerLevel）无法回收。
        cn.dreamgary.hubsuite.ui.ChestMenuScreen.forget(player.getUUID());
        // 任务界面的分页/分类状态同样不该跨会话保留
        cn.dreamgary.hubsuite.quest.QuestMenu.forget(player.getUUID());
        // 登录表单状态（UUID → 当前是登录还是注册表单）也是按玩家存的，
        // 不随退出清理就会一直攒着（玩家进进出出只增不减）。
        cn.dreamgary.hubsuite.auth.AuthGui.forget(player.getUUID());
        /*
         * 成就与统计的缓存也必须放掉 —— 这是"按子服隔离"能不能成立的关键。
         *
         * 原版 PlayerList.remove(player) 不清 stats/advancements 这两个 Map，
         * 而那两个对象**在构造时就把文件路径烧死在字段里**（computeIfAbsent 缓存）。
         * 不清的话，玩家第一次登录建的那份对象会一直留着，
         * 之后切到任何子服、重新登录多少次，成就与统计都写回第一次那个目录 ——
         * 实测表现就是"服务器之间的成就等内容没有隔离"。
         */
        cn.dreamgary.hubsuite.world.AuxDataRouter.forget(player);
    }

    // ------------------------------------------------------------------
    // 限制
    // ------------------------------------------------------------------

    /** 未登录玩家统一放到大厅（避免在小游戏/子服里挂机）。 */
    /** 未认证玩家允许偏离大厅出生点的最大距离（方块）。 */
    private static final double AUTH_ANCHOR_RADIUS = 4.0;

    /**
     * 把未认证玩家拉回大厅出生点附近。
     *
     * <p>只在同一维度内做小幅纠正；跨维度由 {@link #freezeInLobby} 负责，
     * 避免两者互相打架。
     */
    private void pinToLobbyAnchor(ServerPlayer player) {
        var lobby = worlds.lobby().orElse(null);
        if (lobby == null || !player.level().dimension().equals(lobby.level().dimension())) {
            return;
        }
        var anchor = lobby.spawn();
        double dx = player.getX() - anchor.x();
        double dz = player.getZ() - anchor.z();
        boolean outOfBounds = dx * dx + dz * dz > AUTH_ANCHOR_RADIUS * AUTH_ANCHOR_RADIUS
                || Math.abs(player.getY() - anchor.y()) > 8.0;
        if (!outOfBounds) {
            return;
        }
        player.teleportTo(lobby.level(), anchor.x(), anchor.y(), anchor.z(),
                java.util.Set.of(), player.getYRot(), player.getXRot(), false);
        player.setDeltaMovement(0, 0, 0);
        player.hurtMarked = true;
    }

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
            if (isFake(player)) {
                continue;   // 假人不需要被"钉住"
            }
            hold(player);
        }
    }

    /** 把未登录玩家"按"在原地：清速度、关飞行、保持满血饱食。 */
    /**
     * 未认证玩家的"钉住"：清速度 + 拉回大厅锚点。
     *
     * <p><b>为什么必须拉回位置：</b>只清速度是拦不住走动的。
     * 原版 {@code handleMovePlayer} 的 "moved wrongly" 修正只在
     * 客户端上报位置与服务端每 tick 偏差平方 &gt; 0.0625（≈0.25 格）时触发，
     * 而正常步行的每 tick 位移远小于这个阈值，会被直接接受；
     * 对创造/旁观玩家更是完全跳过修正。
     *
     * <p>结果是未登录玩家能在等待区自由行动（探路、贴近他人建筑、
     * 触发区块与实体加载）。所以这里每个 tick 主动比对与锚点的距离，
     * 偏离就强制拉回去（AuthMe 的做法）。
     */
    private void hold(ServerPlayer player) {
        player.setDeltaMovement(0, 0, 0);
        player.hurtMarked = true;
        pinToLobbyAnchor(player);
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
