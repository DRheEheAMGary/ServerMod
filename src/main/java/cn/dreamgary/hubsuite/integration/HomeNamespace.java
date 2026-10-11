package cn.dreamgary.hubsuite.integration;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.SubServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * HuskHomes「家」的**子服命名空间**。
 *
 * <p>HuskHomes 用 {@code (玩家, 家名)} 做主键，同名无法共存；不分开的话，
 * 在创造服 {@code /sethome s1} 会把生存服的 {@code s1} **覆盖掉**。
 * 所以存储时给名字加一个**子服前缀**，让两边的 {@code s1} 各自存在：
 *
 * <pre>
 *   生存服 /sethome s1  → 存 1_s1
 *   创造服 /sethome s1  → 存 2_s1
 *   生存服 /home s1     → 查 1_s1
 * </pre>
 *
 * <p>前缀用**数字 + 下划线**（\`1_\`、\`2_\`…），按子服在配置里的顺序编号，
 * 大厅是 \`0_\`。数字短，能给家名留足 14 个字符（HuskHomes 上限 16）。
 *
 * <p>玩家输入和列表显示都还是原名 —— 输入在命令层改写，显示在列表事件里去掉前缀。
 *
 * <p><b>0.x 阶段不做迁移</b>：旧的无前缀家不会被识别，重设即可。
 */
public final class HomeNamespace {

    /** 需要改写的家命令（/phome 是公共家，不处理）。 */
    private static final Set<String> HOME_COMMANDS = Set.of("home", "sethome", "delhome", "edithome");

    private HomeNamespace() {
    }

    /** 场所对应的前缀，例如 \`1_\`。子服按配置顺序编号，大厅是 \`0_\`。 */
    public static String prefixFor(String placeId) {
        if (placeId == null) {
            return "9_";
        }
        if (Lobby.ID.equals(placeId)) {
            return "0_";
        }
        var worlds = HubSuite.worlds();
        if (worlds == null) {
            return "9_";
        }
        int index = 1;
        for (SubServer sub : worlds.subServers()) {
            if (sub.id().equals(placeId)) {
                return index + "_";
            }
            index++;
        }
        return "9_";
    }

    public static String apply(String placeId, String name) {
        return prefixFor(placeId) + name;
    }

    public static String strip(String placeId, String name) {
        String prefix = prefixFor(placeId);
        return name != null && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    /** 玩家当前场所 id（子服 id / "lobby"）。 */
    public static Optional<String> placeIdOf(ServerPlayer player) {
        var worlds = HubSuite.worlds();
        if (worlds == null || player == null) {
            return Optional.empty();
        }
        return worlds.worldOf(player).map(PlayableWorld::id);
    }

    /**
     * 把家命令的**名字参数**加上当前子服前缀。
     *
     * @return 改写后的命令；不是家命令 / 没参数 / 不在任何场所时**原样返回**
     */
    public static String rewrite(ServerPlayer player, String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return rawCommand;
        }
        int space = rawCommand.indexOf(' ');
        String head = space < 0 ? rawCommand : rawCommand.substring(0, space);
        String rest = space < 0 ? "" : rawCommand.substring(space + 1).trim();
        String name = head.toLowerCase(Locale.ROOT);
        if (name.startsWith("huskhomes:")) {
            name = name.substring("huskhomes:".length());
        }
        if (!HOME_COMMANDS.contains(name) || rest.isEmpty()) {
            return rawCommand;
        }
        Optional<String> place = placeIdOf(player);
        if (place.isEmpty()) {
            return rawCommand;
        }
        String[] args = rest.split("\\s+");
        // 名字是第一个参数；/edithome 的第二个参数（新名字）也要带上，否则改名后会掉出命名空间
        int count = "edithome".equals(name) ? Math.min(2, args.length) : 1;
        for (int i = 0; i < count; i++) {
            if (!args[i].isBlank()) {
                args[i] = apply(place.get(), args[i]);
            }
        }
        String rewritten = head + " " + String.join(" ", args);
        return rewritten.equals(rawCommand) ? rawCommand : rewritten;
    }
}
