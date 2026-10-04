package cn.dreamgary.hubsuite.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 文本与颜色码工具。
 *
 * <p><b>为什么需要它：</b>配置里习惯写 {@code &a生存服}（Bukkit 风格），
 * 但 Minecraft 自己的颜色码是 {@code \u00A7}（section sign，U+00A7）。
 * 如果把 {@code &a} 直接塞进 {@link Component#literal}，游戏会**原样显示
 * "&a生存服"** —— 实测出现过"提示信息带多余的 & 颜色提示符"。
 *
 * <p>这里统一把 {@code &} 与 {@code \u00A7} 两种写法都转成真正的带色 Component。
 *
 * <p>另外：本文件刻意用 {@code \u00A7} 转义而不是直接写 \u00A7 字符，
 * 因为 Java 源码里出现 \u00A7 时，编译器报错信息会把它渲染成 '?'，很难排查。
 */
public final class Text {

    /** Minecraft 的颜色/格式码前缀。 */
    public static final char SECTION = '\u00A7';

    private Text() {
    }

    /** 把 {@code &} 颜色码转成 {@code \u00A7}。 */
    public static String ampersandToSection(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        char[] chars = input.toCharArray();
        for (int i = 0; i + 1 < chars.length; i++) {
            if (chars[i] == '&' && isColorCode(chars[i + 1])) {
                chars[i] = SECTION;
            }
        }
        return new String(chars);
    }

    /** 去掉所有颜色码，得到纯文本（用于日志、Tab 名等不渲染颜色的地方）。 */
    public static String stripColors(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String colored = ampersandToSection(input);
        StringBuilder out = new StringBuilder(colored.length());
        for (int i = 0; i < colored.length(); i++) {
            char c = colored.charAt(i);
            if (c == SECTION && i + 1 < colored.length()) {
                i++;   // 跳过颜色码
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 把带颜色码的字符串转成 {@link Component}。
     *
     * <p>支持 {@code &a} / {@code \u00A7a} 两种写法；同时支持 {@code &&} 表示字面量 &。
     */
    public static MutableComponent of(String input) {
        String text = input == null ? "" : input;
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 字面量 & ：&& → &
            if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                buffer.append('&');
                i++;
                continue;
            }

            boolean isPrefix = (c == '&' || c == SECTION);
            if (isPrefix && i + 1 < text.length() && isColorCode(text.charAt(i + 1))) {
                // 先把已累积的文本按当前样式输出
                if (buffer.length() > 0) {
                    result.append(Component.literal(buffer.toString()).setStyle(style));
                    buffer.setLength(0);
                }
                style = applyCode(style, Character.toLowerCase(text.charAt(i + 1)));
                i++;
                continue;
            }

            buffer.append(c);
        }

        if (buffer.length() > 0) {
            result.append(Component.literal(buffer.toString()).setStyle(style));
        }

        // 单段文本时直接把带样式的 literal 返回。
        // 不要用 `(MutableComponent) result.getSiblings().get(0)` 强转 ——
        // 那样虽然不抛异常，却会把样式丢掉（实测：颜色值变成 null）。
        if (result.getSiblings().size() == 1 && result.getContents().toString().isEmpty()) {
            Component only = result.getSiblings().get(0);
            if (only instanceof MutableComponent mutable) {
                return mutable;
            }
        }
        return result;
    }

    /** 配置里允许的格式码：0-9 a-f 颜色，k-o 格式，r 重置。 */
    private static boolean isColorCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || lower == 'k' || lower == 'l' || lower == 'm'
                || lower == 'n' || lower == 'o' || lower == 'r';
    }

    private static Style applyCode(Style style, char code) {
        if (code == 'r') {
            return Style.EMPTY;
        }
        TextColor color = TextColor.parseColor("#" + legacyHex(code)).result().orElse(null);
        if (color == null) {
            return style;
        }
        return style.withColor(color);
    }

    /** 旧版 16 色码 → 十六进制。 */
    private static String legacyHex(char code) {
        return switch (code) {
            case '0' -> "000000";
            case '1' -> "0000AA";
            case '2' -> "00AA00";
            case '3' -> "00AAAA";
            case '4' -> "AA0000";
            case '5' -> "AA00AA";
            case '6' -> "FFAA00";
            case '7' -> "AAAAAA";
            case '8' -> "555555";
            case '9' -> "5555FF";
            case 'a' -> "55FF55";
            case 'b' -> "55FFFF";
            case 'c' -> "FF5555";
            case 'd' -> "FF55FF";
            case 'e' -> "FFFF55";
            case 'f' -> "FFFFFF";
            default -> "FFFFFF";
        };
    }
}
