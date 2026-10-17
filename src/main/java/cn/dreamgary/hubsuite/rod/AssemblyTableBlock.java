package cn.dreamgary.hubsuite.rod;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 装配台方块（服务端）。
 *
 * <p>0.6 收尾起 <b>Polymer 已下线、客户端强制安装</b>：所有玩家都看到真方块，
 * 注册表同步会把两端 id 对齐；没装客户端的玩家会在注册表同步阶段被 Fabric 直接断开。
 *
 * <p>客户端侧注册同 id、同属性的**普通方块**与物品（见 {@code ClientMenuTypes}）。
 */
public class AssemblyTableBlock extends BaseEntityBlock {

    public static AssemblyTableBlock BLOCK;
    public static Item ITEM;

    public AssemblyTableBlock(Properties properties) {
        super(properties);
    }

    /** 服务端注册（客户端那份在 ClientMenuTypes）。 */
    public static void register() {
        if (BLOCK != null) {
            return;
        }
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, AssemblyTableIds.ID,
                new AssemblyTableBlock(AssemblyTableIds.blockProperties()));
        ITEM = Registry.register(BuiltInRegistries.ITEM, AssemblyTableIds.ID,
                new BlockItem(BLOCK, AssemblyTableIds.itemProperties()));
        // 方块实体类型两端都要注册（Fabric 的注册表同步会因为客户端缺条目直接断开）
        AssemblyBlockEntities.register(BLOCK);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(AssemblyTableBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AssemblyBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        // 用户定的：钓竿系统只限空岛服（别的服打不开）
        if (!RodScope.allowed(level)) {
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("\u00A77钓竿装配台只能在空岛服使用。"));
            }
            return InteractionResult.CONSUME;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof AssemblyBlockEntity table) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new AssemblyMenu(containerId, inventory, table),
                    Component.literal("钓竿装配台")));
        }
        return InteractionResult.CONSUME;
    }

    /** 方块被拆：台子里的东西掉出来（方案第 3.2 节）。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AssemblyBlockEntity table) {
            for (int i = 0; i < cn.dreamgary.hubsuite.rod.AssemblyBlockEntity.INPUT_SIZE; i++) {
                ItemStack stack = table.getItem(i);
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
                }
            }
            table.clearContent();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
