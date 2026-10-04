package cn.dreamgary.hubsuite.fake;

/**
 * 标记接口：由本模组创建的"假玩家"（大厅 NPC、自检用玩家）实现。
 *
 * <p>存在的意义：假玩家在服务端就是真正的 {@code ServerPlayer}，
 * 因此会被原版的玩家计数、在线列表、`/list`、状态查询等一并计入 ——
 * 实测出现"服务器显示 7 人在线，其中 3 个是 NPC"。
 *
 * <p>用标记接口 + Mixin 把这些地方过滤掉，比改名字或改 UUID 都可靠：
 * 名字/UUID 属于玩家身份，改它们会牵连玩家数据文件的命名。
 */
public interface HubSuiteFakePlayer {

    /** 该假玩家的用途（日志/调试用）。 */
    default String hubsuite$fakePurpose() {
        return "unknown";
    }
}
