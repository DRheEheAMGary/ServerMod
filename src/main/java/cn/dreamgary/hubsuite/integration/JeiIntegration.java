package cn.dreamgary.hubsuite.integration;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI（Just Enough Items）适配。
 *
 * <p><b>为什么这里几乎不"调用"JEI：</b>JEI 是 {@code environment: "*"} 的模组，
 * 装在<b>服务端</b>时它只做三件事 —— 读服务端配置（{@code config/jei/jei-server.toml}）、
 * 处理客户端 JEI 发来的包（配方转移 / 作弊拿物品 / 扔物品，均按 OP 等级把关）、
 * 注册 {@code jei:jei_shaped} / {@code jei:jei_smelting} 两个配方序列化器。
 * 它的<b>插件 API（{@code IModPlugin}）只在客户端加载</b>（{@code ClientLifecycleHandler}），
 * 服务端没有任何可调用的插件接口 —— 所以本类不引用 JEI 的类，
 * 软依赖 = 探测 + 校验我们自己的内容对 JEI 友好。
 *
 * <p><b>JEI 里的配方从哪来：</b>客户端的配方管理器（原版登录时同步整份）。
 * 我们的配方是原版 {@code crafting_shaped}/{@code crafting_shapeless}，
 * 其中的自定义物品由 Polymer 在发包时换成"试炼钥匙 + item_model"
 * （{@code IngredientMixin} / {@code SlotDisplayMixin}），所以：
 * <ul>
 *   <li>客户端 JEI 的<b>配方页</b>能看到全部自定义配方，图标/名字都对（装了资源包时）；</li>
 *   <li>"点 + 号自动摆合成格"由服务端 JEI 的通用处理器处理，无需我们做任何事；</li>
 *   <li>但 JEI 的<b>物品列表</b>里不会有这些物品的独立条目（客户端注册表里没有它们）
 *       —— 除非客户端也装本模组。</li>
 * </ul>
 */
public final class JeiIntegration {

    public static final String MOD_ID = "jei";

    private final boolean present;
    private final String version;

    public JeiIntegration() {
        this.present = FabricLoader.getInstance().isModLoaded(MOD_ID);
        this.version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    public boolean present() {
        return present;
    }

    public String version() {
        return version;
    }

    /**
     * 我们的配方里有没有<b>不是</b>原版 {@code shaped}/{@code shapeless} 的。
     *
     * <p>JEI 的"配方转移"只认原版类型（其余要客户端插件注册 transfer handler），
     * 所以自定义配方必须保持原版类型。返回违规的配方 id，空表 = 全部兼容。
     */
    public static List<String> nonVanillaTypeRecipes(MinecraftServer server) {
        List<String> bad = new ArrayList<>();
        for (var holder : server.getRecipeManager().getRecipes()) {
            var id = holder.id().identifier();
            if (!"hubsuite".equals(id.getNamespace())) {
                continue;
            }
            Object recipe = holder.value();
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
                bad.add(id.toString());
            }
        }
        return bad;
    }

    /**
     * 我们的配方里有没有引用<b>没实现 {@link PolymerItem}</b> 的自定义物品。
     *
     * <p>那种物品在发包时不会被换成原版物品，原版客户端的配方管理器会
     * 把整条配方静默丢掉（JEI 里也就看不到）。返回违规描述，空表 = 全部可见。
     */
    public static List<String> recipesWithInvisibleItems(MinecraftServer server) {
        List<String> bad = new ArrayList<>();
        for (var holder : server.getRecipeManager().getRecipes()) {
            var id = holder.id().identifier();
            if (!"hubsuite".equals(id.getNamespace())) {
                continue;
            }
            for (var ingredient : holder.value().placementInfo().ingredients()) {
                ingredient.items().forEach(h -> {
                    Item item = h.value();
                    if ("hubsuite".equals(BuiltInRegistries.ITEM.getKey(item).getNamespace())
                            && !(item instanceof PolymerItem)) {
                        bad.add(id + " 的原料 " + BuiltInRegistries.ITEM.getKey(item)
                                + " 不是 PolymerItem，原版客户端/JEI 看不到这条配方");
                    }
                });
            }
        }
        return bad;
    }
}
