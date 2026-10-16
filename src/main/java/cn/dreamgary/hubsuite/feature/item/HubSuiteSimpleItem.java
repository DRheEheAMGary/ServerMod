package cn.dreamgary.hubsuite.feature.item;

import net.minecraft.world.item.Item;

/**
 * 本模组的"简单物品"（地狱线）。
 *
 * <p>0.6 收尾起 <b>Polymer 已下线、客户端强制安装</b>：所有玩家都收到真物品
 * （{@code hubsuite:nether_string}），客户端用自己的模型与贴图渲染。
 * 没装客户端的玩家会在注册表同步阶段被 Fabric 直接断开（这就是"强制安装"的实现）。
 */
public class HubSuiteSimpleItem extends Item {

    public HubSuiteSimpleItem(Item.Properties properties) {
        super(properties);
    }
}
