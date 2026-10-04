package cn.dreamgary.hubsuite.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code Connection.channel} 是 private。
 *
 * <p>假玩家（大厅 NPC）需要一个"看起来已连接"的 {@link Connection}：
 * 原版多处逻辑会检查 {@code connection.isConnected()}（例如末影珍珠传送、
 * 区块跟随、玩家列表维护）。{@code isConnected()} 的判定就是
 * {@code channel != null && channel.isOpen()}，因此我们注入一个
 * {@link io.netty.channel.embedded.EmbeddedChannel} 让它成立。
 */
@Mixin(Connection.class)
public interface ConnectionChannelAccessor {

    @Accessor("channel")
    void hubsuite$setChannel(Channel channel);
}
