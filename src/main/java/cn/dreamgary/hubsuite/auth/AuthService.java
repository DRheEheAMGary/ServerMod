package cn.dreamgary.hubsuite.auth;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 登录 / 注册的业务逻辑。
 *
 * <p>刻意不依赖任何游戏界面（只依赖 {@link TaskScheduler} 抽象），
 * 因此可以被自检与单元测试直接驱动。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>正版与离线一视同仁</b>：两种玩家都必须注册并登录。</li>
 *   <li><b>会话保持</b>：登录成功后按 UUID 记一条会话（含 IP 与过期时间），
 *       同 IP 在有效期内重进免登录。</li>
 *   <li><b>失败限速</b>：连续失败到阈值即锁定，锁定信息写库，重启依然有效。</li>
 *   <li><b>不阻塞主线程</b>：PBKDF2 有计算开销，校验放在后台线程，
 *       结果再回到服务端线程（见 {@link #loginAsync}）。</li>
 * </ul>
 */
public final class AuthService {

    /** 认证结果状态。 */
    public enum Status {
        OK,
        BAD_PASSWORD,
        LOCKED,
        NO_ACCOUNT,
        NAME_TAKEN,
        WEAK_PASSWORD,
        MISMATCH,
        STORAGE_ERROR,
        DISABLED
    }

    /** 结果 + 给玩家看的原因。 */
    public record Result(Status status, String message) {
        public boolean ok() {
            return status == Status.OK;
        }

        static Result of(Status status, String message) {
            return new Result(status, message);
        }
    }

    /** 需要"回到游戏线程"时用的调度器（服务端就是 {@code server::execute}）。 */
    @FunctionalInterface
    public interface TaskScheduler {
        void execute(Runnable task);
    }

    private final AuthStore store;
    private final HubSuiteConfig.AuthConfig config;
    private final ExecutorService worker;
    private final TaskScheduler scheduler;

    public AuthService(Path dataDir, HubSuiteConfig.AuthConfig config, TaskScheduler scheduler) {
        this.config = config;
        this.scheduler = scheduler == null ? Runnable::run : scheduler;
        this.store = new AuthStore(dataDir);
        this.store.open();
        this.worker = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "HubSuite-Auth");
                    t.setDaemon(true);
                    return t;
                });
    }

    public void shutdown() {
        worker.shutdownNow();
        store.close();
    }

    public AuthStore store() {
        return store;
    }

    public HubSuiteConfig.AuthConfig config() {
        return config;
    }

    /** 后台执行，回调切回服务端线程。 */
    public CompletableFuture<Result> supplyAsync(Supplier<Result> task) {
        return CompletableFuture.supplyAsync(task, worker)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        HubSuite.logger().error("认证任务异常", error);
                    }
                });
    }

    /**
     * 后台判断"该玩家是否已注册"，结果回到服务端线程。
     *
     * <p>为什么不直接同步查：玩家进服时会紧接着要弹对话框，
     * 同步查库会阻塞服务端主线程（数据库首次访问尤其明显），
     * 表现为"进服后要愣几秒才弹窗"。
     */
    public void isRegisteredAsync(String username, java.util.function.Consumer<Boolean> callback) {
        CompletableFuture.supplyAsync(() -> isRegistered(username), worker)
                .thenAccept(registered -> scheduler.execute(() -> callback.accept(registered)));
    }

    /** 后台校验密码，结果回到服务端线程。 */
    public void loginAsync(String username, UUID uuid, String password, String ip,
                           java.util.function.Consumer<Result> callback) {
        supplyAsync(() -> login(username, uuid, password, ip))
                .thenAccept(result -> scheduler.execute(() -> callback.accept(result)));
    }

    /** 后台注册，结果回到服务端线程。 */
    public void registerAsync(String username, UUID uuid, String password, String confirm, String ip,
                              java.util.function.Consumer<Result> callback) {
        supplyAsync(() -> register(username, uuid, password, confirm, ip))
                .thenAccept(result -> scheduler.execute(() -> callback.accept(result)));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public boolean isRegistered(String username) {
        return store.exists(username);
    }

    public boolean isRegistered(UUID uuid) {
        return store.findByUuid(uuid).isPresent();
    }

    public Optional<AuthStore.Account> account(String username) {
        return store.find(username);
    }

    public int accountCount() {
        return store.count();
    }

    /** 会话是否可用于免登录；IP 必须与建档时一致。 */
    public boolean sessionValid(UUID uuid, String ip, long now) {
        if (config.sessionMinutes <= 0) {
            return false;
        }
        return store.sessionIp(uuid, now)
                .map(saved -> saved.isEmpty() || saved.equals(ip))
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    public Result register(String username, UUID uuid, String password, String confirm, String ip) {
        if (!config.enabled) {
            return Result.of(Status.DISABLED, "登录系统已关闭。");
        }
        if (username == null || username.isBlank()) {
            return Result.of(Status.STORAGE_ERROR, "无法获取你的名字。");
        }
        if (password == null || password.length() < config.minPasswordLength) {
            return Result.of(Status.WEAK_PASSWORD, "密码至少 " + config.minPasswordLength + " 位。");
        }
        if (password.length() > config.maxPasswordLength) {
            return Result.of(Status.WEAK_PASSWORD, "密码最多 " + config.maxPasswordLength + " 位。");
        }
        if (!password.equals(confirm)) {
            return Result.of(Status.MISMATCH, "两次输入的密码不一致。");
        }

        // 离线服下"名字"就是身份，所以名字被占用时必须拒绝，否则可以冒名顶替
        Optional<AuthStore.Account> byName = store.find(username);
        if (byName.isPresent()) {
            return Result.of(Status.NAME_TAKEN, "该名字已注册，请直接登录。");
        }
        Optional<AuthStore.Account> byUuid = store.findByUuid(uuid);
        if (byUuid.isPresent()) {
            return Result.of(Status.NAME_TAKEN,
                    "该账号（" + byUuid.get().username() + "）已注册，请使用原名字登录。");
        }

        String hash = PasswordHasher.hash(password, config.pbkdf2Iterations);
        long now = System.currentTimeMillis();
        AuthStore.Account account = new AuthStore.Account(
                username, uuid, hash, now, now, ip == null ? "" : ip, 0, 0L);
        if (!store.create(account)) {
            return Result.of(Status.STORAGE_ERROR, "写入账号失败，请联系管理员。");
        }
        store.putSession(uuid, ip, now + config.sessionMinutes * 60_000L);
        HubSuite.logger().info("新账号注册：{}（{}）", username, uuid);
        return Result.of(Status.OK, "注册成功！");
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    public Result login(String username, UUID uuid, String password, String ip) {
        if (!config.enabled) {
            return Result.of(Status.DISABLED, "登录系统已关闭。");
        }
        Optional<AuthStore.Account> maybe = store.find(username);
        if (maybe.isEmpty()) {
            return Result.of(Status.NO_ACCOUNT, "该名字还没有注册。");
        }
        AuthStore.Account account = maybe.get();
        long now = System.currentTimeMillis();

        if (account.locked()) {
            long remain = Math.max(1, (account.lockedUntil() - now) / 1000L);
            return Result.of(Status.LOCKED, "尝试次数过多，请等待 " + remain + " 秒后重试。");
        }

        if (!PasswordHasher.verify(password, account.passwordHash())) {
            store.recordFailure(username, config.maxFailures, config.lockoutMinutes * 60_000L, now);
            int used = account.failures() + 1;
            int left = Math.max(0, config.maxFailures - used);
            return Result.of(Status.BAD_PASSWORD,
                    left > 0 ? "密码错误，还可尝试 " + left + " 次。" : "密码错误次数过多，账号已临时锁定。");
        }

        store.recordSuccess(username, ip, now, uuid);
        store.putSession(uuid, ip, now + config.sessionMinutes * 60_000L);
        return Result.of(Status.OK, "登录成功！");
    }

    // ------------------------------------------------------------------
    // 管理
    // ------------------------------------------------------------------

    public Result resetPassword(String username, String newPassword) {
        if (!store.exists(username)) {
            return Result.of(Status.NO_ACCOUNT, "账号不存在。");
        }
        if (newPassword == null || newPassword.length() < config.minPasswordLength) {
            return Result.of(Status.WEAK_PASSWORD, "密码至少 " + config.minPasswordLength + " 位。");
        }
        String hash = PasswordHasher.hash(newPassword, config.pbkdf2Iterations);
        if (!store.updatePassword(username, hash)) {
            return Result.of(Status.STORAGE_ERROR, "重置失败。");
        }
        // 改密后必须作废所有会话。
        // 不作废的话，攻击者只要在同 IP 的会话有效期内重连就继续免密登录
        // （默认最长 60 分钟）—— 管理员以为改了密码就封住了，实际没有。
        invalidateSessionsOf(username);
        return Result.of(Status.OK, "已重置 " + username + " 的密码（该账号的登录会话已失效）。");
    }

    /** 作废某个账号的全部会话（改密/删号后必须调用）。 */
    private void invalidateSessionsOf(String username) {
        try {
            store.find(username).ifPresent(account -> store.clearSession(account.uuid()));
        } catch (Throwable t) {
            HubSuite.logger().warn("作废 {} 的会话失败（建议手动 /auth kick）", username, t);
        }
    }

    public Result deleteAccount(String username) {
        // 注意顺序：先取 UUID 作废会话，再删账号（删完就查不到 UUID 了）
        invalidateSessionsOf(username);
        return store.delete(username)
                ? Result.of(Status.OK, "已删除账号 " + username + "。")
                : Result.of(Status.NO_ACCOUNT, "账号不存在。");
    }

    public void invalidateSession(UUID uuid) {
        store.clearSession(uuid);
    }

    public void purgeExpiredSessions() {
        store.purgeExpiredSessions(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    public static String ipOf(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return address == null ? "" : address.toString();
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
