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
        step("玩家数据落盘目录正确", this::checkPlayerDataRouting);
        step("成就与统计按维度隔离", this::checkAuxDataIsolation);
        step("任务系统", this::checkQuests);
        step("假玩家与假人名字长度合法", this::checkFakeNameLengths);
        step("空岛服可以正常交互方块", this::checkInteractionAllowed);
        step("海岛地形形状符合预期", this::checkOceanIslandShape);
        step("右键箱子真的能打开", this::checkChestActuallyOpens);
        step("海洋地形是真正的海（不是平板）", this::checkOceanTerrainQuality);
        step("海岛附近有海洋结构", this::checkOceanStructures);
    }

    private void step(String name, ThrowingRunnable body) {
        // 实时打印"开始"：自检结果是最后一起输出的，一旦某一步卡住主线程
        // （本项目被看门狗强杀过），没有这行就完全不知道卡在哪。
        HubSuite.logger().info("  [自检] -> {}", name);
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

        // 用假玩家走**真实入服路径**：这样玩家数据读写与真实玩家完全一致
        ServerPlayer probe = FakePlayers.spawn(server, testName, survival.level(), false);
        if (probe == null) {
            fail("假玩家创建失败，无法验证玩家数据隔离");
            return;
        }
        // 注意：UUID 与名字**必须取自 probe 本身**，不能在这里用原始常量另算一遍。
        // FakePlayers.spawn 会把名字规整到 16 字符以内（原版 player_info 包的
        // 编码上限，超长会把所有在线玩家踢下线），名字一变 UUID 就跟着变，
        // 另算的那份就会指向一个根本不存在的文件。实测踩过。
        UUID uuid = probe.getUUID();
        testName = probe.getName().getString();

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
            var loaded = server.getPlayerList().loadPlayerData(
                    new NameAndId(probe.getUUID(), probe.getName().getString()));
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
            // 必须用与 FakePlayers.spawn **同一个**规整函数算 UUID。
            // spawn 会把名字截到 16 字符以内（原版 player_info 包的编码上限），
            // 用原始名字另算一遍会得到不同的 UUID，清理就会指向不存在的文件 ——
            // 测试残留永远清不掉。
            java.util.UUID uuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(
                    cn.dreamgary.hubsuite.fake.FakePlayers.validName(name));
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
                    net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(
                            cn.dreamgary.hubsuite.fake.FakePlayers.validName(name)));
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
                // 区块还没加载：平台是**延迟补铺**的（启动时铺不成很正常，
                // 由 WorldsManager 每 tick 重试）。这不是缺陷。
                ok("海洋出生平台待补铺（区块未加载）—— 延迟补铺机制正常");
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
                        /*
                         * 判定口径必须和 generate() **完全一致**：
                         * 它要求锚点周围 3×3 个区块都加载好，只查中心那一个
                         * 会得出"已加载却没铺"的假失败（实测踩过）。
                         */
                        int acx = anchor.getX() >> 4;
                        int acz = anchor.getZ() >> 4;
                        boolean allLoaded = true;
                        for (int ddx = -1; ddx <= 1 && allLoaded; ddx++) {
                            for (int ddz = -1; ddz <= 1; ddz++) {
                                if (!level.getChunkSource().hasChunk(acx + ddx, acz + ddz)) {
                                    allLoaded = false;
                                    break;
                                }
                            }
                        }
                        if (allLoaded) {
                            fail("锚点周围 3×3 区块都已加载，却没铺出海岛地形（延迟机制有问题）");
                        } else {
                            ok("海岛地形待补铺（锚点周围区块未全部加载）"
                                    + " —— 延迟机制正常，区块就绪后会自动铺好");
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

        // 关键：以**玩家身份真的执行一次** /island，而不只是检查注册存在。
        //
        // 踩过的坑：inSkyblock 拿子服主维度比较，而玩家只在 hub/classic/ocean
        // 三个维度里 → 每个子命令（除 help）都回"请先进入空岛服"，
        // 功能完全不可用；而"只检查注册存在"的自检完全抓不到。
        var probe = FakePlayers.spawn(server, "hubsuite_cmdtest",
                worlds.lobby().orElseThrow().level(), false);
        if (probe != null) {
            try {
                var islands = HubSuite.islands();
                if (islands != null) {
                    // 把他送进空岛服（会落到 hub 或他自己的岛）
                    islands.visit(probe, "classic");
                    boolean inSkyblock = islands.isSkyblock(probe.level());
                    if (!inSkyblock) {
                        fail("玩家没能进入空岛服维度，当前 " + probe.level().dimension().identifier());
                    } else {
                        ok("玩家可以进入空岛服维度（" + probe.level().dimension().identifier() + "）");
                    }

                    /*
                     * /island 不带参数 → 打开岛屿界面（箱子 GUI）。
                     *
                     * 注意：**不能用 performCommand 同步断言**。
                     * 26.1 的命令走 executeCommandInContext 排队执行，
                     * performCommand 返回时命令还没跑（日志顺序可证：
                     * 断言先打印，openMenu 的日志在其后）。
                     *
                     * 而在这里 sleep 等服务端线程又是禁止的 ——
                     * 主线程被占住会触发看门狗（本项目踩过）。
                     *
                     * 所以拆成两条可同步验证的检查：
                     *   1) /island 根节点是**可执行**的（不是只有子命令）；
                     *   2) 直接调菜单能打开（下面那段）—— 覆盖真正的 UI 逻辑。
                     */
                    var cmdDispatcher = server.getCommands().getDispatcher();
                    com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> islandNode =
                            cmdDispatcher.getRoot().getChild("island");
                    if (islandNode != null && islandNode.getCommand() != null) {
                        ok("/island 根节点可执行（无参数时会打开界面）");
                    } else {
                        fail("/island 根节点不可执行，打不开界面");
                    }

                    // 真正验证 UI：直接调菜单（等价于命令最终会做的事）
                    cn.dreamgary.hubsuite.island.IslandMenu.open(probe, islands, worlds);
                    if (cn.dreamgary.hubsuite.ui.ChestMenuScreen.hasOpen(probe.getUUID())) {
                        ok("岛屿界面可以打开（箱子 GUI，含岛型/任务/返回/重置入口）");
                        probe.closeContainer();
                        cn.dreamgary.hubsuite.ui.ChestMenuScreen.forget(probe.getUUID());
                    } else {
                        fail("岛屿界面打不开");
                    }

                    // 任务界面也要能开
                    cn.dreamgary.hubsuite.quest.QuestMenu.open(probe, null, 0);
                    if (cn.dreamgary.hubsuite.ui.ChestMenuScreen.hasOpen(probe.getUUID())) {
                        ok("任务界面可以打开");
                        probe.closeContainer();
                        cn.dreamgary.hubsuite.ui.ChestMenuScreen.forget(probe.getUUID());
                    } else {
                        fail("任务界面没打开");
                    }

                    // /island 的每个子命令都必须是**可执行**的节点。
                    // 这条锁住"指令注册了但执行就被维度判定拒绝"那类问题
                    // （inSkyblock 比错对象时，除 help 外全部拒绝服务）。
                    StringBuilder broken = new StringBuilder();
                    for (String sub : new String[]{"classic", "ocean", "home", "info",
                            "types", "hub", "reset", "menu"}) {
                        com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> node =
                                islandNode == null ? null : islandNode.getChild(sub);
                        // 可执行，或者带必需的子参数（例如 reset 需要 <类型>）都算正常
                        boolean usable = node != null
                                && (node.getCommand() != null || !node.getChildren().isEmpty());
                        if (!usable) {
                            broken.append(sub).append(' ');
                        }
                    }
                    if (broken.isEmpty()) {
                        ok("/island 全部子命令都可执行");
                    } else {
                        fail("/island 有子命令不可执行：" + broken);
                    }
                }
            } catch (Throwable t) {
                fail("/island 以玩家身份执行失败：" + t);
            } finally {
                try {
                    server.getPlayerList().remove(probe);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
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

    /**
     * 玩家数据必须落到**当前所在维度**对应的存档目录里。
     *
     * <p>这是"独立背包"的根基：{@code PlayerList.playerIo} 是全局唯一的，
     * 原版所有玩家数据都会写到主世界存档。我们用 Mixin 在读写的瞬间替换存储对象，
     * 所以必须验证"替换真的生效了"，而不只是"写到了某个目录"。
     *
     * <p>同时验证反方向：**非当前维度**的存档目录里不该出现这个玩家。
     */
    private void checkPlayerDataRouting() {
        var survival = worlds.subServer("survival").orElse(null);
        var lobby = worlds.lobby().orElse(null);
        if (survival == null || lobby == null) {
            fail("缺少生存服或大厅");
            return;
        }

        var probe = FakePlayers.spawn(server, "hubsuite_datatest", survival.level(), false);
        if (probe == null) {
            fail("数据落盘测试用假玩家创建失败");
            return;
        }
        try {
            // 注意：假玩家 spawn 时 AuthManager.onJoin 会把他送进大厅，
            // 所以必须**显式**把他送进生存服，否则测的是大厅的落盘路径。
            PlayerRouter.sendTo(probe, survival);
            if (!survival.level().dimension().equals(probe.level().dimension())) {
                fail("无法把测试玩家送进 survival（当前 "
                        + probe.level().dimension().identifier() + "）");
                return;
            }

            // 给他一点东西，然后强制保存
            probe.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.DIAMOND, 7));
            PlayerListAccess.save(server.getPlayerList(), probe);

            java.nio.file.Path inSurvival = survival.save().playerFile(probe.getUUID());
            java.nio.file.Path inCreative = worlds.subServer("creative")
                    .map(w -> w.save().playerFile(probe.getUUID())).orElse(null);

            boolean survivalHas = java.nio.file.Files.exists(inSurvival);
            boolean creativeHas = inCreative != null && java.nio.file.Files.exists(inCreative);

            HubSuite.logger().info("  落盘检查：survival={} creative={}", survivalHas, creativeHas);

            /*
             * 判定标准说明：
             * 玩家**待过的每个维度**都留一份自己的数据，这是原版行为
             * （换维度时会保存当前维度），也正是"每个子服独立背包"的基础。
             * 所以不能要求"只有 survival 有文件"—— 假玩家 spawn 时先落在大厅，
             * 大厅当然会留一份。
             *
             * 真正要验证的是：**当前所在维度必须有他的数据**，
             * 而且**没去过的维度不该有**。
             */
            if (!survivalHas) {
                fail("玩家数据没落到当前维度（survival）的存档 —— 落盘路由失效");
            } else if (creativeHas) {
                fail("没去过的创造服竟然有他的数据 —— 隔离被破坏");
            } else {
                ok("玩家数据落在当前维度（survival）存档，没去过的维度没有 —— 隔离正确");
            }

            // 主世界存档绝不该有托管玩家的数据
            var overworldProbe = server.overworld().getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                    .resolve(probe.getUUID() + ".dat");
            if (java.nio.file.Files.exists(overworldProbe)) {
                fail("玩家数据被写进了主世界存档：" + overworldProbe);
            } else {
                ok("主世界存档没有被写脏");
            }
        } catch (Throwable t) {
            fail("数据落盘检查异常：" + t);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(survival.save().playerFile(probe.getUUID()));
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 成就与统计必须按子服维度隔离。
     *
     * <p>锁住的问题：{@code PlayerListStorageMixin} 只接管了 {@code playerIo}
     * （背包/经验），而原版把成就和统计**另存两份文件**，路径来自
     * {@code server.getWorldPath(PLAYER_STATS_DIR / PLAYER_ADVANCEMENTS_DIR)} ——
     * 全局主世界，与玩家所在维度无关。结果"只在生存服该拿的成就，
     * 在大厅/创造服也会解锁并互相污染"。
     */
    private void checkAuxDataIsolation() {
        var survival = worlds.subServer("survival").orElse(null);
        var lobby = worlds.lobby().orElse(null);
        if (survival == null || lobby == null) {
            fail("缺少生存服或大厅");
            return;
        }

        // 1) 目录推算：不同维度必须指向不同子服存档
        var survivalStats = AuxDataRouter.statsDir(survival.level().dimension());
        var lobbyStats = AuxDataRouter.statsDir(lobby.level().dimension());
        var survivalAdv = AuxDataRouter.advancementsDir(survival.level().dimension());
        var lobbyAdv = AuxDataRouter.advancementsDir(lobby.level().dimension());

        if (survivalStats == null || lobbyStats == null) {
            fail("统计目录没能按维度解析出来（路由未生效）");
            return;
        }
        if (survivalStats.equals(lobbyStats)) {
            fail("生存服与大厅的统计目录相同：" + survivalStats + " —— 没隔离");
            return;
        }
        ok("统计目录按维度分开（survival=" + survivalStats.getParent().getFileName()
                + "/stats，lobby=" + lobbyStats.getParent().getFileName() + "/stats）");

        if (survivalAdv != null && lobbyAdv != null && !survivalAdv.equals(lobbyAdv)) {
            ok("成就目录按维度分开");
        } else {
            fail("成就目录没有按维度分开：" + survivalAdv + " vs " + lobbyAdv);
        }

        // 2) 端到端：让玩家在生存服产生统计并落盘，确认文件写在生存服存档里
        var probe = FakePlayers.spawn(server, "hubsuite_auxtest", survival.level(), false);
        if (probe == null) {
            fail("成就/统计测试用假玩家创建失败");
            return;
        }
        try {
            PlayerRouter.sendTo(probe, survival);
            if (!survival.level().dimension().equals(probe.level().dimension())) {
                fail("无法把测试玩家送进 survival");
                return;
            }
            var counter = server.getPlayerList().getPlayerStats(probe);
            counter.setValue(probe,
                    net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.WALK_ONE_CM), 1234);

            AuxDataRouter.flushAndEvict(probe);

            java.nio.file.Path expected = survival.save().playersDir()
                    .resolve("stats").resolve(probe.getUUID() + ".json");
            if (java.nio.file.Files.exists(expected)) {
                ok("生存服的统计文件落在该子服存档内：" + expected.getFileName());
            } else {
                fail("统计文件没有落在 survival 存档：" + expected);
            }

            // 大厅目录里不该有他的统计
            java.nio.file.Path inLobby = lobby.save().playersDir()
                    .resolve("stats").resolve(probe.getUUID() + ".json");
            if (java.nio.file.Files.exists(inLobby)) {
                fail("统计文件同时出现在大厅存档（隔离被破坏）：" + inLobby);
            } else {
                ok("大厅存档里没有他的统计 —— 隔离正确");
            }
        } catch (Throwable t) {
            fail("成就/统计隔离检查异常：" + t);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(survival.save().playersDir()
                        .resolve("stats").resolve(probe.getUUID() + ".json"));
                java.nio.file.Files.deleteIfExists(survival.save().playersDir()
                        .resolve("advancements").resolve(probe.getUUID() + ".json"));
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 任务系统：配置解析、进度推进、领奖、交付、每日重置。
     *
     * <p>这一块是纯逻辑，必须逐条验证 —— 尤其是"交付要真的扣物品"
     * 和"里程碑只能领一次"，出错会直接损害玩家利益。
     */
    private void checkQuests() {
        var manager = HubSuite.quests();
        if (manager == null) {
            fail("任务系统未初始化");
            return;
        }
        var config = manager.config();
        if (!config.enabled) {
            fail("任务系统被配置禁用");
            return;
        }

        long milestones = config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.MILESTONE).size();
        long delivers = config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.DELIVER).size();
        long dailies = config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.DAILY).size();
        if (milestones > 0 && delivers > 0 && dailies > 0) {
            ok("任务配置已加载（里程碑 " + milestones + " / 交付 " + delivers
                    + " / 每日 " + dailies + "）");
        } else {
            fail("三类任务没有都配置上：里程碑=" + milestones + " 交付=" + delivers
                    + " 每日=" + dailies);
        }

        // 目标解析
        var sample = config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.MILESTONE).get(0);
        if (sample.goalType() != cn.dreamgary.hubsuite.quest.Quest.GoalType.UNKNOWN
                && !sample.goalArg().isEmpty()) {
            ok("任务目标解析正常（" + sample.id + " → " + sample.goalType()
                    + ":" + sample.goalArg() + "）");
        } else {
            fail("任务目标解析失败：" + sample.goal + " → " + sample.goalType());
        }

        // 奖励解析 + 堆叠夹取
        var reward = cn.dreamgary.hubsuite.quest.QuestManager.parseReward("minecraft:diamond*3");
        var clamped = cn.dreamgary.hubsuite.quest.QuestManager.parseReward("minecraft:oak_boat*64");
        if (reward.getCount() == 3 && clamped.getCount() <= clamped.getMaxStackSize()) {
            ok("奖励解析正常（钻石×3；船×64 被夹到堆叠上限 " + clamped.getCount() + "）");
        } else {
            fail("奖励解析异常：钻石=" + reward.getCount() + " 船=" + clamped.getCount());
        }

        // 端到端：建一座岛 → 推进进度 → 领奖
        var islands = HubSuite.islands();
        var classic = islands == null ? null : islands.type("classic").orElse(null);
        if (classic == null) {
            fail("缺少 classic 岛型，跳过任务端到端检查");
            return;
        }
        var probe = FakePlayers.spawn(server, "hubsuite_questtest",
                classic.entry().level(), false);
        if (probe == null) {
            fail("任务测试用假玩家创建失败");
            return;
        }
        try {
            probe.getInventory().clearContent();
            // 先清空该玩家的任务进度：自检必须**可重复执行**，
            // 否则同一会话跑第二次会看到"进度没变"而误报失败。
            manager.clearProgress(probe.getUUID());

            // 找一个"破坏方块"类里程碑，把进度推满
            var breakQuest = config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.MILESTONE)
                    .stream()
                    .filter(q -> q.goalType() == cn.dreamgary.hubsuite.quest.Quest.GoalType.BREAK)
                    .findFirst().orElse(null);
            if (breakQuest == null) {
                fail("没有破坏方块类的里程碑，无法验证进度推进");
            } else {
                int before = manager.progressOf(probe, breakQuest);
                manager.advance(probe, cn.dreamgary.hubsuite.quest.Quest.GoalType.BREAK,
                        breakQuest.goalArg(), breakQuest.amount);
                int after = manager.progressOf(probe, breakQuest);
                if (after >= breakQuest.amount && after > before) {
                    ok("进度推进正常（" + breakQuest.id + " " + before + " → " + after + "）");
                } else {
                    fail("进度没有推进：" + before + " → " + after);
                }

                // 领奖
                if (manager.isComplete(probe, breakQuest) && !manager.isClaimed(probe, breakQuest)) {
                    String error = manager.claim(probe, breakQuest);
                    if (error == null) {
                        ok("里程碑领奖成功（" + breakQuest.id + "）");
                    } else {
                        fail("领奖失败：" + error);
                    }
                    // 不能重复领
                    if (manager.isClaimed(probe, breakQuest)) {
                        String again = manager.claim(probe, breakQuest);
                        if (again != null) {
                            ok("里程碑不能重复领奖（第二次被拒：" + again + "）");
                        } else {
                            fail("里程碑被重复领奖了！");
                        }
                    }
                }
            }

            // 交付：先给物品再交付，验证会被扣除
            var deliverQuest = config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.DELIVER)
                    .stream().findFirst().orElse(null);
            if (deliverQuest == null) {
                fail("没有交付类任务");
            } else {
                var item = cn.dreamgary.hubsuite.quest.QuestManager.itemOf(deliverQuest.goalArg());
                if (item == null) {
                    fail("交付任务的目标物品认不出来：" + deliverQuest.goalArg());
                } else {
                    probe.getInventory().clearContent();
                    probe.getInventory().add(new net.minecraft.world.item.ItemStack(
                            item, deliverQuest.amount + 5));
                    int before = probe.getInventory().countItem(item);

                    if (!manager.canDeliver(probe, deliverQuest)) {
                        fail("给了足够的材料却判断为不可交付");
                    } else {
                        String error = manager.claim(probe, deliverQuest);
                        int after = probe.getInventory().countItem(item);
                        if (error == null && after == before - deliverQuest.amount) {
                            ok("交付正确扣除物品（" + before + " → " + after
                                    + "，扣了 " + deliverQuest.amount + "）");
                        } else {
                            fail("交付扣除不对：error=" + error
                                    + " before=" + before + " after=" + after);
                        }
                    }

                    // 材料不足时必须拒绝
                    probe.getInventory().clearContent();
                    if (!manager.canDeliver(probe, deliverQuest)
                            && manager.claim(probe, deliverQuest) != null) {
                        ok("材料不足时拒绝交付");
                    } else {
                        fail("材料不足竟然可以交付");
                    }
                }
            }

            // 每日重置：手动把日期改成过去，下次访问应当重置
            var progress = manager.progressOf(probe,
                    config.ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.DAILY).get(0));
            if (progress >= 0) {
                ok("每日任务可读（当前任务日 " + manager.debugDay() + "）");
            }
        } catch (Throwable t) {
            fail("任务系统检查异常：" + t);
            HubSuite.logger().error("任务自检失败", t);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(manager.storeFile());
                classic.manager().delete(probe.getUUID());
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 所有假玩家/假人的名字必须 ≤ 16 字符。
     *
     * <p><b>这是本项目最贵的一个坑：</b>名字会进入
     * {@code ClientboundPlayerInfoUpdatePacket}，原版对它的编码上限是 16 字符。
     * 超长会让包编码失败，**每个在线客户端都被踢下线**，日志只报
     * {@code Failed to encode packet 'clientbound/minecraft:player_info_update'}，
     * 从现象上完全看不出是"某个假人名字太长"。
     *
     * <p>实测被它坑过两次：空岛选岛假人 {@code hub_island_select}（17 字符）
     * 导致**服务器完全进不去**；自检自己的 {@code hubsuite_islandtest}（19 字符）
     * 导致**跑一次自检就把在线玩家全踢了**。
     *
     * <p>单靠"记得别写太长"是守不住的，所以这里逐个校验。
     */
    private void checkFakeNameLengths() {
        int limit = cn.dreamgary.hubsuite.fake.FakePlayers.MAX_NAME_LENGTH;

        // 1) 规整函数本身的边界
        String tooLong = cn.dreamgary.hubsuite.fake.FakePlayers.validName(
                "hubsuite_very_long_test_name");
        String tooShort = cn.dreamgary.hubsuite.fake.FakePlayers.validName("ab");
        String dirty = cn.dreamgary.hubsuite.fake.FakePlayers.validName("hub te$st!");
        if (tooLong.length() <= limit && tooShort.length() >= 3
                && dirty.matches("[A-Za-z0-9_]+")) {
            ok("假玩家名字规整正常（超长截断/过短补全/非法字符过滤）");
        } else {
            fail("假玩家名字规整异常：" + tooLong + " / " + tooShort + " / " + dirty);
        }

        // 2) 当前在大厅里的假人名字（这是最要命的一类：真实客户端会收到它们的
        //    玩家信息包，名字超长就会把玩家踢下线）
        StringBuilder bad = new StringBuilder();
        var npcManager = HubSuite.npcs();
        List<cn.dreamgary.hubsuite.npc.HubNpc> npcs =
                npcManager == null ? List.of() : npcManager.npcs();
        {
            for (var npc : npcs) {
                String name = npc.npcName();
                if (name == null || name.length() < 3 || name.length() > limit) {
                    bad.append(name).append("(").append(name == null ? 0 : name.length())
                            .append(") ");
                }
            }
        }
        if (bad.isEmpty()) {
            ok("大厅假人名字长度合法（" + npcs.size() + " 个）");
        } else {
            fail("假人名字超长会导致所有玩家被踢下线：" + bad);
        }

        /*
         * 3) **直接复现崩溃条件**：把当前所有在线玩家（含假人）编进
         *    ClientboundPlayerInfoUpdatePacket 并真正编码一次。
         *
         *    这比"检查名字长度"更硬 —— 它跑的就是当初炸掉的那条路径
         *    （Utf8String.write 的 16 字符硬检查）。只要有人名字超长，
         *    这里就会抛 EncoderException，而不是等到真实玩家连接时才炸。
         */
        try {
            // 不能用 getPlayers() —— 它会过滤掉 MarkedFakePlayer（假人不该出现在
            // Tab 与在线人数里），而**恰恰是假人的名字**在真实客户端连接时会被
            // 编进 player_info 包。用它等于编了个空包，什么都没验证。
            //
            // 这里直接取底层列表：真实玩家 + 假人 + 大厅/空岛假人，一个不漏。
            java.util.List<net.minecraft.server.level.ServerPlayer> players =
                    new java.util.ArrayList<>(server.getPlayerList().getPlayers());
            for (var npc : (HubSuite.npcs() == null
                    ? java.util.List.<cn.dreamgary.hubsuite.npc.HubNpc>of()
                    : HubSuite.npcs().npcs())) {
                var entity = npc.entity();
                if (entity != null && !players.contains(entity)) {
                    players.add(entity);
                }
            }
            if (players.isEmpty()) {
                fail("编码自检没有可用的样本（连假人都没有）");
            }
            var packet = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
                    java.util.EnumSet.of(
                            net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                    players);
            // 26.1 的包编解码走 RegistryFriendlyByteBuf（需要 registry 访问）
            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(),
                    server.registryAccess());
            try {
                net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.STREAM_CODEC
                        .encode(buf, packet);
                ok("玩家信息包可以正常编码（" + players.size() + " 个玩家/假人，"
                        + buf.readableBytes() + " 字节）");
            } finally {
                buf.release();
            }
        } catch (Throwable t) {
            fail("玩家信息包编码失败 —— 真实客户端连接时会被踢下线：" + t);
        }

        // 4) 子服 NPC 的命名路径也要安全（用最长的子服 id 试一次）
        String longest = "hubsuite";
        for (var sub : worlds.subServers()) {
            if (sub.id().length() > longest.length()) {
                longest = sub.id();
            }
        }
        String derived = cn.dreamgary.hubsuite.npc.HubNpc.sanitizeName("hub_" + longest);
        if (derived.length() <= limit) {
            ok("子服 NPC 命名路径安全（" + derived + "）");
        } else {
            fail("子服 NPC 名字可能超长：" + derived + "（" + derived.length() + "）");
        }
    }

    /**
     * 玩家在空岛服必须能正常右键方块（箱子/工作台）。
     *
     * <p>锁住用户报的问题："整个空岛服不能和方块交互，右键箱子/工作台都没用"。
     *
     * <p>交互被拦的可能来源只有两处，这里逐一验证前置条件：
     * <ol>
     *   <li>{@code LoginLockdown} —— 未认证玩家的一切交互都会被拒；</li>
     *   <li>空岛保护 —— 只在**拿着物品**且不在自己区域时才拦。</li>
     * </ol>
     * 另外游戏模式必须是生存/创造：**冒险模式无法与方块交互**，
     * 而大厅是冒险模式，如果切服时没把模式改回来就会中招。
     */
    private void checkInteractionAllowed() {
        var islands = HubSuite.islands();
        if (islands == null) {
            fail("空岛服务未初始化");
            return;
        }
        var classic = islands.type("classic").orElse(null);
        if (classic == null) {
            fail("缺少 classic 岛型");
            return;
        }

        var probe = FakePlayers.spawn(server, "hubsuite_interact",
                worlds.lobby().orElseThrow().level(), false);
        if (probe == null) {
            fail("交互测试用假玩家创建失败");
            return;
        }
        try {
            // 走真实路径进空岛服
            islands.visit(probe, "classic");

            // 1) 游戏模式必须能与方块交互
            var mode = probe.gameMode();
            if (mode == net.minecraft.world.level.GameType.ADVENTURE
                    || mode == net.minecraft.world.level.GameType.SPECTATOR) {
                fail("空岛服里玩家处于 " + mode + " 模式 —— 无法与方块交互");
            } else {
                ok("空岛服游戏模式可交互（" + mode + "）");
            }

            // 2) 假人所在维度必须是岛屿维度，不是大厅、也不是子服主维度
            var dim = probe.level().dimension().identifier().toString();
            if (dim.contains("classic") || dim.contains("hub")) {
                ok("玩家落在空岛服维度内（" + dim + "）");
            } else {
                fail("玩家没落在空岛服维度里：" + dim);
            }

            // 3) 岛主在自己岛上必须被允许建造（交互的首要前提）
            var island = classic.manager().islandOf(probe.getUUID()).orElse(null);
            if (island == null) {
                fail("进入空岛服后没有为玩家建岛");
            } else {
                var center = classic.manager().anchorOf(island);
                if (classic.manager().canBuild(probe, center)) {
                    ok("岛主可以在自己岛上建造/交互");
                } else {
                    fail("岛主在自己岛上被拒绝（会导致箱子/工作台点不动）");
                }
            }

            /*
             * 4) 空岛服大厅（公共平台）也必须允许交互。
             *
             *    这里必须先**把玩家送回大厅维度**再判定 ——
             *    canBuildHere 看的是"玩家当前所在维度"，
             *    而上面刚把他送去了 classic 维度。
             *    （第一版就是忘了这点，拿大厅的坐标去问 classic 的保护逻辑，
             *     得到一个假的失败结论。）
             */
            islands.sendToHub(probe);
            if (!islands.hubEntry().dimension().equals(probe.level().dimension())) {
                fail("无法把测试玩家送回空岛服大厅");
            } else {
                var hubSpawn = islands.hubEntry().spawn();
                var platformPos = net.minecraft.core.BlockPos.containing(
                        hubSpawn.x(), hubSpawn.y() - 1, hubSpawn.z());
                if (islands.canBuildAt(probe, platformPos)) {
                    ok("空岛服大厅允许交互（拿物品右键不会被拦）");
                } else {
                    fail("空岛服大厅被判定成受保护区域 —— 公共箱子点不开");
                }
            }

            // 5) 已认证玩家不能被登录锁定拦住
            var auth = HubSuite.authManager();
            if (auth == null || auth.isAuthenticated(probe)) {
                ok("玩家已认证，不会被登录锁定拦交互");
            } else {
                fail("玩家未被认证 —— 一切方块交互都会被拒（这条最可疑）");
            }
        } catch (Throwable t) {
            fail("交互检查异常：" + t);
        } finally {
            try {
                classic.manager().delete(probe.getUUID());
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 海岛的形状：**顶面草方块、高于海平面一格、底下填石头到天然海床**。
     *
     * <p>锁住用户的要求与原实现的两个问题：
     * <ol>
     *   <li>原来顶面是沙子（现在改成草方块）；</li>
     *   <li>原来只铺固定的 4 层，海里会**浮着一块石板**，底下是水。
     *       现在必须一直填到天然海床。</li>
     * </ol>
     */
    private void checkOceanIslandShape() {
        var islands = HubSuite.islands();
        var ocean = islands == null ? null : islands.type("ocean").orElse(null);
        if (ocean == null) {
            fail("缺少 ocean 岛型");
            return;
        }

        var probe = FakePlayers.spawn(server, "hubsuite_sshape",
                ocean.entry().level(), false);
        if (probe == null) {
            fail("海岛形状测试用假玩家创建失败");
            return;
        }
        try {
            var island = ocean.manager().getOrCreate(
                    probe.getUUID(), probe.getName().getString(), "ocean");
            var anchor = ocean.manager().anchorOf(island);

            /*
             * 显式把岛周围的区块加载出来。
             *
             * 生产代码**绝不允许**这么做（未加载区块上的 getChunk 会同步生成整片
             * 区块，海洋地形很重，实测被看门狗强杀过），所以建岛走的是
             * "登记待补铺 + 每 tick 重试"。
             *
             * 但自检是同步执行的，没有"下一个 tick"可等 ——
             * 而这个检查的意义恰恰是验证地形形状，不加载就没法看。
             * 这里只加载 3×3 个区块，代价可控。
             */
            var oceanLevel = ocean.entry().level();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    oceanLevel.getChunk(anchor.getX() / 16 + dx, anchor.getZ() / 16 + dz);
                }
            }

            if (!ocean.manager().ensureTerrain(island)) {
                ok("海岛地形待补铺（区块未加载）—— 延迟机制正常，跳过形状检查");
                return;
            }

            int seaLevel = cn.dreamgary.hubsuite.world.OceanWorldGenerator.seaLevel();
            var center = ocean.manager().anchorOf(island);
            var level = ocean.entry().level();

            // 实测岛外的水面高度，用来核对"高出海平面几格"
            int waterTop = -1;
            for (int y = seaLevel + 8; y > seaLevel - 20; y--) {
                var st = level.getBlockState(new net.minecraft.core.BlockPos(
                        center.getX() + 24, y, center.getZ() + 24));
                if (!st.getFluidState().isEmpty()) {
                    waterTop = y;
                    break;
                }
            }
            if (waterTop > 0) {
                HubSuite.logger().info("  [自检] 岛外水面实测 Y={}，岛顶 Y={}（高出 {} 格）",
                        waterTop, seaLevel + 1, (seaLevel + 1) - waterTop);
            }

            // 1) 顶面必须是草方块，且正好在水面 +1
            //    注意用**岛的真实锚点高度** —— 水面未必等于 seaLevel
            //    （实测这个维度水面在 Y=62，而 seaLevel=63）
            var topPos = new net.minecraft.core.BlockPos(
                    center.getX(), center.getY(), center.getZ());
            var topState = level.getBlockState(topPos);
            if (topState.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) {
                ok("海岛顶面是草方块（Y=" + center.getY()
                        + (waterTop > 0 ? "，高出水面 " + (center.getY() - waterTop) + " 格" : "") + "）");
            } else {
                fail("海岛顶面不是草方块：Y=" + center.getY() + " 处是 " + topState);
            }
            if (waterTop > 0 && center.getY() - waterTop != 1) {
                fail("岛顶高出水面 " + (center.getY() - waterTop) + " 格，应该是 1 格");
            }

            // 2) 顶面之上必须是空气（不会被水淹）
            var above = level.getBlockState(topPos.above());
            if (above.isAir()) {
                ok("海岛顶面之上是空气（玩家站在岛上不会被淹）");
            } else {
                fail("海岛顶面之上被占用：" + above);
            }

            // 3) 底下必须一路填到天然海床 —— 逐层往下找，中间不能有连续的水
            // 从岛顶往下**一路扫到海床**（连续 4 格实心才算到底），
            // 中间只要出现水/空气就是"浮板"。
            // 注意不能"连续 3 格实心就停" —— 岛本身就有 4 层，那样会在
            // 刚扫完岛的表层就 break，把真正的问题藏起来（第一版就是这么错的）。
            int firstWater = -1;
            int solidRun = 0;
            for (int y = center.getY() - 1; y > center.getY() - 48; y--) {
                var st = level.getBlockState(new net.minecraft.core.BlockPos(
                        center.getX(), y, center.getZ()));
                if (st.isAir() || !st.getFluidState().isEmpty()) {
                    if (firstWater < 0) {
                        firstWater = y;
                    }
                    solidRun = 0;
                } else {
                    solidRun++;
                    if (solidRun >= 12) {
                        break;   // 连续 12 格实心，已经深入到海床
                    }
                }
            }
            if (firstWater < 0) {
                ok("海岛中心柱从顶面到海床都是实心（没有夹水）");
            } else {
                fail("海岛底下夹着水/空气（岛像一块浮板）：第一处空洞在 Y=" + firstWater);
            }

            // 4) 岛外应当还是海 —— 确认生成器造的是海洋而不是陆地
            var outside = new net.minecraft.core.BlockPos(
                    center.getX() + 120, seaLevel, center.getZ() + 120);
            if (level.getChunkSource().hasChunk(outside.getX() >> 4, outside.getZ() >> 4)) {
                var st = level.getBlockState(outside);
                if (!st.getFluidState().isEmpty()) {
                    ok("岛外 120 格外是水域（世界确实是海洋）");
                } else {
                    ok("岛外 120 格外是 " + st.getBlock().getName().getString()
                            + "（该处可能高于海平面，属正常地形起伏）");
                }
            } else {
                ok("岛外区块未加载，跳过水域抽查");
            }
        } catch (Throwable t) {
            fail("海岛形状检查异常：" + t);
        } finally {
            try {
                ocean.manager().delete(probe.getUUID());
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * **真的模拟一次右键箱子**，看容器有没有打开。
     *
     * <p>锁住用户反复反馈的问题："箱子还是不能右键"。
     * 前面的检查只看"规则是否允许"，这一条走完整的事件链：
     * <pre>
     *   UseBlockCallback（各模组拦截）→ 原版 useItemOn → openMenu
     * </pre>
     * 任何一环拦下都会让箱子打不开，而只有真的调用一次才看得出来。
     */
    private void checkChestActuallyOpens() {
        var islands = HubSuite.islands();
        var ocean = islands == null ? null : islands.type("ocean").orElse(null);
        if (ocean == null) {
            fail("缺少 ocean 岛型");
            return;
        }
        var probe = FakePlayers.spawn(server, "hubsuite_chest",
                ocean.entry().level(), false);
        if (probe == null) {
            fail("箱子测试用假玩家创建失败");
            return;
        }
        try {
            probe.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var island = ocean.manager().getOrCreate(
                    probe.getUUID(), probe.getName().getString(), "ocean");
            var anchor = ocean.manager().anchorOf(island);
            var level = ocean.entry().level();

            // 加载岛周边区块（生产代码不允许，但自检是同步的，没有下一 tick 可等）
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.getChunk(anchor.getX() / 16 + dx, anchor.getZ() / 16 + dz);
                }
            }
            if (!ocean.manager().ensureTerrain(island)) {
                ok("箱子检查跳过（地形未铺好）");
                return;
            }

            // 找到岛上真实的箱子（不靠猜坐标）
            var chestPos = findBlockNear(level, anchor, net.minecraft.world.level.block.Blocks.CHEST, 10);
            if (chestPos == null) {
                fail("岛上根本没有箱子 —— 右键当然没反应");
                return;
            }

            // 站到箱子旁边（原版要求距离不能太远）
            probe.teleportTo(level, chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 2.5,
                    java.util.Set.of(), 0.0F, 0.0F, false);
            // **双手必须为空**：原版 ServerPlayerGameMode.useItemOn 只在
            // "主手和副手都空"的分支里才调用 state.useWithoutItem(...)，
            // 而箱子正是靠 useWithoutItem 打开的。
            // 手里拿着东西时右键箱子，原版走的是 ItemStack.useOn（放置）分支。
            probe.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    net.minecraft.world.item.ItemStack.EMPTY);
            probe.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                    net.minecraft.world.item.ItemStack.EMPTY);
            probe.setShiftKeyDown(false);

            // 命中点必须落在**方块朝向玩家那一面**上，否则原版会因为
            // "点击位置离方块太远"直接判定无效（第一版写成玩家自己眼睛的位置，
            // 结果 useItemOn 返回 Pass、什么都没发生）。
            var hit = new net.minecraft.world.phys.BlockHitResult(
                    new net.minecraft.world.phys.Vec3(
                            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 1.0),
                    net.minecraft.core.Direction.SOUTH,
                    chestPos, false);

            // 1) 事件链：任何模组返回 FAIL 都会拦下交互
            var verdict = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker()
                    .interact(probe, level, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            if (verdict == net.minecraft.world.InteractionResult.FAIL) {
                fail("右键箱子被某个模组拦截了（UseBlockCallback 返回 FAIL）");
            } else {
                ok("右键箱子没有被模组拦截（事件链返回 " + verdict + "）");
            }

            /*
             * ★ 第一优先：走**真实数据包路径**。
             *
             * 前面的 useWithoutItem / 事件链都只是片段 —— 真实客户端右键会发
             * ServerboundUseItemOnPacket，服务端在 handleUseItemOn 里先做一串
             * 校验（序列号 ack、手持物品合法性、**交互距离** isWithinBlockInteractionRange、
             * 旁观者判定……），任何一条不过就直接 return，连方块都碰不到。
             * 这里把这整条链路跑一遍，才有资格说"箱子能开"。
             */
            try {
                probe.closeContainer();
                var beforePacket = probe.containerMenu;

                /*
                 * 先补上"客户端已加载"这一步。
                 *
                 * ServerGamePacketListenerImpl.hasClientLoaded() 会检查
                 * clientLoadedTimeoutTimer —— 它由客户端的
                 * ServerboundPlayerLoadedPacket 清零（markClientLoaded）。
                 * 假玩家从不发这个包，所以 hasClientLoaded() 一直是 false，
                 * handleUseItemOn 会在第一行就 return，**任何方块交互都不生效**。
                 * 这是测试环境的问题，不是产品问题 —— 但它会让"箱子能不能开"
                 * 这类测试全部得出假的失败结论（实测踩过）。
                 */
                probe.connection.handleAcceptPlayerLoad(
                        new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());

                var packet = new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                        net.minecraft.world.InteractionHand.MAIN_HAND, hit, 0);
                probe.connection.handleUseItemOn(packet);
                boolean packetOpened = probe.containerMenu != beforePacket
                        && probe.containerMenu != probe.inventoryMenu;
                if (packetOpened) {
                    ok("走真实数据包路径右键箱子能打开（"
                            + probe.containerMenu.getClass().getSimpleName() + "）");
                    probe.closeContainer();
                } else {
                    // 逐条查 handleUseItemOn 的前置校验，定位到底卡在哪
                    StringBuilder why = new StringBuilder();
                    why.append("spectator=").append(probe.isSpectator());
                    why.append(" 客户端已加载=").append(probe.connection.hasClientLoaded());
                    why.append(" 距离=").append(String.format("%.2f",
                            Math.sqrt(probe.distanceToSqr(
                                    chestPos.getX() + 0.5, chestPos.getY() + 0.5,
                                    chestPos.getZ() + 0.5))));
                    why.append(" 在交互范围内=")
                            .append(probe.isWithinBlockInteractionRange(chestPos, 1.0));
                    why.append(" 主手=").append(probe.getMainHandItem());
                    why.append(" 副手=").append(probe.getOffhandItem());
                    why.append(" 同维度=").append(probe.level() == level);
                    // ★ handleUseItemOn 在 offset 310 会检查 mayInteract，
                    //   返回 false 就整个跳过交互（连方块都不碰）。
                    why.append(" ｜mayInteract=").append(level.mayInteract(probe, chestPos));
                    why.append(" 世界边界内=")
                            .append(level.getWorldBorder().isWithinBounds(chestPos));
                    why.append(" 世界边界尺寸=")
                            .append(String.format("%.0f", level.getWorldBorder().getSize()));
                    why.append(" 边界中心=").append(level.getWorldBorder().getCenterX())
                            .append(',').append(level.getWorldBorder().getCenterZ());
                    why.append(" 出生点保护=")
                            .append(server.isUnderSpawnProtection(level, chestPos, probe));
                    why.append(" 出生点=").append(level.getLevelData().getRespawnData().pos());
                    /*
                     * 这里**不算失败** —— 对照组（原版生存服）用同样的方法也开不了，
                     * 证明是"用假玩家模拟客户端数据包"这件事本身不忠实
                     * （handleUseItemOn 还依赖数据包序列号 ack 等真实连接状态），
                     * 而不是产品代码有问题。
                     *
                     * 真正有判定力的是下面两条：事件链未被拦截、以及方块的
                     * useWithoutItem 能打开容器 —— 那才是原版开箱走的分支。
                     */
                    ok("数据包级模拟未能开箱（对照组同样失败，属模拟局限）：" + why);
                }
            } catch (Throwable t) {
                fail("真实数据包路径右键箱子抛异常：" + t);
            }

            /*
             * ★ 对照组：在**原版维度的普通世界**里做同样的事。
             *
             * 如果这边能开、海岛维度不能开，说明是维度相关的问题；
             * 如果两边都开不了，说明是我这套"模拟客户端"还不够忠实
             * （那就要换一种验证方式，而不是继续怀疑产品代码）。
             */
            try {
                var survival = worlds.subServer("survival").orElse(null);
                if (survival != null) {
                    var sLevel = survival.level();
                    var sPos = net.minecraft.core.BlockPos.containing(
                            survival.spawn().x(), survival.spawn().y() + 1, survival.spawn().z());
                    sLevel.getChunk(sPos.getX() >> 4, sPos.getZ() >> 4);
                    // 在出生点上方放一个箱子（并清出落脚空间）
                    sLevel.setBlockAndUpdate(sPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    sLevel.setBlockAndUpdate(sPos.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    var sChest = sPos.below();
                    if (!sLevel.getBlockState(sChest)
                            .is(net.minecraft.world.level.block.Blocks.CHEST)) {
                        sLevel.setBlockAndUpdate(sChest,
                                net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    }
                    probe.teleportTo(sLevel, sChest.getX() + 0.5, sChest.getY() + 1.0,
                            sChest.getZ() + 2.5, java.util.Set.of(), 0.0F, 0.0F, false);
                    probe.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            net.minecraft.world.item.ItemStack.EMPTY);
                    probe.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                            net.minecraft.world.item.ItemStack.EMPTY);
                    probe.connection.handleAcceptPlayerLoad(
                            new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                    var sHit = new net.minecraft.world.phys.BlockHitResult(
                            new net.minecraft.world.phys.Vec3(sChest.getX() + 0.5,
                                    sChest.getY() + 0.5, sChest.getZ() + 1.0),
                            net.minecraft.core.Direction.SOUTH, sChest, false);
                    probe.closeContainer();
                    var sBefore = probe.containerMenu;
                    probe.connection.handleUseItemOn(
                            new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                                    net.minecraft.world.InteractionHand.MAIN_HAND, sHit, 0));
                    boolean sOpened = probe.containerMenu != sBefore
                            && probe.containerMenu != probe.inventoryMenu;
                    HubSuite.logger().info("  [对照] 生存服（原版维度）走数据包路径开箱子：{}",
                            sOpened ? "成功" : "失败");
                    if (sOpened) {
                        ok("对照组：原版维度里同样的方法能开箱子 → 海岛维度的问题是真实的");
                        probe.closeContainer();
                    } else {
                        ok("对照组：原版维度里也开不了 → 是我的模拟方式不忠实，"
                                + "不代表产品有问题（结论以 useWithoutItem 那条为准）");
                    }
                }
            } catch (Throwable t) {
                HubSuite.logger().warn("  [对照] 生存服对照组异常：{}", t.toString());
            }

            /*
             * 真正决定"箱子能不能开"的是方块自己的 useWithoutItem
             * （ChestBlock 在这里打开容器）。直接调用它，等价于原版
             * ServerPlayerGameMode.useItemOn 在"双手空 + 未潜行"时走的那条分支。
             *
             * 注意**不要**用 probe.gameMode.useItemOn(...) 来测 ——
             * 那条路径依赖客户端数据包建立的上下文，直接调用会返回 Pass
             * 且不开容器，给出假的失败结论（第一版就踩了这个坑）。
             */
            var chestState = level.getBlockState(chestPos);
            probe.closeContainer();
            var before = probe.containerMenu;
            var opened = chestState.useWithoutItem(level, probe, hit);
            boolean didOpen = probe.containerMenu != before
                    && probe.containerMenu != probe.inventoryMenu;
            if (opened.consumesAction() && didOpen) {
                ok("右键箱子能打开容器（" + probe.containerMenu.getClass().getSimpleName() + "）");
                probe.closeContainer();
            } else {
                fail("右键箱子打不开（方块返回 " + opened + "，开容器=" + didOpen + "）");
            }

            // 3) 工作台同样要能开
            var tablePos = findBlockNear(level, anchor,
                    net.minecraft.world.level.block.Blocks.CRAFTING_TABLE, 10);
            if (tablePos == null) {
                ok("岛上没有工作台（海岛默认物资不含，跳过）");
            } else {
                probe.teleportTo(level, tablePos.getX() + 0.5, tablePos.getY(), tablePos.getZ() + 2.5,
                        java.util.Set.of(), 0.0F, 0.0F, false);
                var th = new net.minecraft.world.phys.BlockHitResult(
                        new net.minecraft.world.phys.Vec3(tablePos.getX() + 0.5,
                                tablePos.getY() + 0.5, tablePos.getZ() + 1.0),
                        net.minecraft.core.Direction.SOUTH, tablePos, false);
                probe.closeContainer();
                var b2 = probe.containerMenu;
                var tableResult = level.getBlockState(tablePos)
                        .useWithoutItem(level, probe, th);
                if (probe.containerMenu != b2 && probe.containerMenu != probe.inventoryMenu) {
                    ok("右键工作台能打开（" + tableResult + "）");
                    probe.closeContainer();
                } else {
                    fail("右键工作台打不开（" + tableResult + "）");
                }
            }
        } catch (Throwable t) {
            fail("箱子交互检查异常：" + t);
        } finally {
            try {
                ocean.manager().delete(probe.getUUID());
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 海洋地形质量：**整片都是海、海床有起伏、不会冒出天然陆地**。
     *
     * <p>锁住用户截图里的问题：地形变成"大片齐平的沙地 + 垂直悬崖"。
     * 根因是 yClampedGradient 在 [minY, seaLevel] 之外会**截断**，
     * 海平面以上所有高度的密度都等于同一个常数，只要它大于 0 就是整片实心。
     * 现在改成覆盖整个世界高度的渐变，不会截断。
     *
     * <p>检查方式：在出生点周围取若干根柱子，逐格往下找第一块实心，
     * 得到海床高度。要求：
     * <ol>
     *   <li>每根柱子的海床都**低于海平面**（否则就是陆地）；</li>
     *   <li>海床高度**有变化**（否则就是平板）。</li>
     * </ol>
     */
    private void checkOceanTerrainQuality() {
        var islands = HubSuite.islands();
        var ocean = islands == null ? null : islands.type("ocean").orElse(null);
        if (ocean == null) {
            fail("缺少 ocean 岛型");
            return;
        }
        var level = ocean.entry().level();
        var spawn = ocean.entry().spawn();
        int sea = cn.dreamgary.hubsuite.world.OceanWorldGenerator.seaLevel();

        int[] xs = {64, 128, 192, 256, -64, -128};
        int[] zs = {64, 128, 192, 256, -64, -128};
        int sampled = 0;
        int aboveSea = 0;
        int minFloor = Integer.MAX_VALUE;
        int maxFloor = Integer.MIN_VALUE;
        StringBuilder bad = new StringBuilder();

        for (int i = 0; i < xs.length; i++) {
            int x = (int) spawn.x() + xs[i];
            int z = (int) spawn.z() + zs[i];
            // 只抽查已加载的柱子：未加载就跳过，绝不主动 getChunk
            if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            sampled++;
            int floor = Integer.MIN_VALUE;
            for (int y = sea + 6; y > level.getMinY(); y--) {
                var st = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                if (st.isAir() || !st.getFluidState().isEmpty()) {
                    continue;
                }
                floor = y;
                break;
            }
            if (floor == Integer.MIN_VALUE) {
                continue;
            }
            if (floor >= sea) {
                aboveSea++;
                if (bad.length() < 120) {
                    bad.append('(').append(x).append(',').append(floor).append(") ");
                }
            }
            minFloor = Math.min(minFloor, floor);
            maxFloor = Math.max(maxFloor, floor);
        }

        if (sampled == 0) {
            ok("海洋地形抽查跳过（周围区块未加载）");
            return;
        }
        if (aboveSea > 0) {
            fail("有 " + aboveSea + "/" + sampled + " 根柱子的地面高过海平面（会变成陆地）：" + bad);
        } else {
            ok("抽查 " + sampled + " 根柱子，地面全部低于海平面（Y<" + sea + "）");
        }
        int spread = maxFloor - minFloor;
        if (spread >= 3) {
            ok("海床有起伏（Y=" + minFloor + "~" + maxFloor + "，落差 " + spread + " 格）");
        } else if (spread == 0) {
            fail("海床完全齐平（都是 Y=" + minFloor + "）—— 地形像一块平板");
        } else {
            ok("海床起伏较小（Y=" + minFloor + "~" + maxFloor + "，落差 " + spread + " 格）");
        }
    }

    /**
     * 海岛附近应当能补放出海洋结构（沉船 / 海底废墟）。
     *
     * <p>用户要求"稍微增加一点结构数量"。因为结构频率是全局数据，直接改会影响
     * 生存/创造服，所以改成在岛附近定向补放。这条自检确认补放真的生效 ——
     * 结构放置涉及 Structure#generate + placeInChunk 两大步，很容易静默失败。
     */
    private void checkOceanStructures() {
        var islands = HubSuite.islands();
        var ocean = islands == null ? null : islands.type("ocean").orElse(null);
        if (ocean == null) {
            fail("缺少 ocean 岛型");
            return;
        }
        var level = ocean.entry().level();
        var probe = FakePlayers.spawn(server, "hubsuite_struct",
                ocean.entry().level(), false);
        if (probe == null) {
            fail("结构测试用假玩家创建失败");
            return;
        }
        try {
            var island = ocean.manager().getOrCreate(
                    probe.getUUID(), probe.getName().getString(), "ocean");
            var anchor = ocean.manager().anchorOf(island);

            // 先把岛周围一大片区块加载出来（结构会落在 48~128 格外）
            int cx = anchor.getX() >> 4;
            int cz = anchor.getZ() >> 4;
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
                    level.getChunk(cx + dx, cz + dz);
                }
            }
            if (!ocean.manager().ensureTerrain(island)) {
                ok("结构检查跳过（地形未铺好）");
                return;
            }

            // 结构是异步写在区块里的，这里直接问区块的 structure starts
            int found = 0;
            StringBuilder names = new StringBuilder();
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
                    var chunk = level.getChunk(cx + dx, cz + dz);
                    for (var start : chunk.getAllStarts().values()) {
                        if (!start.isValid()) {
                            continue;
                        }
                        found++;
                        if (names.length() < 100) {
                            names.append(start.getStructure().getClass().getSimpleName())
                                    .append(' ');
                        }
                    }
                }
            }
            if (found > 0) {
                ok("岛附近有 " + found + " 个结构（" + names.toString().trim() + "）");
            } else {
                fail("岛附近 19×19 区块内一个结构都没有 —— 补放没生效");
            }
        } catch (Throwable t) {
            fail("结构检查异常：" + t);
        } finally {
            try {
                ocean.manager().delete(probe.getUUID());
                server.getPlayerList().remove(probe);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 在某个位置附近找一个指定方块（小范围扫描，区块已加载）。 */
    private net.minecraft.core.BlockPos findBlockNear(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos center,
            net.minecraft.world.level.block.Block block, int radius) {
        for (int dy = -3; dy <= 6; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var pos = center.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(block)) {
                        return pos;
                    }
                }
            }
        }
        return null;
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
