package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.world.PortalLinks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让火能在**我们自己造的维度**里点燃下界传送门。
 *
 * <p><b>用户实测现象：</b>"我激活不了地狱传送门" —— 黑曜石框架摆好、打火石点上，
 * 火在框架里烧着，但**不生成传送门**。
 *
 * <p><b>根因：</b>原版 {@code BaseFireBlock.isPortal(Level, BlockPos, Direction)}
 * 在检查框架之前先调了 {@code inPortalDimension(Level)}，而它的判据是
 * <b>写死的两个维度 key</b>：
 * <pre>
 *   Level.dimension() == Level.OVERWORLD  ||  Level.dimension() == Level.NETHER
 * </pre>
 * 本模组的子服主维度是 {@code hubsuite:server_survival} 这种自有 key
 * （因为 {@code MinecraftServer.levels} 按 key 唯一，三个子服不能都用
 * {@code minecraft:overworld}），下界也是 {@code hubsuite:survival_nether}。
 * <b>两个都不匹配</b>，于是 {@code isPortal} 直接返回 false，
 * 火永远不会被识别成"框架里的火"。
 *
 * <p><b>做法：</b>把返回值改成"原版结果 <b>或</b> 这个维度是本模组托管的"
 * （{@link PortalLinks#isExtraDimension} 登记的下界/末地，以及子服主维度）。
 *
 * <p>注意：这只修好了"能不能点火"。火点着之后走哪一界，由
 * {@link PortalBlockDestinationMixin} 负责 —— 两者缺一不可。
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockPortalMixin {

    @ModifyReturnValue(method = "inPortalDimension", at = @At("RETURN"))
    private static boolean hubsuite$allowOurDimensions(boolean original, Level level) {
        if (original || level == null) {
            return original;
        }
        try {
            var dimension = level.dimension();
            // 我们自己造的下界/末地（例如 hubsuite:survival_nether）
            if (PortalLinks.isExtraDimension(dimension)) {
                return true;
            }
            // 子服的"主维度"（hubsuite:server_survival 等）—— 它们不在
            // PortalLinks 的两个反向表里，所以单独问一句
            return PortalLinks.isSubServerDimension(dimension);
        } catch (Throwable t) {
            return original;
        }
    }
}
