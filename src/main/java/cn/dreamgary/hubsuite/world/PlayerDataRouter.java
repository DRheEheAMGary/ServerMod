package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.PlayerDataStorage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据的按子服分发。
 *
 * <p><b>为什么需要它：</b>原版的玩家数据读写用的是 {@code PlayerList.playerIo}
 * —— 一个全局唯一的对象，目录固定在主世界存档的 {@code players/data/} 下。
 * 也就是说"玩家数据天生是全局的"，光给每个子服建独立存档目录并不能分开背包。
 * 这一点是被内置自检实测抓出来的（文件确实写到了 {@code world/players/data/}）。
 *
 * <p><b>做法：</b>
 * <ol>
 *   <li>为每个子服/大厅的存档各建一个 {@link PlayerDataStorage}（目录指向各自存档）；</li>
 *   <li>{@code PlayerListStorageMixin} 在读写玩家数据的那一刻，把原版的存储对象
 *       换成"当前玩家所属子服"的那一份；</li>
 *   <li>"当前玩家是谁"由 {@code PlayerList.save} / {@code loadPlayerData} 的
 *       进入/退出钩子用线程栈维护，嵌套调用也安全。</li>
 * </ol>
 *
 * <p>因为读档（{@code placeNewPlayer}）发生在玩家进入任何世界之前，
 * 所以还需要 {@link #PENDING_STORAGE}：传送/建号之前先把目标存档登记进去。
 * 服务端每 tick 会依据在线玩家的真实维度刷新它，因此正常游玩时始终是对的。
 */
public final class PlayerDataRouter {

    /** 维度 → 该维度使用的玩家数据存储。 */
    private static final Map<ResourceKey<Level>, PlayerDataStorage> BY_DIMENSION = new ConcurrentHashMap<>();

    /** 存档名 → 存储对象（登记与反查用）。 */
    private static final Map<String, PlayerDataStorage> BY_SAVE = new ConcurrentHashMap<>();

    /** 玩家 UUID → 下一次读档该用哪个存储（读档时玩家还没进世界）。 */
    private static final Map<UUID, PlayerDataStorage> PENDING_STORAGE = new ConcurrentHashMap<>();

    /** 当前线程正在处理的玩家（支持嵌套，用栈）。 */
    private static final ThreadLocal<Deque<ServerPlayer>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    private PlayerDataRouter() {
    }

    // ------------------------------------------------------------------
    // 登记
    // ------------------------------------------------------------------

    /** 登记一个存档的玩家数据存储与它对应的维度。 */
    public static void register(IsolatedSave save, PlayerDataStorage storage, ResourceKey<Level> dimension) {
        if (save == null || storage == null) {
            return;
        }
        BY_SAVE.put(save.saveName(), storage);
        BY_DIMENSION.put(dimension, storage);
        HubSuite.logger().debug("玩家数据目录绑定：{} -> {}", dimension.identifier(), save.playersDir());
    }

    public static PlayerDataStorage storageFor(IsolatedSave save) {
        return save == null ? null : BY_SAVE.get(save.saveName());
    }

    public static PlayerDataStorage storageForDimension(ResourceKey<Level> dimension) {
        return BY_DIMENSION.get(dimension);
    }

    /** 指定某个玩家下次读档用哪个存档（传送/首次生成前调用）。 */
    public static void setPending(UUID uuid, PlayerDataStorage storage) {
        if (uuid == null || storage == null) {
            return;
        }
        PENDING_STORAGE.put(uuid, storage);
    }

    public static void clearPending(UUID uuid) {
        PENDING_STORAGE.remove(uuid);
    }

    /**
     * 玩家退出时清掉他的待读档登记。
     *
     * <p>不清的话，下次登录会按**上一次会话**记下的目标去读档 ——
     * 那个目标可能指向别的子服，于是背包对不上。
     */
    public static void forget(UUID uuid) {
        if (uuid != null) {
            PENDING_STORAGE.remove(uuid);
        }
    }

    public static void clear() {
        SERVER = null;
        BY_DIMENSION.clear();
        BY_SAVE.clear();
        PENDING_STORAGE.clear();
        // 注意：DEFAULT_FALLBACK 也要清。它是"第一个登记的存档"，
        // 服务端关闭后还留着旧对象的话，下次启动在 bindPlayerStorage 之前
        // 用它去读写，就会落到上一个存档里（而且那个 access 已经 close 了）。
        DEFAULT_FALLBACK = null;
        currentLoadStorage.remove();
        CURRENT.remove();
    }

    // ------------------------------------------------------------------
    // 上下文（由 Mixin 调用）
    // ------------------------------------------------------------------

    /** 进入"正在保存/处理某玩家"的上下文。 */
    public static void enter(ServerPlayer player) {
        CURRENT.get().push(player);
    }

    /** 退出上下文。 */
    public static void exit() {
        Deque<ServerPlayer> stack = CURRENT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * 进入"正在为某个 UUID 读档"的上下文。
     *
     * <p>三类来源按可靠性排序：
     * <ol>
     *   <li>该玩家**当前实际所在维度**（最可靠：切服后维度已经变了，
     *       而 PENDING 可能还留着上一次的目标）；</li>
     *   <li>预先登记的目标存档（玩家还没进任何托管维度时用）；</li>
     *   <li>都没有就不设置 → 交由原版处理。</li>
     * </ol>
     */
    public static void enterLoad(UUID uuid) {
        if (uuid == null) {
            return;
        }
        ServerPlayer online = onlinePlayer(uuid);
        if (online != null) {
            PlayerDataStorage byDimension = BY_DIMENSION.get(online.level().dimension());
            if (byDimension != null) {
                currentLoadStorage.set(byDimension);
                return;
            }
        }
        PlayerDataStorage pending = PENDING_STORAGE.get(uuid);
        if (pending != null) {
            currentLoadStorage.set(pending);
            return;
        }
        // 既不在线、也没有登记过目标 → 用兜底存档。
        //
        // 不设置的话会退回原版的全局 playerIo（也就是**主世界**的 players/data），
        // 而玩家在别处登出时数据是写到对应子服的 —— 两边路径不一致，
        // 跨会话就会出现"背包对不上"。兜底至少保证读和写落在同一个地方。
        if (DEFAULT_FALLBACK != null) {
            currentLoadStorage.set(DEFAULT_FALLBACK);
        }
    }

    private static ServerPlayer onlinePlayer(UUID uuid) {
        MinecraftServer server = SERVER;
        if (server == null) {
            return null;
        }
        // 用 playersByUUID 精确查（不要走 getPlayers()，那个已被假玩家过滤改写）
        return server.getPlayerList().getPlayer(uuid);
    }

    /** 服务端引用。用静态字段而不是 ThreadLocal：读档可能发生在不同线程上。 */
    private static volatile MinecraftServer SERVER;

    /** 由 WorldsManager 在服务端启动时登记。 */
    public static void setServer(MinecraftServer server) {
        SERVER = server;
    }

    public static void exitLoad() {
        currentLoadStorage.remove();
    }

    private static final ThreadLocal<PlayerDataStorage> currentLoadStorage = new ThreadLocal<>();

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 当前线程栈顶玩家（诊断用）。 */
    public static ServerPlayer peekCurrent() {
        return CURRENT.get().peek();
    }

    /** 当前线程栈顶玩家所属的存储；没有上下文返回 null。 */
    public static PlayerDataStorage resolveCurrent() {
        ServerPlayer player = CURRENT.get().peek();
        if (player == null) {
            return null;
        }
        return resolveFor(player);
    }

    /** 读档阶段该用的存储。 */
    public static PlayerDataStorage resolvePending() {
        PlayerDataStorage direct = currentLoadStorage.get();
        if (direct != null) {
            return direct;
        }
        return null;
    }

    /**
     * 某个玩家当前应该用哪个存储。
     *
     * <p>顺序：先看他所在维度；维度未托管（例如还没被送进大厅/子服的原版世界）时，
     * 回落到"这个玩家登记过的存档"，最后才交给原版。
     * 这样大厅 NPC 之类的假玩家不会把数据写进主世界存档。
     */
    public static PlayerDataStorage resolveFor(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        PlayerDataStorage byDimension = BY_DIMENSION.get(player.level().dimension());
        if (byDimension != null) {
            return byDimension;
        }
        PlayerDataStorage pending = PENDING_STORAGE.get(player.getUUID());
        if (pending != null) {
            return pending;
        }
        return DEFAULT_FALLBACK;
    }

    /** 兜底存储：第一个被登记的存档（通常是主子服），用于"还没进任何托管世界"的假玩家。 */
    private static volatile PlayerDataStorage DEFAULT_FALLBACK;

    public static void setDefaultFallback(PlayerDataStorage storage) {
        if (DEFAULT_FALLBACK == null) {
            DEFAULT_FALLBACK = storage;
        }
    }

    /**
     * 每 tick 刷新在线玩家的目标存档，保证读档/保存总是落到正确的子服。
     * 传送是瞬时的，但保存可能发生在 tick 之后，所以这里持续纠正。
     */
    public static void refreshPending(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerDataStorage storage = BY_DIMENSION.get(player.level().dimension());
            if (storage != null) {
                PENDING_STORAGE.put(player.getUUID(), storage);
            }
        }
    }
}
