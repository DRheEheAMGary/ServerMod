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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渔桶：钓饵的收纳袋（木 64 / 铁 256 / 钻 1024 个）。
 *
 * <p><b>操作方式完全照抄原版收纳袋</b>（玩家不用学）：
 * <ul>
 *   <li>背包里把钓饵"点"到桶上（或拿着桶点钓饵）→ 装入：左键全装、右键装一个；</li>
 *   <li>空手右键点桶（或拿着桶右键空格子）→ 取出一组（最多一个堆叠 16 个）；</li>
 *   <li><b>可以混装</b>：6 种饵（普通/地狱 × 普通/压缩/二重压缩）都能进，
 *       容量按**总个数**算；</li>
 *   <li>钓鱼时桶放副手即可生效：按档次 <b>二重压缩 → 压缩 → 普通</b> 消耗
 *       （见 {@link FishingBait#select}）。</li>
 * </ul>
 *
 * <p>内容物存在原版的 {@code CUSTOM_DATA} 组件里（存档 / 背包同步都安全）：
 * {@code hubsuite_baits} = { 物品id: 个数 }（旧版单一种的字段读的时候会自动迁移）。
 * 客户端看到的仍是 Polymer 换出来的"试炼钥匙 + 模型"，内容物通过
 * {@link #modifyClientTooltip} 展示；模型按**桶里最高档**的那种饵切换。
 */
public class BaitBucketItem extends SimplePolymerItem {

    /** 新格式：{@code hubsuite_baits} = { 物品id: 个数 }（可以混装）。 */
    private static final String TAG_BAITS = "hubsuite_baits";
    /** 旧格式（一桶一种）：只读，读到时迁移成新格式。 */
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

    /**
     * 桶里的内容物：{@code 物品 → 个数}，按档次从高到低排（二重压缩 → 压缩 → 普通）。
     * 空桶 / 不是桶 → 空 Map。
     */
    public static Map<Item, Integer> contents(ItemStack bucket) {
        Map<Item, Integer> raw = new LinkedHashMap<>();
        if (bucket.getItem() instanceof BaitBucketItem) {
            CustomData data = bucket.get(DataComponents.CUSTOM_DATA);
            if (data != null && !data.isEmpty()) {
                CompoundTag tag = data.copyTag();
                CompoundTag baits = tag.getCompoundOrEmpty(TAG_BAITS);
                for (String key : baits.keySet()) {
                    int count = baits.getIntOr(key, 0);
                    if (count <= 0) {
                        continue;
                    }
                    Item item = itemOf(key);
                    if (item != null && FishingBait.isBait(item)) {
                        raw.merge(item, count, Integer::sum);
                    }
                }
                // 旧格式（一桶一种）迁移
                if (raw.isEmpty()) {
                    int old = tag.getIntOr(TAG_COUNT, 0);
                    Item item = itemOf(tag.getStringOr(TAG_BAIT, ""));
                    if (old > 0 && item != null && FishingBait.isBait(item)) {
                        raw.put(item, old);
                    }
                }
            }
        }
        // 按档次降序（同档按物品 id 稳定排序，tooltip 不会跳来跳去）
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(raw.entrySet());
        list.sort((a, b) -> {
            int byTier = Integer.compare(FishingBait.tierOf(b.getKey()), FishingBait.tierOf(a.getKey()));
            return byTier != 0 ? byTier : String.valueOf(BuiltInRegistries.ITEM.getKey(a.getKey()))
                    .compareTo(String.valueOf(BuiltInRegistries.ITEM.getKey(b.getKey())));
        });
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : list) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 桶里一共有多少个（所有饵加起来）。 */
    public static int containedCount(ItemStack bucket) {
        int total = 0;
        for (int n : contents(bucket).values()) {
            total += n;
        }
        return total;
    }

    /** 桶里**最高档**的那种饵（模型 / 取出 / 默认消耗都用它）；空桶 → null。 */
    public static Item containedBait(ItemStack bucket) {
        var it = contents(bucket).entrySet().iterator();
        return it.hasNext() ? it.next().getKey() : null;
    }

    /** 桶里某种饵有多少个。 */
    public static int countOf(ItemStack bucket, Item bait) {
        return contents(bucket).getOrDefault(bait, 0);
    }

    /**
     * 往桶里装（最多 {@code max} 个）。会直接扣减 {@code source}。
     *
     * <p>任何钓饵都能进（可以混装），只要总数没超容量。
     *
     * @return 实际装进去的数量（0 = 满了 / 不是饵）
     */
    public static int insert(ItemStack bucket, ItemStack source, int max) {
        if (!(bucket.getItem() instanceof BaitBucketItem bucketItem)
                || source.isEmpty() || !FishingBait.isBait(source.getItem())) {
            return 0;
        }
        int canTake = Math.min(Math.min(source.getCount(), max),
                bucketItem.capacity() - containedCount(bucket));
        if (canTake <= 0) {
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(source.getItem()).toString();
        CustomData.update(DataComponents.CUSTOM_DATA, bucket, tag -> {
            CompoundTag baits = tag.getCompoundOrEmpty(TAG_BAITS);
            baits.putInt(id, baits.getIntOr(id, 0) + canTake);
            tag.put(TAG_BAITS, baits);
            tag.remove(TAG_BAIT);
            tag.remove(TAG_COUNT);
        });
        source.shrink(canTake);
        return canTake;
    }

    /**
     * 从桶里取出一组**最高档**的饵（最多一个堆叠）。
     * 空桶返回 {@link ItemStack#EMPTY}。
     */
    public static ItemStack extract(ItemStack bucket) {
        Item contained = containedBait(bucket);
        if (contained == null) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(countOf(bucket, contained), contained.getDefaultMaxStackSize());
        if (take <= 0) {
            return ItemStack.EMPTY;
        }
        remove(bucket, contained, take);
        return new ItemStack(contained, take);
    }

    /** 从桶里扣掉某种饵 {@code n} 个（不产生物品）。返回实际扣掉的数量。 */
    public static int remove(ItemStack bucket, Item bait, int n) {
        if (bait == null || n <= 0 || !FishingBait.isBait(bait)) {
            return 0;
        }
        int have = countOf(bucket, bait);
        int take = Math.min(have, n);
        if (take <= 0) {
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(bait).toString();
        CustomData.update(DataComponents.CUSTOM_DATA, bucket, tag -> {
            CompoundTag baits = tag.getCompoundOrEmpty(TAG_BAITS);
            int left = baits.getIntOr(id, 0) - take;
            if (left > 0) {
                baits.putInt(id, left);
            } else {
                baits.remove(id);
            }
            if (baits.isEmpty()) {
                tag.remove(TAG_BAITS);
            } else {
                tag.put(TAG_BAITS, baits);
            }
            tag.remove(TAG_BAIT);
            tag.remove(TAG_COUNT);
        });
        return take;
    }

    /** 从桶里扣掉**最高档**的饵 {@code n} 个。返回实际扣掉的数量。 */
    public static int remove(ItemStack bucket, int n) {
        return remove(bucket, containedBait(bucket), n);
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

    /**
     * 客户端模型只看"空 / 有饵"两种：空桶用 {@code hubsuite:<桶>}，
     * **只要装了饵**（不管哪种、装了几种）都用 {@code hubsuite:<桶>_bait}。
     *
     * <p>用户定的：装了饵的桶就一张贴图，不按饵的种类分。资源包每只桶只要
     * 2 个模型（空 + 有饵），见 {@code docs/自定义物品.md}。
     */
    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context,
                                          net.minecraft.core.HolderLookup.Provider registries) {
        Identifier base = stack.get(DataComponents.ITEM_MODEL);
        if (base == null || containedBait(stack) == null) {
            return base;
        }
        return Identifier.fromNamespaceAndPath(base.getNamespace(), base.getPath() + "_bait");
    }

    /** 客户端 tooltip：总量 + 内容物明细（最多列 3 种）。 */
    @Override
    public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
        Map<Item, Integer> contents = contents(stack);
        if (contents.isEmpty()) {
            tooltip.add(Component.literal("空的 —— 把钓饵点到桶上装入，右键空格子取出")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.literal("内容物 " + containedCount(stack) + " / " + capacity)
                .withStyle(ChatFormatting.GRAY));
        int lines = 0;
        for (Map.Entry<Item, Integer> e : contents.entrySet()) {
            if (lines++ >= 3) {
                tooltip.add(Component.literal("  …还有 " + (contents.size() - 3) + " 种")
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            tooltip.add(Component.literal(" · ").append(Component.translatable(e.getKey().getDescriptionId()))
                    .append(Component.literal(" × " + e.getValue()))
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static Item itemOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }
}
