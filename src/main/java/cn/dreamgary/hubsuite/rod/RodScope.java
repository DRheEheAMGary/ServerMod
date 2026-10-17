package cn.dreamgary.hubsuite.rod;

import net.minecraft.world.level.Level;
import net.minecraft.resources.Identifier;

/**
 * 钓竿系统的**作用域**：只在空岛服（skyblock 子服的所有维度）生效。
 *
 * <p>用户定的：别管兼容 / 别管别的服 —— 在生存服、创造服、大厅里，
 * 装配台打不开、钓竿的耐久 / 断线 / 铁砧升级统统不生效（就是一根普通原版钓竿）。
 */
public final class RodScope {

    private RodScope() {
    }

    /** 这个维度属不属于空岛服。 */
    public static boolean allowed(Level level) {
        if (level == null) {
            return false;
        }
        Identifier id = level.dimension().identifier();
        // 空岛服的维度都叫 hubsuite:server_skyblock / skyblock_hub / skyblock_ocean(_nether/_end) …
        return id.getPath().contains("skyblock");
    }
}
