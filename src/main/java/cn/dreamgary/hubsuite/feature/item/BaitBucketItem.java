package cn.dreamgary.hubsuite.feature.item;

import cn.dreamgary.hubsuite.feature.FishingBait;
import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * 渔桶：钓饵的收纳袋（木 16 / 铁 64 / 钻 1024 个）。
 *
 * <p><b>操作方式完全照抄原版收纳袋</b>（玩家不用学）：
 * <ul>
 *   <li>背包里把钓饵"点"到桶上（或拿着桶点钓饵）→ 装入：左键全装、右键装一个；</li>
 *   <li>空手右键点桶（或拿着桶右键空格子）→ 取出一组（最多一个堆叠 8 个）；</li>
 *   <li>一桶只装<b>一种</b>饵；普通/地狱/压缩/二重压缩都能进，按"个"计数；</li>
 *   <li>钓鱼时桶放副手即可生效（加速按桶里饵的档次算，钓上来从桶里扣 1 个，
 *       见 {@link FishingBait}）。</li>
 * </ul>
 *
 * <p>内容物存在原版的 {@code CUSTOM_DATA} 组件里（存档 / 背包同步都安全）；
 * 客户端看到的仍是 Polymer 换出来的"试炼钥匙 + 模型"，内容物通过
 * {@link #modifyClientTooltip} 追加一行 tooltip 展示。
 */
public class BaitBucketItem extends SimplePolymerItem {

    private static final String TAG_BAIT = "hubsuite_bait";
    private static final String TAG_COUNT = "hubsuite_bait_count";

    private final int capacity;

    public BaitBucketItem(Item.Properties properties, int capacity) {
        super(properties, Items.TRIAL_KEY, true);
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    /** 桶里装的是哪种饵；空桶 / 不是桶 → null。 */
    public static Item containedBait(ItemStack bucket) {
        if (!(bucket.getItem() instanceof BaitBucketItem)) {
            return null;
        }
        CustomData data = bucket.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (tag.getIntOr(TAG_COUNT, 0) <= 0) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(tag.getStringOr(TAG_BAIT, "minecraft:air")));
        return FishingBait.isBait(item) ? item : null;
    }

    /** 桶里现在有多少个。 */
    public static int containedCount(ItemStack bucket) {
        if (!(bucket.getItem() instanceof BaitBucketItem)) {
            return 0;
        }
        CustomData data = bucket.get(DataComponents.CUSTOM_DATA);
        return data == null || data.isEmpty() ? 0 : data.copyTag().getIntOr(TAG_COUNT, 0);
    }

    /**
     * 往桶里装（最多 {@code max} 个）。会直接扣减 {@code source}。
     *
     * @return 实际装进去的数量（0 = 满了 / 不是饵 / 桶里已是别的饵）
     */
    public static int insert(ItemStack bucket, ItemStack source, int max) {
        if (!(bucket.getItem() instanceof BaitBucketItem bucketItem)
                || source.isEmpty() || !FishingBait.isBait(source.getItem())) {
            return 0;
        }
        Item contained = containedBait(bucket);
        if (contained != null && contained != source.getItem()) {
            return 0;
        }
        int count = containedCount(bucket);
        int canTake = Math.min(Math.min(source.getCount(), max), bucketItem.capacity() - count);
        if (canTake <= 0) {
            return 0;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(source.getItem()).toString();
        int newCount = count + canTake;
        CustomData.update(DataComponents.CUSTOM_DATA, bucket, tag -> {
            tag.putString(TAG_BAIT, itemId);
            tag.putInt(TAG_COUNT, newCount);
        });
        source.shrink(canTake);
        return canTake;
    }

    /** 从桶里取出一组（最多一个堆叠）。空桶返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack extract(ItemStack bucket) {
        Item contained = containedBait(bucket);
        int count = containedCount(bucket);
        if (contained == null || count <= 0) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(count, contained.getDefaultMaxStackSize());
        int left = count - take;
        CustomData.update(DataComponents.CUSTOM_DATA, bucket, tag -> {
            if (left <= 0) {
                tag.remove(TAG_BAIT);
                tag.remove(TAG_COUNT);
            } else {
                tag.putInt(TAG_COUNT, left);
            }
        });
        return new ItemStack(contained, take);
    }

    /** 钓鱼消耗：从桶里扣 {@code n} 个（不产生物品）。返回实际扣掉的数量。 */
    public static int remove(ItemStack bucket, int n) {
        int count = containedCount(bucket);
        int take = Math.min(count, n);
        if (take <= 0) {
            return 0;
        }
        int left = count - take;
        CustomData.update(DataComponents.CUSTOM_DATA, bucket, tag -> {
            if (left <= 0) {
                tag.remove(TAG_BAIT);
                tag.remove(TAG_COUNT);
            } else {
                tag.putInt(TAG_COUNT, left);
            }
        });
        return take;
    }

    /** 桶在手上，点到别的格子上。 */
    @Override
    public boolean overrideStackedOnOther(ItemStack bucket, Slot slot, ClickAction action, Player player) {
        ItemStack inSlot = slot.getItem();
        if (!inSlot.isEmpty()) {
            // 桶点饵 → 装入（左键全装，右键装一个）
            if (!FishingBait.isBait(inSlot.getItem()) || !slot.mayPickup(player)) {
                return false;
            }
            int moved = insert(bucket, inSlot, action == ClickAction.PRIMARY ? Integer.MAX_VALUE : 1);
            if (moved > 0) {
                slot.setChanged();
                return true;
            }
            return false;
        }
        // 桶右键点空格子 → 取一组放进那格
        if (action == ClickAction.SECONDARY) {
            ItemStack taken = extract(bucket);
            if (!taken.isEmpty() && slot.mayPlace(taken)) {
                slot.safeInsert(taken);
                slot.setChanged();
                return true;
            }
        }
        return false;
    }

    /** 别的东西点到桶上（桶在格子里）。 */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack bucket, ItemStack carried, Slot slot, ClickAction action,
                                            Player player, SlotAccess carriedAccess) {
        if (!carried.isEmpty()) {
            // 饵点桶 → 装入
            if (!FishingBait.isBait(carried.getItem())) {
                return false;
            }
            int moved = insert(bucket, carried, action == ClickAction.PRIMARY ? Integer.MAX_VALUE : 1);
            if (moved > 0) {
                carriedAccess.set(carried.isEmpty() ? ItemStack.EMPTY : carried);
                slot.setChanged();
                return true;
            }
            return false;
        }
        // 空手右键点桶 → 取一组到手上
        if (action == ClickAction.SECONDARY) {
            ItemStack taken = extract(bucket);
            if (!taken.isEmpty()) {
                carriedAccess.set(taken);
                slot.setChanged();
                return true;
            }
        }
        return false;
    }

    /** 客户端 tooltip 里追加一行"内容物 数量/容量"。 */
    @Override
    public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
        Item contained = containedBait(stack);
        int count = containedCount(stack);
        if (contained != null && count > 0) {
            tooltip.add(Component.translatable(contained.getDescriptionId())
                    .append(Component.literal(" × " + count + " / " + capacity))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("空的 —— 把钓饵点到桶上装入，右键空格子取出")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
