package cn.dreamgary.hubsuite.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 每个维度自己的**地形种子**。
 *
 * <p><b>为什么需要这个东西：</b>原版的地形种子是**全服一份**的 ——
 * {@code ServerLevel.getSeed()} 的实现是
 * {@code server.getWorldGenSettings().options().seed()}，
 * 也就是主世界那个种子。而区块系统正是在 {@code ChunkMap} 里用它建
 * {@code RandomState} 的：
 * <pre>
 *   ServerLevel.&lt;init&gt; → 建 ChunkMap
 *   ChunkMap.&lt;init&gt;   → ServerLevel.getSeed() → RandomState.create(settings, noises, seed)
 * </pre>
 * 所以同一个服务器里**所有维度都会生成一模一样的地形**（相同的噪声、矿脉、结构）。
 *
 * <p>踩过的坑：配置里的 {@code servers[].seed} 以前只被喂给
 * {@code BiomeManager.obfuscateSeed(...)}，那只影响**群系分布**，对地形毫无作用。
 *
 * <p><b>为什么要分两张表：</b>上面那条调用链的关键是"ChunkMap 在
 * {@code ServerLevel} 构造函数**内部**就取走了种子" —— 那一刻还没有 level 实例，
 * 拿不到实例引用去查表。所以：
 * <ul>
 *   <li>{@link #registerPending} 在构造**之前**按**维度 key** 登记；</li>
 *   <li>{@link #bind} 在构造**之后**迁移到实例键上；</li>
 *   <li>{@link #seedOf} 两个都查（实例优先，其次维度），保证构造期间也能问到正确的值。</li>
 * </ul>
 * 第一版只做了"构造之后按实例登记"，于是 ChunkMap 拿到的仍是全服种子，
 * 各子服照样生成同一张地形（用户复测："种子还是没生效"）。
 */
public final class LevelSeeds {

    private LevelSeeds() {
    }

    /**
     * 维度 key → 种子，**仅在构造期间**有效。
     * 构造结束后由 {@link #clearPending} 清掉，避免下次误用旧值。
     */
    private static final java.util.Map<ResourceKey<Level>, Long> PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 实例 → 种子，构造完成后登记。
     *
     * <p>用 {@code synchronizedMap(WeakHashMap)}：它在服务端线程与区块生成的
     * worker 线程之间共享，且不该把已关闭的维度对象长期拴住。
     */
    private static final java.util.Map<ServerLevel, Long> SEEDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 构造**之前**调用：按维度 key 预登记种子。 */
    public static void registerPending(ResourceKey<Level> dimension, long seed) {
        if (dimension != null) {
            PENDING.put(dimension, seed);
        }
    }

    /** 构造完成后调用：把种子迁到实例键上。 */
    public static void bind(ServerLevel level) {
        if (level == null) {
            return;
        }
        Long seed = PENDING.get(level.dimension());
        if (seed != null) {
            SEEDS.put(level, seed);
        }
    }

    /** 构造结束（无论成败）后清掉预登记项。 */
    public static void clearPending(ResourceKey<Level> dimension) {
        if (dimension != null) {
            PENDING.remove(dimension);
        }
    }

    /**
     * 取某个维度该用的地形种子；没登记过返回 {@code null}（调用方回落到原版行为）。
     *
     * <p>先查实例（正常运行时命中），再查预登记（构造期间命中 —— 这一步不能少，
     * 否则 ChunkMap 拿到的还是全服种子）。
     */
    public static Long seedOf(ServerLevel level) {
        if (level == null) {
            return null;
        }
        Long bound = SEEDS.get(level);
        if (bound != null) {
            return bound;
        }
        return PENDING.get(level.dimension());
    }

    /** 服务端停服时清掉，避免把上一局的维度对象留在这个静态表里。 */
    public static void clear() {
        SEEDS.clear();
        PENDING.clear();
    }
}
