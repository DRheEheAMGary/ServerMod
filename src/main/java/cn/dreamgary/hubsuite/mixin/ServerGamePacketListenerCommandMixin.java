package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.CommandGuard;
import cn.dreamgary.hubsuite.feature.CommandGuardHolder;
import cn.dreamgary.hubsuite.integration.HomeNamespace;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 命令的预处理点：命令闸门、家命令的**子服命名空间改写**、以及补全的翻译。
 *
 * <p>Fabric API 只提供聊天消息事件，没有"命令即将执行"事件，所以只能挂在
 * 原版的命令包处理上。两种命令包都要拦：未签名与带签名的。
 *
 * <p>家命令（{@code /home}、{@code /sethome} 等）的存储名带子服前缀
 * （见 {@link HomeNamespace}），所以：
 * <ul>
 *   <li>执行前把名字参数补上前缀，再用改写后的命令**重新派发**；</li>
 *   <li>补全时先用加前缀的命令算（否则打 \`s\` 匹配不到存的 \`1_s1\`），
 *       再把结果去前缀 / 丢掉别的子服。</li>
 * </ul>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerCommandMixin {

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardUnsigned(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        hubsuite$preprocess(packet.command(), ci);
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardSigned(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        hubsuite$preprocess(packet.command(), ci);
    }

    private void hubsuite$preprocess(String command, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) {
            return;
        }

        // 1) 命令闸门（按命令名判定；必须先于改写，改写后就不再走这里了）
        CommandGuard guard = CommandGuardHolder.get();
        if (guard != null) {
            try {
                Component denial = guard.check(player, command);
                if (denial != null) {
                    player.sendSystemMessage(denial);
                    HubSuite.logger().debug("已拦截 {} 的命令：/{}",
                            player.getName().getString(), command.split(" ", 2)[0]);
                    ci.cancel();
                    return;
                }
            } catch (Throwable t) {
                // fail-closed：闸门自己出错时必须拒绝
                HubSuite.logger().error("命令闸门异常，已按拒绝处理 /{}", command, t);
                player.sendSystemMessage(Component.literal(
                        "\u00A7c指令校验出错，已阻止执行，请联系管理员查看控制台。"));
                ci.cancel();
                return;
            }
        }

        // 2) 家命令：加子服前缀后重新派发
        try {
            String rewritten = HomeNamespace.rewrite(player, command);
            if (rewritten != null && !rewritten.equals(command)) {
                var server = player.level().getServer();
                if (server != null) {
                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), rewritten);
                }
                ci.cancel();
            }
        } catch (Throwable t) {
            HubSuite.logger().error("改写家命令失败 /{}", command, t);
        }
    }

    /**
     * 家命令的补全：用"加了前缀"的请求去算，再把结果翻译回原名。
     *
     * <p>必须先改写请求：HuskHomes 存的是 {@code 1_s1}，玩家打 {@code s} 时
     * 原版补全是拿 {@code s} 去匹配 {@code 1_s1} 的，匹配不上就没提示。
     */
    @Inject(method = "handleCustomCommandSuggestions", at = @At("HEAD"), cancellable = true)
    private void hubsuite$cleanSuggestions(ServerboundCommandSuggestionPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) {
            return;
        }
        String original = packet.getCommand();
        if (!HomeNamespace.isHomeCommand(original)) {
            return;   // 不是家命令 → 原版处理
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        try {
            String rewritten = HomeNamespace.rewrite(player, original);
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            StringReader reader = new StringReader(rewritten);
            if (reader.canRead() && reader.peek() == '/') {
                reader.skip();
            }
            ParseResults<CommandSourceStack> parsed =
                    dispatcher.parse(reader, player.createCommandSourceStack());
            int id = packet.getId();
            dispatcher.getCompletionSuggestions(parsed).thenAccept(suggestions -> {
                Suggestions cleaned = HomeNamespace.cleanSuggestions(player, original, suggestions);
                server.execute(() -> player.connection.send(
                        new ClientboundCommandSuggestionsPacket(id, cleaned)));
            });
            ci.cancel();
        } catch (Throwable t) {
            // 算不出来就交回原版，别把补全整个搞没
            HubSuite.logger().debug("家命令补全改写失败，交回原版：{}", t.toString());
        }
    }
}
