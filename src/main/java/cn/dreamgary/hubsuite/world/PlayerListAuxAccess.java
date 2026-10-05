package cn.dreamgary.hubsuite.world;

import java.util.UUID;

/**
 * 由 Mixin 实现的"成就/统计缓存操作"接口。
 *
 * <p>放在业务包（而不是 {@code mixin} 包）：Mixin 实现类不允许在 mixin 包里
 * 定义非 private 的静态成员，而且被实现的接口放在业务包更容易被找到。
 *
 * @see AuxDataRouter
 */
public interface PlayerListAuxAccess {

    /**
     * 把某个玩家的成就与统计存盘，并从 {@code PlayerList} 的缓存里移除。
     *
     * <p>用于玩家切换子服维度时：旧维度那份数据必须落盘，且缓存要清掉，
     * 下一次访问才会按新维度重建。
     *
     * @return 实际处理的条目数（统计 + 成就，各算 1）
     */
    int hubsuite$flushAux(UUID uuid);
}
