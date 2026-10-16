package cn.dreamgary.hubsuite.rod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 装配台的方块实体：4 个语义格的**世界侧**存放处。
 *
 * <p>为什么要有它：装配台是方块，界面关掉之后东西得有个地方待着（还留在台里 / 被破坏时掉出来），
 * 不能只挂在某个玩家的菜单对象上。规则仍是"关界面还给你"（方案第 3.2 节），
 * 所以正常情况下它一直是空的；它兜的是"人没关界面就掉线 / 方块被拆"这类情况。
 */
public class AssemblyBlockEntity extends BlockEntity implements Container {

    /** 0-3 = 输入（竿 / 杆 / 线 / 钩），4 = 成品格（**派生**，不落盘也不掉落）。 */
    public static final int SIZE = 5;

    /** 输入格数量（成品格之前）。 */
    public static final int INPUT_SIZE = 4;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public AssemblyBlockEntity(BlockPos pos, BlockState state) {
        super(AssemblyBlockEntities.TYPE, pos, state);
    }

    // ------------------------------------------------------------------
    // Container
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack taken = ContainerHelper.removeItem(items, slot, amount);
        if (!taken.isEmpty()) {
            setChanged();
        }
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    // 26.1 起方块实体的存取走 ValueOutput / ValueInput（不再是 CompoundTag + RegistryAccess）
    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
    }
}
