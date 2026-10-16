package cn.dreamgary.hubsuite.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发用**客户端冒烟测试**（只认系统属性 {@code -Dhubsuite.smoke=1}）。
 *
 * <p>为什么要有它：钓竿 tooltip / Shift 展开 / 我们物品的显示，这些**只有客户端才知道对不对**，
 * 服务端自检一条都覆盖不到。这一跑就是"自己拿客户端验一遍"，把结论写进日志：
 * <ul>
 *   <li>新竿（还没装配过）有没有信息；</li>
 *   <li>按住 Shift 展开部件明细有没有生效（程序化按下 Shift 再取一次 tooltip）；</li>
 *   <li>5 个自定义物品 + 装配台方块能不能建出 tooltip（不抛异常）；</li>
 *   <li>装配台布局的自洽性（槽位在面板内、预览框盖住部件槽）。</li>
 * </ul>
 *
 * <p>刻意**不**去开界面：直接 setScreen 一个假菜单，关掉时会给服务端发"关闭容器"、
 * 可能把服务端的背包菜单搞乱 —— 无人值守跑这个不值得。
 */
public final class DevSmoke {

    public static final String PROPERTY = "hubsuite.smoke";

    private static final Logger LOGGER = LoggerFactory.getLogger("HubSuite/Smoke");

    private static int ticks;
    private static boolean done;

    private DevSmoke() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (done || client.player == null || client.level == null) {
                return;
            }
            if (++ticks < 60) {
                return;   // 等资源包 / 世界完全就绪
            }
            done = true;
            run(client);
        });
    }

    private static void run(Minecraft client) {
        List<String> fails = new ArrayList<>();
        var context = Item.TooltipContext.of(client.level);

        // 1) 新竿（没有任何数据）也要有信息
        ItemStack fresh = new ItemStack(Items.FISHING_ROD);
        List<Component> freshLines = tooltip(fresh, context, client);
        boolean freshOk = freshLines.stream().anyMatch(l -> l.getString().contains("耐久"));
        LOGGER.info("[SMOKE] 新竿 tooltip: {} 行，有耐久信息 = {}", freshLines.size(), freshOk);
        if (!freshOk) {
            fails.add("新竿（无数据）不显示 tooltip 信息");
        }

        // 2) 装配过的竿：Shift 展开要能看到部件明细
        ItemStack assembled = new ItemStack(Items.FISHING_ROD);
        cn.dreamgary.hubsuite.rod.RodData.defaults().writeTo(assembled);
        List<Component> normal = tooltip(assembled, context, client);
        boolean summaryOk = normal.stream().anyMatch(l -> l.getString().contains("速度"));
        LOGGER.info("[SMOKE] 装配竿 tooltip: {} 行，有总览数值 = {}", normal.size(), summaryOk);
        if (!summaryOk) {
            fails.add("装配过的竿没有总览数值");
        }

        boolean shifted = client.options.keyShift.isDown();
        client.options.keyShift.setDown(true);
        List<Component> expanded;
        try {
            expanded = tooltip(assembled, context, client);
        } finally {
            client.options.keyShift.setDown(shifted);
        }
        boolean detailsOk = expanded.stream().anyMatch(l -> l.getString().contains("基础耐久"));
        LOGGER.info("[SMOKE] Shift 展开: {} 行（收起 {} 行），有部件明细 = {}",
                expanded.size(), normal.size(), detailsOk);
        if (!detailsOk) {
            fails.add("按住 Shift 没有展开部件明细");
        }

        // 3) 所有自定义物品都能建 tooltip（不抛异常）、且都有名字
        List<Item> ours = new ArrayList<>();
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals("hubsuite")) {
                ours.add(item);
            }
        }
        int empty = 0;
        for (Item item : ours) {
            try {
                if (tooltip(new ItemStack(item), context, client).isEmpty()) {
                    empty++;
                }
            } catch (Throwable t) {
                fails.add("物品 tooltip 抛异常: " + item + " -> " + t);
            }
        }
        LOGGER.info("[SMOKE] hubsuite 物品 {} 个，tooltip 空的 {} 个", ours.size(), empty);
        if (ours.isEmpty()) {
            fails.add("客户端没注册任何 hubsuite 物品（真物品迁移没生效？）");
        }

        // 4) 装配台布局自洽：槽位都在面板内，预览框盖住 1/2/3 号部件槽
        var layout = cn.dreamgary.hubsuite.ui.UiLayouts.ASSEMBLY;
        for (var frame : layout.frames()) {
            if (frame.x() < 0 || frame.y() < 0
                    || frame.x() + 18 > layout.width() || frame.y() + 18 > layout.height()) {
                fails.add("布局槽位越界: " + frame.hint() + " (" + frame.x() + "," + frame.y() + ")");
            }
        }
        var preview = layout.preview();
        if (preview != null) {
            int pw = (int) (16 * preview.scale());
            for (int i = 1; i <= 3 && i < layout.frames().size(); i++) {
                var f = layout.frames().get(i);
                boolean inside = f.x() >= preview.x() - 20 && f.x() + 18 <= preview.x() + pw + 20
                        && f.y() >= preview.y() - 20 && f.y() + 18 <= preview.y() + pw + 20;
                if (!inside) {
                    fails.add("部件槽没钉在预览竿上: " + f.hint());
                }
            }
        }
        LOGGER.info("[SMOKE] 装配台布局: 面板 {}x{}，槽位 {} 个，预览 scale {}",
                layout.width(), layout.height(), layout.frames().size(), preview == null ? 0 : preview.scale());

        LOGGER.info("[SMOKE] ===== 结论：{} =====",
                fails.isEmpty() ? "全部通过" : (fails.size() + " 项失败 -> " + String.join("；", fails)));
    }

    private static List<Component> tooltip(ItemStack stack, Item.TooltipContext context, Minecraft client) {
        return stack.getTooltipLines(context, client.player, TooltipFlag.Default.NORMAL);
    }
}
