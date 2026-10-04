package cn.dreamgary.hubsuite.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 一个"可进入的场所"：大厅，或者某个子服。
 *
 * <p>把它抽出来，是为了让玩家切服逻辑只依赖这个接口，
 * 将来新增"活动服/小游戏服"时不用改任何切服代码。
 */
public interface PlayableWorld {

    /** 唯一 id（大厅固定为 {@code lobby}）。 */
    String id();

    /** 显示名，支持颜色代码。 */
    String displayName();

    /** 运行期维度。 */
    ServerLevel level();

    /** 该场所的规则集。 */
    ServerRules rules();

    /** 该场所的独立存档。 */
    IsolatedSave save();

    /** 进入时的落点。 */
    SpawnPoint spawn();

    /** 给玩家看的简介（大厅 NPC 的确认框里会用到）。 */
    default String description() {
        return "";
    }

    /** 落点：坐标 + 朝向。 */
    record SpawnPoint(double x, double y, double z, float yaw, float pitch) {
        public Vec3 vec() {
            return new Vec3(x, y, z);
        }
    }
}
