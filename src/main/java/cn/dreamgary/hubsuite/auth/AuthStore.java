package cn.dreamgary.hubsuite.auth;

import cn.dreamgary.hubsuite.HubSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

/**
 * 账号库。默认 SQLite（{@code config/hubsuite/accounts.db}）。
 *
 * <p>SQLite 驱动由 HuskHomes 自带（xerial sqlite-jdbc），因此本模组不再打包一份。
 * 如果驱动缺失（例如同时卸载了 HuskHomes），会自动回落到同目录下的
 * {@code accounts.json}，保证登录功能不会因为依赖缺失而彻底失效。
 *
 * <p>所有方法都会把 {@link SQLException} 转成"操作失败"的返回值，不让异常冒到
 * 服务端主线程 —— 一场数据库抖动不该把玩家踢下线。
 */
public final class AuthStore {

    private final Path databaseFile;
    private final FallbackStore fallback;
    private boolean useFallback;
    private Connection connection;

    public AuthStore(Path dataDir) {
        this.databaseFile = dataDir.resolve("accounts.db");
        this.fallback = new FallbackStore(dataDir.resolve("accounts.json"));
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void open() {
        try {
            Files.createDirectories(databaseFile.getParent());
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            connection.setAutoCommit(true);
            migrate();
            useFallback = false;
            HubSuite.logger().info("账号库已就绪（SQLite）：{}", databaseFile);
        } catch (Throwable t) {
            useFallback = true;
            connection = null;
            HubSuite.logger().warn("SQLite 不可用（{}），账号将保存到 {}。",
                    t.toString(), fallback.file());
            fallback.load();
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败无所谓
            }
            connection = null;
        }
        if (useFallback) {
            fallback.save();
        }
    }

    public boolean usingFallback() {
        return useFallback;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        username      TEXT PRIMARY KEY COLLATE NOCASE,
                        uuid          TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at    INTEGER NOT NULL,
                        last_login_at INTEGER NOT NULL DEFAULT 0,
                        last_ip       TEXT NOT NULL DEFAULT '',
                        failures      INTEGER NOT NULL DEFAULT 0,
                        locked_until  INTEGER NOT NULL DEFAULT 0
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        uuid       TEXT PRIMARY KEY,
                        ip         TEXT NOT NULL,
                        expires_at INTEGER NOT NULL
                    )""");
        }
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------

    /** 账号记录。 */
    public record Account(String username,
                          UUID uuid,
                          String passwordHash,
                          long createdAt,
                          long lastLoginAt,
                          String lastIp,
                          int failures,
                          long lockedUntil) {

        public boolean locked() {
            return lockedUntil > System.currentTimeMillis();
        }
    }

    public Optional<Account> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        if (useFallback) {
            return fallback.find(username);
        }
        String sql = "SELECT username, uuid, password_hash, created_at, last_login_at, last_ip, failures, locked_until "
                + "FROM accounts WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readAccount(rs));
                }
            }
        } catch (SQLException e) {
            HubSuite.logger().error("查询账号 '{}' 失败", username, e);
        }
        return Optional.empty();
    }

    public Optional<Account> findByUuid(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        if (useFallback) {
            return fallback.findByUuid(uuid);
        }
        String sql = "SELECT username, uuid, password_hash, created_at, last_login_at, last_ip, failures, locked_until "
                + "FROM accounts WHERE uuid = ? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readAccount(rs));
                }
            }
        } catch (SQLException e) {
            HubSuite.logger().error("按 UUID 查询账号失败", e);
        }
        return Optional.empty();
    }

    public boolean exists(String username) {
        return find(username).isPresent();
    }

    /** 新建账号。用户名冲突返回 false。 */
    public boolean create(Account account) {
        if (useFallback) {
            return fallback.create(account);
        }
        String sql = "INSERT INTO accounts (username, uuid, password_hash, created_at, last_login_at, last_ip, failures, locked_until) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, account.username());
            ps.setString(2, account.uuid().toString());
            ps.setString(3, account.passwordHash());
            ps.setLong(4, account.createdAt());
            ps.setLong(5, account.lastLoginAt());
            ps.setString(6, account.lastIp());
            ps.setInt(7, account.failures());
            ps.setLong(8, account.lockedUntil());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            HubSuite.logger().warn("创建账号 '{}' 失败（可能已存在）：{}", account.username(), e.getMessage());
            return false;
        }
    }

    /** 登录成功后更新记录。 */
    public void recordSuccess(String username, String ip, long now, UUID uuid) {
        if (useFallback) {
            fallback.recordSuccess(username, ip, now, uuid);
            return;
        }
        String sql = "UPDATE accounts SET last_login_at = ?, last_ip = ?, failures = 0, locked_until = 0, uuid = ? "
                + "WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, ip == null ? "" : ip);
            ps.setString(3, uuid.toString());
            ps.setString(4, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            HubSuite.logger().error("更新账号 '{}' 登录信息失败", username, e);
        }
    }

    /** 登录失败：累加次数，达到阈值则锁定。 */
    public void recordFailure(String username, int maxFailures, long lockMillis, long now) {
        if (useFallback) {
            fallback.recordFailure(username, maxFailures, lockMillis, now);
            return;
        }
        String select = "SELECT failures FROM accounts WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(select)) {
            ps.setString(1, username);
            int failures;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                failures = rs.getInt(1) + 1;
            }
            long lockedUntil = failures >= maxFailures ? now + lockMillis : 0L;
            try (PreparedStatement up = connection.prepareStatement(
                    "UPDATE accounts SET failures = ?, locked_until = ? WHERE username = ? COLLATE NOCASE")) {
                up.setInt(1, failures);
                up.setLong(2, lockedUntil);
                up.setString(3, username);
                up.executeUpdate();
            }
            if (lockedUntil > 0) {
                HubSuite.logger().warn("账号 '{}' 连续失败 {} 次，已锁定至 {}。",
                        username, failures, java.time.Instant.ofEpochMilli(lockedUntil));
            }
        } catch (SQLException e) {
            HubSuite.logger().error("记录账号 '{}' 失败次数出错", username, e);
        }
    }

    /** 管理员重置密码 / 解锁。 */
    public boolean updatePassword(String username, String newHash) {
        if (useFallback) {
            return fallback.updatePassword(username, newHash);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET password_hash = ?, failures = 0, locked_until = 0 WHERE username = ? COLLATE NOCASE")) {
            ps.setString(1, newHash);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            HubSuite.logger().error("重置账号 '{}' 密码失败", username, e);
            return false;
        }
    }

    public boolean delete(String username) {
        if (useFallback) {
            return fallback.delete(username);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM accounts WHERE username = ? COLLATE NOCASE")) {
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            HubSuite.logger().error("删除账号 '{}' 失败", username, e);
            return false;
        }
    }

    public int count() {
        if (useFallback) {
            return fallback.count();
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private static Account readAccount(ResultSet rs) throws SQLException {
        return new Account(
                rs.getString("username"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("password_hash"),
                rs.getLong("created_at"),
                rs.getLong("last_login_at"),
                rs.getString("last_ip"),
                rs.getInt("failures"),
                rs.getLong("locked_until"));
    }

    // ------------------------------------------------------------------
    // 会话（记住登录状态）
    // ------------------------------------------------------------------

    public void putSession(UUID uuid, String ip, long expiresAt) {
        if (useFallback) {
            fallback.putSession(uuid, ip, expiresAt);
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions (uuid, ip, expires_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET ip = excluded.ip, expires_at = excluded.expires_at")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip == null ? "" : ip);
            ps.setLong(3, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            HubSuite.logger().error("写入会话失败", e);
        }
    }

    /** 查询有效会话；过期会被顺手清掉。 */
    public Optional<String> sessionIp(UUID uuid, long now) {
        if (useFallback) {
            return fallback.sessionIp(uuid, now);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ip, expires_at FROM sessions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                if (rs.getLong("expires_at") < now) {
                    clearSession(uuid);
                    return Optional.empty();
                }
                return Optional.ofNullable(rs.getString("ip"));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public void clearSession(UUID uuid) {
        if (useFallback) {
            fallback.clearSession(uuid);
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            HubSuite.logger().error("清理会话失败", e);
        }
    }

    public void purgeExpiredSessions(long now) {
        if (useFallback) {
            fallback.purgeExpiredSessions(now);
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            HubSuite.logger().error("清理过期会话失败", e);
        }
    }
}
