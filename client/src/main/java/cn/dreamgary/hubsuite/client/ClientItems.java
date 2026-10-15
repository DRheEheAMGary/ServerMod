package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.item.HubSuiteItemDefs;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * 客户端侧注册本模组的**真物品**（0.5.0 起）。
 *
 * <p>为什么客户端也要注册：{@code item} 注册表**不参与网络同步**，服务端发来的
 * {@code hubsuite:nether_string} 在客户端必须存在同 id 的物品，否则整叠东西变成空气。
 * 属性必须和服务端**同源**（都来自 {@code common/} 的 {@link HubSuiteItemDefs}）——
 * 物品栈的网络同步只发"与原型不同的组件"，原型不一致就会显示成另一个东西。
 *
 * <p>注册成功后，客户端会向服务端报 {@code real_items_v1} 能力，
 * 服务端于是不再用 Polymer 把它映射成原版物品，直接发真物品（见 {@code HubSuiteSimpleItem}）。
 */
public final class ClientItems {

    private static final List<HubSuiteItemDefs.Def> REGISTERED = new ArrayList<>();
    private static boolean assetChecked;

    private ClientItems() {
    }

    /** 在客户端初始化里调一次（必须早于注册表冻结，即 {@code onInitializeClient} 里）。 */
    public static void register() {
        try {
            for (HubSuiteItemDefs.Def def : HubSuiteItemDefs.ALL) {
                Registry.register(BuiltInRegistries.ITEM, def.id(), new Item(HubSuiteItemDefs.properties(def)));
                REGISTERED.add(def);
            }
        } catch (Throwable t) {
            HubSuiteClient.LOGGER.error("注册真物品失败（这些物品在客户端会显示成空气）：{}", t.toString());
            return;
        }
        List<String> paths = new ArrayList<>();
        for (HubSuiteItemDefs.Def def : REGISTERED) {
            paths.add(def.path());
        }
        HubSuiteClient.LOGGER.info("已注册 {} 个真物品（用自己的模型贴图渲染，不再靠 Polymer 映射）：{}",
                REGISTERED.size(), String.join(", ", paths));
    }

    public static List<HubSuiteItemDefs.Def> registered() {
        return List.copyOf(REGISTERED);
    }

    /**
     * 资源管理器就绪后自检一次：每件真物品的**物品定义 + 模型 + 贴图**三件套都要在客户端里。
     *
     * <p>少任何一个，游戏里就是紫黑格（而且只在真的渲染到它时才报错，很容易漏）——
     * 所以这里主动查一遍并把结果写进日志，方便无人值守验证。
     */
    public static void registerAssetCheck() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (assetChecked || client.screen == null || client.getResourceManager() == null) {
                return;
            }
            assetChecked = true;
            List<String> missing = new ArrayList<>();
            int found = 0;
            for (HubSuiteItemDefs.Def def : REGISTERED) {
                for (String file : List.of("items/" + def.path() + ".json",
                        "models/item/" + def.path() + ".json",
                        "textures/item/" + def.path() + ".png")) {
                    Identifier id = Identifier.fromNamespaceAndPath(HubSuiteItemDefs.NAMESPACE, file);
                    if (client.getResourceManager().getResourceStack(id).isEmpty()) {
                        missing.add(file);
                    } else {
                        found++;
                    }
                }
            }
            if (missing.isEmpty()) {
                HubSuiteClient.LOGGER.info("真物品素材自检通过：{} 个文件都在（物品定义 + 模型 + 贴图）", found);
            } else {
                HubSuiteClient.LOGGER.error("真物品素材缺失 {} 个：{} —— 客户端 jar 里少了文件，游戏里会显示成紫黑格",
                        missing.size(), missing);
            }
        });
    }
}
