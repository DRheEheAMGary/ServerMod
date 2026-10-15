package cn.dreamgary.hubsuite.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSuite 客户端模组的入口。
 *
 * <p>0.5.0 的第一步只是骨架：先把双端构建、版本号、协议号立起来，
 * 渲染器与界面在后续的小步里逐个加（见 docs/客户端模组方案.md 第 4 节）。
 *
 * <p>协议纪律（docs/客户端契约.md 第 3.1 节）：payload 的字段只增不改，
 * 读方必须容忍未知的尾部字段；协议号放在每个 payload 的第一个字节。
 */
public final class HubSuiteClient implements ClientModInitializer {

	/** 模组 id（与 fabric.mod.json 一致）。 */
	public static final String MOD_ID = "hubsuite-client";

	/** 双端协议号：1.0 之前可以改，1.0 之后只能加。 */
	public static final int PROTOCOL = 1;

	public static final Logger LOGGER = LoggerFactory.getLogger("HubSuite-Client");

	@Override
	public void onInitializeClient() {
		LOGGER.info("HubSuite 客户端 {} 已加载（骨架：暂无渲染与界面）", version());
	}

	/** 从模组元数据里读自己的版本号（构建时写入）。 */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}
}
