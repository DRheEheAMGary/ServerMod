package cn.dreamgary.hubsuite.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * 开发用自动连服（只在传了 {@code -Dhubsuite.autoconnect=主机:端口} 时生效）。
 *
 * <p>客户端**初始加载结束之后**自动连本机测试服，省掉
 * "多人游戏 → 直连 → 输地址"那几步，方便无人值守地跑双端联调。
 * 正式玩家不会碰到它 —— 不传这个属性就完全不注册。
 *
 * <p>踩过的两个坑（都实测过）：
 * <ol>
 *   <li>原版 {@code --quickPlayMultiplayer} 在 26.1 上不生效：参数进了命令行，
 *       {@code quickplay.json} 也没生成，就是不连 —— 所以自己来；</li>
 *   <li>不能在初始加载（{@link GenericMessageScreen}）期间发起连接：
 *       那时 {@code startConnecting} 建出来的界面会被随后的标题界面顶掉，
 *       日志里只留下"Connecting to …"，服务端什么都不会收到。</li>
 * </ol>
 */
public final class DevAutoConnect {

    /** 触发用的系统属性名。 */
    public static final String PROPERTY = "hubsuite.autoconnect";

    private static int ticks;
    private static boolean fired;

    private DevAutoConnect() {
    }

    public static void register(String target) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (fired) {
                return;
            }
            ticks++;
            if (client.level != null || client.screen == null || client.screen instanceof GenericMessageScreen) {
                if (ticks % 200 == 0) {
                    HubSuiteClient.LOGGER.info("自动连服：还在等初始加载结束（tick {}，当前界面 {}）",
                            ticks, client.screen == null ? "null" : client.screen.getClass().getName());
                }
                return;
            }
            fired = true;
            HubSuiteClient.LOGGER.info("自动连服：界面已就绪（{}），连接 {}",
                    client.screen.getClass().getName(), target);
            try {
                ServerData data = new ServerData("本机测试服", target, ServerData.Type.OTHER);
                // 服务器会通过 Polymer 下发资源包；默认会弹"是否接受"等人点，
                // 无人值守时必须自己选"启用"，否则会卡在配置阶段直到超时掉线（实测）。
                data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
                ConnectScreen.startConnecting(
                        client.screen,
                        client,
                        ServerAddress.parseString(target),
                        data,
                        false,
                        null);
                HubSuiteClient.LOGGER.info("自动连服：已发起连接 {}", target);
            } catch (Throwable t) {
                HubSuiteClient.LOGGER.error("自动连服失败（手动连也一样可以测）：{}", t.toString());
            }
        });
    }
}
