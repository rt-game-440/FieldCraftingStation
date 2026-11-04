package com.rtgame.fieldcrafting.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;

import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.utils.item.IItemHandler;
import com.cleanroommc.modularui.widgets.slot.InventoryCraftingWrapper;
import com.cleanroommc.modularui.widgets.slot.ModularCraftingSlot;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * 场域合成站的结果槽。取走成品时：
 * <ol>
 * <li>像普通工作台一样从网格里消耗材料；</li>
 * <li>剩余物（空桶等）先进玩家背包，放不下再放回邻近容器。</li>
 * </ol>
 * 被用光的格子不会补料，所以 Shift+点击只消耗网格里摆着的材料。邻近容器只在服务端、供电正常时才会接收剩余物。
 */
public class FieldCraftingSlot extends ModularCraftingSlot {

    private final TileFieldStation tile;
    private InventoryCraftingWrapper matrix;

    public FieldCraftingSlot(IItemHandler itemHandler, int index, TileFieldStation tile) {
        super(itemHandler, index);
        this.tile = tile;
    }

    @Override
    public void setCraftMatrix(InventoryCraftingWrapper craftMatrix) {
        super.setCraftMatrix(craftMatrix);
        this.matrix = craftMatrix;
    }

    /** 和原版结果槽一样，成品总是整组取走，否则没取走的部分会被下一次配方计算覆盖而丢失。 */
    @Override
    public ItemStack decrStackSize(int amount) {
        ItemStack stack = getStack();
        return super.decrStackSize(stack == null ? amount : stack.stackSize);
    }

    @Override
    public void onPickupFromSlot(EntityPlayer player, ItemStack stack) {
        FMLCommonHandler.instance()
            .firePlayerCraftingEvent(player, stack, matrix);
        onCrafting(stack);

        boolean server = !player.worldObj.isRemote;
        FieldInventory field = server ? tile.createFieldInventory(player) : null;

        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            ItemStack inSlot = matrix.getStackInSlot(i);
            if (Platform.isStackEmpty(inSlot)) continue;
            matrix.decrStackSize(i, 1, false);

            if (!inSlot.getItem()
                .hasContainerItem(inSlot)) continue;
            ItemStack container = inSlot.getItem()
                .getContainerItem(inSlot);
            if (container == null) continue;
            if (container.isItemStackDamageable() && container.getItemDamage() > container.getMaxDamage()) {
                MinecraftForge.EVENT_BUS.post(new PlayerDestroyItemEvent(player, container));
                continue;
            }
            boolean leavesGrid = inSlot.getItem()
                .doesContainerItemLeaveCraftingGrid(inSlot);
            if (!leavesGrid && Platform.isStackEmpty(matrix.getStackInSlot(i))) {
                matrix.setSlot(i, container, false); // 如锤子之类的工具留在网格里
            } else if (server) {
                ItemStack rest = field.giveToPlayerOrField(container);
                if (rest != null) player.dropPlayerItemWithRandomChoice(rest, false);
            } else if (!player.inventory.addItemStackToInventory(container)) {
                player.dropPlayerItemWithRandomChoice(container, false);
            }
        }

        matrix.notifyContainer();
    }
}
