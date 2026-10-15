package cn.dreamgary.hubsuite.net;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 双端握手用的自定义 payload（客户端契约 v1 · 第 3.3 节）。
 *
 * <p>本文件放在 {@code common/} 下，服务端与客户端**各自编译同一份源码** ——
 * 线格式只有一个来源，不可能各写一份然后漂移。
 *
 * <p><b>协议纪律（第 3.1 节）：</b>字段只增不改。新字段一律追加在每个 payload 的
 * <b>末尾</b>，旧字段永不改序 / 改型 / 删除。线格式的回归由
 * {@code world/SelfTest} 的「握手契约（含黄金字节）」用例守着：改坏了会当场失败。
 */
public final class HandshakePayloads {

    /** 双端协议号：1.0 之前可以改，1.0 之后只能加。 */
    public static final int PROTOCOL = 1;

    /** 能力名（caps）命名：小写 + 下划线。**只增不减** —— 废弃的能力要留在列表里。 */
    public static final String CAP_BASE = "base_v1";

    /**
     * 客户端注册了本模组的**真物品**（自带模型贴图），服务端可以不再用 Polymer 把它映射成原版物品。
     *
     * <p>老客户端没有这个能力 → 服务端继续发映射过的原版物品，它照旧能玩（客户端契约 v1 第 5 节）。
     */
    public static final String CAP_REAL_ITEMS = "real_items_v1";

    private HandshakePayloads() {
    }

    /** 服务端 → 客户端：打招呼。客户端必须回 {@link HelloAck}。 */
    public record Hello(int protocol, String serverVersion, String minClient, int timeoutSeconds)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Hello> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("hubsuite", "hello"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Hello::protocol,
                ByteBufCodecs.STRING_UTF8, Hello::serverVersion,
                ByteBufCodecs.STRING_UTF8, Hello::minClient,
                ByteBufCodecs.VAR_INT, Hello::timeoutSeconds,
                Hello::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 客户端 → 服务端：应答。
     *
     * <p>字段顺序 = 线上的字节顺序，**不要重排**（黄金字节用例盯着它）。
     */
    public record HelloAck(int protocol, String clientVersion, String fingerprint, List<String> caps)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<HelloAck> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("hubsuite", "hello_ack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, HelloAck> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HelloAck::protocol,
                ByteBufCodecs.STRING_UTF8, HelloAck::clientVersion,
                ByteBufCodecs.STRING_UTF8, HelloAck::fingerprint,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), HelloAck::caps,
                HelloAck::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：校验结果（客户端只用来打日志，不参与判定）。 */
    public record Welcome(boolean ok, String reason) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Welcome> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("hubsuite", "welcome"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Welcome> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Welcome::ok,
                ByteBufCodecs.STRING_UTF8, Welcome::reason,
                Welcome::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
