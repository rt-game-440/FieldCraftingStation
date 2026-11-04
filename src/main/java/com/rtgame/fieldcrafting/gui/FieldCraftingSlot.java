package com.rtgame.fieldcrafting.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;

import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * 场域合成站的结果槽，是原版的 {@link SlotCrafting}。和原版的区别只有一点：
 * 剩余物（空桶等）先进玩家背包，放不下再放回邻近容器（只在服务端、供电正常时）。
 * 被用光的格子不会补料，所以 Shift+点击只消耗网格里摆着的材料。
 */
public class FieldCraftingSlot extends SlotCrafting {

    private final TileFieldStation tile;
    private final FieldCraftMatrix matrix;

    public FieldCraftingSlot(EntityPlayer player, TileFieldStation tile, FieldCraftMatrix matrix, IInventory result,
        int x, int y) {
        super(player, matrix, result, 0, x, y);
        this.tile = tile;
        this.matrix = matrix;
    }

    /** 和点击一样，Q 键也总是取走整组成品，否则没取走的部分会被下一次配方计算覆盖而丢失。 */
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
            if (Stacks.isEmpty(inSlot)) continue;
            matrix.decrQuietly(i, 1);

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
            if (!leavesGrid && Stacks.isEmpty(matrix.getStackInSlot(i))) {
                matrix.setQuietly(i, container); // 如锤子之类的工具留在网格里
            } else if (server) {
                ItemStack rest = field.giveToPlayerOrField(container);
                if (rest != null) player.dropPlayerItemWithRandomChoice(rest, false);
            } else if (!player.inventory.addItemStackToInventory(container)) {
                player.dropPlayerItemWithRandomChoice(container, false);
            }
        }

        matrix.notifyChanged();
    }
}
