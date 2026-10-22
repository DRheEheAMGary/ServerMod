package cn.dreamgary.hubsuite.integration;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.ConfigManager;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 更新检查：查询 GitHub Releases，发现新版本时提示管理员。
 *
 * <p><b>为什么用 REST API 而不是 {@code /releases/latest}：</b>配置要区分
 * "正式版"和"测试版（预发布）"，而 {@code /releases/latest} 永远只返回正式版，
 * 拿不到预发布。{@code /releases} 列表带 {@code prerelease} 标志，才能实现
 * {@code update.checkBeta}。
 *
 * <p><b>对 60/h 限流的处理：</b>
 * <ul>
 *   <li>用 ETag 做条件请求 —— GitHub 文档明确"收到 304 的请求不计入限额"；</li>
 *   <li>结果缓存到 {@code config/hubsuite/update-check.json}，24h 内不重复请求。</li>
 * </ul>
 *
 * <p><b>约定：</b>全程异步、失败静默、开发环境跳过、只提示不自动下载或替换 jar。
 */
public final class UpdateChecker {

    /** GitHub Releases API（含预发布，才能实现 checkBeta）。 */
    private static final String API =
            "https://api.github.com/repos/DRheEheAMServer/HubSuite/releases?per_page=10";
    /** 同类检查的最小间隔：24h。避免频繁重启把 API 配额耗光。 */
    private static final long CACHE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final String CACHE_FILE = "update-check.json";

    /** 一个待提示的版本。 */
    private record Candidate(String version, String url, boolean beta) {
    }

    /** 一次检查的结果（新版本 tag + release 链接 + 是否测试版）。 */
    public record Notice(String version, String url, boolean beta) {
    }

    private final ConfigManager configManager;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 发现的新版本；null = 没有更新或还没查。 */
    private volatile Notice notice;

    public UpdateChecker(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** 服务端启动完成后调用：异步查一次（不阻塞启动）。 */
    public void onServerStarted(MinecraftServer server) {
        HubSuiteConfig.UpdateConfig cfg = configManager.config().update;
        if (cfg == null || !cfg.check) {
            return;
        }
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            HubSuite.logger().debug("开发环境，跳过更新检查。");
            return;
        }
        CompletableFuture.runAsync(this::check);
    }

    /** 玩家认证成功时调用：管理员会收到一条提示。 */
    public void onPlayerAuthenticated(ServerPlayer player) {
        Notice n = notice;
        if (n == null || player == null) {
            return;
        }
        var permissions = HubSuite.permissions();
        if (permissions == null || !permissions.hasAdminPermission(player)) {
            return;
        }
        player.sendSystemMessage(Component.literal(
                "\u00A7e[HubSuite] \u00A7f" + (n.beta() ? "发现新的测试版" : "发现新版本")
                        + " \u00A7a" + n.version()
                        + "\u00A77（当前 " + currentVersion() + "）：\u00A7b" + n.url()));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    private void check() {
        try {
            HubSuiteConfig.UpdateConfig cfg = configManager.config().update;
            Path cache = configManager.configDir().resolve(CACHE_FILE);

            JsonObject cached = readCache(cache);
            if (cached != null && isFresh(cached)) {
                apply(cached, cfg, false);
                return;
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "HubSuite/" + currentVersion())
                    .header("Accept", "application/vnd.github+json")
                    .GET();
            if (cached != null && cached.has("etag")) {
                builder.header("If-None-Match", cached.get("etag").getAsString());
            }

            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 304) {
                // 304 不计入 GitHub 限额：刷新时间戳后继续用缓存内容
                JsonObject merged = cached == null ? new JsonObject() : cached;
                merged.addProperty("checkedAt", System.currentTimeMillis());
                writeCache(cache, merged);
                apply(merged, cfg, false);
                return;
            }
            if (response.statusCode() != 200) {
                HubSuite.logger().debug("更新检查返回 {}（不影响运行）。", response.statusCode());
                return;
            }

            JsonObject fresh = parse(response.body(),
                    response.headers().firstValue("ETag").orElse(null));
            writeCache(cache, fresh);
            apply(fresh, cfg, true);
        } catch (Throwable t) {
            // 离线服 / 被墙 / 限流都不该影响启动
            HubSuite.logger().debug("更新检查失败（不影响运行）：{}", t.toString());
        }
    }

    /** 从结果里挑出「比当前新」的最大候选。 */
    private void apply(JsonObject data, HubSuiteConfig.UpdateConfig cfg, boolean log) {
        String current = currentVersion();
        Candidate best = better(null, candidate(data, "stable", false), current);
        if (cfg.checkBeta) {
            best = better(best, candidate(data, "beta", true), current);
        }
        if (best == null) {
            notice = null;
            return;
        }
        notice = new Notice(best.version(), best.url(), best.beta());
        if (log) {
            HubSuite.logger().info("{}：{}（当前 {}）：{}",
                    best.beta() ? "发现新的测试版" : "发现新版本",
                    best.version(), current, best.url());
        }
    }

    private static Candidate candidate(JsonObject data, String key, boolean beta) {
        if (data == null || !data.has(key)) {
            return null;
        }
        String version = data.get(key).getAsString();
        if (version == null || version.isBlank()) {
            return null;
        }
        String urlKey = key + "Url";
        String url = data.has(urlKey) ? data.get(urlKey).getAsString() : "";
        return new Candidate(version, url, beta);
    }

    private static Candidate better(Candidate best, Candidate other, String current) {
        if (other == null || compareVersions(other.version(), current) <= 0) {
            return best;
        }
        if (best == null || compareVersions(other.version(), best.version()) > 0) {
            return other;
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 解析 / 缓存
    // ------------------------------------------------------------------

    /** 解析 /releases 列表，缓存里只留"最新正式版 + 最新预发布"。 */
    private static JsonObject parse(String body, String etag) {
        JsonObject out = new JsonObject();
        out.addProperty("checkedAt", System.currentTimeMillis());
        if (etag != null && !etag.isBlank()) {
            out.addProperty("etag", etag);
        }
        JsonArray array = JsonParser.parseString(body).getAsJsonArray();
        Candidate stable = null;
        Candidate beta = null;
        for (JsonElement element : array) {
            JsonObject release = element.getAsJsonObject();
            if (release.has("draft") && release.get("draft").getAsBoolean()) {
                continue;
            }
            if (!release.has("tag_name")) {
                continue;
            }
            String tag = release.get("tag_name").getAsString();
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String url = release.has("html_url") ? release.get("html_url").getAsString() : "";
            boolean pre = release.has("prerelease") && release.get("prerelease").getAsBoolean();
            Candidate c = new Candidate(tag, url, pre);
            if (pre) {
                if (beta == null || compareVersions(c.version(), beta.version()) > 0) {
                    beta = c;
                }
            } else if (stable == null || compareVersions(c.version(), stable.version()) > 0) {
                stable = c;
            }
        }
        if (stable != null) {
            out.addProperty("stable", stable.version());
            out.addProperty("stableUrl", stable.url());
        }
        if (beta != null) {
            out.addProperty("beta", beta.version());
            out.addProperty("betaUrl", beta.url());
        }
        return out;
    }

    private static JsonObject readCache(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(Path file, JsonObject data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, data.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            HubSuite.logger().debug("写入更新缓存失败：{}", t.toString());
        }
    }

    private static boolean isFresh(JsonObject data) {
        return data.has("checkedAt")
                && System.currentTimeMillis() - data.get("checkedAt").getAsLong() < CACHE_TTL_MS;
    }

    // ------------------------------------------------------------------
    // 版本比较
    // ------------------------------------------------------------------

    private static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("hubsuite")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    /**
     * 语义化比较：容忍 {@code v} 前缀与预发布后缀。
     * {@code 0.2.7-rc.1 < 0.2.7}；返回 &gt;0 表示 a 比 b 新。
     */
    static int compareVersions(String a, String b) {
        int[] na = core(a);
        int[] nb = core(b);
        for (int i = 0; i < 3; i++) {
            if (na[i] != nb[i]) {
                return Integer.compare(na[i], nb[i]);
            }
        }
        String pa = pre(a);
        String pb = pre(b);
        boolean xa = !pa.isBlank();
        boolean xb = !pb.isBlank();
        if (xa != xb) {
            return xa ? -1 : 1;   // 有预发布后缀的更旧
        }
        if (!xa) {
            return 0;
        }
        String[] ta = pa.split("[.-]");
        String[] tb = pb.split("[.-]");
        // 四档预发布按内定顺序排：alpha < beta < pre < rc；
        // 同一档再比后面的 N；未知标识符（如 SNAPSHOT）rank=0，排在最前。
        int rankA = prereleaseRank(ta[0]);
        int rankB = prereleaseRank(tb[0]);
        int start = 0;
        if (rankA != 0 || rankB != 0) {
            if (rankA != rankB) {
                return Integer.compare(rankA, rankB);
            }
            start = 1;
        }
        for (int i = start; i < Math.max(ta.length, tb.length); i++) {
            String x = i < ta.length ? ta[i] : "0";
            String y = i < tb.length ? tb[i] : "0";
            boolean xn = x.matches("\\d+");
            boolean yn = y.matches("\\d+");
            int c = xn && yn ? Integer.compare(Integer.parseInt(x), Integer.parseInt(y)) : x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** 四档预发布的内定顺序：alpha &lt; beta &lt; pre &lt; rc；未识别的返回 0。 */
    private static int prereleaseRank(String label) {
        return switch (label.toLowerCase(java.util.Locale.ROOT)) {
            case "alpha" -> 1;
            case "beta" -> 2;
            case "pre", "preview" -> 3;
            case "rc" -> 4;
            default -> 0;
        };
    }

    private static String stripV(String s) {
        String t = s == null ? "" : s.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    private static int[] core(String s) {
        String t = stripV(s);
        int dash = t.indexOf('-');
        if (dash >= 0) {
            t = t.substring(0, dash);
        }
        String[] parts = t.split("\\.");
        int[] nums = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                nums[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                nums[i] = 0;
            }
        }
        return nums;
    }

    private static String pre(String s) {
        String t = stripV(s);
        int dash = t.indexOf('-');
        return dash >= 0 ? t.substring(dash + 1) : "";
    }
}
