package cn.dreamgary.hubsuite.world;

import net.minecraft.server.level.ServerLevel;

/**
 * 每个维度自己的**地形种子**。
 *
 * <p><b>为什么需要这个东西：</b>原版的地形种子是**全服一份**的 ——
 * {@code ServerLevel.getSeed()} 的实现是
 * {@code server.getWorldGenSettings().options().seed()}，
 * 也就是主世界那个种子。而区块生成器正是在 {@code ChunkMap} 里用
 * {@code ServerLevel.getSeed()} 建 {@code RandomState} 的：
 * <pre>
 *   ChunkMap.&lt;init&gt;:  serverLevel.getSeed()  →  RandomState.create(settings, noises, seed)
 * </pre>
 * 所以**同一个服务器里所有维度都会生成一模一样的地形**（相同的噪声、矿脉、结构）。
 *
 * <p>踩过的坑：配置里的 {@code servers[].seed} 以前只被喂给
 * {@code BiomeManager.obfuscateSeed(...)}，那只影响**群系分布**，对地形毫无作用。
 * 结果就是"生存服和创造服是同一张地图"（用户实测反馈），
 * 而 {@code HubSuiteConfig.normalizeSeeds()} 还一本正经地警告"同种子会生成同一张地图、
 * 已帮你改成不同值"—— 改了也没用，因为那个值压根没进地形管线。
 *
 * <p>这里做一层登记：建维度时把"这个维度该用什么种子"记下来，
 * 由 {@code ServerLevelSeedMixin} 让 {@code getSeed()} 返回它。
 * 整个地形管线（噪声、结构、矿脉、地表规则）都会跟着走各自子服的种子。
 */
public final class LevelSeeds {

    private LevelSeeds() {
    }

    /**
     * 维度 → 种子。用 {@code synchronizedMap} 是因为它在服务端启动（主线程）
     * 与区块生成的 worker 线程之间共享。
     */
    private static final java.util.Map<ServerLevel, Long> SEEDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 登记某个维度的地形种子。 */
    public static void register(ServerLevel level, long seed) {
        if (level != null) {
            SEEDS.put(level, seed);
        }
    }

    /** 取某个维度该用的地形种子；没登记过返回 null（调用方应回落到原版行为）。 */
    public static Long seedOf(ServerLevel level) {
        return level == null ? null : SEEDS.get(level);
    }

    /** 服务端停服时清掉，避免把上一局的维度对象留在这个静态表里。 */
    public static void clear() {
        SEEDS.clear();
    }
}
