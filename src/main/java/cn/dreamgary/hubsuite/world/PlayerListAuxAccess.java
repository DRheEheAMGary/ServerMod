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
     * 把某个玩家的成就与统计存盘，并从 {@code PlayerList} 的缓存里移除，
     * **同时把玩家自己身上那份引用也换掉**。
     *
     * <p>用于玩家切换子服维度时：旧维度那份数据必须落盘，且缓存要清掉，
     * 下一次访问才会按新维度重建。
     *
     * <p><b>为什么必须传 player（不能只给 UUID）：</b>
     * {@code ServerPlayer} 自己也持有两个 <b>final</b> 字段
     * （{@code advancements} / {@code stats}），而
     * {@code getStats()} / {@code getAdvancements()} 的字节码就是
     * "读那个字段并返回"。只清 {@code PlayerList} 的两张 map 时，
     * 玩家身上的引用还指着旧对象 —— 而那个对象的文件路径是**构造时烧死的**。
     * 换维度时玩家对象是复用的（子服只是同进程内的不同维度），
     * 于是它继续读写旧子服的目录，隔离等于没做。
     * 实测现象就是"服务器之间的成就没有隔离"。
     *
     * @return 实际处理的条目数（统计 + 成就，各算 1）
     */
    int hubsuite$flushAux(net.minecraft.server.level.ServerPlayer player);

    /**
     * 为这个玩家重建成就/统计对象，并**把玩家身上那两个 final 引用换成新的**。
     *
     * <p>必须在玩家**已经在目标维度里**之后调用：新对象是在构造时按
     * "当时所在维度"解析目录的（{@code AuxDataRouter} 的上下文来自
     * {@code player.level().dimension()}），所以在传送**之前**换会又绑到旧目录。
     *
     * @return 实际换掉的引用个数（0..2）
     */
    int hubsuite$rebindAux(net.minecraft.server.level.ServerPlayer player);

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
