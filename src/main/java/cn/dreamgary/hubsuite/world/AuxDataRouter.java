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

    /**
     * 把**主世界维度**也绑到某个存档上（通常是大厅）。
     *
     * <p><b>为什么必须绑：</b>玩家登录的那一刻还没被送进大厅/子服，
     * 维度仍是主世界 {@code minecraft:overworld}。而
     * {@code PlayerList.placeNewPlayer} 在**送进目标维度之前**就会调用
     * {@code ServerPlayer.getStats()} —— 这时 {@link #subDirForContext} 查不到
     * 主世界的存档，于是返回 {@code null}、路径回落成原版的
     * {@code world/players/stats/}，而且那个路径**烧死在对象里**；
     * 之后玩家被传送进大厅/子服，命中缓存的还是同一个对象，重定向再也不生效。
     *
     * <p>结果就是用户实测的"服务器之间的成就等内容没有隔离"：
     * 成就与统计一路写在主世界全局目录，各子服互相污染。
     *
     * <p>主世界只认第一个绑上来的存档（大厅最先加载）。
     */
    public static void bindOverworldFallback(IsolatedSave save) {
        if (save == null) {
            return;
        }
        SAVE_BY_DIMENSION.putIfAbsent(Level.OVERWORLD, save);
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

    /**
     * 排查用：把"这次给谁解析成了哪个目录"记一行 **debug** 日志。
     *
     * <p>默认是静音的（logger 配的是 INFO），要用的时候开
     * {@code -Dlog4j.configurationFile=...} 或把 HubSuite 的日志级别调到 DEBUG 即可。
     * 之所以留着它：这条路径的隔离是"构造时定死、错过就再也不生效"，
     * 出问题时必须能一眼看出目录解析成了什么，否则只能靠猜。
     */
    public static void logResolvedDirs(net.minecraft.server.level.ServerPlayer player,
                                       ResourceKey<Level> dimension) {
        if (!HubSuite.logger().isDebugEnabled() || player == null) {
            return;
        }
        Path stats = subDir(dimension, "stats");
        Path adv = subDir(dimension, "advancements");
        HubSuite.logger().debug("成就/统计目录解析：玩家 {} 维度 {} → stats={} advancements={}",
                player.getName() == null ? "?" : player.getName().getString(),
                dimension == null ? "null" : dimension.identifier(),
                stats == null ? "(回落原版全局)" : stats.toString(),
                adv == null ? "(回落原版全局)" : adv.toString());
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
     * 玩家**下线/退出**时调用：把成就与统计存盘并从缓存放掉。
     *
     * <p><b>这一步是隔离能不能成立的关键，之前漏了，导致隔离实际从未生效：</b>
     * <ul>
     *   <li>原版 {@code PlayerList.remove(player)} <b>不清</b> {@code stats} /
     *       {@code advancements} 这两个 Map（字节码里只调了
     *       {@code PlayerAdvancements.stopListening()}）；</li>
     *   <li>而 {@code getPlayerStats}/{@code getPlayerAdvancements} 是
     *       {@code computeIfAbsent} 缓存 —— 对象一建出来就把文件路径
     *       <b>烧死在字段里</b>（{@code playerSavePath}）；</li>
     *   <li>于是"玩家第一次登录时建的那份对象"会一直留着。之后他无论切到哪个子服、
     *       重新登录多少次，成就与统计都继续写回**第一次那个目录**。</li>
     * </ul>
     *
     * <p>用户实测现象就是"服务器之间的成就等内容没有隔离"。
     * 在退出时放掉缓存，下次登录（此时维度上下文是对的）会按正确的子服重建。
     */
    public static void forget(net.minecraft.server.level.ServerPlayer player,
                              net.minecraft.server.MinecraftServer server) {
        if (player == null) {
            return;
        }
        try {
            /*
             * 注意：**不能**用 player.level() 去拿服务器/维度。
             *
             * 这个方法是在 ServerPlayConnectionEvents.DISCONNECT 里调的，
             * 那一刻玩家可能已经脱离世界（level() 为 null 或抛异常）——
             * 第一版就是这么写的，结果 forget() 直接 return、什么都没存，
             * 表现就是用户实测的"退出重进成就就没了"。
             *
             * 所以服务器引用**由调用方传进来**（AuthManager 手里一定有）。
             */
            if (server == null) {
                return;
            }
            ((PlayerListAuxAccess) server.getPlayerList())
                    .hubsuite$flushAux(player.getUUID());
        } catch (Throwable t) {
            HubSuite.logger().warn("退出时落盘成就/统计失败（玩家 {}）：{}",
                    player.getName() == null ? "?" : player.getName().getString(), t.toString());
        }
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
