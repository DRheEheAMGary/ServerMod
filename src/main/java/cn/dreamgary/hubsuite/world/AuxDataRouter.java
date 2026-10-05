package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成就与统计的**按维度隔离**。
 *
 * <p><b>为什么需要单独做：</b>{@code PlayerListStorageMixin} 只替换了
 * {@code PlayerList.playerIo} 这一个字段（玩家背包/经验/末影箱都在那个 NBT 里），
 * 但原版把成就和统计**另存两份文件**：
 * <pre>
 *   PlayerList.save(player):
 *       playerIo.save(player);                  ← 我们接管了
 *       stats.get(uuid).save();                 ← 全局：world/players/stats/
 *       advancements.get(uuid).save();          ← 全局：world/players/advancements/
 * </pre>
 * 路径来自 {@code MinecraftServer.getWorldPath(...)}，跟玩家所在维度无关。
 * 结果是"只在生存服该拿的成就，在大厅/创造服也会解锁并互相污染"，
 * 统计计数跨所有子服累加 —— 与"每个子服完全独立"的设计承诺不符。
 *
 * <p><b>做法：</b>把这两份数据的目录改成"玩家当前所在维度对应的子服存档"，
 * 并在玩家**切换维度时把缓存对象存盘并移除**，这样下一次
 * {@code getPlayerStats}/{@code getPlayerAdvancements} 会用新维度的文件重建，
 * 而旧维度的进度已经落盘。
 */
public final class AuxDataRouter {

    /** 维度 → 该维度所属的独立存档（用于推算成就/统计目录）。 */
    private static final Map<ResourceKey<Level>, IsolatedSave> SAVE_BY_DIMENSION = new ConcurrentHashMap<>();

    /**
     * 当前线程正在为其解析成就/统计目录的**维度**。
     *
     * <p>原版 {@code locateStatsFile(GameProfile)} 只拿得到 profile、拿不到玩家，
     * 所以在 {@code getPlayerStats(Player)} 入口把这名玩家所在的维度记在这里，
     * 供路径重定向读取。
     *
     * <p><b>为什么记维度而不是 UUID：</b>一开始记的是 UUID，靠
     * {@code playerList.getPlayer(uuid)} 反查维度 —— 但**玩家加入的过程中
     * 还没被放进玩家列表**（{@code placeNewPlayer} 末尾才 add），
     * 于是查不到、重定向返回 null、成就与统计又落回原版的全局路径。
     * 这个 bug 对真实玩家同样成立，只是被"进服后总会被传送一次"掩盖了。
     * 直接带维度就没有这个时序问题。
     */
    private static final ThreadLocal<ResourceKey<Level>> CONTEXT = new ThreadLocal<>();

    private AuxDataRouter() {
    }

    public static void register(ResourceKey<Level> dimension, IsolatedSave save) {
        if (dimension != null && save != null) {
            SAVE_BY_DIMENSION.put(dimension, save);
        }
    }

    public static void clear() {
        SAVE_BY_DIMENSION.clear();
        CONTEXT.remove();
    }

    public static void setContext(ResourceKey<Level> dimension) {
        CONTEXT.set(dimension);
    }

    public static void clearContext() {
        CONTEXT.remove();
    }

    /**
     * 取"当前上下文玩家所在维度"的统计目录。
     *
     * <p>上下文的玩家可能还没落进目标维度（例如正在被传送），
     * 所以优先用玩家**实体当前**所在维度；拿不到就返回 null 让原版处理。
     */
    public static Path statsDirForContext() {
        return subDirForContext("stats");
    }

    /** 同上，成就目录。 */
    public static Path advancementsDirForContext() {
        return subDirForContext("advancements");
    }

    private static Path subDirForContext(String sub) {
        return subDir(CONTEXT.get(), sub);
    }

    /** 统计文件所在目录（当前上下文维度）。返回 null 表示"不改，用原版路径"。 */
    public static Path statsDir(ResourceKey<Level> dimension) {
        return subDir(dimension, "stats");
    }

    /** 成就文件所在目录。 */
    public static Path advancementsDir(ResourceKey<Level> dimension) {
        return subDir(dimension, "advancements");
    }

    /**
     * 取某个维度下 {@code players/<sub>} 目录；该维度没被托管时返回 null。
     *
     * <p>目录结构刻意与各子服存档一致：{@code <存档根>/players/stats/<uuid>.json}。
     */
    private static Path subDir(ResourceKey<Level> dimension, String sub) {
        if (dimension == null) {
            return null;
        }
        IsolatedSave save = SAVE_BY_DIMENSION.get(dimension);
        if (save == null) {
            return null;
        }
        return save.playersDir().resolve(sub);
    }

    /** 玩家当前所在维度对应的存档（没有则返回 null）。 */
    public static IsolatedSave saveFor(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return SAVE_BY_DIMENSION.get(player.level().dimension());
    }

    /**
     * 玩家切换维度时调用：把成就与统计**存盘并从 PlayerList 的缓存里移除**。
     *
     * <p>不移除的话，缓存里还是旧维度那份对象（它的文件路径已经烧死在字段里），
     * 之后无论玩家走到哪，读写都还落在旧子服的目录 —— 隔离就白做了。
     *
     * @return 处理过的条目数（诊断用）
     */
    public static int flushAndEvict(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        int handled = 0;
        try {
            var server = player.level().getServer();
            if (server == null) {
                return 0;
            }
            handled += ((PlayerListAuxAccess) server.getPlayerList())
                    .hubsuite$flushAux(player.getUUID());
        } catch (Throwable t) {
            HubSuite.logger().warn("切换维度时落盘成就/统计失败（玩家 {}）：{}",
                    player.getName().getString(), t.toString());
        }
        return handled;
    }
}
