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
 *   <li><b>材料进格只是暂存</b>：不消耗，输入一变就重算**成品格**（面板中间也是活预览）；</li>
 *   <li><b>从成品格取走才算装配</b>：消耗竿 + 真正起作用的材料各 1 个（原版铁砧的手感）；</li>
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
    /** 成品格（派生）：由输入实时算出，取走时消耗竿 + 真正起作用的材料各 1 个。 */
    public static final int SLOT_RESULT = 4;

    /** 界面布局（画框与真实槽位共用这一份，见 {@code UiLayouts}）。 */
    public static final cn.dreamgary.hubsuite.ui.UiLayout LAYOUT =
            cn.dreamgary.hubsuite.ui.UiLayouts.ASSEMBLY;

    public static final Identifier ID = Identifier.fromNamespaceAndPath("hubsuite", "assembly");
    public static final ResourceKey<MenuType<?>> KEY = ResourceKey.create(Registries.MENU, ID);

    /** 两端的初始化里各调一次（注册表不参与同步，客户端必须自己注册同 id 的菜单类型）。 */
    public static MenuType<AssemblyMenu> registerType() {
        return net.minecraft.core.Registry.register(BuiltInRegistries.MENU, ID,
                new MenuType<>(AssemblyMenu::new, FeatureFlags.DEFAULT_FLAGS));
    }

    private final Container parts;
    private final Player player;

    /** 客户端按 menuType 建菜单时走这个构造：格子内容由服务端同步过来。 */
    public AssemblyMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(AssemblyBlockEntity.SIZE));
    }

    /** 方块打开的走这个：格子直接架在**方块实体**上（关界面前东西留在世界里）。 */
    public AssemblyMenu(int containerId, Inventory playerInventory, Container parts) {
        super(menuType(), containerId);
        this.parts = parts;
        this.player = playerInventory.player;

        addSlot(new Slot(parts, SLOT_ROD, LAYOUT.frame(SLOT_ROD).x(), LAYOUT.frame(SLOT_ROD).y()) {
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
        addSlot(new Slot(parts, SLOT_RESULT, LAYOUT.frame(SLOT_RESULT).x(), LAYOUT.frame(SLOT_RESULT).y()) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;   // 成品格只出不进
            }

            @Override
            public boolean mayPickup(Player p) {
                return !getItem().isEmpty();
            }

            @Override
            public void onTake(Player p, ItemStack taken) {
                if (!p.level().isClientSide()) {
                    // 竿变成了产物 → 竿位清空；只有**真正参与了变化**的材料各扣 1 个
                    parts.setItem(SLOT_ROD, ItemStack.EMPTY);
                    for (int i = 0; i < contributing.length; i++) {
                        if (!contributing[i]) {
                            continue;
                        }
                        ItemStack material = parts.getItem(i + 1);
                        if (!material.isEmpty()) {
                            material.shrink(1);
                            if (material.isEmpty()) {
                                parts.setItem(i + 1, ItemStack.EMPTY);
                            }
                        }
                    }
                }
                super.onTake(p, taken);
                updateResult();
            }
        });

        // 玩家背包（3×9 + 快捷栏），位置沿用原版箱子界面的排布
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 104 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 162));
        }

        // 打开时就先算一次成品：预填的材料（/rod demo）和"关界面留在方块里"的情况
        // 都不会经过 clicked，不补这一步成品格就是空的（自己实测抓到）。
        updateResult();
    }

    private Slot materialSlot(int index, RodMaterials.Part part) {
        return new Slot(parts, index, LAYOUT.frame(index).x(), LAYOUT.frame(index).y()) {
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

    /**
     * 玩家点了界面上的「装配」按钮（{@code UiActionPayload} 过来）。
     *
     * <p><b>为什么不再"放上去就消耗"</b>（用户实测定的）：材料进格只是**暂存**，
     * 面板里是"如果应用"的活预览；点了装配才消耗生效。不放心的材料随时能取回。
     */
    public void applyFromButton() {
        updateResult();
    }

    /** 每 3 个材料槽是否"真的改变了竿"（取走成品时只扣这些）。 */
    private final boolean[] contributing = new boolean[3];

    /**
     * 重算成品格。输入一变就调（26.1 的 slotsChanged 不会自己触发，
     * 所以挂在 clicked 上 —— 和之前"换不了配件"同一个坑）。
     */
    public void updateResult() {
        if (player == null || player.level().isClientSide()) {
            return;   // 客户端等同步，别本地瞎算
        }
        java.util.Arrays.fill(contributing, false);
        ItemStack rod = parts.getItem(SLOT_ROD);
        if (!isRod(rod)) {
            setResult(ItemStack.EMPTY);
            return;
        }
        RodData data = RodData.of(rod);
        if (data == null) {
            data = RodData.defaults();
        }
        RodData next = data;
        RodMaterials.Part[] partsBySlot = {RodMaterials.Part.ROD, RodMaterials.Part.LINE, RodMaterials.Part.HOOK};
        for (int i = 0; i < partsBySlot.length; i++) {
            String material = RodMaterials.match(partsBySlot[i], parts.getItem(i + 1));
            if (material == null) {
                continue;
            }
            RodData applied = switch (partsBySlot[i]) {
                case ROD -> AssemblyRules.applyRodMaterial(next, material);
                case LINE -> AssemblyRules.applyLine(next, material);
                case HOOK -> AssemblyRules.applyHook(next, material);
            };
            if (!applied.equals(next)) {
                contributing[i] = true;
                next = applied;
            }
        }
        if (next.equals(data)) {
            setResult(ItemStack.EMPTY);
            return;
        }
        ItemStack preview = rod.copy();
        preview.setCount(1);
        next.writeTo(preview);
        next.applyVanillaBar(preview);
        setResult(preview);
    }

    private void setResult(ItemStack stack) {
        if (ItemStack.matches(parts.getItem(SLOT_RESULT), stack)) {
            return;   // 没变就别动，省一次同步
        }
        parts.setItem(SLOT_RESULT, stack);
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

    /**
     * 原版所有点击（放 / 取 / shift 快移）都走这里；super 之后槽位已落定，
     * 再重算成品格 —— 26.1 里 slotsChanged 不会被容器自动触发（踩过的坑）。
     */
    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ContainerInput input,
                        Player player) {
        super.clicked(slotId, button, input, player);
        updateResult();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player.level().isClientSide()) {
            return;
        }
        // 只交还输入格；成品格是派生的，直接丢掉
        for (int i = 0; i < SLOT_RESULT; i++) {
            ItemStack stack = parts.removeItemNoUpdate(i);
            if (!stack.isEmpty() && !player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        parts.setItem(SLOT_RESULT, ItemStack.EMPTY);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index == SLOT_RESULT) {
            // shift 快移成品：等价于取走（消耗输入）
            slot.onTake(player, stack);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            slot.setByPlayer(ItemStack.EMPTY);
            updateResult();
            return copy;
        }
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
