package cn.dreamgary.hubsuite.auth;

import cn.dreamgary.hubsuite.HubSuite;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite 不可用时的兜底账号存储（{@code accounts.json}）。
 *
 * <p>存在意义：万一 SQLite 驱动加载失败（例如 HuskHomes 被移除），
 * 登录功能不应该整个瘫掉，而是降级成 JSON 存储并给出明确告警。
 * 数据量小、并发低，够用。
 */
final class FallbackStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final TypeToken<Map<String, AuthStore.Account>> ACCOUNTS = new TypeToken<>() {
    };
    private static final TypeToken<Map<String, Session>> SESSIONS = new TypeToken<>() {
    };

    record Session(String ip, long expiresAt) {
    }

    private final Path file;
    private final Path sessionFile;
    private final Map<String, AuthStore.Account> accounts = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    FallbackStore(Path file) {
        this.file = file;
        this.sessionFile = file.resolveSibling("sessions.json");
    }

    Path file() {
        return file;
    }

    void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Map<String, AuthStore.Account> data = GSON.fromJson(r, ACCOUNTS.getType());
                    if (data != null) {
                        accounts.putAll(data);
                    }
                }
            }
            if (Files.exists(sessionFile)) {
                try (Reader r = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
                    Map<String, Session> data = GSON.fromJson(r, SESSIONS.getType());
                    if (data != null) {
                        sessions.putAll(data);
                    }
                }
            }
            HubSuite.logger().info("已载入兜底账号存储：{}（{} 个账号）", file, accounts.size());
        } catch (Exception e) {
            HubSuite.logger().error("载入兜底账号存储失败", e);
        }
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            writeAtomic(file, accounts);
            writeAtomic(sessionFile, sessions);
        } catch (Exception e) {
            HubSuite.logger().error("保存兜底账号存储失败", e);
        }
    }

    private void writeAtomic(Path target, Object data) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException unsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    Optional<AuthStore.Account> find(String username) {
        return Optional.ofNullable(accounts.get(key(username)));
    }

    Optional<AuthStore.Account> findByUuid(UUID uuid) {
        return accounts.values().stream()
                .filter(a -> a.uuid().equals(uuid))
                .findFirst();
    }

    boolean create(AuthStore.Account account) {
        String key = key(account.username());
        if (accounts.containsKey(key)) {
            return false;
        }
        accounts.put(key, account);
        save();
        return true;
    }

    void recordSuccess(String username, String ip, long now, UUID uuid) {
        String key = key(username);
        AuthStore.Account old = accounts.get(key);
        if (old != null) {
            accounts.put(key, new AuthStore.Account(old.username(), uuid, old.passwordHash(),
                    old.createdAt(), now, ip == null ? "" : ip, 0, 0));
            save();
        }
    }

    void recordFailure(String username, int maxFailures, long lockMillis, long now) {
        String key = key(username);
        AuthStore.Account old = accounts.get(key);
        if (old == null) {
            return;
        }
        int failures = old.failures() + 1;
        long lockedUntil = failures >= maxFailures ? now + lockMillis : 0;
        accounts.put(key, new AuthStore.Account(old.username(), old.uuid(), old.passwordHash(),
                old.createdAt(), old.lastLoginAt(), old.lastIp(), failures, lockedUntil));
        save();
    }

    boolean updatePassword(String username, String newHash) {
        String key = key(username);
        AuthStore.Account old = accounts.get(key);
        if (old == null) {
            return false;
        }
        accounts.put(key, new AuthStore.Account(old.username(), old.uuid(), newHash,
                old.createdAt(), old.lastLoginAt(), old.lastIp(), 0, 0));
        save();
        return true;
    }

    boolean delete(String username) {
        boolean removed = accounts.remove(key(username)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    int count() {
        return accounts.size();
    }

    void putSession(UUID uuid, String ip, long expiresAt) {
        sessions.put(uuid.toString(), new Session(ip == null ? "" : ip, expiresAt));
        save();
    }

    Optional<String> sessionIp(UUID uuid, long now) {
        Session s = sessions.get(uuid.toString());
        if (s == null) {
            return Optional.empty();
        }
        if (s.expiresAt() < now) {
            sessions.remove(uuid.toString());
            save();
            return Optional.empty();
        }
        return Optional.ofNullable(s.ip());
    }

    void clearSession(UUID uuid) {
        if (sessions.remove(uuid.toString()) != null) {
            save();
        }
    }

    void purgeExpiredSessions(long now) {
        if (sessions.values().removeIf(s -> s.expiresAt() < now)) {
            save();
        }
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(java.util.Locale.ROOT);
    }
}
