package cn.dreamgary.hubsuite.rod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.flag.FeatureFlags;

/**
 * 装配台菜单（见 docs/钓竿系统方案.md 第 3 节）。
 *
 * <p>4 个语义格：**钓竿位 + 杆位 / 线位 / 钩位**。规则照方案：
 * <ul>
 *   <li><b>放上去就消失</b>：材料进格 → 立刻消耗 1 个并生效 → 格子回到空；</li>
 *   <li><b>放错部位</b>直接放不进去（槽位自己拒绝 → 材料留在光标上，原版行为）；</li>
 *   <li><b>杆位</b>：同种材料 + 没满耐久 → 修满；不同种 → 换底（基数换掉、耐久回满）；</li>
 *   <li>线 / 钩：只换属性，**不动耐久**；</li>
 *   <li>关界面 / 破坏方块时把竿还回背包（这里实现"关界面还竿"）。</li>
 * </ul>
 *
 * <p>放在 {@code common/} 里：服务端造它、客户端也造它（客户端要按 menuType 建出同一个类）。
 */
public class AssemblyMenu extends AbstractContainerMenu {

    public static final int SLOT_ROD = 0;
    public static final int SLOT_ROD_MAT = 1;
    public static final int SLOT_LINE = 2;
    public static final int SLOT_HOOK = 3;

    /** 界面里 4 个语义格的位置（相对 GUI 左上角）。 */
    public static final int[][] SLOT_POSITIONS = {
            {26, 20},   // 钓竿位（左中）
            {98, 20},   // 杆位
            {98, 40},   // 线位
            {98, 60},   // 钩位
    };

    public static final Identifier ID = Identifier.fromNamespaceAndPath("hubsuite", "assembly");
    public static final ResourceKey<MenuType<?>> KEY = ResourceKey.create(Registries.MENU, ID);

    /** 两端的初始化里各调一次（注册表不参与同步，客户端必须自己注册同 id 的菜单类型）。 */
    public static MenuType<AssemblyMenu> registerType() {
        return net.minecraft.core.Registry.register(BuiltInRegistries.MENU, ID,
                new MenuType<>(AssemblyMenu::new, FeatureFlags.DEFAULT_FLAGS));
    }

    private final SimpleContainer parts = new SimpleContainer(4);
    private final Player owner;

    /** 客户端按 menuType 建菜单时走这个构造（容器 id + 玩家背包）。 */
    public AssemblyMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    private AssemblyMenu(int containerId, Inventory playerInventory, Player owner) {
        super(menuType(), containerId);
        this.owner = owner;

        addSlot(new Slot(parts, SLOT_ROD, SLOT_POSITIONS[SLOT_ROD][0], SLOT_POSITIONS[SLOT_ROD][1]) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // 竿位：放竿，或者放"杆材料"（用来修补 / 换底）
                if (isRod(stack)) {
                    return !hasItem();
                }
                return RodMaterials.match(RodMaterials.Part.ROD, stack) != null && isRod(getRod());
            }
        });
        addSlot(materialSlot(SLOT_ROD_MAT, RodMaterials.Part.ROD));
        addSlot(materialSlot(SLOT_LINE, RodMaterials.Part.LINE));
        addSlot(materialSlot(SLOT_HOOK, RodMaterials.Part.HOOK));

        // 玩家背包（3×9 + 快捷栏），位置沿用原版箱子界面的排布
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    private Slot materialSlot(int index, RodMaterials.Part part) {
        return new Slot(parts, index, SLOT_POSITIONS[index][0], SLOT_POSITIONS[index][1]) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return RodMaterials.match(part, stack) != null;
            }
        };
    }

    private static MenuType<AssemblyMenu> menuType() {
        @SuppressWarnings("unchecked")
        MenuType<AssemblyMenu> type = (MenuType<AssemblyMenu>) BuiltInRegistries.MENU.getValue(ID);
        return type;
    }

    // ------------------------------------------------------------------
    // 规则
    // ------------------------------------------------------------------

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (owner == null || owner.level().isClientSide()) {
            return;   // 只在服务端改数据
        }
        applyMaterials();
    }

    /** 把三个材料格里的东西"吃掉并生效"。 */
    private void applyMaterials() {
        ItemStack rodStack = parts.getItem(SLOT_ROD);
        if (!isRod(rodStack)) {
            return;
        }
        RodData data = RodData.of(rodStack);
        if (data == null) {
            data = RodData.defaults();
        }
        boolean changed = false;

        data = apply(data, SLOT_ROD_MAT, RodMaterials.Part.ROD);
        data = apply(data, SLOT_LINE, RodMaterials.Part.LINE);
        data = apply(data, SLOT_HOOK, RodMaterials.Part.HOOK);

        if (!data.equals(RodData.of(rodStack))) {
            data.writeTo(rodStack);
        }
    }

    /** 吃材料格里的 1 个材料并套用规则；规则说"没变化"就不消耗。 */
    private RodData apply(RodData data, int slot, RodMaterials.Part part) {
        ItemStack stack = parts.getItem(slot);
        if (stack.isEmpty()) {
            return data;
        }
        String material = RodMaterials.match(part, stack);
        if (material == null) {
            return data;
        }
        RodData next = switch (part) {
            case ROD -> AssemblyRules.applyRodMaterial(data, material);
            case LINE -> AssemblyRules.applyLine(data, material);
            case HOOK -> AssemblyRules.applyHook(data, material);
        };
        if (next.equals(data)) {
            return data;   // 规则判定"不变"（比如耐久已满还塞同种材料）→ 材料留着，不动
        }
        stack.shrink(1);
        if (stack.isEmpty()) {
            parts.setItem(slot, ItemStack.EMPTY);
        }
        return next;
    }

    private ItemStack getRod() {
        return parts.getItem(SLOT_ROD);
    }

    private static boolean isRod(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.FISHING_ROD);
    }

    // ------------------------------------------------------------------
    // 关界面：把东西还给玩家（方案第 3.2 节）
    // ------------------------------------------------------------------

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player.level().isClientSide()) {
            return;
        }
        for (int i = 0; i < parts.getContainerSize(); i++) {
            ItemStack stack = parts.removeItemNoUpdate(i);
            if (!stack.isEmpty() && !player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < 4) {
            if (!moveItemStackTo(stack, 4, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, SLOT_ROD, SLOT_ROD + 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
