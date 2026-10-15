package cn.dreamgary.hubsuite.net;

import java.util.Locale;

/**
 * 客户端版本号比较（客户端契约 v1 · 第 5 节）。
 *
 * <p>只认 {@code 主.次.补-限定词.序号} 这种形式；限定词顺序照 AGENT.md：
 * {@code alpha < beta < pre < rc < 正式}。{@code -SNAPSHOT} 按"同版本的正式版"处理，
 * 这样开发期服务端与客户端出自同一次构建时不会被版本门槛挡住。
 */
public final class ClientVersions {

    private ClientVersions() {
    }

    /** {@code required} 为空 = 不检查版本；{@code actual} 为空 = 不通过。 */
    public static boolean atLeast(String actual, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (actual == null || actual.isBlank()) {
            return false;
        }
        int[] a = numbers(actual);
        int[] r = numbers(required);
        for (int i = 0; i < 3; i++) {
            if (a[i] != r[i]) {
                return a[i] > r[i];
            }
        }
        return rank(actual) >= rank(required);
    }

    /** 主 / 次 / 补三个数字，解析不出来的位按 0。 */
    static int[] numbers(String version) {
        int[] out = new int[3];
        String core = version.trim();
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            try {
                out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * 限定词的可比较值：{@code 档位 * 1000 + 序号}。
     *
     * <p>正式版（含 {@code -SNAPSHOT}）= 4000，所以
     * {@code alpha.99(99) < beta.1(1001) < pre.9(2009) < rc.1(3001) < 正式(4000)}。
     */
    static int rank(String version) {
        String s = version.trim().toLowerCase(Locale.ROOT);
        int dash = s.indexOf('-');
        String q = dash < 0 ? "" : s.substring(dash + 1);
        int tier;
        if (q.startsWith("alpha")) {
            tier = 0;
        } else if (q.startsWith("beta")) {
            tier = 1;
        } else if (q.startsWith("pre")) {
            tier = 2;
        } else if (q.startsWith("rc")) {
            tier = 3;
        } else {
            return 4000;   // 正式版 / SNAPSHOT / 认不出来的限定词
        }
        int num = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(q);
        if (m.find()) {
            try {
                num = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                num = 0;
            }
        }
        return tier * 1000 + Math.min(num, 999);
    }
}
