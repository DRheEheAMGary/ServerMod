package cn.dreamgary.hubsuite.feature;

import net.minecraft.world.entity.projectile.FishingHook;

/**
 * 给 {@code FishingHook} 挂一个"这枚钩子是熔岩钓竿抛的"标记。
 *
 * <p><b>为什么放在业务包、而不是 {@code mixin} 包：</b>
 * 普通代码（{@link FishingGameplay}）要 {@code hook instanceof LavaFishingHook}，
 * 而 Mixin 的规则是"mixin 包里的类不能被普通代码直接引用"，否则在启动期
 * （{@code Blocks.<clinit>} → {@code FireBlock.bootStrap}）就抛
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly}，
 * 服务端起不来。这正是 AGENT.md「十、Mixin 约定」第 2 条：
 * helper 放业务包，mixin 包只做转发。
 */
public interface LavaFishingHook {

    boolean hubsuite$fromLavaRod();

    void hubsuite$setFromLavaRod(boolean value);
}
