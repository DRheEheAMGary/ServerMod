package cn.dreamgary.hubsuite.config;

import cn.dreamgary.hubsuite.HubSuite;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 配置读写。位置：{@code <游戏目录>/config/hubsuite/config.json}。
 *
 * <p>读取失败（文件损坏）时不会让服务端崩掉，而是备份坏文件并回落到默认配置，
 * 这是运维友好的做法 —— 一个写错的逗号不该让服务器起不来。
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private final Path configDir;
    private final Path configFile;
    private HubSuiteConfig config = new HubSuiteConfig();

    public ConfigManager() {
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve(HubSuite.MOD_ID);
        this.configFile = configDir.resolve("config.json");
    }

    public HubSuiteConfig config() {
        return config;
    }

    public Path configFile() {
        return configFile;
    }

    public Path configDir() {
        return configDir;
    }

    /** 首次启动时写出默认配置（含三个子服），方便管理员直接改。 */
    public void loadOrCreate() {
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            HubSuite.logger().error("无法创建配置目录 {}", configDir, e);
        }

        if (!Files.exists(configFile)) {
            config = DefaultConfigs.create();
            save();
            HubSuite.logger().info("已生成默认配置：{}", configFile);
            return;
        }

        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            HubSuiteConfig loaded = GSON.fromJson(reader, HubSuiteConfig.class);
            config = loaded == null ? DefaultConfigs.create() : loaded;
        } catch (Exception e) {
            Path backup = configFile.resolveSibling("config.json.broken-" + System.currentTimeMillis());
            HubSuite.logger().error("配置解析失败，已备份到 {} 并回落到默认配置。", backup, e);
            try {
                Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 备份失败不影响继续运行
            }
            config = DefaultConfigs.create();
        }

        config.normalize();
        save();
    }

    /** 原子写：先写临时文件再 move，避免半截文件。 */
    public void save() {
        config.normalize();
        try {
            Files.createDirectories(configDir);
            Path tmp = configFile.resolveSibling("config.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
            try {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            HubSuite.logger().error("保存配置失败：{}", configFile, e);
        }
    }
}
