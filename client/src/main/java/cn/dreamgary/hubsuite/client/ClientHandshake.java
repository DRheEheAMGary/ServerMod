package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.net.HandshakePayloads;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 客户端侧的握手（见 docs/客户端契约.md 第 3.3 节）。
 *
 * <p>只做一件事：收到服务端的 {@code hubsuite:hello} 就把自己的
 * 版本 / 协议号 / 指纹 / 能力清单回过去。判定全在服务端 —— 客户端不自己决定能否进服。
 */
public final class ClientHandshake {

    /** 本客户端支持的能力。**只增不减**：废弃的留在列表里，服务端不再依赖即可。 */
    public static final List<String> CAPS = List.of(HandshakePayloads.CAP_BASE);

    private ClientHandshake() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayloads.Hello.TYPE, HandshakePayloads.Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayloads.Welcome.TYPE, HandshakePayloads.Welcome.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayloads.HelloAck.TYPE, HandshakePayloads.HelloAck.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(HandshakePayloads.Hello.TYPE, (payload, context) -> {
            if (payload.protocol() != HandshakePayloads.PROTOCOL) {
                HubSuiteClient.LOGGER.warn("服务端协议号 {} 与本客户端 {} 不同，仍按本端协议应答（能否进服由服务端判定）",
                        payload.protocol(), HandshakePayloads.PROTOCOL);
            }
            HandshakePayloads.HelloAck ack = new HandshakePayloads.HelloAck(
                    HandshakePayloads.PROTOCOL,
                    HubSuiteClient.version(),
                    HubSuiteClient.fingerprint(),
                    CAPS);
            context.client().execute(() -> ClientPlayNetworking.send(ack));
        });

        ClientPlayNetworking.registerGlobalReceiver(HandshakePayloads.Welcome.TYPE, (payload, context) ->
                HubSuiteClient.LOGGER.info("服务端校验结果：{} {}", payload.ok() ? "通过" : "未通过", payload.reason()));
    }
}
