package cn.dreamgary.hubsuite.client;

import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发用**自动操作界面**（只认 {@code -Dhubsuite.drive=assembly}）。
 *
 * <p>为什么要有它：装配台的**双端交互**（放料到槽位 → 成品格刷新 → 取走消耗）
 * 服务端自检只能测服务端那一半；客户端发不发得对点击、槽位同步对不对、
 * 成品格取走后输入有没有扣掉 —— 只有真点一遍才知道。这里就按真人操作发点击包，
 * 每步截图 + 打印状态，把"手感"变成可复查的日志。
 */
public final class DevDrive {

    public static final String PROPERTY = "hubsuite.drive";

    private static final Logger LOGGER = LoggerFactory.getLogger("HubSuite/Drive");

    private static final int SLOT_ROD = cn.dreamgary.hubsuite.rod.AssemblyMenu.SLOT_ROD;
    private static final int SLOT_ROD_MAT = cn.dreamgary.hubsuite.rod.AssemblyMenu.SLOT_ROD_MAT;
    private static final int SLOT_LINE = cn.dreamgary.hubsuite.rod.AssemblyMenu.SLOT_LINE;
    private static final int SLOT_HOOK = cn.dreamgary.hubsuite.rod.AssemblyMenu.SLOT_HOOK;
    private static final int SLOT_RESULT = cn.dreamgary.hubsuite.rod.AssemblyMenu.SLOT_RESULT;

    private static final List<String> FAILS = new java.util.ArrayList<>();

    private static int beforeRod;
    private static int beforeLine;
    private static int beforeHook;

    private static int step;
    private static int wait;
    private static boolean running;
    private static boolean done;   // 跑过一次就不再跑（之前 finish 后会被立刻重启，状态互相污染）
    private static String shotDir = "/tmp/hubsuite-drive";

    private DevDrive() {
    }

    public static void register(String dir) {
        shotDir = dir;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || !(client.screen instanceof HubSuiteContainerScreen<?> screen)) {
                if (running) {
                    LOGGER.info("[DRIVE] 界面关了，停止驱动");
                    running = false;
                }
                return;
            }
            if (done) {
                return;
            }
            if (!running) {
                running = true;
                step = 0;
                wait = 30;   // 等界面稳定
                LOGGER.info("[DRIVE] 开始驱动装配台（containerId={}）",
                        screen.getMenu().containerId);
                return;
            }
            if (--wait > 0) {
                return;
            }
            try {
                next(client, screen.getMenu());
            } catch (Throwable t) {
                LOGGER.warn("[DRIVE] 第 {} 步异常: {}", step, t.toString());
                running = false;
            }
        });
    }

    private static void next(Minecraft client, AbstractContainerMenu menu) {
        switch (step) {
            case 0 -> {
                log(menu, "初始状态");
                DevShot.capture(client, shotDir, "0-open");
                wait = 10;
            }
            case 1 -> {
                if (!grabFromInventory(client, menu, Items.FISHING_ROD)) {
                    FAILS.add("背包里没有钓竿（先 give）");
                    finish(client, menu);
                    return;
                }
                wait = 6;
            }
            case 2 -> {
                click(client, menu, SLOT_ROD, 0);
                LOGGER.info("[DRIVE] 把竿放进竿位 → {}", brief(menu.getSlot(SLOT_ROD).getItem()));
                wait = 8;
            }
            case 3 -> {
                if (grabFromInventory(client, menu, Items.STRING)) {
                    wait = 6;
                } else {
                    LOGGER.info("[DRIVE] 背包里没有线，跳过线位");
                    step++;
                    wait = 1;
                }
            }
            case 4 -> {
                click(client, menu, SLOT_LINE, 0);
                LOGGER.info("[DRIVE] 把线放进线位 → {}", brief(menu.getSlot(SLOT_LINE).getItem()));
                wait = 8;
            }
            case 5 -> {
                if (grabFromInventory(client, menu, Items.OBSIDIAN)) {
                    wait = 6;
                } else {
                    LOGGER.info("[DRIVE] 背包里没有黑曜石，跳过钩位");
                    step++;
                    wait = 1;
                }
            }
            case 6 -> {
                click(client, menu, SLOT_HOOK, 0);
                LOGGER.info("[DRIVE] 把黑曜石放进钩位 → {}", brief(menu.getSlot(SLOT_HOOK).getItem()));
                wait = 10;
            }
            case 7 -> {
                ItemStack result = menu.getSlot(SLOT_RESULT).getItem();
                LOGGER.info("[DRIVE] 三格就位后成品格 = {}", brief(result));
                if (result.isEmpty()) {
                    FAILS.add("放了竿+线+黑曜石，成品格却是空的");
                }
                beforeRod = menu.getSlot(SLOT_ROD).getItem().getCount();
                beforeLine = menu.getSlot(SLOT_LINE).getItem().getCount();
                beforeHook = menu.getSlot(SLOT_HOOK).getItem().getCount();
                DevShot.capture(client, shotDir, "1-staged");
                wait = 8;
            }
            case 8 -> {
                click(client, menu, SLOT_RESULT, 0);
                wait = 8;
            }
            case 9 -> {
                ItemStack carried = menu.getCarried();
                LOGGER.info("[DRIVE] 取走成品后：手上 = {}，竿位 = {}，线位 = {}，钩位 = {}，成品格 = {}",
                        brief(carried), brief(menu.getSlot(SLOT_ROD).getItem()),
                        brief(menu.getSlot(SLOT_LINE).getItem()), brief(menu.getSlot(SLOT_HOOK).getItem()),
                        brief(menu.getSlot(SLOT_RESULT).getItem()));
                if (carried.isEmpty()) {
                    FAILS.add("从成品格取走，手上却是空的");
                }
                if (beforeRod > 0 && !menu.getSlot(SLOT_ROD).getItem().isEmpty()) {
                    FAILS.add("取走成品后竿位没清空");
                }
                // 钩位：黑曜石确实改了属性 → 必须少 1 个
                int afterHook = menu.getSlot(SLOT_HOOK).getItem().getCount();
                if (beforeHook - afterHook != 1) {
                    FAILS.add("取走成品后钩位应少 1 个（" + beforeHook + " -> " + afterHook + "）");
                }
                // 线位：本来就是线，规则判定"没变化" → 一个都不该扣（这是设计，不是 bug）
                int afterLine = menu.getSlot(SLOT_LINE).getItem().getCount();
                if (afterLine != beforeLine) {
                    FAILS.add("线位没起变化却被扣了（" + beforeLine + " -> " + afterLine + "）");
                }
                DevShot.capture(client, shotDir, "2-taken");
                wait = 10;
            }
            default -> {
                finish(client, menu);
                return;
            }
        }
        step++;
    }

    private static void finish(Minecraft client, AbstractContainerMenu menu) {
        running = false;
        done = true;
        DevShot.capture(client, shotDir, "9-final");
        LOGGER.info("[DRIVE] ===== 结论：{} =====",
                FAILS.isEmpty() ? "全部通过" : (FAILS.size() + " 项失败 -> " + String.join("；", FAILS)));
    }

    private static void log(AbstractContainerMenu menu, String what) {
        LOGGER.info("[DRIVE] {}：竿位={}，杆位={}，线位={}，钩位={}，成品={}，光标={}",
                what, brief(menu.getSlot(SLOT_ROD).getItem()),
                brief(menu.getSlot(SLOT_ROD_MAT).getItem()), brief(menu.getSlot(SLOT_LINE).getItem()),
                brief(menu.getSlot(SLOT_HOOK).getItem()), brief(menu.getSlot(SLOT_RESULT).getItem()),
                brief(menu.getCarried()));
    }

    /** 从玩家背包区找一件东西、点起来（光标上带着）。 */
    private static boolean grabFromInventory(Minecraft client, AbstractContainerMenu menu,
                                             net.minecraft.world.item.Item item) {
        for (int i = SLOT_RESULT + 1; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.is(item)) {
                String before = brief(stack);   // 必须在点击之前取：点完东西就到光标上了
                click(client, menu, i, 0);
                LOGGER.info("[DRIVE] 从背包第 {} 格拿起 {}", i, before);
                return true;
            }
        }
        return false;
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button) {
        client.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP,
                client.player);
    }

    private static String brief(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "空";
        }
        var data = cn.dreamgary.hubsuite.rod.RodData.of(stack);
        String extra = data == null ? "" : ("(" + data.rod() + "/" + data.line() + "/" + data.hook()
                + (data.upgrade() == null || data.upgrade().isBlank() ? "" : "/" + data.upgrade()) + ")");
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                + " x" + stack.getCount() + extra;
    }
}
