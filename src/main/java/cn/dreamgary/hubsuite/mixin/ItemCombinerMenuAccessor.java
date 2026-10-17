package cn.dreamgary.hubsuite.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@code ItemCombinerMenu.player}（protected，铁砧/砂轮等共用的基类）。
 *
 * <p>为什么不直接 {@code @Shadow}：影子只能命中**目标类自己声明**的字段，
 * 这个字段在父类上（同样的坑踩过一次，见 AGENT.md）。
 */
@Mixin(ItemCombinerMenu.class)
public interface ItemCombinerMenuAccessor {

    @Accessor("player")
    Player hubsuite$player();
}
