package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;

/**
 * {@link CommandGuard} 的持有者。
 *
 * <p>为什么单独放一个类：Mixin 类里<strong>不允许</strong>出现非 private 的静态方法
 * （Mixin 会把它们当作要注入到目标类的方法，直接报
 * {@code InvalidMixinException: contains non-private static method}）。
 * 所以"注入闸门"这件事必须由外部持有者来做，Mixin 只负责读取。
 */
public final class CommandGuardHolder {

    private static volatile CommandGuard guard;

    private CommandGuardHolder() {
    }

    public static void set(CommandGuard value) {
        guard = value;
        if (value != null) {
            HubSuite.logger().debug("命令闸门已注入。");
        }
    }

    public static CommandGuard get() {
        return guard;
    }
}
