package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.FishingLoot;
import cn.dreamgary.hubsuite.feature.FishingPresets;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * 海岛服的特殊渔获。
 *
 * <p><b>为什么挂在 {@code getRandomItems} 的返回值上：</b>
 * 26.1.2 的钓鱼只查一张表（{@code BuiltInLootTables.FISHING}），
 * 调用链是
 * {@code FishingHook.retrieve → Holder.getLootTable(FISHING) → LootTable.getRandomItems(LootParams)}。
 * 在这一步**换掉结果**，附魔、耐久、统计数据、鱼钩回收仍然全由原版负责 ——
 * 只有"钓上来的是什么"归我们管，改动面最小。
 *
 * <p><b>没配置的维度一律原样返回</b>（默认只有 {@code hubsuite:skyblock_ocean}），
 * 所以生存服/创造服/大厅的钓鱼完全不受影响。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookLootMixin {

    @ModifyExpressionValue(
            method = "retrieve",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<ItemStack> hubsuite$islandFishingLoot(ObjectArrayList<ItemStack> original) {
        try {
            FishingHook self = (FishingHook) (Object) this;
            if (!(self.level() instanceof ServerLevel level)) {
                return original;
            }
            var config = HubSuite.fishing();
            FishingPresets.Table table = FishingLoot.tableFor(config, level.dimension());
            if (table == null) {
                return original;   // 没配置 → 原版行为，一个字都不改
            }
            Player owner = self.getPlayerOwner();
            String biome = level.getBiome(self.blockPosition()).unwrapKey()
                    .map(k -> k.identifier().getPath())
                    .orElse(null);
            int luck = owner instanceof ServerPlayer sp ? (int) sp.getLuck() : 0;
            List<FishingLoot.Catch> rolled = FishingLoot.roll(table, biome, level.getRandom(), luck);
            if (rolled.isEmpty()) {
                return original;   // 表里没有可用条目 → 别把玩家的这次钓获吃掉
            }
            ObjectArrayList<ItemStack> result = new ObjectArrayList<>();
            if (!table.replaceVanilla) {
                result.addAll(original);
            }
            for (FishingLoot.Catch c : rolled) {
                if (c.stack().isEmpty()) {
                    continue;
                }
                if (owner instanceof ServerPlayer sp && config != null && config.announceRare
                        && c.entry().rare()) {
                    sp.sendSystemMessage(Component.literal(
                            "\u00A77你钓上来了 " + rarityColor(c.entry().rarity) + "\u00A77品质的 "
                                    + c.stack().getHoverName().getString() + "\u00A77！"));
                }
                result.add(c.stack());
            }
            return result.isEmpty() ? original : result;
        } catch (Throwable t) {
            HubSuite.logger().debug("自定义渔获失败，沿用原版：{}", t.toString());
            return original;
        }
    }

    private static String rarityColor(String rarity) {
        return switch (rarity == null ? "" : rarity) {
            case "legendary" -> "\u00A7d";
            case "rare" -> "\u00A7b";
            case "uncommon" -> "\u00A7a";
            default -> "\u00A7f";
        };
    }
}
