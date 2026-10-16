package cn.dreamgary.hubsuite.rod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 装配台的方块实体类型（**两端都要注册**）。
 *
 * <p>实测教训：Fabric 的注册表同步（fabric-registry-sync-v0）会把服务端注册表里的自定义条目
 * 发给客户端，客户端不认识就直接断开：
 * <pre>Received a registry entry that is unknown to this client.（namespaces: hubsuite）</pre>
 * 所以方块、方块实体类型、物品、菜单类型这四样**两端必须一一对应**。
 *
 * <p>两端的方块类不同（服务端那份是 {@code BaseEntityBlock}，客户端那份只是普通方块用来渲染），
 * 但方块实体类型必须挂到各自那份方块实例上，所以这里用 {@link #register(Block)} 参数化。
 */
public final class AssemblyBlockEntities {

    public static BlockEntityType<AssemblyBlockEntity> TYPE;

    private AssemblyBlockEntities() {
    }

    /** 两端各调一次（传各自那份方块实例）。 */
    public static BlockEntityType<AssemblyBlockEntity> register(Block block) {
        if (TYPE == null) {
            TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AssemblyTableIds.ID,
                    net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder
                            .create(AssemblyBlockEntity::new, block).build());
        }
        return TYPE;
    }
}
