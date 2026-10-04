package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子服规则的落盘位置：{@code config/hubsuite/rules/<子服id>.json}。
 *
 * <p>为什么规则要自己存：26.1 的 gamerule 是全局的（存在主世界的 {@code data/} 里），
 * 不再随 {@code level.dat} 走，所以每个子服的规则必须由 mod 自己持久化。
 * 格式就是一串 {@code "KEEP_INVENTORY": "true"}，管理员可以直接改。
 */
public final class RulesStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final TypeToken<Map<String, String>> MAP_TYPE = new TypeToken<>() {
    };

    private RulesStorage() {
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(HubSuite.MOD_ID).resolve("rules");
    }

    public static Path fileOf(String ownerId) {
        return dir().resolve(ownerId + ".json");
    }

    /** 读取规则；文件不存在或损坏返回 null。 */
    public static Map<String, String> load(String ownerId) {
        Path file = fileOf(ownerId);
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> data = GSON.fromJson(reader, MAP_TYPE.getType());
            return data == null ? null : new LinkedHashMap<>(data);
        } catch (Exception e) {
            HubSuite.logger().warn("读取规则文件 {} 失败，将使用默认值。", file, e);
            return null;
        }
    }

    /** 原子写入。 */
    public static void save(String ownerId, Map<String, String> rules) {
        Path file = fileOf(ownerId);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(rules, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            HubSuite.logger().error("保存规则文件 {} 失败。", file, e);
        }
    }
}
