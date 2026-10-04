package cn.dreamgary.hubsuite.auth;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.ui.DialogKit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 / 注册对话框。
 *
 * <p>两个表单：
 * <ul>
 *   <li><b>登录</b>：一个密码框 + [登录] [去注册] [退出]</li>
 *   <li><b>注册</b>：确认名字、密码、确认密码 + [注册] [返回登录]</li>
 * </ul>
 *
 * <p>点击按钮后，客户端会把输入框的值按 key 塞进 NBT 负载，
 * 通过 {@code hubsuite:auth/...} 动作发回服务端（见
 * {@link cn.dreamgary.hubsuite.mixin.ServerCommonPacketListenerMixin}）。
 * 密码全程不经过聊天与命令，因此不会出现在任何日志里。
 */
public final class AuthGui {

    /** 动作 id。 */
    public static final String ACTION_LOGIN = "auth/login";
    public static final String ACTION_REGISTER = "auth/register";
    public static final String ACTION_OPEN_LOGIN = "auth/open_login";
    public static final String ACTION_OPEN_REGISTER = "auth/open_register";

    /** 输入框 key（客户端回传 NBT 里的字段名）。 */
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_CONFIRM = "confirm";

    /** 已发出的动作 id → 玩家，用于判断回包是否是我们关心的表单。 */
    private static final Map<UUID, String> OPEN_FORM = new ConcurrentHashMap<>();

    private AuthGui() {
    }

    // ------------------------------------------------------------------
    // 打开
    // ------------------------------------------------------------------

    /**
     * 根据玩家是否已注册，弹出对应的表单。
     *
     * <p>查库放到后台线程，避免进服瞬间卡主线程；查完再回主线程弹窗。
     */
    public static void openFor(ServerPlayer player, AuthService service, AuthManager manager) {
        String username = player.getName().getString();
        service.isRegisteredAsync(username, registered -> {
            HubSuite.logger().info("为 {} 弹出{}表单（账号{}存在）。",
                    username,
                    registered ? "登录" : "注册",
                    registered ? "已" : "未");
            if (registered) {
                openLogin(player, service, null);
            } else {
                openRegister(player, service, null);
            }
        });
    }

    /** 打开登录表单；{@code error} 非空时显示为提示文字。 */
    public static void openLogin(ServerPlayer player, AuthService service, String error) {
        var body = new java.util.ArrayList<net.minecraft.server.dialog.body.DialogBody>();
        body.add(DialogKit.text(Component.literal("\u00A77请输入密码完成登录。")));
        if (error != null && !error.isBlank()) {
            body.add(DialogKit.text(Component.literal("\u00A7c" + error)));
        }
        body.add(DialogKit.text(Component.literal(
                "\u00A78若忘记密码，请联系管理员重置。")));

        var inputs = java.util.List.of(
                DialogKit.textInput(KEY_PASSWORD,
                        Component.literal("密码"),
                        "",
                        service.config().maxPasswordLength));

        var actions = java.util.List.of(
                DialogKit.submitButton(Component.literal("\u00A7a登 录"), DialogKit.action(ACTION_LOGIN)),
                DialogKit.customButton(Component.literal("\u00A7e去注册"), DialogKit.action(ACTION_OPEN_REGISTER),
                        java.util.Optional.empty()));

        DialogKit.open(player, DialogKit.form(
                Component.literal("\u00A7bHubSuite \u00A77登录"),
                body,
                inputs,
                actions,
                DialogKit.customButton(Component.literal("\u00A7c退 出"), DialogKit.action("auth/quit"),
                        java.util.Optional.empty())));
        OPEN_FORM.put(player.getUUID(), ACTION_LOGIN);
    }

    /** 打开注册表单。 */
    public static void openRegister(ServerPlayer player, AuthService service, String error) {
        var body = new java.util.ArrayList<net.minecraft.server.dialog.body.DialogBody>();
        body.add(DialogKit.text(Component.literal("\u00A77首次进入请设置密码完成注册。")));
        body.add(DialogKit.text(Component.literal("\u00A77用户名固定为你的游戏名：\u00A7f" + player.getName().getString())));
        if (error != null && !error.isBlank()) {
            body.add(DialogKit.text(Component.literal("\u00A7c" + error)));
        }

        var inputs = java.util.List.of(
                DialogKit.textInput(KEY_PASSWORD,
                        Component.literal("密码（至少 " + service.config().minPasswordLength + " 位）"),
                        "",
                        service.config().maxPasswordLength),
                DialogKit.textInput(KEY_CONFIRM,
                        Component.literal("确认密码"),
                        "",
                        service.config().maxPasswordLength));

        var actions = java.util.List.of(
                DialogKit.submitButton(Component.literal("\u00A7a注 册"), DialogKit.action(ACTION_REGISTER)),
                DialogKit.customButton(Component.literal("\u00A7e已有账号，去登录"), DialogKit.action(ACTION_OPEN_LOGIN),
                        java.util.Optional.empty()));

        DialogKit.open(player, DialogKit.form(
                Component.literal("\u00A7bHubSuite \u00A77注册"),
                body,
                inputs,
                actions,
                DialogKit.customButton(Component.literal("\u00A7c退 出"), DialogKit.action("auth/quit"),
                        java.util.Optional.empty())));
        OPEN_FORM.put(player.getUUID(), ACTION_REGISTER);
    }

    /** 认证失败后重开当前表单并显示错误。 */
    public static void reopenWithError(ServerPlayer player, AuthService service, AuthManager manager, String error) {
        String form = OPEN_FORM.getOrDefault(player.getUUID(), ACTION_LOGIN);
        if (ACTION_REGISTER.equals(form)) {
            openRegister(player, service, error);
        } else {
            openLogin(player, service, error);
        }
    }

    public static void forget(UUID uuid) {
        OPEN_FORM.remove(uuid);
    }
}
