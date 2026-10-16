package cn.dreamgary.hubsuite.net;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端模组校验（0.5.0，见 docs/客户端契约.md 第 3.3 / 5 节）。
 *
 * <p>流程：玩家进服 → 服务端发 {@link HandshakePayloads.Hello} →
 * 客户端回 {@link HandshakePayloads.HelloAck}（版本 / 协议号 / 指纹 / 能力清单）→
 * 服务端判定并按 {@code clientCompat.mode} 处理（off 不管 / warn 提示 / enforce 踢出）。
 *
 * <p>没有客户端模组时客户端压根不会声明这个频道，
 * {@link ServerPlayNetworking#canSend} 当场就是 false —— 不用等超时。
 * 超时只兜"装了但没应答"这种情况。
 *
 * <p>假人（NPC）没有网络连接，一律跳过（{@code MarkedFakePlayer}）。
 */
public final class ClientCompat {

    public enum Mode {
        /** 不校验（开发期默认）。 */
        OFF,
        /** 只提示玩家 + 记日志。 */
        WARN,
        /** 踢出并给出下载地址。 */
        ENFORCE
    }

    public enum Action {
        ALLOW, WARN, KICK
    }

    /** 一个玩家报上来的客户端信息。 */
    public record ClientInfo(String version, int protocol, String fingerprint, List<String> caps) {
        public boolean has(String cap) {
            return caps != null && caps.contains(cap);
        }

        @Override
        public String toString() {
            return "客户端 v" + version + " / 协议 " + protocol + " / 指纹 " + fingerprint + " / 能力 " + caps;
        }
    }

    /**
     * 判定表（纯函数，自检里直接测）。
     *
     * <p>{@code ok=false} 覆盖两种情形：没应答（没装 / 太老 / 超时）与应答了但版本不达标。
     */
    public static Action decide(Mode mode, boolean ok) {
        if (mode == null || mode == Mode.OFF || ok) {
            return Action.ALLOW;
        }
        return mode == Mode.ENFORCE ? Action.KICK : Action.WARN;
    }

    private final HubSuiteConfig.ClientCompatConfig config;
    /** 已通过校验的玩家。 */
    private final Map<UUID, ClientInfo> known = new ConcurrentHashMap<>();
    /** 已打招呼、还在等应答的玩家 → 剩余 tick。 */
    private final Map<UUID, Integer> waiting = new ConcurrentHashMap<>();

    public ClientCompat(HubSuiteConfig.ClientCompatConfig config) {
        this.config = config;
    }

    public void register() {
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayloads.Hello.TYPE, HandshakePayloads.Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayloads.Welcome.TYPE, HandshakePayloads.Welcome.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayloads.HelloAck.TYPE, HandshakePayloads.HelloAck.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HandshakePayloads.HelloAck.TYPE, this::onAck);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onQuit(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        HubSuite.logger().info("客户端校验已就绪：模式={}，最低客户端版本={}，等待应答 {} 秒",
                config.modeOf(),
                config.minClientVersion == null || config.minClientVersion.isBlank()
                        ? "（不检查）" : config.minClientVersion,
                config.timeoutSeconds);
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    private void onJoin(ServerPlayer player) {
        if (config.modeOf() == Mode.OFF || isFake(player)) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, HandshakePayloads.Hello.TYPE)) {
            // 客户端没有声明这个频道 = 没装客户端模组（旧版客户端也一样）。
            apply(player, null, "客户端没有声明握手频道（没装客户端模组？）");
            return;
        }
        waiting.put(player.getUUID(), Math.max(1, config.timeoutSeconds) * 20);
        ServerPlayNetworking.send(player, new HandshakePayloads.Hello(
                HandshakePayloads.PROTOCOL, serverVersion(), safeMin(), config.timeoutSeconds));
    }

    private void onAck(HandshakePayloads.HelloAck payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (player == null || waiting.remove(player.getUUID()) == null) {
            return;   // 没打过招呼（或已经处理过）：忽略
        }
        ClientInfo info = new ClientInfo(payload.clientVersion(), payload.protocol(),
                payload.fingerprint(), payload.caps() == null ? List.of() : List.copyOf(payload.caps()));

        boolean protocolOk = info.protocol() == HandshakePayloads.PROTOCOL;
        boolean versionOk = ClientVersions.atLeast(info.version(), safeMin());
        if (!protocolOk) {
            apply(player, info, "协议号不一致（服务端 " + HandshakePayloads.PROTOCOL + "，客户端 " + info.protocol() + "）");
            return;
        }
        if (!versionOk) {
            apply(player, info, "客户端版本低于服务端要求");
            return;
        }
        known.put(player.getUUID(), info);
        ServerPlayNetworking.send(player, new HandshakePayloads.Welcome(true, ""));
        /*
         * 重发一次背包菜单。
         *
         * 进服时的第一次背包同步**早于**这次握手完成，那时服务端还不知道对方装了客户端，
         * 只能按 Polymer 的原版映射发（实测：玩家背包里已有的自定义物品会显示成 trial_key，
         * 要等下一次同步才变回真物品）。这里补一次全量同步，把已有物品立刻纠正过来。
         */
        try {
            player.inventoryMenu.broadcastFullState();
        } catch (Throwable t) {
            HubSuite.logger().warn("握手后重发背包失败（已有物品可能要等下次同步才显示为真物品）：{}", t.toString());
        }
        HubSuite.logger().info("客户端校验通过：{} —— {}", player.getName().getString(), info);
    }

    private void onQuit(ServerPlayer player) {
        known.remove(player.getUUID());
        waiting.remove(player.getUUID());
    }

    private void tick(MinecraftServer server) {
        if (waiting.isEmpty()) {
            return;
        }
        for (var it = waiting.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                apply(player, null, "等了 " + Math.max(1, config.timeoutSeconds) + " 秒没有收到客户端应答");
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外查询（后续功能按能力分叉时用）
    // ------------------------------------------------------------------

    public ClientInfo info(ServerPlayer player) {
        return player == null ? null : known.get(player.getUUID());
    }

    public boolean supports(ServerPlayer player, String cap) {
        ClientInfo info = info(player);
        return info != null && info.has(cap);
    }

    public int knownCount() {
        return known.size();
    }

    public int waitingCount() {
        return waiting.size();
    }

    public Mode mode() {
        return config.modeOf();
    }

    // ------------------------------------------------------------------

    private void apply(ServerPlayer player, ClientInfo info, String why) {
        Action action = decide(config.modeOf(), info != null);
        if (action == Action.ALLOW) {
            return;
        }
        String name = player.getName().getString();
        if (action == Action.KICK) {
            HubSuite.logger().warn("客户端校验未通过，踢出 {}：{}", name, why);
            player.connection.disconnect(Component.literal(message(info, why)));
        } else {
            HubSuite.logger().warn("客户端校验未通过（仅提示，不拦）{}：{}", name, why);
            player.sendSystemMessage(Component.literal(message(info, why)));
        }
    }

    private String message(ClientInfo info, String why) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u00A7c本服务器需要安装 HubSuite 客户端模组。");
        sb.append("\n\u00A77原因：").append(why);
        if (info != null) {
            sb.append("\n\u00A77你的客户端：v").append(info.version())
                    .append("（协议 ").append(info.protocol()).append("）");
        }
        sb.append("\n\u00A77最低要求：v").append(safeMin());
        sb.append("\n\u00A77下载：").append(config.downloadUrl);
        return sb.toString();
    }


    private static boolean isFake(ServerPlayer player) {
        return player instanceof cn.dreamgary.hubsuite.fake.MarkedFakePlayer;
    }

    private String safeMin() {
        return config.minClientVersion == null ? "" : config.minClientVersion.trim();
    }

    private static String serverVersion() {
        return FabricLoader.getInstance().getModContainer(HubSuite.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
