package cn.dreamgary.hubsuite.rod;

import net.minecraft.world.level.Level;
import net.minecraft.resources.Identifier;

/**
 * 钓竿系统的**作用域**：只在**海岛服**（{@code hubsuite:skyblock_ocean*}）生效。
 *
 * <p>用户定的：钓鱼是海岛服的内容。在空岛服（经典空岛）、生存服、创造服、大厅里，
 * 装配台打不开、钓竿的耐久 / 断线 / 铁砧升级统统不生效（就是一根普通原版钓竿）。
 */
public final class RodScope {

    private RodScope() {
    }

    /** 这个维度属不属于海岛服。 */
    public static boolean allowed(Level level) {
        if (level == null) {
            return false;
        }
        Identifier id = level.dimension().identifier();
        // 海岛服的维度：hubsuite:skyblock_ocean / skyblock_ocean_nether / skyblock_ocean_end
        return id.getPath().contains("skyblock_ocean");
    }
}
