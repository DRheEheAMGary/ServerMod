package cn.dreamgary.hubsuite.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送门该把玩家送到**哪个**维度。
 *
 * <p><b>为什么需要这个类：</b>原版传送门的目标维度是**写死**的 ——
 * {@code NetherPortalBlock} 跳 {@code Level.NETHER}、{@code EndPortalBlock} 跳
 * {@code Level.END}，而这两个 key 在 {@code MinecraftServer.levels} 这张表里
 * **只能存在一份**。所以"每个子服一个独立下界"不能靠多注册几个
 * {@code minecraft:the_nether} 实现 —— 后来的会把先前的顶掉。
 *
 * <p>做法：给每个子服造 `hubsuite:server_<id>_nether` /
 * `hubsuite:server_<id>_end` 这样的**自有 key** 维度，然后在这里登记
 * "谁 ↔ 谁"的对应关系；传送时由 {@code PortalBlockMixin} 把原版算出来的
 * 目标维度替换成登记的那一个。位置、朝向、传送门创建逻辑全部沿用原版，
 * 我们只改"去哪一界"。
 *
 * <p>双向都要登记：从子服进传送门要去它自己的下界，从那个下界回来要回到
 * 对应子服，而不是回到主世界。
 */
public final class PortalLinks {

    private PortalLinks() {
    }

    /** 子服主维度 → 它的下界维度。 */
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> NETHER_OF =
            new ConcurrentHashMap<>();

    /** 下界维度 → 它归属的子服主维度。 */
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> NETHER_BACK =
            new ConcurrentHashMap<>();

    /** 子服主维度 → 它的末地维度。 */
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> END_OF =
            new ConcurrentHashMap<>();

    /** 末地维度 → 它归属的子服主维度。 */
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> END_BACK =
            new ConcurrentHashMap<>();

    /** 登记"子服主维度 ↔ 它的下界/末地"。两个都可为 null（表示没造）。 */
    public static void register(ResourceKey<Level> subDimension,
                                ResourceKey<Level> nether,
                                ResourceKey<Level> end) {
        if (subDimension == null) {
            return;
        }
        if (nether != null) {
            NETHER_OF.put(subDimension, nether);
            NETHER_BACK.put(nether, subDimension);
        }
        if (end != null) {
            END_OF.put(subDimension, end);
            END_BACK.put(end, subDimension);
        }
    }

    /**
     * 把原版算出的目标维度换成"这个玩家所在子服自己的那一界"。
     *
     * <p>规则：
     * <ul>
     *   <li>目标是下界、且当前维度是某个子服主维度 → 换成那个子服的下界；</li>
     *   <li>目标是下界、且当前维度**本身就是**某个子服的下界 → 说明是原版把
     *       回程也算成下界了，换成它归属的子服主维度；</li>
     *   <li>末地同理；</li>
     *   <li>其余情况（大厅、原版主世界、没登记过的维度）原样返回 —— 不动原版行为。</li>
     * </ul>
     *
     * @param from 玩家当前所在维度
     * @param vanillaTarget 原版算出来的目标维度 key
     * @return 实际应该去的维度 key
     */
    public static ResourceKey<Level> redirect(ResourceKey<Level> from,
                                              ResourceKey<Level> vanillaTarget) {
        if (from == null || vanillaTarget == null) {
            return vanillaTarget;
        }
        if (Level.NETHER.equals(vanillaTarget)) {
            // 从子服主维度进下界
            ResourceKey<Level> own = NETHER_OF.get(from);
            if (own != null) {
                return own;
            }
            // 从子服自己的下界回去 → 回到它归属的子服，而不是主世界
            ResourceKey<Level> back = NETHER_BACK.get(from);
            if (back != null) {
                return back;
            }
        } else if (Level.END.equals(vanillaTarget)) {
            ResourceKey<Level> own = END_OF.get(from);
            if (own != null) {
                return own;
            }
            ResourceKey<Level> back = END_BACK.get(from);
            if (back != null) {
                return back;
            }
        }
        return vanillaTarget;
    }

    /** 取目标维度对应的 {@link ServerLevel}；没有就返回 null（调用方保留原版目标）。 */
    public static ServerLevel levelOf(MinecraftServer server, ResourceKey<Level> dimension) {
        if (server == null || dimension == null) {
            return null;
        }
        return server.getLevel(dimension);
    }

    /** 这个维度是不是某个子服的下界或末地（用于自检/诊断）。 */
    public static boolean isExtraDimension(ResourceKey<Level> dimension) {
        return dimension != null
                && (NETHER_BACK.containsKey(dimension) || END_BACK.containsKey(dimension));
    }

    /** 停服时清掉，别把上一局的维度 key 留着。 */
    public static void clear() {
        NETHER_OF.clear();
        NETHER_BACK.clear();
        END_OF.clear();
        END_BACK.clear();
    }
}
