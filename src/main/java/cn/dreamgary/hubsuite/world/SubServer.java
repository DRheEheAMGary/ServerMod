package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.ConfigManager;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一个子服 = 一个独立存档 + 一个可进入的维度 + 一套独立规则。
 *
 * <p>扩展一个新子服只要在配置里加一条 {@link HubSuiteConfig.SubServerConfig}，
 * 本类不需要修改。
 */
public final class SubServer implements PlayableWorld {

    private final MinecraftServer server;
    private final HubSuiteConfig.SubServerConfig config;
    private final ServerRules rules;

    /** 主维度（玩家默认进这个）。 */
    private final Entry primary;

    /** 全部维度，按"入口 id"索引。主维度的入口 id 就是 {@code "main"}。 */
    private final Map<String, Entry> entries;

    /**
     * 入口解析器：决定某个玩家该进哪个维度。
     *
     * <p>空岛服靠它实现"有岛回岛上、没岛回大厅"。返回 null 表示用主维度。
     */
    private EntryResolver entryResolver;

    /** 一个维度：自己的存档、维度、出生点。 */
    public record Entry(String id,
                        ResourceKey<Level> dimension,
                        ServerLevel level,
                        IsolatedSave save,
                        PlayableWorld.SpawnPoint spawn,
                        String label) {
    }

    /** 决定玩家该进哪个维度。 */
    @FunctionalInterface
    public interface EntryResolver {
        Entry resolve(ServerPlayer player, SubServer sub);
    }

    /**
     * 原版出生点搜索的半径（区块）—— {@code MinecraftServer.setInitialSpawn} 里
     * 是个最多 11×11 = 121 个区块的**同步螺旋**，每个区块都要 {@code getChunk(...).join()}。
     */
    private static final int SPAWN_SEARCH_CHUNK_RADIUS = 5;

    /**
     * 还没算原版出生点的维度：等区块就绪后在 tick 里补算。
     *
     * <p><b>为什么必须延迟：</b>冷存档上那 121 个区块全要现生成，
     * 单次 tick 直接超过 60 秒看门狗上限 —— 服务端被强杀（实测崩溃栈：
     * {@code calculateVanillaSpawn → MinecraftServer.setInitialSpawn
     * → PlayerSpawnFinder.getOverworldRespawnPos → ServerChunkCache.getChunk
     * → BlockableEventLoop.managedBlock}）。
     *
     * <p>这也是"出生点缓存"存在的意义（见 {@link #rememberSpawn}）：缓存命中就跳过整个搜索。
     * 但**缓存只在上次成功启动之后才存在** —— 全新安装 / 清档后的第一次启动必然要算一次，
     * 那时就是上面这条崩溃路径。所以加载阶段改成"区块没就绪就用配置坐标顶着"，
     * 等世界跑起来、区块自然加载好之后再补算并写回缓存。
     */
    private record PendingSpawnFix(MinecraftServer server, ServerLevel level,
                                   HubSuiteConfig.SubServerConfig config,
                                   PlayableWorld.SpawnPoint cachedSpawn) {
    }

    private static final java.util.List<PendingSpawnFix> pendingSpawnFixes =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 登记一次"等区块就绪后修出生点"。同一维度只登记一次。 */
    private static void queuePendingSpawnFix(MinecraftServer server, ServerLevel level,
                                             HubSuiteConfig.SubServerConfig config,
                                             PlayableWorld.SpawnPoint cachedSpawn) {
        for (PendingSpawnFix p : pendingSpawnFixes) {
            if (p.level() == level) {
                return;
            }
        }
        pendingSpawnFixes.add(new PendingSpawnFix(server, level, config, cachedSpawn));
    }

    /**
     * 等区块就绪后修出生点，每个 tick 调一次。
     *
     * <p>两种情况：
     * <ul>
     *   <li>{@code cachedSpawn != null}：缓存坐标复检 —— 落到真实地表并写回缓存
     *       （地图被重置过时缓存会失效）；</li>
     *   <li>{@code cachedSpawn == null}：加载阶段跳过了原版搜索，这里补算。</li>
     * </ul>
     * 两种都会把结果写回配置缓存 —— **下次启动就是缓存命中**，不再走昂贵的搜索。
     */
    public static void tickPendingSpawns() {
        if (pendingSpawnFixes.isEmpty()) {
            return;
        }
        for (PendingSpawnFix pending : new java.util.ArrayList<>(pendingSpawnFixes)) {
            if (!canRunVanillaSpawnSearch(pending.level())) {
                continue;   // 还没就绪，下一 tick 再看
            }
            HubSuiteConfig.SubServerConfig config = pending.config();
            PlayableWorld.SpawnPoint resolved;
            if (pending.cachedSpawn() != null) {
                /*
                 * 缓存复检：就地校验能不能站人，不能就落到地表。
                 *
                 * 注意**必须校验出生点所在的那一格区块也已经就位** ——
                 * 光看"原版搜索区域就绪"不够：sanitizeSpawn 内部遇到区块没加载时
                 * 会直接返回原值（保守设计），于是复检等于没做，玩家照样卡在地里。
                 */
                int sx = (int) Math.floor(pending.cachedSpawn().x());
                int sz = (int) Math.floor(pending.cachedSpawn().z());
                if (!isChunkReady(pending.level(), sx >> 4, sz >> 4)) {
                    continue;   // 出生点所在区块还没就绪，下一 tick 再看
                }
                resolved = sanitizeSpawn(pending.level(), config.id, pending.cachedSpawn());
                // **无条件写回**（以前只在坐标变化时写）：
                // 坐标没变也必须把结果落到配置里，否则每局都要重算一遍，
                // 而且一旦算出来是"埋在地下"就永远修不好。
                rememberSpawnAndApply(pending.level(), config, resolved);
                if (Math.abs(resolved.y() - pending.cachedSpawn().y()) > 0.01
                        || Math.abs(resolved.x() - pending.cachedSpawn().x()) > 0.01
                        || Math.abs(resolved.z() - pending.cachedSpawn().z()) > 0.01) {
                    HubSuite.logger().info("子服 '{}' 的缓存出生点已失效（地图可能被重置过），"
                                    + "从 ({}, {}, {}) 修正到 ({}, {}, {})",
                            config.id, pending.cachedSpawn().x(), pending.cachedSpawn().y(),
                            pending.cachedSpawn().z(), resolved.x(), resolved.y(), resolved.z());
                }
            } else {
                resolved = rawVanillaSpawn(pending.server(), pending.level(), config);
                if (resolved == null) {
                    HubSuite.logger().warn("子服 '{}' 的原版出生点补算失败，下个 tick 再试", config.id);
                    continue;
                }
                if (!hasGroundBelow(pending.level(), resolved.x(), resolved.y(), resolved.z())) {
                    HubSuite.logger().warn("子服 '{}' 的原版出生点 ({}, {}, {}) 下方没有地面，保留配置坐标",
                            config.id, resolved.x(), resolved.y(), resolved.z());
                    pendingSpawnFixes.remove(pending);
                    continue;
                }
                rememberSpawnAndApply(pending.level(), config, resolved);
                HubSuite.logger().info("子服 '{}' 的原版出生点已补算并缓存：({}, {}, {})",
                        config.id, resolved.x(), resolved.y(), resolved.z());
            }
            pendingSpawnFixes.remove(pending);
        }
    }

    /**
     * 原版出生点搜索范围内的区块是否都已到 FULL（只有 FULL 才不会阻塞）。
     *
     * <p>顺便**挂一个加载票据把这片区域催起来** —— 不催的话没人会主动加载它，
     * 条件永远不满足，补算就永远等不到（实测：补算一直没发生，出生点一直是配置坐标）。
     */
    private static boolean canRunVanillaSpawnSearch(ServerLevel level) {
        var chunkSource = level.getChunkSource();
        try {
            chunkSource.addTicketWithRadius(
                    net.minecraft.server.level.TicketType.PLAYER_LOADING,
                    new net.minecraft.world.level.ChunkPos(0, 0), SPAWN_SEARCH_CHUNK_RADIUS);
        } catch (Throwable t) {
            HubSuite.logger().warn("出生点搜索区域加载票据失败：{}", t.toString());
        }
        for (int cx = -SPAWN_SEARCH_CHUNK_RADIUS; cx <= SPAWN_SEARCH_CHUNK_RADIUS; cx++) {
            for (int cz = -SPAWN_SEARCH_CHUNK_RADIUS; cz <= SPAWN_SEARCH_CHUNK_RADIUS; cz++) {
                if (!isChunkReady(level, cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 区块是否已到 **FULL** —— 只有 FULL 才能安全读/写方块而不阻塞主线程。
     *
     * <p><b>别用 {@code hasChunk} 当这个判据：</b>它对"已加载到任意非空阶段"
     * 都返回 true，而 {@code getBlockState} / {@code setBlockAndUpdate} 要的是 FULL，
     * 没到就 {@code getChunk(...).join()} 同步等下去 —— 这些调用都在
     * 启动/每 tick 的路径上，一下就是 60 秒看门狗强杀。
     */
    private static boolean isChunkReady(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    private SubServer(MinecraftServer server,
                      HubSuiteConfig.SubServerConfig config,
                      ServerRules rules,
                      Entry primary,
                      Map<String, Entry> entries) {
        this.server = server;
        this.config = config;
        this.rules = rules;
        this.primary = primary;
        this.entries = entries;
    }

    // ------------------------------------------------------------------
    // 多维度访问
    // ------------------------------------------------------------------

    /** 全部维度（主维度在前）。 */
    public Collection<Entry> entries() {
        return java.util.Collections.unmodifiableCollection(entries.values());
    }

    /** 按入口 id 取维度。 */
    public Optional<Entry> entry(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Entry primaryEntry() {
        return primary;
    }

    /** 注册一个额外维度。 */
    public void addEntry(Entry entry) {
        entries.put(entry.id(), entry);
    }

    /**
     * 把某个维度的出生点换成新值（延迟补算出生点后调用）。
     *
     * <p><b>为什么必须回填：</b>{@code Entry} 是 record，出生点在**启动时**就固化了，
     * 而补算是等区块就绪后才发生的 —— 不回填的话，
     * {@code primary().spawn()} 与玩家实际落地用的出生点会长期是旧值
     * （实测：补算成功但自检仍报"出生点悬空"）。
     */
    public void updateEntrySpawn(ServerLevel level, PlayableWorld.SpawnPoint spawn) {
        for (var e : new java.util.ArrayList<>(entries.entrySet())) {
            if (e.getValue().level() == level) {
                entries.put(e.getKey(), new Entry(e.getValue().id(), e.getValue().dimension(),
                        level, e.getValue().save(), spawn, e.getValue().label()));
                return;
            }
        }
    }

    /** 找出配置对象与给定实例相同的子服（延迟任务靠它定位）。 */
    static SubServer findByConfig(HubSuiteConfig.SubServerConfig config) {
        for (SubServer sub : ALL) {
            if (sub.config == config) {
                return sub;
            }
        }
        return null;
    }

    /**
     * 所有已加载的子服。延迟任务（出生点补算、平台补铺）需要从静态上下文回填 Entry。
     */
    private static final java.util.List<SubServer> ALL =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 供 {@link #load} 登记。 */
    private static void registerInstance(SubServer sub) {
        if (!ALL.contains(sub)) {
            ALL.add(sub);
        }
    }

    public void setEntryResolver(EntryResolver resolver) {
        this.entryResolver = resolver;
    }

    /**
     * 解析某个玩家这次该进哪个维度。
     *
     * <p>解析器没设、或返回 null 时用主维度。
     */
    public Entry entryFor(ServerPlayer player) {
        if (entryResolver != null) {
            try {
                Entry resolved = entryResolver.resolve(player, this);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Throwable t) {
                HubSuite.logger().error("解析子服 '{}' 的入口维度失败，回退到安全入口", id(), t);
            }
        }
        return fallbackEntry();
    }

    /**
     * 解析不出入口时的兜底。
     *
     * <p><b>绝不能退回 primary。</b>多维度子服的主维度是启动时顺手建的，
     * 对空岛服来说它是一个**没有地形的虚空世界** —— 玩家被送进去就是无限下坠。
     * 退回"大厅"（或多维度里的第一个维度）永远是安全的。
     */
    private Entry fallbackEntry() {
        if (!entries.isEmpty()) {
            Entry hub = entries.get("hub");
            if (hub != null) {
                return hub;
            }
            return entries.values().iterator().next();
        }
        return primary;
    }

    /** 这个维度是不是本子服的。 */
    /**
     * 这个维度是否属于本子服。
     *
     * <p><b>必须包含主维度。</b>一开始只遍历 {@code entries}（大厅/岛屿维度），
     * 主维度被漏掉 —— 后果是玩家一旦落进主维度，{@code WorldsManager.worldOf()}
     * 就返回空，于是**子服规则和虚空救援全部跳过**，人会一直往下掉，
     * 看起来就是"卡住了"（用户实测：kill 一下自己就卡住）。
     */
    public boolean owns(ResourceKey<Level> dimension) {
        if (primary.dimension().equals(dimension)) {
            return true;
        }
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    /** 某个维度对应的入口。 */
    public Optional<Entry> entryOf(ResourceKey<Level> dimension) {
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(dimension)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * 决定这个子服"玩家该从哪出生"。
     *
     * <p>规则（按优先级）：
     * <ol>
     *   <li>配置关了 {@code useWorldSpawn} → 用配置坐标（管理员说了算）；</li>
     *   <li>正常地形世界 → 用**原版**算出的出生点（新建时让
     *       {@code setInitialSpawn} 算，已有存档直接读 {@code level.dat}）；</li>
     *   <li>原版给的坐标**下方没有地面**（虚空/超平坦世界会返回主世界的
     *       出生点当默认值，跟本维度无关）→ 退回配置坐标。</li>
     * </ol>
     * 最后无论走哪条路，都会做一次"能不能站人"的校验，避免把玩家埋进方块里。
     */
    private static PlayableWorld.SpawnPoint resolveSpawn(MinecraftServer server, ServerLevel level,
                                                        HubSuiteConfig.SubServerConfig config,
                                                        IsolatedSave save) {
        // 特殊世界：出生点由别的机制负责，配置坐标就是权威值，不做站立校验。
        //
        //   void   → 空岛服。小岛是玩家进服时才生成的，此时中心当然是空气；
        //            而且这是**故意**的：在岛生成之前，玩家必须保持在子服出生点
        //            （也就是子服的原点），这样 IslandManager 分配岛屿时
        //            才能算出正确的位置。
        //   flat   → 超平坦世界，平台本身可能还没生成。
        HubSuiteConfig.WorldKind kind =
                HubSuiteConfig.WorldKind.parse(config.worldKind, HubSuiteConfig.WorldKind.NORMAL);
        boolean specialSpawn = kind == HubSuiteConfig.WorldKind.VOID
                || kind == HubSuiteConfig.WorldKind.FLAT;

        // 1) 管理员显式要求用固定坐标，或特殊世界 → 直接用配置值
        if (!config.useWorldSpawn || specialSpawn) {
            return new PlayableWorld.SpawnPoint(
                    config.spawnX, config.spawnY, config.spawnZ,
                    config.spawnYaw, config.spawnPitch);
        }

        // 2) 先看配置里有没有上次算好的结果（最可靠，不依赖 level.dat）
        //
        //    注意：groundBelowOrNull 返回 null 表示"区块没加载、无法判断"。
        //    这种情况下**要接受缓存**而不是重算 —— 重算会走原版 setInitialSpawn，
        //    那是个最多 121 个区块的同步螺旋（主线程），代价极高。
        //    缓存值是上次区块已加载时算出来的，可信度高于"未知"。
        boolean seedMatches = config.resolvedSpawnSeed != null
                && config.resolvedSpawnSeed == config.seed;
        if (seedMatches
                && config.resolvedSpawnX != null && config.resolvedSpawnY != null
                && config.resolvedSpawnZ != null
                && !Boolean.FALSE.equals(groundBelowOrNull(level,
                        config.resolvedSpawnX, config.resolvedSpawnY, config.resolvedSpawnZ))) {
            PlayableWorld.SpawnPoint cached = sanitizeSpawn(level, config.id,
                    new PlayableWorld.SpawnPoint(config.resolvedSpawnX, config.resolvedSpawnY,
                            config.resolvedSpawnZ, config.spawnYaw, config.spawnPitch));
            HubSuite.logger().info("子服 '{}' 使用缓存的出生点：({}, {}, {})",
                    config.id, cached.x(), cached.y(), cached.z());
            /*
             * 缓存是"上次的世界"算出来的 —— **地图被重置过就会失效**
             * （实测：清档后缓存还留着旧地形的 Y，新地形该处是空气，
             * 自检直接报"出生点悬空，玩家会掉下去摔死"）。
             *
             * 这里登记一次延迟复检：等区块真的加载好之后，把它落到真实地表并写回缓存。
             * 加载阶段不能判 —— 区块没加载时 getBlockState 会同步生成并卡死主线程。
             */
            queuePendingSpawnFix(server, level, config, cached);
            return cached;
        }

        // 3) 没有缓存 → 让原版算一个
        PlayableWorld.SpawnPoint vanilla = calculateVanillaSpawn(server, level, config);

        // 4) 候选点必须是"底下真的有地"才可信，然后写回配置长期保存。
        //    本次刚调过 setInitialSpawn，区块已被它加载，所以这里能拿到确定结果。
        if (vanilla != null && hasGroundBelow(level, vanilla.x(), vanilla.y(), vanilla.z())) {
            PlayableWorld.SpawnPoint finalSpawn = sanitizeSpawn(level, config.id, vanilla);
            rememberSpawnAndApply(level, config, finalSpawn);
            HubSuite.logger().info("子服 '{}' 使用原版出生点：({}, {}, {})",
                    config.id, finalSpawn.x(), finalSpawn.y(), finalSpawn.z());
            return finalSpawn;
        }

        // 5) 退回配置坐标
        if (vanilla != null) {
            HubSuite.logger().info("子服 '{}' 原版出生点 ({}, {}, {}) 下方没有地面，"
                            + "改用配置坐标 ({}, {}, {})",
                    config.id, vanilla.x(), vanilla.y(), vanilla.z(),
                    config.spawnX, config.spawnY, config.spawnZ);
        }
        return sanitizeSpawn(level, config.id, new PlayableWorld.SpawnPoint(
                config.spawnX, config.spawnY, config.spawnZ,
                config.spawnYaw, config.spawnPitch));
    }

    /**
     * 把算好的出生点写回配置，避免每次启动重算（也避免读到 level.dat 里的坏值）。
     *
     * <p>同时：把这个新值**回填到 Entry**（否则 {@code primary().spawn()} 还是启动时那个旧值），
     * 并再排一次延迟复检 —— 缓存刚写下来时世界可能还没加载好，等区块就绪后
     * 校验它到底能不能站人（地图被重置过时缓存会失效）。
     */
    private static void rememberSpawn(HubSuiteConfig.SubServerConfig config,
                                      PlayableWorld.SpawnPoint spawn) {
        config.resolvedSpawnX = spawn.x();
        config.resolvedSpawnY = spawn.y();
        config.resolvedSpawnZ = spawn.z();
        config.resolvedSpawnSeed = config.seed;
        try {
            ConfigManager manager = HubSuite.configManager();
            if (manager != null) {
                manager.save();
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("保存出生点缓存失败：{}", t.toString());
        }
    }

    /** 写回缓存，并把这个值**回填到 Entry、世界边界与重生点**（本次运行立刻生效）。 */
    private static void rememberSpawnAndApply(ServerLevel level,
                                              HubSuiteConfig.SubServerConfig config,
                                              PlayableWorld.SpawnPoint spawn) {
        rememberSpawn(config, spawn);
        SubServer owner = findByConfig(config);
        if (owner == null) {
            return;
        }
        owner.updateEntrySpawn(level, spawn);
        /*
         * 边界圆心跟着出生点走 —— 启动时是用**当时的**出生点设的，
         * 补算改了出生点就必须重设。不重设的话边界圆心会停在旧坐标上，
         * 自检"边界圆心跟着算出来的出生点"那条会红（实测就是这个）。
         * 用 owner.rules（该子服自己的规则）取半径，语义与启动时一致。
         */
        try {
            applySpawn(level, spawn);
            applyWorldBorder(level, owner.rules, spawn);
        } catch (Throwable t) {
            HubSuite.logger().warn("出生点补算后重新套用边界/重生点失败：{}", t.toString());
        }
    }

    /** 按配置创建/载入一个子服。 */
    public static SubServer load(MinecraftServer server, HubSuiteConfig.SubServerConfig config) throws IOException {
        GameType gameType = parseGameType(config.gameMode, GameType.SURVIVAL);
        Difficulty difficulty = parseDifficulty(config.difficulty, Difficulty.NORMAL);

        IsolatedSave save = IsolatedSave.open(
                server,
                "hubsuite_" + config.id,
                config.displayName,
                gameType,
                difficulty,
                true);
        // 从这里往后任何一步失败，都要把 save 关掉：
        // 调用方是 catch 后 continue，不会替我们清理，泄漏的就是 session.lock。
        try {
            return loadWithSave(server, config, gameType, difficulty, save);
        } catch (Throwable t) {
            save.close();
            throw t;
        }
    }

    private static SubServer loadWithSave(MinecraftServer server,
                                         HubSuiteConfig.SubServerConfig config,
                                         GameType gameType,
                                         Difficulty difficulty,
                                         IsolatedSave save) throws IOException {
        ServerRules rules = new ServerRules(config.id, config.behaviour);
        rules.initialize(config.gameRules);

        HubSuiteConfig.WorldKind kind = HubSuiteConfig.WorldKind.parse(config.worldKind, HubSuiteConfig.WorldKind.NORMAL);
        LevelStem stem = WorldFactory.createStem(server, "minecraft:overworld", kind, config.flatLayers);
        ResourceKey<Level> dimension = WorldBuilder.dimensionKey("server_" + config.id);

        ServerLevel level = WorldBuilder.create(server, save, dimension, stem, config.seed, true);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());

        PlayableWorld.SpawnPoint spawn = resolveSpawn(server, level, config, save);

        applySpawn(level, spawn);
        applyWorldBorder(level, rules, spawn);

        save.register(level);
        save.bindPlayerStorage(dimension);
        RulesManager.register(dimension, rules);

        Entry primary = new Entry("main", dimension, level, save, spawn, config.displayName);
        Map<String, Entry> entries = new LinkedHashMap<>();
        entries.put(primary.id(), primary);
        SubServer sub = new SubServer(server, config, rules, primary, entries);
        registerInstance(sub);   // 延迟任务（出生点补算）要能从静态上下文回填 Entry
        sub.createOwnNetherAndEnd();
        return sub;
    }

    /**
     * 给这个子服造**它自己的**下界与末地。
     *
     * <p><b>为什么要各自一份：</b>下界/末地是玩家能拿资源、也能互相串门的地方。
     * 共用一个下界等于把三个子服连通了 —— 生存服挖的下界通道，创造服能直接走过去，
     * 这跟"每个子服完全独立"的承诺直接冲突。
     *
     * <p><b>为什么不能直接用 {@code minecraft:the_nether}：</b>
     * {@code MinecraftServer.levels} 按维度 key 唯一，三个子服各注册一个
     * {@code minecraft:the_nether} 会互相顶掉。所以用自己的 key
     * （{@code hubsuite:server_<id>_nether} / {@code _end}），
     * 再由 {@link PortalLinks} + {@code PortalBlockDestinationMixin} 负责传送时改道。
     *
     * <p>方块生成器直接用**原版那一份**（见 {@link WorldFactory#vanillaStem}），
     * 所以地形、结构、刷怪、光照都与原版下界/末地一致。
     *
     * <p>只给普通地形（normal）的子服造：虚空/超平坦是功能性维度（大厅、空岛），
     * 给它们配下界没有意义，只会白占内存和启动时间。
     */
    private void createOwnNetherAndEnd() {
        if (!"normal".equalsIgnoreCase(config.worldKind)) {
            return;
        }
        ResourceKey<Level> nether = null;
        ResourceKey<Level> end = null;
        if (config.ownNether) {
            nether = createVanillaSideDimension("nether", "下界", true,
                    config.seed ^ 0x4E45544845524CL);
        }
        if (config.ownEnd) {
            end = createVanillaSideDimension("end", "末地", false,
                    config.seed ^ 0x454E4421212121L);
        }
        if (nether != null || end != null) {
            // 正反两个方向都要登记：从子服过去、以及从那边回来
            PortalLinks.register(primary.dimension(), nether, end);
            HubSuite.logger().info("子服 '{}' 的独立维度已就绪：下界={} 末地={}",
                    config.id,
                    nether == null ? "未启用" : nether.identifier().toString(),
                    end == null ? "未启用" : end.identifier().toString());
        }
    }

    /**
     * 造一个"原版风格"的侧维度（下界或末地）。
     *
     * @param suffix 维度 key 后缀，{@code nether} / {@code end}
     * @param seed   这个维度自己的地形种子（由子服种子派生，保证每次启动一致）
     * @return 维度 key；失败返回 null（调用方跳过，不影响子服本身）
     */
    private ResourceKey<Level> createVanillaSideDimension(String suffix, String label,
                                                          boolean nether, long seed) {
        String saveName = "hubsuite_" + config.id + "_" + suffix;
        try {
            LevelStem stem = WorldFactory.vanillaStem(server, nether);
            if (stem == null) {
                return null;   // 构造失败，WorldFactory 已经打过日志
            }
            IsolatedSave sideSave = IsolatedSave.open(server, saveName,
                    config.displayName + " §7" + label, GameType.SURVIVAL,
                    Difficulty.NORMAL, false);
            ResourceKey<Level> dimension = WorldBuilder.dimensionKey(config.id + "_" + suffix);
            ServerLevel level = WorldBuilder.create(server, sideSave, dimension, stem, seed, true);
            level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());

            /*
             * 出生点：交给原版算。
             *
             * 下界出生点原版是拿主世界出生点 /8 得出来的，我们的下界和子服主维度
             * 同样是 1:8 关系，所以直接按同一个坐标换算即可。
             * 末地则固定落在 (100, 50, 0) 的黑曜石平台上 —— 原版
             * ServerLevel 构造时就会铺那块平台，这里只要把重生点指过去。
             */
            int sx = (int) Math.floor(primary.spawn().x() / 8.0);
            int sz = (int) Math.floor(primary.spawn().z() / 8.0);
            int sy = nether ? 64 : 50;
            if (!nether) {
                sx = 100;
                sz = 0;
            }
            PlayableWorld.SpawnPoint sideSpawn =
                    new PlayableWorld.SpawnPoint(sx + 0.5, sy, sz + 0.5, 0.0F, 0.0F);
            applySpawn(level, sideSpawn);
            applyWorldBorder(level, rules, sideSpawn);

            sideSave.register(level);
            RulesManager.register(dimension, rules);
            entries.put(suffix, new Entry(suffix, dimension, level, sideSave, sideSpawn, label));
            return dimension;
        } catch (Throwable t) {
            HubSuite.logger().error("子服 '{}' 的{}维度创建失败，将退回原版共用维度",
                    config.id, label, t);
            return null;
        }
    }

    /**
     * 给这个子服再加一个维度（用于"一个子服多个世界"，例如空岛服的大厅/经典/海岛）。
     *
     * <p>每个维度都是**独立存档**：{@code PlayerDataStorage} 是按存档分目录的，
     * 共用一份存档的话三个维度的玩家数据会互相覆盖（背包、经验都会串）。
     *
     * @param entryId   入口 id，后续用 {@link #entry(String)} 取
     * @param worldKind 世界类型（void / flat / normal）
     * @param label     显示名（调试与界面用）
     */
    public Entry addDimension(String entryId,
                              HubSuiteConfig.WorldKind worldKind,
                              List<String> flatLayers,
                              String label) throws IOException {
        return addDimension(entryId, worldKind, flatLayers, label, null);
    }

    /**
     * 同上，但可以指定自定义生成器（例如"一片自然海洋"）。
     *
     * @param customGenerator 传 null 表示按 {@code worldKind} 用标准生成器
     */
    public Entry addDimension(String entryId,
                              HubSuiteConfig.WorldKind worldKind,
                              List<String> flatLayers,
                              String label,
                              net.minecraft.world.level.chunk.ChunkGenerator customGenerator) throws IOException {
        String saveName = "hubsuite_" + config.id + "_" + entryId;
        GameType gameType = parseGameType(config.gameMode, GameType.SURVIVAL);
        Difficulty difficulty = parseDifficulty(config.difficulty, Difficulty.NORMAL);

        IsolatedSave save = IsolatedSave.open(
                server, saveName, label, gameType, difficulty, true);

        LevelStem stem = customGenerator != null
                ? new LevelStem(WorldFactory.dimensionType(server, "minecraft:overworld"), customGenerator)
                : WorldFactory.createStem(server, "minecraft:overworld", worldKind, flatLayers);
        ResourceKey<Level> dimension = WorldBuilder.dimensionKey(config.id + "_" + entryId);

        ServerLevel level = WorldBuilder.create(server, save, dimension, stem, config.seed, true);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());

        // 出生点：
        //   虚空 / 超平坦 → 配置坐标
        //   自定义生成器（海洋）→ 海上找一个开阔海域，再铺个小平台
        //   普通地形 → 让原版算地表
        PlayableWorld.SpawnPoint spawn;
        if (customGenerator != null) {
            var oceanSpot = WorldFactory.findOceanSpot(level, level.getSeaLevel());
            if (oceanSpot != null) {
                spawn = new PlayableWorld.SpawnPoint(
                        oceanSpot.getX() + 0.5, oceanSpot.getY(), oceanSpot.getZ() + 0.5, 0.0F, 0.0F);
                // 海上没有落脚点，铺一块小沙洲；玩家一进来就面朝大海。
                //
                // 注意：此刻区块**大概率还没加载**，而 setBlockAndUpdate 会静默失败
                // （往未加载区块写方块要么无效、要么阻塞主线程）。所以这里先试一次，
                // 没铺成的话登记到"待铺"列表，由 WorldsManager 的 tick 重试。
                if (!buildOceanPlatform(level, spawn)) {
                    queueSpawnPlatform(level, spawn, 4, entryId,
                            net.minecraft.world.level.block.Blocks.SAND);
                    HubSuite.logger().info("海洋维度 '{}' 的出生平台等区块加载后补铺", entryId);
                }
            } else {
                spawn = new PlayableWorld.SpawnPoint(config.spawnX, config.spawnY, config.spawnZ,
                        config.spawnYaw, config.spawnPitch);
            }
        } else if (worldKind == HubSuiteConfig.WorldKind.NORMAL) {
            PlayableWorld.SpawnPoint vanilla = calculateVanillaSpawn(server, level, config);
            spawn = vanilla != null && hasGroundBelow(level, vanilla.x(), vanilla.y(), vanilla.z())
                    ? sanitizeSpawn(level, config.id + "/" + entryId, vanilla)
                    : new PlayableWorld.SpawnPoint(config.spawnX, config.spawnY, config.spawnZ,
                            config.spawnYaw, config.spawnPitch);
        } else {
            spawn = new PlayableWorld.SpawnPoint(config.spawnX, config.spawnY, config.spawnZ,
                    config.spawnYaw, config.spawnPitch);
        }

        applySpawn(level, spawn);
        applyWorldBorder(level, rules, spawn);
        save.register(level);
        save.bindPlayerStorage(dimension);
        RulesManager.register(dimension, rules);

        Entry entry = new Entry(entryId, dimension, level, save, spawn, label);
        entries.put(entryId, entry);
        HubSuite.logger().info("子服 '{}' 新增维度 '{}'：{}（存档 {}）",
                config.id, entryId, dimension.identifier(), saveName);
        return entry;
    }

    /** 还没铺成的出生平台（等区块加载）。 */
    private record PendingPlatform(ServerLevel level, PlayableWorld.SpawnPoint spawn,
                                   String label, int radius,
                                   net.minecraft.world.level.block.Block block) {
    }

    private static final java.util.List<PendingPlatform> pendingPlatforms =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 登记一个"等区块就绪再铺"的出生平台。
     *
     * <p><b>踩过的坑：</b>这个方法以前没有任何调用点 —— 各处都是"试一次，
     * 失败就只打一行日志"，于是**待铺队列永远是空的**，{@link #tickPendingPlatforms}
     * 每 tick 直接 return。表现就是"新存档大厅不生成平台，玩家进服掉虚空"。
     *
     * @param block 平台方块。传 {@code Blocks.STONE} 表示"装饰版"（石英地面 +
     *              海晶灯 + 隐形屏障护栏），与 {@link SpawnPlatform#build} 的语义一致；
     *              传别的方块（例如沙子）则是朴素版、不加护栏。
     */
    public static void queueSpawnPlatform(ServerLevel level, PlayableWorld.SpawnPoint spawn,
                                          int radius, String label,
                                          net.minecraft.world.level.block.Block block) {
        for (PendingPlatform p : pendingPlatforms) {
            if (p.level() == level) {
                return;   // 同一维度只登记一次
            }
        }
        pendingPlatforms.add(new PendingPlatform(level, spawn, label, radius, block));
    }

    /** 装饰版平台的快捷登记（大厅用）。 */
    public static void queueSpawnPlatform(ServerLevel level, PlayableWorld.SpawnPoint spawn,
                                          int radius, String label) {
        queueSpawnPlatform(level, spawn, radius, label,
                net.minecraft.world.level.block.Blocks.STONE);
    }

    /**
     * 给"海上出生点"铺一小块沙洲。
     *
     * @return true 表示铺成功；false 表示区块没加载，需要稍后重试
     */
    private static boolean buildOceanPlatform(ServerLevel level, PlayableWorld.SpawnPoint spawn) {
        int cx = ((int) Math.floor(spawn.x())) >> 4;
        int cz = ((int) Math.floor(spawn.z())) >> 4;
        if (!isChunkReady(level, cx, cz)) {
            return false;
        }
        int blocks = SpawnPlatform.build(level, spawn, 4, net.minecraft.world.level.block.Blocks.SAND);
        if (blocks == SpawnPlatform.CHUNKS_NOT_READY) {
            return false;   // 区块没就位，下一 tick 再来（别在这里同步生成）
        }
        // SpawnPlatform 内部已按 debug 记录，这里不再重复打一条
        return true;
    }

    /**
     * 由 WorldsManager 每个 tick 调用：把还没铺成的出生平台补上。
     *
     * <p>区块加载是异步的，所以启动时铺不成很正常，这里等它就绪。
     */
    public static void tickPendingPlatforms() {
        if (pendingPlatforms.isEmpty()) {
            return;
        }
        for (PendingPlatform pending : new java.util.ArrayList<>(pendingPlatforms)) {
            if (buildPendingPlatform(pending)) {
                pendingPlatforms.remove(pending);
            }
        }
    }

    /** 补铺一个待铺平台；true 表示已完成（不必再重试）。 */
    private static boolean buildPendingPlatform(PendingPlatform pending) {
        int blocks = SpawnPlatform.build(pending.level(), pending.spawn(), pending.radius(),
                pending.block());
        if (blocks == SpawnPlatform.CHUNKS_NOT_READY) {
            return false;   // 区块还没就位，下一 tick 再来
        }
        HubSuite.logger().info("出生平台已补铺：维度 '{}'，{} 个方块", pending.label(), blocks);
        return true;
    }

    /**
     * 让**原版**为这个自定义维度算出地表出生点，并原样采用它的结果。
     *
     * <p>原版只在创建主世界时调用 {@code MinecraftServer.setInitialSpawn}，
     * 自定义维度必须自己调一次。它内部会用 {@code PlayerSpawnFinder} 找一个
     * "实体地面 + 上方无遮挡"的位置 —— 这正是我们想要的，不需要自己再扫一遍。
     *
     * <p>（早期版本我自己写了一套"往下找第一块实心方块"，结果找到洞穴顶或
     * 被树覆盖的地面，把玩家放进地里。已改为完全信任原版。）
     *
     * @return 原版算出的出生点；失败时返回 null
     */
    private static PlayableWorld.SpawnPoint calculateVanillaSpawn(MinecraftServer server, ServerLevel level,
                                                                 HubSuiteConfig.SubServerConfig config) {
        /*
         * 先确认"现在调它不会把主线程卡死"。
         *
         * setInitialSpawn 内部是个同步螺旋，最多 121 个区块，每个都 getChunk(...).join()。
         * 区块没就绪时这一步在冷存档上要几十秒 → 看门狗强杀。
         * 这时**先不调**，用配置坐标顶着，并登记"等区块就绪后补算"。
         */
        if (!canRunVanillaSpawnSearch(level)) {
            HubSuite.logger().info("子服 '{}' 的出生点搜索区域尚未加载，本次先用配置坐标，"
                    + "等世界跑起来后补算并缓存（避免主线程被 121 区块的同步搜索卡死）", config.id);
            queuePendingSpawnFix(server, level, config, null);
            return null;
        }
        return rawVanillaSpawn(server, level, config);
    }

    /** 真正去调原版那句（调用方必须先确认区块已就绪）。 */
    private static PlayableWorld.SpawnPoint rawVanillaSpawn(MinecraftServer server, ServerLevel level,
                                                            HubSuiteConfig.SubServerConfig config) {
        try {
            net.minecraft.world.level.storage.ServerLevelData data =
                    (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
            cn.dreamgary.hubsuite.mixin.MinecraftServerLevelsAccessor.hubsuite$setInitialSpawn(
                    level, data,
                    false,                 // 不要奖励箱
                    false,                 // 非调试世界
                    server.getLevelLoadListener());
            data.setInitialized(true);

            var respawn = data.getRespawnData();
            if (respawn != null && respawn.pos() != null) {
                var pos = respawn.pos();
                HubSuite.logger().info("子服 '{}' 由原版算出出生点：({}, {}, {})",
                        config.id, pos.getX(), pos.getY(), pos.getZ());
                return new PlayableWorld.SpawnPoint(
                        pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                        respawn.yaw(), respawn.pitch());
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("原版出生点计算失败（子服 '{}'）：{}", config.id, t.toString());
        }
        return null;
    }

    /**
     * 把出生点修正到"真正能站人的地表"。
     *
     * <p>不再盲目相信任何来源的 Y 值 —— 实测出现过原版/存档给出 {@code Y=100}
     * 而实际地表在 {@code Y=69}，玩家一进去就卡在地里。
     *
     * <p>搜索策略是**就近**：先在原坐标上下 24 格内找。
     * 不要一路向下扫 —— 那会钻到地下几十格（实测把 Y=83 一路降到 Y=54 的砂砾层）。
     */
    private static PlayableWorld.SpawnPoint sanitizeSpawn(ServerLevel level, String id,
                                                          PlayableWorld.SpawnPoint spawn) {
        try {
            int x = (int) Math.floor(spawn.x());
            int z = (int) Math.floor(spawn.z());
            int y = (int) Math.floor(spawn.y());

            // 区块没加载就别判断 —— getBlockState 会同步生成区块并阻塞主线程。
            // 缓存/原版给的坐标本来就来自"区块已加载时"的计算，
            // 这里保守地原样返回，等玩家真落地时区块自然会被加载。
            if (!isChunkReady(level, x >> 4, z >> 4)) {
                HubSuite.logger().debug(
                        "子服 '{}' 出生点校验跳过（区块未加载）：({}, {}, {})", id, x, y, z);
                return spawn;
            }

            if (isStandable(level, x, y, z)) {
                return spawn;
            }

            for (int offset = 1; offset <= 24; offset++) {
                int down = y - offset;
                if (down >= level.getMinY() + 1 && isStandable(level, x, down, z)) {
                    HubSuite.logger().info("子服 '{}' 出生点 Y={} 悬空，就近下落到 Y={}",
                            id, y, down);
                    return new PlayableWorld.SpawnPoint(
                            x + 0.5, down, z + 0.5, spawn.yaw(), spawn.pitch());
                }
                int up = y + offset;
                if (up <= level.getMaxY() && isStandable(level, x, up, z)) {
                    HubSuite.logger().info("子服 '{}' 出生点 Y={} 无法站立，就近抬升到 Y={}",
                            id, y, up);
                    return new PlayableWorld.SpawnPoint(
                            x + 0.5, up, z + 0.5, spawn.yaw(), spawn.pitch());
                }
            }
            /*
             * 就近 24 格没找到 → 再**一路向上扫到世界顶层**。
             *
             * 为什么必须有这一步：配置里的 spawnY 是"写死的兜底值"（默认 64），
             * 而正常地形世界的地表通常在它之上（实测 70）。被埋在这一列实心方块里时，
             * 向下找不到、向上又超出 24 格窗口，就会走到"沿用原值"——
             * 玩家一进服**卡在地里**（用户实测反馈："生存/创造服又卡在地里了"）。
             *
             * 只向上、不向下：向上一定能到地表，向下会钻进矿洞（早期版本就是这么错的）。
             * 这一列所在区块此时已确认加载，读方块不会触发同步生成。
             */
            for (int up = y + 1; up <= level.getMaxY(); up++) {
                if (isStandable(level, x, up, z)) {
                    HubSuite.logger().info("子服 '{}' 出生点 Y={} 被埋在地下，"
                            + "向上找到地表 Y={}（配置坐标只是兜底值，地表在它之上）", id, y, up);
                    return new PlayableWorld.SpawnPoint(
                            x + 0.5, up, z + 0.5, spawn.yaw(), spawn.pitch());
                }
            }
            HubSuite.logger().warn("子服 '{}' 在 ({}, {}) 这一列从 Y={} 到顶层都站不住，"
                    + "沿用原值 Y={}（请检查世界生成或坐标配置）", id, x, z, y, y);
        } catch (Throwable t) {
            HubSuite.logger().warn("修正子服 '{}' 出生点失败：{}", id, t.toString());
        }
        return spawn;
    }

    /**
     * 某个坐标下方有没有地面（最多向下找 64 格）。
     *
     * <p>用于判断"能不能信任这个出生点" —— 虚空世界里这一列全是空气，直接返回 false。
     */
    private static boolean hasGroundBelow(ServerLevel level, double x, double y, double z) {
        Boolean known = groundBelowOrNull(level, x, y, z);
        return known != null && known;
    }

    /**
     * 某个坐标下方有没有地面。
     *
     * @return {@code TRUE}/{@code FALSE}；{@code null} 表示**区块还没加载、
     *         无法判断**（此时绝不下结论，也绝不主动生成区块）
     *
     * <p><b>为什么不能直接 getBlockState：</b>它在未加载区块上会走
     * {@code getChunk(x, z, FULL, true)}，主线程原地等整片区块生成完
     * （{@code ServerChunkCache} 里的 {@code managedBlock}）。
     * 正常地形一个区块就够慢，攒够 60 秒就被看门狗强杀 —— 本项目踩过四次。
     * {@code hasChunk()} 只查已加载的 ChunkHolder，是安全的探测方式。
     */
    private static Boolean groundBelowOrNull(ServerLevel level, double x, double y, double z) {
        try {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int by = (int) Math.floor(y);
            if (!isChunkReady(level, bx >> 4, bz >> 4)) {
                return null;   // 未知：不加载、不下结论
            }
            for (int probe = by; probe >= by - 64 && probe >= level.getMinY(); probe--) {
                var pos = new net.minecraft.core.BlockPos(bx, probe, bz);
                if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            HubSuite.logger().warn("检查出生点下方地面时出错（按未知处理）", t);
            return null;
        }
    }

    /** 判断某个位置能不能站人：脚下实心、本体与头顶可穿过。 */
    private static boolean isStandable(ServerLevel level, int x, int y, int z) {
        var feet = new net.minecraft.core.BlockPos(x, y, z);
        var groundState = level.getBlockState(feet.below());
        if (groundState.isAir() || !groundState.isSolidRender()) {
            return false;
        }
        return isPassable(level, feet) && isPassable(level, feet.above());
    }

    /** 玩家能不能站在这个方块里：没有碰撞体积即可（空气、草、流体都算）。 */
    private static boolean isPassable(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static void applySpawn(ServerLevel level, PlayableWorld.SpawnPoint spawn) {
        try {
            level.setRespawnData(LevelData.RespawnData.of(
                    level.dimension(),
                    BlockPos.containing(spawn.x(), spawn.y(), spawn.z()),
                    spawn.yaw(),
                    spawn.pitch()));
        } catch (Exception e) {
            HubSuite.logger().warn("设置子服出生点失败：{}", e.toString());
        }
    }

    /**
     * 按规则设置世界边界。
     *
     * <p><b>圆心必须用"算出来的出生点"，不能用配置坐标。</b>
     * {@code config.spawnX/spawnZ} 只是配置里的**默认/兜底**值 ——
     * 正常地形世界（{@code useWorldSpawn=true}）的实际出生点是原版
     * {@code setInitialSpawn} 算出来的，通常不在原点。用配置坐标当圆心，
     * 就会得到一个"圆心与实际出生点不一致"的边界：半径小的时候
     * （例如创造服 2000），玩家一出生就可能已经在边界外或贴着边界。
     */
    private static void applyWorldBorder(ServerLevel level, ServerRules rules,
                                         PlayableWorld.SpawnPoint spawn) {
        if (rules.worldBorderRadius() <= 0) {
            return;
        }
        var border = level.getWorldBorder();
        border.setCenter(spawn.x(), spawn.z());
        border.setSize(rules.worldBorderRadius() * 2.0);
    }

    private static GameType parseGameType(String raw, GameType fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "survival" -> GameType.SURVIVAL;
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> fallback;
        };
    }

    private static Difficulty parseDifficulty(String raw, Difficulty fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            case "hard" -> Difficulty.HARD;
            default -> fallback;
        };
    }

    // ------------------------------------------------------------------
    // PlayableWorld
    // ------------------------------------------------------------------

    @Override
    public String id() {
        return config.id;
    }

    @Override
    public String displayName() {
        return config.displayName;
    }

    @Override
    public ServerLevel level() {
        return primary.level();
    }

    @Override
    public ServerRules rules() {
        return rules;
    }

    @Override
    public IsolatedSave save() {
        return primary.save();
    }

    @Override
    public PlayableWorld.SpawnPoint spawn() {
        return primary.spawn();
    }

    @Override
    public String description() {
        return "&7类型 &f" + config.worldKind + " &7| 模式 &f" + config.gameMode + " &7| 难度 &f" + config.difficulty;
    }

    // ------------------------------------------------------------------
    // 附加信息
    // ------------------------------------------------------------------

    public HubSuiteConfig.SubServerConfig config() {
        return config;
    }

    public List<ServerLevel> levels() {
        List<ServerLevel> all = new java.util.ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            all.add(entry.level());
        }
        return all;
    }

    /** 该子服当前的在线人数（跨它全部维度）。 */
    public int playerCount() {
        int total = 0;
        for (Entry entry : entries.values()) {
            total += entry.level().players().size();
        }
        return total;
    }

    public void save(boolean flush) {
        for (Entry entry : entries.values()) {
            entry.save().save(List.of(entry.level()), flush);
        }
    }

    public void close() {
        for (Entry entry : entries.values()) {
            RulesManager.unregister(entry.dimension());
            ServerLevelsAccess.remove(server, entry.dimension());
            entry.save().close();
        }
    }
}
