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

    /**
     * 诊断用：缓存里现在**还有没有**这个玩家的成就条目。
     *
     * <p>给自检判断"切服到底有没有把缓存放掉"用 —— 这是成就/统计能否隔离的
     * 唯一前提，光看文件路径推断不出结论。
     */
    boolean hubsuite$debugHasAdvancements(UUID uuid);

    /** 诊断用：缓存里现在还有没有这个玩家的统计条目。 */
    boolean hubsuite$debugHasStats(UUID uuid);
}
