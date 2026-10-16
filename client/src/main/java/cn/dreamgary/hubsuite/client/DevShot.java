package cn.dreamgary.hubsuite.client;

import java.io.File;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;

/**
 * 开发用**自动截图**（只认系统属性 {@code -Dhubsuite.shot=目录}）。
 *
 * <p>为什么要有它：界面渲染对不对（裁剪有没有生效、槽位钉没钉在竿上、按钮/成品格位置）
 * **只有看图才知道**。macOS 的命令行 {@code screencapture} 需要录屏权限、拿不到画面，
 * 但游戏读自己的帧缓冲不需要任何权限 —— 所以让客户端自己截。
 *
 * <p>规则：只要当前界面是 {@link HubSuiteContainerScreen}，稳定 20 帧后截一张、
 * 存进指定目录（一张界面只截一次），日志里打出路径。
 */
public final class DevShot {

    public static final String PROPERTY = "hubsuite.shot";

    private static Object lastScreen;
    private static int ticks;

    private DevShot() {
    }

    public static void register(String dir) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var screen = client.screen;
            if (!(screen instanceof HubSuiteContainerScreen<?>)) {
                lastScreen = null;
                ticks = 0;
                return;
            }
            if (screen != lastScreen) {
                lastScreen = screen;
                ticks = 0;
                return;
            }
            if (++ticks != 20) {
                return;
            }
            ticks = 0;
            lastScreen = screen;   // 一张界面只截一次（置 null 会每 21 tick 又截一张）
            try {
                Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
                    try {
                        File file = new File(dir, "gui-" + System.currentTimeMillis() + ".png");
                        File parent = file.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        image.writeToFile(file);
                        HubSuiteClient.LOGGER.info("[SHOT] 界面截图已保存 {}", file.getAbsolutePath());
                    } catch (Throwable t) {
                        HubSuiteClient.LOGGER.warn("[SHOT] 写截图失败: {}", t.toString());
                    } finally {
                        image.close();
                    }
                });
            } catch (Throwable t) {
                HubSuiteClient.LOGGER.warn("[SHOT] 截图失败: {}", t.toString());
            }
        });
    }
}
