package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * 把玩家送到某个 {@link PlayableWorld}。
 *
 * <p>隔离能成立的关键：每个子服的 {@link ServerLevel} 持有自己的
 * {@code LevelStorageAccess} 与 {@code WorldData}，而玩家数据的读写
 * （{@code PlayerList.loadPlayerData} / {@code PlayerList.save}）
 * 走的正是这两个对象。所以把玩家传送进那个维度，读到的就是那个子服的玩家数据 ——
 * 背包、末影箱、经验、成就、统计一起切过去，不需要额外搬运。
 */
public final class PlayerRouter {

    private PlayerRouter() {
    }

    /** 可选的落点解析器（由 ServerRulesEngine 注入，用于"回到上次位置"）。 */
    @FunctionalInterface
    public interface SpawnResolver {
        /**
         * @param level 这次要进入的**具体维度**（多维度子服必须区分：
         *              空岛服的大厅/经典/海岛场所 id 都是 skyblock，
         *              坐标记忆按维度存，不能再按 id 存）
         */
        PlayableWorld.SpawnPoint resolve(ServerPlayer player, PlayableWorld target,
                                        ServerLevel level);
    }

    /**
     * 落点解析器**链**（后注册的优先）。
     *
     * <p>为什么是链而不是单个：空岛系统与规则引擎各自都要参与解析
     * （前者管"落到自己的岛上"，后者管"回到上次的位置"）。
     * 一开始用的是 {@code setSpawnResolver}（单值覆盖）——
     * 结果空岛系统注册得晚，把规则引擎那个**整个顶掉了**，
     * 于是所有子服的"回到上次位置"都失效（实测发现）。
     *
     * <p>解析顺序：从后往前问，第一个返回非 null 的生效；
     * 全都返回 null 才用场所默认出生点。
     */
    private static final java.util.List<SpawnResolver> spawnResolvers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addSpawnResolver(SpawnResolver resolver) {
        if (resolver != null) {
            spawnResolvers.add(resolver);
        }
    }

    /** @deprecated 用 {@link #addSpawnResolver} —— 覆盖式注册会顶掉别人 */
    @Deprecated
    public static void setSpawnResolver(SpawnResolver resolver) {
        addSpawnResolver(resolver);
    }

    /** 入场监听器：玩家落地后调用（例如空岛服要为首次进入的玩家建岛）。 */
    @FunctionalInterface
    public interface EntryListener {
        /**
         * @param player 刚进场的玩家
         * @param world  目标场所
         * @param level  实际要落地的维度（多维度子服里，这可能不是 world.level()）
         */
        void onEnter(ServerPlayer player, PlayableWorld world, ServerLevel level);
    }

    /** 离场监听器：玩家**即将离开**某个场所时调用（记录位置等）。 */
    @FunctionalInterface
    public interface ExitListener {
        void onExit(ServerPlayer player, PlayableWorld world);
    }

    private static final java.util.List<EntryListener> ENTRY_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<ExitListener> EXIT_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addEntryListener(EntryListener listener) {
        ENTRY_LISTENERS.add(listener);
    }

    public static void addExitListener(ExitListener listener) {
        EXIT_LISTENERS.add(listener);
    }

    /**
     * 传送玩家到一个场所。
     *
     * <p><b>不清空任何玩家状态。</b>背包、经验、血量、属性、成就、统计全部原样保留 ——
     * 每个子服的存档里各自存着一份完整状态，切服时读的是对应那一份，天然互不干扰。
     *
     * <p>（早期版本在回大厅时会清空背包，那是错误的：玩家在生存服辛苦攒的东西
     * 一进大厅就没了。已移除。）
     *
     * @return 是否成功
     */
    public static boolean sendTo(ServerPlayer player, PlayableWorld target) {
        // 多维度子服：先问它"这个玩家该进哪个维度"。
        // 空岛服靠这个实现"有岛回岛上、没岛回大厅"。
        ServerLevel level = target.level();
        if (target instanceof SubServer sub) {
            level = sub.entryFor(player).level();
        }
        if (!level.getServer().levelKeys().contains(level.dimension())) {
            HubSuite.logger().error("目标维度 {} 未注册，无法传送 {}。",
                    level.dimension().identifier(), player.getName().getString());
            return false;
        }

        HubSuite.logger().info("切服请求：{} -> {}", player.getName().getString(), target.id());

        // 离开前先通知监听器记录当前位置（"生存服回到原位"就靠这个）。
        // 注意不能用原版的离开事件：那只在**断开服务器**时触发，切服不触发 ——
        // 这正是原来"生存服不保留位置"的原因。
        PlayableWorld from = worlds().worldOf(player).orElse(null);
        // 判断标准是**维度是否变化**，不是场所 id 是否变化。
        // 空岛服是一个"多维度子服"：hub → classic 的场所 id 都是 "skyblock"，
        // 用 id 比较会跳过暂存，玩家在大厅里捡的东西一到岛上就没了（实测）。
        boolean dimensionChanged = !player.level().dimension().equals(level.dimension());
        if (from != null && dimensionChanged) {
            // 把当前状态（背包/经验/血量）按"离开的场所"暂存起来。
            // 不做这一步的话，玩家会背着自己的东西跨服 —— 实测表现为
            // "生存服攒的物品被带到大厅来了"。
            // 按**维度**暂存，而不是按场所：空岛服有大厅/经典/海岛三个维度，
            // 背包必须各自独立。
            //
            // 注意必须用 player.level()（玩家**真实所在**维度），不能用 from.level()
            // —— 后者对 SubServer 恒返回 primary 维度，玩家在海岛维度时会把背包
            // 存进主维度的槽位，于是切服时背包对不上（实测自检发现）。
            PlayerStateStash.capture(player, player.level().dimension().identifier().toString());

            // 成就与统计也要按维度隔离：离开前把**旧维度**那份存盘并清掉缓存，
            // 否则缓存对象里烧死的文件路径会让读写继续落在旧子服。
            AuxDataRouter.flushAndEvict(player);

            for (ExitListener listener : EXIT_LISTENERS) {
                try {
                    listener.onExit(player, from);
                } catch (Throwable t) {
                    HubSuite.logger().error("离场监听器执行失败（{}）", from.id(), t);
                }
            }
        }

        try {
            // 步骤 1：先让入场监听器准备好这个场所（例如空岛服要先给玩家建岛）。
            //         必须在解析出生点之前做 —— 否则解析出来的是"子服出生点"，
            //         而玩家应该落在自己的岛上。
            for (EntryListener listener : ENTRY_LISTENERS) {
                try {
                    listener.onEnter(player, target, level);
                } catch (Throwable t) {
                    HubSuite.logger().error("入场监听器执行失败（{}）", target.id(), t);
                }
            }

            // 步骤 2a：套用目标场所的状态（独立背包）。
            //          首次进入该场所会得到一份全新状态。
            PlayerStateStash.apply(player, level.dimension().identifier().toString());

            // 步骤 2b：解析这个玩家在该场所的实际出生点
            PlayableWorld.SpawnPoint spawn = spawnFor(player, target, level);

            // 步骤 3：用票据加载目标区块（比手动强载安全，见 addLoadingTicket）
            addLoadingTicket(level, spawn.x(), spawn.z());

            // 步骤 4：传送
            TeleportTransition transition = new TeleportTransition(
                    level,
                    new Vec3(spawn.x(), spawn.y(), spawn.z()),
                    Vec3.ZERO,
                    spawn.yaw(),
                    spawn.pitch(),
                    java.util.Set.of(),
                    TeleportTransition.DO_NOTHING);

            // 取消骑乘，否则会以乘客身份传送导致落点计算错误
            player.stopRiding();
            player.teleport(transition);

            applyEnterEffects(player, target);

            // 步骤 5：把重生点强制绑到"当前所在的场所"（空岛服绑到玩家自己的岛上）。
            //
            // 必须 force=true：原版重生点是全局一份的，玩家上次进过别的子服时，
            // 那个子服的出生点会留在他的 respawnConfig 里；不强制覆盖就会出现
            // "在生存服摔死却重生到空岛服"（实测踩过）。
            // 必须带上 level：多维度子服的主维度不是玩家要去的地方。
            // 不带的话，在空岛服的岛屿维度里绑出来的重生点是**虚空主维度**，
            // 一死就一直往下掉（用户实测："kill 一下自己就卡住了"）。
            RespawnPoints.bind(player, target, true, spawn, level);

            HubSuite.logger().info("切服完成：{} 现在位于 {}（{}，{} 人）",
                    player.getName().getString(), target.id(),
                    level.dimension().identifier(), level.players().size());
            return true;
        } catch (Exception e) {
            HubSuite.logger().error("传送 {} 到 {} 失败",
                    player.getName().getString(), target.id(), e);
            player.sendSystemMessage(Component.literal("\u00A7c传送失败，请联系管理员查看控制台。"));
            return false;
        }
    }

    /** 进入某个场所后套用该场所的规则（游戏模式、飞行、血量、饥饿）。 */
    public static void applyEnterEffects(ServerPlayer player, PlayableWorld target) {
        ServerRules rules = target.rules();

        if (target instanceof SubServer sub) {
            GameType mode = parseMode(sub.config().gameMode);
            if (sub.config().forceGameMode || player.gameMode() != mode) {
                player.setGameMode(mode);
            }
        } else if (target instanceof Lobby lobby) {
            if (lobby.config().adventureMode) {
                player.setGameMode(GameType.ADVENTURE);
            }
            // 注意：这里**不清空背包**。玩家的物品必须完整保留。
        }

        boolean canFly = rules.allowFlight() || player.isCreative() || player.isSpectator();
        player.getAbilities().mayfly = canFly;
        if (!canFly) {
            player.getAbilities().flying = false;
        }
        player.onUpdateAbilities();

        if (rules.keepHungerFull()) {
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
        }
        if (rules.invulnerable() && player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    /**
     * 用 {@code TicketType.PLAYER_SPAWN} 给目标区块打一个加载票据。
     *
     * <p>为什么用票据而不是 {@code getChunk(FULL, true)}：票据是引擎支持的
     * 加载方式，区块会被正常追踪与释放。手动强载会破坏区块追踪状态，
     * 实测导致传送时 {@code DistanceManager.removePlayer} 抛 NPE。
     *
     * <p>作用是让目标区块在玩家落地前就绪 —— 虚空世界（空岛/大厅）尤其需要，
     * 否则玩家会落进未生成的区块里。
     */
    private static void addLoadingTicket(ServerLevel level, double x, double z) {
        try {
            var chunkPos = net.minecraft.world.level.ChunkPos.containing(
                    net.minecraft.core.BlockPos.containing(x, 0, z));
            level.getChunkSource().addTicketWithRadius(
                    net.minecraft.server.level.TicketType.PLAYER_SPAWN, chunkPos, 1);
        } catch (Throwable t) {
            HubSuite.logger().debug("添加传送区块票据失败（可忽略）：{}", t.toString());
        }
    }

    private static cn.dreamgary.hubsuite.world.WorldsManager worlds() {
        return HubSuite.worlds();
    }

    /**
     * 解析某个场所对"这个玩家"的实际出生点。
     *
     * <p>默认就是场所自己的出生点；但像空岛服这种"玩家站在自己的岛上"的地方，
     * 需要由 {@link #setSpawnResolver} 注入的解析器换成岛上的位置。
     */
    private static PlayableWorld.SpawnPoint spawnFor(ServerPlayer player, PlayableWorld target,
                                                     ServerLevel level) {
        for (int i = spawnResolvers.size() - 1; i >= 0; i--) {
            try {
                PlayableWorld.SpawnPoint custom =
                        spawnResolvers.get(i).resolve(player, target, level);
                if (custom != null) {
                    return custom;
                }
            } catch (Throwable t) {
                HubSuite.logger().debug("出生点解析器执行失败，继续问下一个：{}", t.toString());
            }
        }
        return target.spawn();
    }

    private static GameType parseMode(String raw) {
        if (raw == null) {
            return GameType.SURVIVAL;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
        };
    }
}
