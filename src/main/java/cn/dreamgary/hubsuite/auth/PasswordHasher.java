package cn.dreamgary.hubsuite.auth;

import cn.dreamgary.hubsuite.HubSuite;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希：PBKDF2-HMAC-SHA256 + 每账号独立随机盐。
 *
 * <p>存库格式（单字段，便于以后平滑升级算法）：
 * <pre>pbkdf2-sha256$&lt;迭代次数&gt;$&lt;盐 base64&gt;$&lt;哈希 base64&gt;</pre>
 *
 * <p>校验用 {@link MessageDigest#isEqual} 做常量时间比较，避免计时侧信道。
 */
public final class PasswordHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 生成 {@code pbkdf2-sha256$迭代$盐$哈希} 形式的密码串。 */
    public static String hash(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, iterations);
        Base64.Encoder enc = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(key);
    }

    /**
     * 校验密码。
     *
     * @return 是否匹配；存储串格式非法时返回 false
     */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            HubSuite.logger().warn("密码存储格式非法，拒绝校验。");
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            HubSuite.logger().warn("密码校验异常：{}", e.toString());
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("当前 JVM 不支持 " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }
}
