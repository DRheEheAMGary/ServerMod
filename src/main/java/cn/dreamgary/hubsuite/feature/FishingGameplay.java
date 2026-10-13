package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.mixin.LavaFishingHook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;

/**
 * 钓鱼玩法的判定（岩浆能不能钓、钓饵有没有生效）。
 *
 * <p>和 {@link FishingLoot} 一样刻意做成纯查询，方便自检。
 */
public final class FishingGameplay {

    private FishingGameplay() {
    }

    /** 浮漂此刻是不是泡在某种流体里。 */
    public static boolean inFluid(FishingHook hook, net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
        return hook.level().getFluidState(hook.blockPosition()).is(tag);
    }

    /**
     * 这枚浮漂能不能在**岩浆**里咬钩。
     *
     * <p>三个条件缺一不可：这枚钩子是用**熔岩钓竿**抛的、所在维度配了渔获表、
     * 而且真的泡在岩浆里。
     */
    public static boolean canFishInLava(FishingHook hook) {
        if (!(hook instanceof LavaFishingHook duck) || !duck.hubsuite$fromLavaRod()) {
            return false;
        }
        if (!(hook.level() instanceof ServerLevel level)) {
            return false;
        }
        if (FishingLoot.tableFor(HubSuite.fishing(), level.dimension()) == null) {
            return false;
        }
        return inFluid(hook, FluidTags.LAVA);
    }

    /** 副手钓饵当前给这枚钩子的加速比例（0 = 没生效）。 */
    public static double baitSpeedup(FishingHook hook) {
        Player owner = hook.getPlayerOwner();
        if (owner == null) {
            return 0;
        }
        return FishingBait.speedup(owner, inFluid(hook, FluidTags.LAVA));
    }

    /** 钓上来一条：扣掉一个生效的副手钓饵。 */
    public static void consumeBaitOnCatch(FishingHook hook) {
        Player owner = hook.getPlayerOwner();
        if (owner == null) {
            return;
        }
        FishingBait.consume(owner, inFluid(hook, FluidTags.LAVA));
    }
}
