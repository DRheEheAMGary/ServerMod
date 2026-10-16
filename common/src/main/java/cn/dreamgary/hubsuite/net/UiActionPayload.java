package cn.dreamgary.hubsuite.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：通用容器界面里点了**按钮**（客户端契约第 8 节的 UI 动作）。
 *
 * <p>线格式刻意只用字符串（菜单 id + 动作名），跟 {@link HandshakePayloads} 一样
 * 放在 {@code common/} 双边共编，只有一个来源。
 *
 * <p>目前唯一的用途是装配台的「装配」按钮：材料在槽里只是**暂存预览**，
 * 点了按钮才消耗生效（用户实测：放上去立刻打上去不行）。
 */
public final class UiActionPayload {

    /** 菜单 id + 动作名 → 服务端找玩家当前菜单自己分发。 */
    public record UiAction(String menu, String action) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<UiAction> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("hubsuite", "ui_action"));

        public static final StreamCodec<RegistryFriendlyByteBuf, UiAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, UiAction::menu,
                ByteBufCodecs.STRING_UTF8, UiAction::action,
                UiAction::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private UiActionPayload() {
    }
}
