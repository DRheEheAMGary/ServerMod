package cn.dreamgary.hubsuite.ui;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.ButtonListDialog;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对话框构造工具。
 *
 * <p><b>为什么用对话框而不是命令：</b>26.1 的原版对话框支持文本输入框
 * （{@link TextInput}），配合 {@link CustomAll} 动作，客户端会把**每个输入框的值
 * 按 key 合并进一个 NBT 负载**，通过 {@code ServerboundCustomClickActionPacket}
 * 发回服务端。整条链路不经过聊天、不经过命令、不写日志 —— 所以密码是安全的。
 *
 * <p><b>关键约束：</b>
 * <ul>
 *   <li>对话框用 {@link Holder#direct} 内联发送，不需要注册到数据包仓库；</li>
 *   <li>{@code after_action} 用 {@code CLOSE}。不要用 {@code WAIT_FOR_RESPONSE} ——
 *       后者会让客户端进入"等待服务器响应"状态并屏蔽点击数秒，看起来像卡死；</li>
 *   <li>每个对话框必须有一个"取消/退出"按钮，玩家不能关闭时会被卡住。</li>
 * </ul>
 */
public final class DialogKit {

    /** 输入框宽度：原版默认 200。 */
    public static final int DEFAULT_WIDTH = 200;

    private DialogKit() {
    }

    // ------------------------------------------------------------------
    // 打开
    // ------------------------------------------------------------------

    /** 把对话框发给玩家。 */
    public static void open(ServerPlayer player, net.minecraft.server.dialog.Dialog dialog) {
        try {
            player.openDialog(Holder.direct(dialog));
        } catch (Exception e) {
            HubSuite.logger().error("向 {} 打开对话框失败", player.getName().getString(), e);
        }
    }

    /** 关闭玩家当前对话框。 */
    public static void close(ServerPlayer player) {
        try {
            player.connection.send(
                    net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
        } catch (Exception e) {
            HubSuite.logger().debug("关闭对话框失败：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    /** 一个文本输入框。 */
    public static Input textInput(String key, Component label, String initial, int maxLength) {
        return new Input(key, new TextInput(
                DEFAULT_WIDTH,
                label,
                true,
                initial == null ? "" : initial,
                Math.max(1, maxLength),
                Optional.empty()));
    }

    /** 一段说明文字。 */
    public static DialogBody text(Component contents) {
        return new PlainMessage(contents, DEFAULT_WIDTH);
    }

    /** 一个按钮：点击后把输入值以 {@code payload} 发到服务端指定 {@code actionId}。 */
    public static ActionButton customButton(Component label, Identifier actionId, Optional<CompoundTag> additions) {
        return new ActionButton(
                new CommonButtonData(label, CommonButtonData.DEFAULT_WIDTH),
                Optional.of(new CustomAll(actionId, additions)));
    }

    /** 提交型按钮：带上所有输入框的值一起回传。 */
    public static ActionButton submitButton(Component label, Identifier actionId) {
        return customButton(label, actionId, Optional.empty());
    }

    /** 纯关闭按钮（点一下关掉对话框，不产生负载）。 */
    public static ActionButton closeButton(Component label) {
        return new ActionButton(
                new CommonButtonData(label, CommonButtonData.DEFAULT_WIDTH),
                Optional.empty());
    }

    /** 组装 {@link CommonDialogData}（所有对话框类型共用）。 */
    public static CommonDialogData common(Component title,
                                          List<DialogBody> body,
                                          List<Input> inputs,
                                          boolean canCloseWithEscape) {
        return new CommonDialogData(
                title,
                Optional.empty(),
                canCloseWithEscape,
                false,                 // pause=false：多人服务端不应该暂停
                // 必须用 CLOSE 而不是 WAIT_FOR_RESPONSE：
                // WAIT_FOR_RESPONSE 会让客户端一直显示"等待服务器响应"并屏蔽后续点击约 5 秒，
                // 玩家会以为卡死了（实测踩过）。CLOSE 会在动作执行后就地关闭对话框。
                DialogAction.CLOSE,
                body == null ? List.of() : body,
                inputs == null ? List.of() : inputs);
    }

    /**
     * 带输入框 + 多个按钮的对话框（登录/注册表单用它）。
     *
     * @param exitButton 右下角的退出按钮，必须提供，否则玩家可能被卡在对话框里
     */
    public static MultiActionDialog form(Component title,
                                         List<DialogBody> body,
                                         List<Input> inputs,
                                         List<ActionButton> actions,
                                         ActionButton exitButton) {
        return new MultiActionDialog(
                common(title, body, inputs, false),
                actions,
                Optional.ofNullable(exitButton),
                1);
    }

    /** 单按钮通知框。 */
    public static NoticeDialog notice(Component title, Component message, ActionButton action) {
        return new NoticeDialog(
                common(title, List.of(text(message)), List.of(), true),
                action);
    }

    /** 确认框（是 / 否）。 */
    public static ConfirmationDialog confirm(Component title,
                                             Component message,
                                             ActionButton yes,
                                             ActionButton no) {
        return new ConfirmationDialog(
                common(title, List.of(text(message)), List.of(), true),
                yes,
                no);
    }

    /** 便于日志/调试：列出对话框需要的输入 key。 */
    public static List<String> inputKeys(ButtonListDialog dialog) {
        List<String> keys = new ArrayList<>();
        if (dialog instanceof MultiActionDialog multi) {
            multi.common().inputs().forEach(i -> keys.add(i.key()));
        }
        return List.copyOf(keys);
    }

    /** 统一构造 hubsuite 命名空间的动作 id。 */
    public static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath("hubsuite", path);
    }
}
