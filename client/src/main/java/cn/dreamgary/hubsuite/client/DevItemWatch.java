package cn.dreamgary.hubsuite.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * 开发用：盯着背包里的 hubsuite 物品（进服后 / 物品变化时各打一行日志）。
 *
 * <p>它回答的是"服务端到底发的是真物品，还是 Polymer 映射过的原版物品" ——
 * 真物品会打出 {@code hubsuite:nether_string}；映射过的会打出 {@code minecraft:trial_key}
 * 但模型指向 {@code hubsuite:nether_string}。无人值守时靠这一行判断迁移有没有生效。
 *
 * <p>只在传了 {@code -Dhubsuite.autoconnect=主机:端口}（开发模式）时才注册。
 */
public final class DevItemWatch {

    private static String last = "";

    private DevItemWatch() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            List<String> found = new ArrayList<>();
            var inventory = client.player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                Identifier model = stack.get(DataComponents.ITEM_MODEL);
                boolean ours = id.startsWith("hubsuite:") || (model != null && model.getNamespace().equals("hubsuite"));
                if (ours) {
                    found.add(id + " x" + stack.getCount() + "（模型 " + (model == null ? "默认" : model) + "）");
                }
            }
            String now = String.join("；", found);
            if (!now.equals(last)) {
                last = now;
                if (found.isEmpty()) {
                    HubSuiteClient.LOGGER.info("开发用物品观察：背包里没有 hubsuite 物品");
                } else {
                    HubSuiteClient.LOGGER.info("开发用物品观察：{}", now);
                }
            }
        });
    }
}
