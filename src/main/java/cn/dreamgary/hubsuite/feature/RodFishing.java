package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.rod.RodData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

/**
 * 钓竿的耐久 / 断线（见 docs/钓竿系统方案.md 第 5 节）。
 *
 * <p>规则：钓上东西 → 耐久 −1；**断线**（咬钩成功后掷 {@code 线.断线概率}）→
 * 空手而归 + 扣饵 + 耐久 −3 + 原版"装备损坏"音效。耐久 ≤ 0 就是坏了：不能钓、物品不消失、
 * 模型叠裂纹，只能回装配台修补（杆位放同种材料）。
 *
 * <p><b>只对"带我们数据的竿"生效</b>：普通原版钓竿一行逻辑都不碰（原版那条路照旧）。
 */
public final class RodFishing {

    /** 正常钓上来扣多少耐久。 */
    public static final int DURABILITY_PER_CATCH = 1;
    /** 断线扣多少（方案写的是 −3）。 */
    public static final int DURABILITY_PER_BREAK = 3;

    private RodFishing() {
    }

    /**
     * 断线判定：{@code rollPermille} 落在 {@code [0, 断线‰)} 里就算断。
     *
     * <p>掷骰抽成参数是为了自检能测**边界**（0 与恰好等于概率），不用去碰随机数。
     */
    public static boolean lineBreaks(RodData data, int rollPermille) {
        return data != null && rollPermille < data.breakPermille();
    }

    /**
     * 钓上一条之后处理竿（**所有维度都生效**，不只是配了渔获表的维度）。
     *
     * @return 是否断线（true = 这一杆空手而归）
     */
    public static boolean onCatch(FishingHook hook) {
        try {
            Player owner = hook.getPlayerOwner();
            // 用户定的：钓竿系统只限空岛服，别的服当普通原版钓竿
            if (!cn.dreamgary.hubsuite.rod.RodScope.allowed(hook.level())) {
                return false;
            }
            if (!(owner instanceof ServerPlayer player) || !(hook.level() instanceof ServerLevel level)) {
                return false;
            }
            ItemStack rod = player.getMainHandItem();
            RodData data = RodData.of(rod);
            if (data == null) {
                return false;   // 不是"我们的竿" → 原版行为，一个字都不改
            }
            int roll = level.getRandom().nextInt(1000);
            boolean broke = lineBreaks(data, roll);
            RodData next = data.damage(broke ? DURABILITY_PER_BREAK : DURABILITY_PER_CATCH);
            next.writeTo(rod);          // 写数据 + 刷新渲染提示（坏了就会叠裂纹）
            next.applyVanillaBar(rod);  // 原版耐久条按比例跟着走

            if (broke) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
                player.sendSystemMessage(Component.literal(
                        "\u00A7c线断了 —— 这一杆空手而归，竿也受了点伤。"));
            }
            if (next.broken()) {
                player.sendSystemMessage(Component.literal(
                        "\u00A7c这根竿已经坏掉了：去装配台在杆位放一根同种材料修补，或换底。"));
            }
            return broke;
        } catch (Throwable t) {
            HubSuite.logger().debug("钓竿耐久处理失败，沿用原版：{}", t.toString());
            return false;
        }
    }
}
