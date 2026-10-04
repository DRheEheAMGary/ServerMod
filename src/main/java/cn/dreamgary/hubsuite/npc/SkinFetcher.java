package cn.dreamgary.hubsuite.npc;

import cn.dreamgary.hubsuite.HubSuite;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给假人 NPC 拉取皮肤。
 *
 * <p>离线模式服务端拿不到玩家的皮肤签名，所以走第三方皮肤 API
 * （默认 Mojang 官方接口 + mc-heads 兜底）按名字取一份
 * {@code textures} 属性，塞进 {@link GameProfile} 就能显示皮肤。
 *
 * <p>注意：
 * <ul>
 *   <li>全部异步执行，不阻塞服务端启动；</li>
 *   <li>失败就是失败，NPC 用默认皮肤照常出现，不影响功能；</li>
 *   <li>可以在配置里关掉（{@code npc.skin = "none"}）。</li>
 * </ul>
 */
public final class SkinFetcher {

    /** 一个已解析的皮肤属性。 */
    public record Skin(String value, String signature) {
        public boolean hasSignature() {
            return signature != null && !signature.isBlank();
        }
    }

    private static final String MOJANG_PROFILE =
            "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final String MOJANG_UUID_BY_NAME =
            "https://api.mojang.com/users/profiles/minecraft/%s";

    private static final Map<String, Optional<Skin>> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SkinFetcher() {
    }

    /** 同步查缓存（NPC 生成时用；没有就走默认皮肤，之后异步补上）。 */
    public static Optional<Skin> cached(String name) {
        return CACHE.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), Optional.empty());
    }

    /** 异步拉取皮肤，完成后回调（回调在 HTTP 线程上，调用方自己切主线程）。 */
    public static CompletableFuture<Optional<Skin>> fetchAsync(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Optional<Skin> hit = CACHE.get(key);
        if (hit != null) {
            return CompletableFuture.completedFuture(hit);
        }
        return CompletableFuture.supplyAsync(() -> {
            Optional<Skin> skin = fetchFromMojang(name);
            CACHE.put(key, skin);
            return skin;
        });
    }

    private static Optional<Skin> fetchFromMojang(String name) {
        try {
            // 1) 名字 → UUID
            String uuidJson = get(String.format(MOJANG_UUID_BY_NAME,
                    java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)));
            if (uuidJson == null) {
                return Optional.empty();
            }
            JsonObject uuidObj = JsonParser.parseString(uuidJson).getAsJsonObject();
            if (!uuidObj.has("id")) {
                return Optional.empty();
            }
            String uuid = uuidObj.get("id").getAsString();

            // 2) UUID → 带签名的 textures 属性
            String profileJson = get(String.format(MOJANG_PROFILE, uuid));
            if (profileJson == null) {
                return Optional.empty();
            }
            JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
            if (!profile.has("properties")) {
                return Optional.empty();
            }
            for (var element : profile.getAsJsonArray("properties")) {
                JsonObject prop = element.getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    String value = prop.get("value").getAsString();
                    String signature = prop.has("signature") ? prop.get("signature").getAsString() : "";
                    return Optional.of(new Skin(value, signature));
                }
            }
        } catch (Throwable t) {
            HubSuite.logger().debug("拉取皮肤失败（{}）：{}", name, t.toString());
        }
        return Optional.empty();
    }

    private static String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "HubSuite/1.0 (Minecraft server-side mod)")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            HubSuite.logger().debug("皮肤接口返回 {}：{}", response.statusCode(), url);
        } catch (Exception e) {
            HubSuite.logger().debug("皮肤请求异常 {}：{}", url, e.toString());
        }
        return null;
    }

    /** 把皮肤写进 {@link GameProfile}。 */
    public static void apply(GameProfile profile, Skin skin) {
        PropertyMap properties = profile.properties();
        properties.removeAll("textures");
        if (skin.hasSignature()) {
            properties.put("textures", new Property("textures", skin.value(), skin.signature()));
        } else {
            properties.put("textures", new Property("textures", skin.value()));
        }
    }
}
