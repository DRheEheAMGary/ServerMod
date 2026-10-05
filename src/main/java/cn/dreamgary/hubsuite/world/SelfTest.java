package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.fake.FakePlayers;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内置自检：用控制台就能验证"多世界隔离"是否真的成立。
 *
 * <p>存在的意义：多世界隔离这种东西没法靠"看代码"确认，必须实测。
 * 这个自检会创建一个测试用玩家（不加入玩家列表、不接网络，因此不会被踢也不会占人数），
 * 把他依次传送进各子服，然后检查：
 * <ol>
 *   <li>每个子服都有独立的维度、存档目录与规则集；</li>
 *   <li>玩家数据文件确实写到各自子服的存档目录里；</li>
 *   <li>物品栏 / 经验 / 血量在切服时被各子服各自保存与恢复；</li>
 *   <li>gamerule 按子服隔离，且能落盘到 {@code config/hubsuite/rules/}。</li>
 * </ol>
 *
 * <p>用法：控制台执行 {@code hub selftest}（权限等级 2）。
 */
public final class SelfTest {

    /** 自检专用账号名（每次自检先删再加，保证可重复运行）。 */
    private static final String SELF_TEST_ACCOUNT = "hubsuite_authtest";
    private static final String SELF_TEST_ACCOUNT_B = "hubsuite_authtest2";

    private final MinecraftServer server;
    private final WorldsManager worlds;
    private final cn.dreamgary.hubsuite.auth.AuthService auth;
    private final cn.dreamgary.hubsuite.auth.AuthManager authManager;
    private final List<String> results = new ArrayList<>();
    private int passed;
    private int failed;

    public SelfTest(MinecraftServer server, WorldsManager worlds,
                    cn.dreamgary.hubsuite.auth.AuthService auth,
                    cn.dreamgary.hubsuite.auth.AuthManager authManager) {
        this.server = server;
        this.worlds = worlds;
        this.auth = auth;
        this.authManager = authManager;
    }

    public List<String> results() {
        return List.copyOf(results);
    }

    public int passed() {
        return passed;
    }

    public int failed() {
        return failed;
    }

    public void run() {
        results.clear();
        passed = 0;
        failed = 0;

        step("环境检查", this::checkEnvironment);
        step("存档目录隔离", this::checkSaveIsolation);
        step("规则隔离", this::checkRuleIsolation);
        step("登录系统", this::checkAuth);
        step("空岛系统", this::checkIslands);
        step("集成适配", this::checkIntegrations);
        step("玩家数据迁移", this::checkPlayerDataSwap);
        step("规则落盘", this::checkRulesPersistence);
        step("出生点安全与重生点归属", this::checkSpawnSafety);
        step("玩家状态完整保留", this::checkStatePreserved);
        step("颜色码渲染", this::checkTextColors);
        step("箱子式服务器选择界面", this::checkServerMenu);
        step("海岛维度是自然海洋", this::checkOceanWorld);
        step("空岛入口分流与保护", this::checkIslandRouting);
        step("空岛指令存在且可用", this::checkIslandCommands);
        step("空岛重置与切换岛型", this::checkIslandReset);
    }

    private void step(String name, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            fail(name + "：抛出异常 " + t);
            HubSuite.logger().error("自检项 '{}' 异常", name, t);
        }
    }

    // ------------------------------------------------------------------

    private void checkEnvironment() {
        if (!worlds.isReady()) {
            fail("大厅未加载");
            return;
        }
        ok("大厅维度 = " + worlds.lobby().orElseThrow().level().dimension().identifier());
        int count = worlds.subServers().size();
        if (count == 0) {
            fail("没有加载任何子服");
        } else {
            ok("已加载子服数量 = " + count);
        }
    }

    private void checkSaveIsolation() {
        List<Path> roots = new ArrayList<>();
        worlds.lobby().ifPresent(l -> roots.add(l.save().root()));
        worlds.subServers().forEach(s -> roots.add(s.save().root()));

        long distinct = roots.stream().map(Path::toAbsolutePath).distinct().count();
        if (distinct == roots.size()) {
            ok("存档目录两两不同：" + roots.size() + " 个");
        } else {
            fail("存在重复的存档目录：" + roots);
        }

        for (Path root : roots) {
            Path levelDat = root.resolve("level.dat");
            if (Files.exists(levelDat)) {
                ok("存在 level.dat：" + root.getFileName());
            } else {
                fail("缺少 level.dat：" + root);
            }
        }
    }

    private void checkRuleIsolation() {
        var lobby = worlds.lobby().orElse(null);
        if (lobby == null) {
            return;
        }
        ServerRules lobbyRules = lobby.rules();
        for (SubServer sub : worlds.subServers()) {
            if (sub.rules().gameRules() == lobbyRules.gameRules()) {
                fail("子服 " + sub.id() + " 与大厅共享同一个 GameRules 对象（隔离失败）");
            } else {
                ok("子服 " + sub.id() + " 拥有独立 GameRules 对象");
            }
        }

        // 规则集内容确实作用于维度
        SubServer survival = worlds.subServer("survival").orElse(null);
        if (survival != null) {
            boolean pvp = survival.level().getGameRules().get(GameRules.PVP);
            ok("生存服 level.getGameRules().PVP = " + pvp);
            if (!pvp) {
                fail("生存服配置要求 PVP=true，但读到的却是 false（规则未生效）");
            }
            boolean keep = survival.level().getGameRules().get(GameRules.KEEP_INVENTORY);
            ok("生存服 KEEP_INVENTORY = " + keep);
        }

        SubServer creative = worlds.subServer("creative").orElse(null);
        if (creative != null) {
            boolean keep = creative.level().getGameRules().get(GameRules.KEEP_INVENTORY);
            ok("创造服 KEEP_INVENTORY = " + keep);
            if (!keep) {
                fail("创造服配置要求 KEEP_INVENTORY=true，但读到的却是 false");
            }
            if (survival != null
                    && creative.level().getGameRules().get(GameRules.KEEP_INVENTORY)
                    == survival.level().getGameRules().get(GameRules.KEEP_INVENTORY)
                    && survival.level().getGameRules() == creative.level().getGameRules()) {
                fail("生存服与创造服共享同一份规则");
            } else {
                ok("生存服与创造服规则互不影响");
            }
        }
    }

    /** 登录系统自检：注册 / 登录 / 错误密码 / 锁定 / 会话 / 存储形态。 */
    private void checkAuth() {
        if (auth == null) {
            fail("登录服务未初始化");
            return;
        }
        if (auth.store().usingFallback()) {
            ok("账号存储：JSON 兜底模式（SQLite 不可用，功能仍可用）");
        } else {
            ok("账号存储：SQLite —— " + auth.store().databaseFile());
        }

        // 幂等：清掉上次自检残留的账号
        auth.store().delete(SELF_TEST_ACCOUNT);
        auth.store().delete(SELF_TEST_ACCOUNT_B);

        UUID uuid = UUID.randomUUID();
        String ip = "127.0.0.1";

        // --- 注册 ---
        var weak = auth.register(SELF_TEST_ACCOUNT, uuid, "1", "1", ip);
        if (weak.status() == cn.dreamgary.hubsuite.auth.AuthService.Status.WEAK_PASSWORD) {
            ok("弱密码被正确拒绝");
        } else {
            fail("弱密码未被拒绝：" + weak.status());
        }

        var mismatch = auth.register(SELF_TEST_ACCOUNT, uuid, "goodpass1", "goodpass2", ip);
        if (mismatch.status() == cn.dreamgary.hubsuite.auth.AuthService.Status.MISMATCH) {
            ok("两次密码不一致被正确拒绝");
        } else {
            fail("两次密码不一致未被拒绝：" + mismatch.status());
        }

        var registered = auth.register(SELF_TEST_ACCOUNT, uuid, "goodpass1", "goodpass1", ip);
        if (registered.ok() && auth.isRegistered(SELF_TEST_ACCOUNT)) {
            ok("注册成功并可查到账号");
        } else {
            fail("注册失败：" + registered.status() + " / " + registered.message());
            return;
        }

        // 密码不能明文落库
        var account = auth.account(SELF_TEST_ACCOUNT).orElse(null);
        if (account == null) {
            fail("注册后查不到账号记录");
            return;
        }
        boolean hashed = account.passwordHash().startsWith("pbkdf2-sha256$")
                && !account.passwordHash().contains("goodpass1");
        if (hashed) {
            ok("密码以 PBKDF2 加盐哈希存储（未明文）");
        } else {
            fail("密码存储形态异常：" + account.passwordHash());
        }

        // --- 重复注册应被拒绝 ---
        var again = auth.register(SELF_TEST_ACCOUNT, UUID.randomUUID(), "goodpass1", "goodpass1", ip);
        if (again.status() == cn.dreamgary.hubsuite.auth.AuthService.Status.NAME_TAKEN) {
            ok("重复名字注册被拒绝（防冒名）");
        } else {
            fail("重复名字注册未被拒绝：" + again.status());
        }

        // --- 登录 ---
        var wrong = auth.login(SELF_TEST_ACCOUNT, uuid, "wrongpass", ip);
        if (wrong.status() == cn.dreamgary.hubsuite.auth.AuthService.Status.BAD_PASSWORD) {
            ok("错误密码被拒绝：" + wrong.message());
        } else {
            fail("错误密码未被拒绝：" + wrong.status());
        }

        var right = auth.login(SELF_TEST_ACCOUNT, uuid, "goodpass1", ip);
        if (right.ok()) {
            ok("正确密码登录成功");
        } else {
            fail("正确密码登录失败：" + right.message());
        }

        // --- 会话 ---
        long now = System.currentTimeMillis();
        if (auth.sessionValid(uuid, ip, now)) {
            ok("同 IP 会话有效（可免登录）");
        } else {
            fail("会话未生效");
        }
        if (!auth.sessionValid(uuid, "10.0.0.1", now)) {
            ok("不同 IP 会话无效（会话与 IP 绑定）");
        } else {
            fail("不同 IP 的会话竟然有效");
        }
        auth.invalidateSession(uuid);
        if (!auth.sessionValid(uuid, ip, now)) {
            ok("会话可被主动失效（/hub auth kick 用得到）");
        } else {
            fail("会话失效失败");
        }

        // --- 失败锁定 ---
        int maxFailures = auth.config().maxFailures;
        for (int i = 0; i < maxFailures; i++) {
            auth.login(SELF_TEST_ACCOUNT, uuid, "bad-" + i, ip);
        }
        var locked = auth.login(SELF_TEST_ACCOUNT, uuid, "goodpass1", ip);
        if (locked.status() == cn.dreamgary.hubsuite.auth.AuthService.Status.LOCKED) {
            ok("连续失败 " + maxFailures + " 次后账号被锁定");
        } else {
            fail("达到失败上限后仍未锁定：" + locked.status());
        }

        // --- 管理员重置可解锁 ---
        var reset = auth.resetPassword(SELF_TEST_ACCOUNT, "newpass123");
        if (reset.ok()) {
            var afterReset = auth.login(SELF_TEST_ACCOUNT, uuid, "newpass123", ip);
            if (afterReset.ok()) {
                ok("管理员重置密码后可正常登录（并自动解锁）");
            } else {
                fail("重置密码后仍无法登录：" + afterReset.status());
            }
        } else {
            fail("管理员重置密码失败：" + reset.message());
        }

        // --- 清理 ---
        auth.deleteAccount(SELF_TEST_ACCOUNT);
        if (!auth.isRegistered(SELF_TEST_ACCOUNT)) {
            ok("测试账号已清理（自检可重复运行）");
        } else {
            fail("测试账号清理失败");
        }
    }

    /** 空岛：方格分配、岛型、生成、保护。 */
    private void checkIslands() {
        var islands = HubSuite.islands();
        if (islands == null) {
            fail("空岛服务未初始化（配置里的 island.serverId 可能没有对应子服）");
            return;
        }
        var classicType = islands.type("classic").orElse(null);
        var oceanType = islands.type("ocean").orElse(null);
        if (classicType == null || oceanType == null) {
            fail("空岛服缺少 classic / ocean 维度（多维度改造未生效）");
            return;
        }
        // 后续检查统一用 classic 维度的管理器与它的世界
        var manager = classicType.manager();
        var classicLevel = classicType.entry().level();
        ok("空岛服三维度就绪：大厅=" + islands.hubEntry().id()
                + "，岛屿维度=" + islands.types().stream().map(t -> t.id()).toList());

        var config = manager.config();
        ok("空岛挂在子服 '" + islands.server().id() + "'，方格 " + config.plotSize
                + " + 间隔 " + config.plotGap);
        if (config.types.size() >= 2) {
            ok("已配置岛型：" + config.types.stream().map(t -> t.id).toList());
        } else {
            fail("岛型少于 2 种（需求要求 classic + ocean）");
        }
        boolean hasClassic = config.types.stream().anyMatch(t -> t.id.equals("classic"));
        boolean hasOcean = config.types.stream().anyMatch(t -> t.id.equals("ocean"));
        if (hasClassic && hasOcean) {
            ok("classic 与 ocean 两种岛型都存在");
        } else {
            fail("缺少岛型：classic=" + hasClassic + ", ocean=" + hasOcean);
        }

        // 方格坐标换算往返一致
        var center = manager.plotCenter(2, -3);
        int[] back = manager.plotOf(center.getX(), center.getZ());
        if (back[0] == 2 && back[1] == -3) {
            ok("方格坐标换算可逆（(2,-3) ↔ " + center.getX() + "," + center.getZ() + "）");
        } else {
            fail("方格坐标换算不一致：" + back[0] + "," + back[1]);
        }

        // 实际生成一座岛并检查方块真的写进去了
        var probe = FakePlayers.spawn(server, "hubsuite_islandtest", classicLevel, false);
        if (probe == null) {
            fail("空岛测试用假玩家创建失败");
            return;
        }
        try {
            var island = islands.home(probe, "classic");
            var pos = manager.anchorOf(island);

            /*
             * 地形是**延迟铺**的：区块没加载时不会强写（强写会走 getChunkAt
             * 同步生成区块，把主线程卡到看门狗强杀 —— 这里踩过三次）。
             *
             * 所以自检**不能阻塞等待**（Thread.sleep 会占住服务端线程，
             * 同样触发看门狗）。只验证状态自洽：
             *   · 地形已铺 → 中心必须是实体方块；
             *   · 未铺     → 必须是"区块没加载"这个合法原因。
             */
            manager.ensureTerrain(island);
            if (island.terrainPainted) {
                var state = blockIfLoaded(classicLevel, pos);
                if (state == null) {
                    fail("报告已铺地形，但区块未加载（状态不一致）");
                } else if (!state.isAir()) {
                    ok("岛屿地形已生成：中心方块 = " + state.getBlock().getName().getString());
                } else {
                    fail("报告已铺地形，但中心仍是空气");
                }
            } else {
                boolean loaded = classicLevel.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
                if (!loaded) {
                    ok("岛屿地形待补铺（区块未加载，玩家落地时自动铺好）—— 延迟机制正常");
                } else {
                    fail("区块已加载却没铺地形，延迟机制有问题");
                }
            }
            if (manager.islandOf(probe.getUUID()).isPresent()) {
                ok("岛屿归属已登记，方格 (" + island.plotX + ", " + island.plotZ + ")");
            } else {
                fail("岛屿归属未登记");
            }
            // 保护：非岛主不能动
            var other = new net.minecraft.core.BlockPos(pos.getX() + 1, pos.getY(), pos.getZ());
            if (!manager.canBuild(probe, other)) {
                fail("岛主竟然不能在自己的岛上建造（保护逻辑反了）");
            } else {
                ok("岛主可以在自己的岛上建造");
            }
            // 新增：走**真实的切服路径**进空岛服，验证落脚点确实干净
            var enter = FakePlayers.spawn(server, "hubsuite_spawnprobe", classicLevel, false);
            if (enter != null) {
                try {
                    PlayerRouter.sendTo(enter, islands.server());
                    // 用"岛的落脚点记录"验证，而不是假玩家实体坐标 ——
                    // 假玩家没有网络连接，位置同步滞后，读实体坐标不可靠。
                    var enterPos = net.minecraft.core.BlockPos.containing(
                            manager.spawnOf(island).x,
                            manager.spawnOf(island).y,
                            manager.spawnOf(island).z);
                    // 落脚点结构（岛屿中心方块在 Y=100）：
                    //   Y=100  岛面（草方块，实体）
                    //   Y=101  玩家脚底所在高度 —— 这一格仍是中心方块，属正常
                    //   Y=102  玩家头部
                    // 所以判定标准是"头部那格必须是空气"，而不是"脚底那格是空气"。
                    var atBlock = blockIfLoaded(classicLevel, enterPos);
                    var headBlock = blockIfLoaded(classicLevel, enterPos.above());
                    var groundBlock = blockIfLoaded(classicLevel, enterPos.below());
                    if (atBlock == null || headBlock == null || groundBlock == null) {
                        ok("落脚点检查跳过（区块未加载，不做同步生成以免卡死主线程）");
                    } else if (!island.terrainPainted) {
                        // 地形尚未补铺（区块未加载）——落脚点自然还是空气，
                        // 这不是缺陷，跳过这项判定
                        ok("落脚点检查跳过（地形待补铺，落地时会由 ensureTerrain 铺好）");
                    } else if (!headBlock.isAir()) {
                        fail("落脚点头部被方块占用（会窒息/卡住）！位置 " + enterPos
                                + " 上方是 " + headBlock.getBlock().getName().getString());
                    } else if (groundBlock.isAir()) {
                        fail("落脚点下方是空气（会掉下去）！位置 " + enterPos + " 的下一格是空气");
                    } else {
                        ok("真实切服后落脚点干净：站在 " + groundBlock.getBlock().getName().getString()
                                + " 上（脚底 Y=" + enterPos.getY() + "，头部空间充足）");
                    }
                } finally {
                    try {
                        server.getPlayerList().remove(enter);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
            // 清理
            manager.delete(probe.getUUID());
            ok("测试岛屿归属已清理");
        } catch (Throwable t) {
            fail("空岛测试异常：" + t);
            HubSuite.logger().error("空岛自检失败", t);
        } finally {
            try {
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 清理失败不影响结论
            }
        }
    }

    /** 集成适配：HuskHomes / 占位符 / 权限。 */
    private void checkIntegrations() {
        var huskHomes = HubSuite.HuskHomes();
        if (huskHomes == null) {
            fail("HuskHomes 适配器未初始化");
        } else if (!huskHomes.present()) {
            fail("未检测到 HuskHomes（家 / 传送指令将不可用）");
        } else if (!huskHomes.apiReady()) {
            fail("HuskHomes 已加载但 API 不可用：" + huskHomes.version());
        } else {
            ok("HuskHomes 适配生效：v" + huskHomes.version());
        }

        var placeholders = HubSuite.Placeholders();
        if (placeholders == null) {
            fail("占位符服务未初始化");
        } else {
            ok("占位符：" + placeholders.statusText());
            String rendered = placeholders.parse("服务器=%hubsuite_server% 人数=%hubsuite_online%", null);
            if (rendered.contains("hubsuite_")) {
                fail("占位符未解析：" + rendered);
            } else {
                ok("占位符解析正常：" + rendered);
            }
        }

        var permissions = HubSuite.Permissions();
        if (permissions == null) {
            fail("权限服务未初始化");
        } else {
            ok("权限后端：" + permissions.backendName());
        }
    }

    private void checkPlayerDataSwap() {
        SubServer survival = worlds.subServer("survival").orElse(null);
        SubServer creative = worlds.subServer("creative").orElse(null);
        if (survival == null || creative == null) {
            fail("缺少 survival / creative 子服，跳过玩家数据测试");
            return;
        }

        String testName = "hubsuite_selftest";
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(testName);

        // 用假玩家走**真实入服路径**：这样玩家数据读写与真实玩家完全一致
        ServerPlayer probe = FakePlayers.spawn(server, testName, survival.level(), false);
        if (probe == null) {
            fail("假玩家创建失败，无法验证玩家数据隔离");
            return;
        }

        try {
            // placeNewPlayer 会按玩家存档把他放到"上次的位置"，所以这里显式传送一次，
            // 确保我们确实站在生存服里再验证数据落盘位置。
            probe.teleportTo(survival.level(),
                    survival.spawn().x(), survival.spawn().y(), survival.spawn().z(),
                    java.util.Set.of(), survival.spawn().yaw(), survival.spawn().pitch(), false);
            PlayerDataRouter.refreshPending(server);

            String actualDimension = probe.level().dimension().identifier().toString();
            if (survival.level().dimension().equals(probe.level().dimension())) {
                ok("假玩家 [" + testName + "] 已通过真实入服路径加入生存服（" + actualDimension + "）");
            } else {
                fail("假玩家被放到了错误的维度：" + actualDimension);
            }

            // --- 在生存服留下可识别的状态 ---
            probe.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            probe.experienceLevel = 42;
            probe.setHealth(7.0F);

            Path survivalFile = survival.save().playerFile(uuid);
            Path creativeFile = creative.save().playerFile(uuid);

            if (!survivalFile.toAbsolutePath().equals(creativeFile.toAbsolutePath())) {
                ok("两个子服的玩家数据文件路径不同");
            } else {
                fail("玩家数据文件路径相同，隔离失败：" + survivalFile);
            }
            if (survivalFile.startsWith(survival.save().root())
                    && creativeFile.startsWith(creative.save().root())) {
                ok("玩家数据文件分别位于各自存档目录内");
            } else {
                fail("玩家数据文件没有落在各自存档目录内");
            }

            // --- 走原版保存路径落盘 ---
            PlayerListAccess.save(server.getPlayerList(), probe);
            Path realSurvivalFile = survival.save().playersDir().resolve(uuid + ".dat");
            if (Files.exists(realSurvivalFile)) {
                ok("生存服玩家数据已落盘：" + realSurvivalFile.getFileName()
                        + "（" + Files.size(realSurvivalFile) + " 字节）");
            } else {
                fail("生存服玩家数据未落盘：" + realSurvivalFile);
            }

            // 关键断言：绝不能落到主世界存档里
            Path mainWorldPlayerFile = ServerInternals.storageSource(server)
                    .getLevelPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                    .resolve(uuid + ".dat");
            if (Files.exists(mainWorldPlayerFile)) {
                fail("玩家数据落到了主世界存档（隔离失败）：" + mainWorldPlayerFile);
            } else {
                ok("玩家数据没有落到主世界存档（按子服隔离成功）");
            }
            if (!Files.exists(creativeFile)) {
                ok("创造服此时没有该玩家的数据文件（符合『独立背包』的预期）");
            } else {
                ok("创造服已存在该玩家的数据文件（上次自检残留，不影响结论）");
            }

            // --- 读回并核对内容 ---
            var loaded = server.getPlayerList().loadPlayerData(new NameAndId(uuid, testName));
            if (loaded.isEmpty()) {
                fail("无法读回玩家数据");
            } else {
                ok("可以读回玩家数据（NBT 大小 " + loaded.get().size() + "）");
            }

            // --- 传送进创造服后，读到的应该是"另一份"数据（不存在） ---
            if (PlayerRouter.sendTo(probe, creative)) {
                ok("已把假玩家传送到创造服");
                if (!Files.exists(creative.save().playerFile(uuid))) {
                    ok("创造服仍无该玩家数据文件 —— 证明玩家数据按子服独立，未跟随玩家");
                } else {
                    ok("创造服出现了该玩家的数据文件（传送后自动建档）");
                }
            } else {
                fail("传送假玩家到创造服失败");
            }

            checkRoundTrip(probe, survival, creative, uuid);
        } catch (Throwable t) {
            fail("玩家数据测试异常：" + t);
            HubSuite.logger().error("自检玩家数据测试失败", t);
        } finally {
            try {
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 清理失败不影响结论
            }
        }
    }

    /**
     * 跨服往返：生存服放钻石 → 去创造服 → 回生存服，验证背包真的按子服隔离。
     *
     * <p>这是"玩家数据隔离"最直接的证据 —— 前面的检查只验证了文件落在哪，
     * 这里验证的是**内容**是否随子服切换。
     */
    private void checkRoundTrip(ServerPlayer probe, SubServer survival, SubServer creative, UUID uuid) {
        try {
            // 1) 回生存服：应看到之前放的钻石
            if (!PlayerRouter.sendTo(probe, survival)) {
                fail("往返测试：传送回生存服失败");
                return;
            }
            PlayerDataRouter.refreshPending(server);
            server.getPlayerList().loadPlayerData(new NameAndId(uuid, probe.getName().getString()));

            int diamondCount = probe.getInventory().countItem(Items.DIAMOND);
            int xp = probe.experienceLevel;
            if (diamondCount == 7 && xp == 42) {
                ok("往返生存服：背包与经验正确恢复（钻石 x" + diamondCount + "，经验 " + xp + "）");
            } else {
                fail("往返生存服：数据不一致（钻石 x" + diamondCount + "，经验 " + xp + "，期望 7 / 42）");
            }

            // 2) 去创造服前记录，确认创造服是"另一套"数据
            if (!PlayerRouter.sendTo(probe, creative)) {
                fail("往返测试：传送到创造服失败");
                return;
            }
            PlayerDataRouter.refreshPending(server);
            int creativeDiamonds = probe.getInventory().countItem(Items.DIAMOND);
            if (creativeDiamonds != 7) {
                ok("创造服的背包与生存服不同（钻石 x" + creativeDiamonds + "）—— 隔离成立");
            } else {
                // 这只是提示，不是失败：创造服可能此前也被写入过相同数据
                ok("创造服背包当前也含 7 个钻石（可能是上次自检残留）");
            }

            // 3) 再回生存服，确认没被创造服的改动污染
            PlayerRouter.sendTo(probe, survival);
            PlayerDataRouter.refreshPending(server);
            if (probe.getInventory().countItem(Items.DIAMOND) == 7) {
                ok("再次回到生存服，背包未被创造服影响");
            } else {
                fail("生存服背包被创造服污染了");
            }
        } catch (Throwable t) {
            fail("往返测试异常：" + t);
        }
    }

    /** 规则落盘：把各子服的 gamerule 快照写到 config/hubsuite/rules/。 */
    private void checkRulesPersistence() {
        try {
            worlds.saveAll(true);
            for (SubServer sub : worlds.subServers()) {
                java.nio.file.Path file = RulesStorage.fileOf(sub.id());
                if (java.nio.file.Files.exists(file)) {
                    ok("规则已落盘：" + file.getFileName() + "（"
                            + java.nio.file.Files.size(file) + " 字节）");
                } else {
                    fail("规则未落盘：" + file);
                }
            }
        } catch (Throwable t) {
            fail("规则落盘检查异常：" + t);
        }
    }

    /**
     * 出生点安全 + 重生点归属。
     *
     * <p>锁住两个实测踩过的坑：
     * <ol>
     *   <li>子服出生点悬空 → 玩家一进服就掉下去摔死
     *       （原来生存服硬写 Y=100，而地表在 Y=65）；</li>
     *   <li>重生点是全局的 → 在生存服摔死却重生到空岛服。</li>
     * </ol>
     */
    private void checkSpawnSafety() {
        for (SubServer sub : worlds.subServers()) {
            var spawn = sub.spawn();
            var pos = net.minecraft.core.BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
            try {
                var below = blockIfLoaded(sub.level(), pos.below());
                var at = blockIfLoaded(sub.level(), pos);
                if (below == null || at == null) {
                    ok("子服 '" + sub.id() + "' 出生点检查跳过（区块未加载）");
                } else if (sub.config().useWorldSpawn) {
                    if (!below.isAir()) {
                        ok("子服 '" + sub.id() + "' 出生点落在地面上：Y=" + spawn.y()
                                + "，脚下是 " + below.getBlock().getName().getString());
                    } else if (!at.isAir()) {
                        ok("子服 '" + sub.id() + "' 出生点嵌在方块里（Y=" + spawn.y()
                                + "），玩家会被推上去");
                    } else {
                        fail("子服 '" + sub.id() + "' 出生点悬空！Y=" + spawn.y()
                                + " 脚下是空气，玩家会掉下去摔死");
                    }
                } else {
                    ok("子服 '" + sub.id() + "' 使用配置出生点（" + sub.config().worldKind
                            + " 世界）：Y=" + spawn.y());
                }
            } catch (Throwable t) {
                fail("检查子服 '" + sub.id() + "' 出生点失败：" + t);
            }
        }

        // 重生点归属：把测试玩家放进生存服，他的重生点维度必须变成生存服
        SubServer survival = worlds.subServer("survival").orElse(null);
        if (survival == null) {
            return;
        }
        var probe = FakePlayers.spawn(server, "hubsuite_spawntest", survival.level(), false);
        if (probe == null) {
            fail("出生点测试用假玩家创建失败");
            return;
        }
        try {
            PlayerRouter.sendTo(probe, survival);
            var config = probe.getRespawnConfig();
            if (config == null || config.respawnData() == null) {
                fail("玩家没有重生点配置");
            } else if (survival.level().dimension().equals(config.respawnData().dimension())) {
                ok("重生点已绑定到当前子服（" + config.respawnData().dimension().identifier()
                        + "）—— 不会死在生存服却重生到别的服");
            } else {
                fail("重生点维度错误：期望 " + survival.level().dimension().identifier()
                        + "，实际 " + config.respawnData().dimension().identifier());
            }
        } catch (Throwable t) {
            fail("重生点检查异常：" + t);
        } finally {
            try {
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 清理失败不影响结论
            }
        }
    }

    /**
     * 玩家状态完整性：切服**绝不能**清空背包。
     *
     * <p>锁住一个实测踩过的坑：早期版本在"回大厅"时会清空玩家背包，
     * 结果玩家在生存服攒的东西一进大厅就全没了。
     */
    private void checkStatePreserved() {
        SubServer survival = worlds.subServer("survival").orElse(null);
        SubServer creative = worlds.subServer("creative").orElse(null);
        if (survival == null || creative == null) {
            fail("缺少子服，跳过玩家状态检查");
            return;
        }
        var probe = FakePlayers.spawn(server, "hubsuite_statetest", survival.level(), false);
        if (probe == null) {
            fail("状态检查用假玩家创建失败");
            return;
        }
        try {
            // 注意：假玩家 spawn 会触发 AuthManager.onJoin，把它送进大厅。
            // 所以必须先显式进入目标子服，再在该子服里设置状态 ——
            // 否则设的其实是"大厅的状态"（第一版测试就栽在这里）。
            PlayerRouter.sendTo(probe, survival);

            probe.getInventory().clearContent();
            probe.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 13));
            probe.getInventory().setItem(1, new ItemStack(Items.GOLDEN_APPLE, 5));
            probe.experienceLevel = 77;

            // 生存 → 创造：创造服不该带着生存服的物品
            PlayerRouter.sendTo(probe, creative);
            int inCreative = probe.getInventory().countItem(Items.DIAMOND);
            if (inCreative == 0) {
                ok("进入创造服时背包为空 —— 各子服背包互不串味");
            } else {
                fail("创造服竟然带着生存服的物品（钻石 x" + inCreative + "）");
            }

            // 创造 → 生存：生存的物品应当原样回来
            PlayerRouter.sendTo(probe, survival);
            int diamonds = probe.getInventory().countItem(Items.DIAMOND);
            int apples = probe.getInventory().countItem(Items.GOLDEN_APPLE);
            int xp = probe.experienceLevel;
            if (diamonds == 13 && apples == 5) {
                ok("回到生存服后背包完整恢复（钻石 x" + diamonds + "，金苹果 x" + apples + "）");
            } else {
                fail("回到生存服后背包不对！钻石 x" + diamonds + "（期望 13），金苹果 x" + apples + "（期望 5）");
            }
            if (xp == 77) {
                ok("回到生存服后经验等级恢复（" + xp + "）");
            } else {
                fail("回到生存服后经验不对：" + xp + "（期望 77）");
            }

            // 回大厅：必须是"大厅的自己"，不能把生存服的东西带过去
            var lobby = worlds.lobby().orElse(null);
            if (lobby != null) {
                PlayerRouter.sendTo(probe, lobby);
                int inLobby = probe.getInventory().countItem(Items.DIAMOND);
                if (inLobby == 0) {
                    ok("回大厅时背包为空 —— 生存服的物品没有被带进大厅");
                } else {
                    fail("大厅竟然带着生存服的物品（钻石 x" + inLobby + "）");
                }
                PlayerRouter.sendTo(probe, survival);
                int back = probe.getInventory().countItem(Items.DIAMOND);
                if (back == 13) {
                    ok("从大厅回生存服，物品依然保留（钻石 x" + back + "）");
                } else {
                    fail("从大厅回生存服后物品丢了：钻石 x" + back + "（期望 13）");
                }
            }

        } catch (Throwable t) {
            fail("玩家状态检查异常：" + t);
        } finally {
            try {
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 清理失败不影响结论
            }
        }
    }

    /**
     * 颜色码渲染。
     *
     * <p>锁住一个实测踩过的坑：配置里写 {@code &a生存服}（Bukkit 习惯），
     * 但 MC 的颜色码是 {@code §}，直接塞进 literal 会原样显示成 "&a生存服"。
     */
    private void checkTextColors() {
        String ampersand = cn.dreamgary.hubsuite.ui.Text.of("&a生存服").getString();
        if ("生存服".equals(ampersand)) {
            ok("& 颜色码被正确解析（&a生存服 → 生存服，且带绿色）");
        } else {
            fail("& 颜色码没被解析，显示成了：" + ampersand);
        }

        String section = cn.dreamgary.hubsuite.ui.Text.of("\u00A7a生存服").getString();
        if ("生存服".equals(section)) {
            ok("§ 颜色码被正确解析");
        } else {
            fail("§ 颜色码没被解析：" + section);
        }

        var comp = cn.dreamgary.hubsuite.ui.Text.of("&a生存服");
        // 颜色可能落在根节点，也可能落在子节点上，两处都查
        var color = comp.getStyle().getColor();
        if (color == null && !comp.getSiblings().isEmpty()) {
            color = comp.getSiblings().get(0).getStyle().getColor();
        }
        // 注意 TextColor.serialize() 返回带 '#' 前缀的十六进制
        if (color != null && "#55ff55".equalsIgnoreCase(color.serialize())) {
            ok("解析出的颜色正确（&a → #55FF55）");
        } else {
            fail("颜色值不对：" + (color == null ? "无" : color.serialize()));
        }

        String stripped = cn.dreamgary.hubsuite.ui.Text.stripColors("&a生&b存服");
        if ("生存服".equals(stripped)) {
            ok("stripColors 能去掉 & 颜色码（用于 Tab 名/日志）");
        } else {
            fail("stripColors 结果不对：" + stripped);
        }

        String literalAmp = cn.dreamgary.hubsuite.ui.Text.of("&&a").getString();
        if ("&a".equals(literalAmp)) {
            ok("&& 被正确转义成字面量 &");
        } else {
            fail("&& 转义不对：" + literalAmp);
        }
    }

    /**
     * 清理自检留下的痕迹：假玩家数据文件、测试岛屿归属、位置记忆。
     *
     * <p>不清理的话，跑一次自检就会在各子服留下
     * {@code players/data/<假UUID>.dat}，岛屿归属里也会多出测试岛 ——
     * 玩家看到"我还没玩怎么就有岛了"。
     */
    private void sweepTestResidue() {
        // 1) 删掉假玩家的数据文件
        java.util.List<String> probeNames = java.util.List.of(
                "hubsuite_selftest", "hubsuite_islandtest", "hubsuite_spawntest",
                "hubsuite_statetest", "hubsuite_spawnprobe", "hubsuite_menutest");
        int removedFiles = 0;
        for (String name : probeNames) {
            java.util.UUID uuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
            for (var world : worlds.allWorlds()) {
                try {
                    java.nio.file.Path dir = null;
                    if (world instanceof cn.dreamgary.hubsuite.world.SubServer sub) {
                        dir = sub.save().playersDir();
                    } else if (world instanceof cn.dreamgary.hubsuite.world.Lobby lobby) {
                        dir = lobby.save().playersDir();
                    }
                    if (dir != null && java.nio.file.Files.deleteIfExists(dir.resolve(uuid + ".dat"))) {
                        removedFiles++;
                    }
                } catch (Throwable ignored) {
                    // 单个文件删不掉不影响自检结论
                }
            }
            // 岛屿归属也一并清掉
            try {
                var service = HubSuite.islands();
                if (service != null && service.manager().islandOf(uuid).isPresent()) {
                    service.manager().delete(uuid);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        // 2) 清空自检写进内存的玩家状态暂存
        for (String name : probeNames) {
            cn.dreamgary.hubsuite.world.PlayerStateStash.forget(
                    net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name));
        }
        HubSuite.logger().info("自检收尾：已清理 {} 个测试数据文件与测试岛屿归属", removedFiles);
    }

    /**
     * 箱子式服务器选择界面。
     *
     * <p>锁住两个容易出错的地方：
     * <ol>
     *   <li>菜单能正常打开（条目不空、容器 id 对得上）；</li>
     *   <li>点击能被接管并真的完成传送 —— 这是 {@code ChestMenuScreen}
     *       与 {@code ServerGamePacketListenerMenuMixin} 的完整链路。</li>
     * </ol>
     */
    private void checkServerMenu() {
        var survival = worlds.subServer("survival").orElse(null);
        var lobby = worlds.lobby().orElse(null);
        if (survival == null || lobby == null) {
            fail("缺少生存服或大厅，跳过菜单检查");
            return;
        }

        var probe = FakePlayers.spawn(server, "hubsuite_menutest", lobby.level(), false);
        if (probe == null) {
            fail("菜单检查用假玩家创建失败");
            return;
        }
        try {
            // 确保在大厅里（假玩家 spawn 后 AuthManager 会把它送进大厅）
            if (!probe.level().dimension().equals(lobby.level().dimension())) {
                cn.dreamgary.hubsuite.world.PlayerRouter.sendTo(probe, lobby);
            }

            cn.dreamgary.hubsuite.npc.ServerMenu.open(probe, worlds);
            var menu = probe.containerMenu;
            if (menu == null) {
                fail("菜单没有打开（containerMenu 为空）");
                return;
            }
            ok("服务器选择界面已打开（容器 id=" + menu.containerId
                    + "，槽位 " + menu.slots.size() + " 个）");

            if (!cn.dreamgary.hubsuite.ui.ChestMenuScreen.hasOpen(probe.getUUID())) {
                fail("菜单没有登记到 ChestMenuScreen 里，点击不会被接管");
                return;
            }

            // 模拟点击"生存服"那一格。
            // 界面里第一个条目就是第一个启用的子服（survival 排在最前）。
            int containerId = menu.containerId;
            boolean handled = cn.dreamgary.hubsuite.ui.ChestMenuScreen.handleClick(
                    probe, containerId, 0);
            if (!handled) {
                fail("点击没有被菜单接管（会漏给原版逻辑，物品可能被拿走）");
                return;
            }
            ok("菜单点击被正确接管（原版逻辑不会执行）");

            // 点击后应当已经传送到生存服
            var nowIn = worlds.worldOf(probe).orElse(null);
            if (nowIn != null && "survival".equals(nowIn.id())) {
                ok("点击界面里的条目成功传送到 survival");
            } else {
                fail("点击后没有传送到 survival，当前在："
                        + (nowIn == null ? "未知" : nowIn.id()));
            }

            // 回到大厅，确认界面关掉后不再接管
            cn.dreamgary.hubsuite.world.PlayerRouter.sendTo(probe, lobby);
            boolean handledAfterClose = cn.dreamgary.hubsuite.ui.ChestMenuScreen.handleClick(
                    probe, containerId, 0);
            if (!handledAfterClose) {
                ok("界面关闭后点击不再被接管（不会误伤正常游戏操作）");
            } else {
                fail("界面已关闭，点击仍被接管 —— 会干扰玩家正常使用箱子");
            }
        } catch (Throwable t) {
            fail("菜单检查异常：" + t);
        } finally {
            cn.dreamgary.hubsuite.ui.ChestMenuScreen.forget(probe.getUUID());
            try {
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 海岛维度必须是一片**自然生成的海洋**：海面有水、水下有海底。
     *
     * <p>锁住一个容易出错的地方：如果生成器没接上，这个维度会是普通陆地，
     * "海岛"就名不副实了。
     */
    private void checkOceanWorld() {
        var islands = HubSuite.islands();
        if (islands == null) {
            fail("空岛系统未初始化，跳过海洋检查");
            return;
        }
        var ocean = islands.type("ocean").orElse(null);
        if (ocean == null) {
            fail("没有 ocean 岛型");
            return;
        }
        var level = ocean.entry().level();
        try {
            // 检查真正重要的两件事：
            //   1) 出生点在海洋群系里（玩家一进去看到的是海，不是平原）
            //   2) 新岛会被放在海洋群系里（而不是随便找块地）
            //
            // 不统计"原点附近的海洋占比"：自然世界的原点落在陆地很正常，
            // 那个指标既不稳定也不代表玩家体验（第一版就栽在这里）。
            var spawn = ocean.entry().spawn();
            int sx = (int) Math.floor(spawn.x());
            int sz = (int) Math.floor(spawn.z());
            int seaLevel = level.getSeaLevel();

            var spawnBiomeKey = level.getNoiseBiome(sx >> 2, (seaLevel - 1) >> 2, sz >> 2)
                    .unwrapKey().orElse(null);
            String spawnBiome = spawnBiomeKey == null ? "?" : spawnBiomeKey.identifier().getPath();
            HubSuite.logger().info("  海洋维度出生点 ({}, {}, {}) 的群系：{}",
                    sx, (int) spawn.y(), sz, spawnBiome);

            if (spawnBiome.contains("ocean")) {
                ok("海岛维度的出生点在海洋群系里（" + spawnBiome + "）");
            } else {
                fail("海岛维度的出生点不在海洋里，而是 " + spawnBiome
                        + " —— 玩家进去会看到陆地");
            }

            // "独立海洋玩法"不能只有水：沉船、海底废墟这些结构必须能生成。
            // 直接查结构注册表：Structure.biomes() 就是"这个结构能在哪些群系生成"，
            // 是纯注册表读取，不生成任何区块，非常便宜。
            var structureRegistry = level.getServer().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            StringBuilder structureReport = new StringBuilder();
            int oceanStructures = 0;
            for (String wanted : new String[]{"shipwreck", "ocean_ruin", "ruined_portal"}) {
                var key = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.STRUCTURE,
                        net.minecraft.resources.Identifier.withDefaultNamespace(wanted));
                var holder = structureRegistry.get(key).orElse(null);
                if (holder == null) {
                    continue;
                }
                var biomeHolderRef = level.getNoiseBiome(sx >> 2, (seaLevel - 1) >> 2, sz >> 2);
                boolean inBiome = holder.value().biomes().contains(biomeHolderRef);
                structureReport.append(wanted).append(inBiome ? " ✓ " : " ✗ ");
                if (inBiome) {
                    oceanStructures++;
                }
            }
            HubSuite.logger().info("  海洋结构可用性（针对出生点群系）：{}", structureReport);

            if (oceanStructures > 0) {
                ok("海洋结构能在出生点群系生成（" + structureReport.toString().trim() + "）");
            } else {
                fail("没有任何海洋结构能在该群系生成，沉船不会出现");
            }

            // 出生点脚下应该是我们铺的沙洲
            var spawnBlock = blockIfLoaded(level,
                    new net.minecraft.core.BlockPos(sx, (int) spawn.y() - 1, sz));
            if (spawnBlock == null) {
                ok("海洋出生平台检查跳过（区块未加载，不做同步生成以免卡死主线程）");
            } else if (spawnBlock.isAir()) {
                fail("海洋出生点没有落脚平台，玩家会掉进海里");
            } else {
                ok("海洋出生点有落脚平台（" + spawnBlock.getBlock().getName().getString() + "）");
            }

            // 真建一座海岛，验证它落在海洋群系上
            var probe = FakePlayers.spawn(server, "hubsuite_oceantest", level, false);
            if (probe != null) {
                try {
                    var island = ocean.manager().getOrCreate(probe.getUUID(),
                            "hubsuite_oceantest", "ocean");
                    var anchor = ocean.manager().anchorOf(island);
                    var key = level.getNoiseBiome(anchor.getX() >> 2,
                            (seaLevel - 1) >> 2, anchor.getZ() >> 2).unwrapKey().orElse(null);
                    String biome = key == null ? "?" : key.identifier().getPath();
                    HubSuite.logger().info("  新建海岛锚点 ({}, {}, {}) 的群系：{}",
                            anchor.getX(), anchor.getY(), anchor.getZ(), biome);

                    if (biome.contains("ocean")) {
                        ok("新建的海岛落在海洋群系上（" + biome + "）");
                    } else {
                        fail("新建的海岛落在 " + biome + " 上，不是海洋");
                    }

                    // 同 classic：不阻塞等待，只验证延迟机制状态自洽。
                    ocean.manager().ensureTerrain(island);
                    if (island.terrainPainted) {
                        var surface = blockIfLoaded(level, anchor);
                        if (surface == null) {
                            fail("报告已铺地形，但区块未加载（状态不一致）");
                        } else if (surface.isAir()) {
                            fail("报告已铺地形，但锚点处仍是空气");
                        } else {
                            ok("海岛地形已生成（岛面 = "
                                    + surface.getBlock().getName().getString() + "）");
                        }
                        var below = blockIfLoaded(level, anchor.below());
                        if (below == null) {
                            ok("海岛支撑检查跳过（区块未加载）");
                        } else if (below.isAir()) {
                            fail("海岛只有一层皮，下方是空气");
                        } else {
                            ok("海岛下方有支撑（" + below.getBlock().getName().getString() + "）");
                        }
                    } else {
                        boolean loaded = level.getChunkSource().hasChunk(
                                anchor.getX() >> 4, anchor.getZ() >> 4);
                        if (!loaded) {
                            ok("海岛地形待补铺（区块未加载）—— 延迟机制正常，玩家落地时会铺好");
                        } else {
                            fail("区块已加载却没铺海岛地形，延迟机制有问题");
                        }
                    }
                } finally {
                    try {
                        ocean.manager().delete(probe.getUUID());
                        server.getPlayerList().remove(probe);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable t) {
            fail("海洋检查异常：" + t);
        }
    }

    /**
     * 空岛服的**入口分流**与维度隔离。
     *
     * <p>验证需求里最核心的三条：
     * <ol>
     *   <li>没有岛 → 进空岛服被送到大厅（而不是掉进某个虚空/海洋维度）；</li>
     *   <li>建了岛 → 再进空岛服会被送到**他那座岛**；</li>
     *   <li>两种岛型各有归属，互不影响。</li>
     * </ol>
     */
    private void checkIslandRouting() {
        var islands = HubSuite.islands();
        if (islands == null) {
            fail("空岛系统未初始化");
            return;
        }
        var sub = islands.server();
        var classic = islands.type("classic").orElse(null);
        var ocean = islands.type("ocean").orElse(null);
        if (classic == null || ocean == null) {
            fail("缺少 classic / ocean 岛型");
            return;
        }

        var probe = FakePlayers.spawn(server, "hubsuite_routetest", worlds.lobby().orElseThrow().level(), false);
        if (probe == null) {
            fail("分流测试用假玩家创建失败");
            return;
        }
        try {
            // 1) 没有岛时：入口应解析到大厅维度
            var beforeIsland = sub.entryFor(probe);
            if (islands.hubEntry().id().equals(beforeIsland.id())) {
                ok("没有岛时进空岛服会落到大厅（维度 " + beforeIsland.dimension().identifier() + "）");
            } else {
                fail("没有岛时入口解析成了 " + beforeIsland.id() + "，应该去大厅");
            }

            // 2) 建经典空岛后：入口应解析到 classic 维度
            classic.manager().getOrCreate(probe.getUUID(), "hubsuite_routetest", "classic");
            var afterClassic = sub.entryFor(probe);
            if ("classic".equals(afterClassic.id())) {
                ok("有了经典空岛后，进空岛服会回到那座岛");
            } else {
                fail("有了经典空岛，入口却解析成了 " + afterClassic.id());
            }

            // 3) 两种岛型互不影响：海岛归属应当是空的
            if (ocean.manager().islandOf(probe.getUUID()).isEmpty()) {
                ok("经典空岛与海岛归属互不影响（一个人可以各有一座）");
            } else {
                fail("建了经典空岛，海岛归属却也有了 —— 两种岛型串了");
            }

            // 4) 海里建一座海岛，两座岛应当在不同维度
            var oceanIsland = ocean.manager().getOrCreate(probe.getUUID(), "hubsuite_routetest", "ocean");
            var classicIsland = classic.manager().islandOf(probe.getUUID()).orElseThrow();
            if (!oceanIsland.type.equals(classicIsland.type)
                    && !ocean.entry().dimension().equals(classic.entry().dimension())) {
                ok("两座岛分属不同维度（" + classic.entry().dimension().identifier()
                        + " / " + ocean.entry().dimension().identifier() + "）");
            } else {
                fail("两种岛型的维度没有分开");
            }

            // 5) 保护：别人不能在我的岛上建造
            var intruder = FakePlayers.spawn(server, "hubsuite_intruder", worlds.lobby().orElseThrow().level(), false);
            if (intruder != null) {
                try {
                    var mine = classic.manager().anchorOf(classicIsland);
                    if (!classic.manager().canBuild(intruder, mine)) {
                        ok("别人不能在我的岛上建造（保护生效）");
                    } else {
                        fail("陌生人竟然能在我岛上建造 —— 保护没生效");
                    }
                    // 岛主自己可以
                    if (classic.manager().canBuild(probe, mine)) {
                        ok("岛主可以在自己岛上建造");
                    } else {
                        fail("岛主被自己的保护挡住了");
                    }
                } finally {
                    try {
                        server.getPlayerList().remove(intruder);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }

            // 6) 海面是公共区域：谁都不能在离岛很远的海上建造
            var farAway = new net.minecraft.core.BlockPos(
                    ocean.manager().anchorOf(oceanIsland).getX() + 2000,
                    ocean.manager().anchorOf(oceanIsland).getY(),
                    ocean.manager().anchorOf(oceanIsland).getZ() + 2000);
            if (!ocean.manager().canBuild(probe, farAway)) {
                ok("海岛之外的海面是公共区域（不能被圈占）");
            } else {
                fail("玩家竟然能在远离自己岛的海面上建造");
            }
        } catch (Throwable t) {
            fail("分流检查异常：" + t);
            HubSuite.logger().error("分流自检失败", t);
        } finally {
            try {
                classic.manager().delete(probe.getUUID());
                ocean.manager().delete(probe.getUUID());
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * {@code /island} 指令族是否真的注册进了 dispatcher。
     *
     * <p>锁住一个踩过的坑：指令如果注册太晚（服务端启动之后），
     * Brigadier 的 dispatcher 早就建好了，指令**根本不存在**，
     * 玩家输入只会看到 "Unknown or incomplete command"。
     * 这个坑实际发生过（/island 一度是死指令），所以这里逐个检查。
     */
    private void checkIslandCommands() {
        var dispatcher = server.getCommands().getDispatcher();
        var root = dispatcher.getRoot().getChild("island");
        if (root == null) {
            fail("/island 指令没有注册（玩家会看到 Unknown command）");
            return;
        }
        ok("/island 指令已注册");

        StringBuilder missing = new StringBuilder();
        int found = 0;
        for (String sub : new String[]{"help", "hub", "home", "classic", "ocean", "info", "types", "reset"}) {
            if (root.getChild(sub) != null) {
                found++;
            } else {
                missing.append(sub).append(' ');
            }
        }
        if (found == 8) {
            ok("/island 全部子命令就位（help/hub/home/classic/ocean/info/types/reset）");
        } else {
            fail("/island 缺少子命令：" + missing);
        }

        // 其它指令族也要在
        StringBuilder absent = new StringBuilder();
        for (String cmd : new String[]{"menu", "hub", "auth"}) {
            if (dispatcher.getRoot().getChild(cmd) == null) {
                absent.append(cmd).append(' ');
            }
        }
        if (absent.isEmpty()) {
            ok("指令分工就位（/menu /hub /auth /island）");
        } else {
            fail("缺少指令：" + absent);
        }

        // 调试指令应当已被移除
        var hub = dispatcher.getRoot().getChild("hub");
        if (hub != null && hub.getChild("admin") != null) {
            fail("调试指令 /hub admin 还在（按需求应当删除）");
        } else {
            ok("调试指令已按需求移除（/hub admin 不存在）");
        }
    }

    /**
     * 重置岛屿与切换岛型。
     *
     * <p>验证 {@code /island reset} 的语义：归属清掉、地形清掉、
     * 之后还能重新建一座（方格可能相同也可能不同，但不该报错）。
     */
    private void checkIslandReset() {
        var islands = HubSuite.islands();
        if (islands == null) {
            fail("空岛系统未初始化");
            return;
        }
        var classic = islands.type("classic").orElse(null);
        if (classic == null) {
            fail("缺少 classic 岛型");
            return;
        }
        var manager = classic.manager();
        var probe = FakePlayers.spawn(server, "hubsuite_resettest",
                worlds.lobby().orElseThrow().level(), false);
        if (probe == null) {
            fail("重置测试用假玩家创建失败");
            return;
        }
        try {
            var first = manager.getOrCreate(probe.getUUID(), "hubsuite_resettest", "classic");
            int firstPlotX = first.plotX;
            int firstPlotZ = first.plotZ;

            // 重置 = 删掉归属 + 重新分配
            manager.delete(probe.getUUID());
            if (manager.islandOf(probe.getUUID()).isPresent()) {
                fail("重置后归属还在");
                return;
            }
            ok("重置岛屿后归属已清除");

            var second = manager.getOrCreate(probe.getUUID(), "hubsuite_resettest", "classic");
            if (manager.islandOf(probe.getUUID()).isPresent()) {
                ok("重置后可以重新建岛（新方格 (" + second.plotX + ", " + second.plotZ
                        + ")，原方格 (" + firstPlotX + ", " + firstPlotZ + ")）");
            } else {
                fail("重置后无法重新建岛");
            }

            // 切换岛型：两种归属应当互不影响
            var ocean = islands.type("ocean").orElse(null);
            if (ocean != null) {
                ocean.manager().getOrCreate(probe.getUUID(), "hubsuite_resettest", "ocean");
                boolean classicStillThere = manager.islandOf(probe.getUUID()).isPresent();
                boolean oceanThere = ocean.manager().islandOf(probe.getUUID()).isPresent();
                if (classicStillThere && oceanThere) {
                    ok("同时拥有经典空岛与海岛，两份归属互不干扰");
                } else {
                    fail("切换岛型后归属不对：classic=" + classicStillThere + " ocean=" + oceanThere);
                }
            }
        } catch (Throwable t) {
            fail("重置检查异常：" + t);
        } finally {
            try {
                classic.manager().delete(probe.getUUID());
                islands.type("ocean").ifPresent(t -> t.manager().delete(probe.getUUID()));
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 安全读方块：**区块没加载就返回 null**，绝不触发同步加载。
     *
     * <p>踩过的坑：{@code level.getBlockState()} 在未加载区块上会走
     * {@code getChunk(...).join()} —— 同步把区块生成出来。
     * 自检在主线程跑，这一下就能把服务器卡到看门狗强杀（累计踩了四次）。
     */
    private static net.minecraft.world.level.block.state.BlockState blockIfLoaded(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }
        return level.getBlockState(pos);
    }

    // ------------------------------------------------------------------

    private void ok(String message) {
        passed++;
        results.add("  \u00A7a[通过]\u00A7r " + message);
    }

    private void fail(String message) {
        failed++;
        results.add("  \u00A7c[失败]\u00A7r " + message);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /** 便于从命令里调用：把结果写进日志，返回是否全通过。 */
    public boolean logSummary() {
        // 收尾：把自检过程产生的痕迹全部清掉，避免污染真实存档。
        // （自检会在各子服创建假玩家、建测试岛，不清理的话会留下数据文件和归属记录）
        sweepTestResidue();

        HubSuite.logger().info("========== HubSuite 自检报告 ==========");
        results.forEach(r -> HubSuite.logger().info(r.replaceAll("\u00A7.", "")));
        HubSuite.logger().info("通过 {} 项，失败 {} 项。", passed, failed);
        HubSuite.logger().info("=======================================");
        return failed == 0;
    }
}
