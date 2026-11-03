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
 * <li>剩余物（空桶等）先进玩家背包，放不下再放回邻近容器；</li>
 * <li>被用光的网格格子，按玩家背包优先、邻近容器其次的顺序补充同种材料。</li>
 * </ol>
 * 邻近容器只在服务端、供电正常时才会参与。
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

    @Override
    public void onPickupFromSlot(EntityPlayer player, ItemStack stack) {
        FMLCommonHandler.instance()
            .firePlayerCraftingEvent(player, stack, matrix);
        onCrafting(stack);

        boolean server = !player.worldObj.isRemote;
        FieldInventory field = server ? tile.createFieldInventory(player) : null;
        int size = TileFieldStation.GRID_SIZE;
        ItemStack[] used = new ItemStack[size];

        for (int i = 0; i < size; i++) {
            ItemStack inSlot = matrix.getStackInSlot(i);
            if (Platform.isStackEmpty(inSlot)) continue;
            used[i] = inSlot.copy();
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

        if (server) {
            for (int i = 0; i < size; i++) {
                if (used[i] == null || !Platform.isStackEmpty(matrix.getStackInSlot(i))) continue;
                ItemStack refill = field.consumeMaterial(used[i], 1);
                if (refill != null) matrix.setSlot(i, refill, false);
            }
        }
        matrix.notifyContainer();
        if (server && matrix.getContainer() instanceof FieldStationContainer container) {
            container.forceSyncGrid();
        }
    }
}
