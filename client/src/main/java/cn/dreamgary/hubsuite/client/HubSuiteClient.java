package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.net.HandshakePayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSuite 客户端模组的入口。
 *
 * <p>0.5.0 的进度：双端构建 + 握手契约已经立起来，渲染器与界面在后续小步里逐个加
 * （见 docs/客户端模组方案.md 第 4 节）。
 *
 * <p>协议纪律（docs/客户端契约.md 第 3.1 节）：payload 的字段只增不改，
 * 读方必须容忍未知的尾部字段 —— 线格式定义在 {@code common/}，两端编译同一份源码。
 */
public final class HubSuiteClient implements ClientModInitializer {

    /** 模组 id（与 fabric.mod.json 一致）。 */
    public static final String MOD_ID = "hubsuite-client";

    public static final Logger LOGGER = LoggerFactory.getLogger("HubSuite-Client");

    @Override
    public void onInitializeClient() {
        ClientMenuTypes.register();
        ClientItems.register();
        ClientItems.registerAssetCheck();
        ClientHandshake.register();

        // 开发用自动连服（不传 -Dhubsuite.autoconnect 就什么都不做）
        String autoConnect = System.getProperty(DevAutoConnect.PROPERTY, "");
        if (!autoConnect.isBlank()) {
            DevAutoConnect.register(autoConnect);
            DevItemWatch.register();
        }

        LOGGER.info("HubSuite 客户端 {} 已加载（握手 + 真物品 + 分层钓竿 + 装配台界面）", version());
    }

    /** 从模组元数据里读自己的版本号（构建时写入）。 */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * 客户端指纹：构建时对"客户端源码 + 素材 + 共用契约"算的 hash，
     * 写进 jar 里的 {@code hubsuite-client.properties}（也同步写在 fabric.mod.json 的 custom 里）。
     *
     * <p>用途：排查时一眼看出玩家在跑哪个客户端；发版时对比上一版就能知道
     * "客户端这一版有没有变动"（见 docs/分发与更新.md 第 4 节）。
     */
    public static String fingerprint() {
        try (java.io.InputStream in = HubSuiteClient.class.getResourceAsStream("/hubsuite-client.properties")) {
            if (in == null) {
                return "unknown";
            }
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            return props.getProperty("fingerprint", "unknown");
        } catch (java.io.IOException e) {
            LOGGER.warn("读不到客户端指纹：{}", e.toString());
            return "unknown";
        }
    }

    /** 协议号别名，方便客户端内部引用。 */
    public static int protocol() {
        return HandshakePayloads.PROTOCOL;
    }
}
