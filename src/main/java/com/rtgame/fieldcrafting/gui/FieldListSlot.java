package com.rtgame.fieldcrafting.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 物品清单里的一个格子。取出走原版槽位逻辑；断电或物品不能取出时禁止取出。
 * 物品不能直接放进格子：格子只是真实容器的展示，光标存入由 {@link FieldStationContainer#slotClick} 处理，
 * 否则 NEI 合成链恢复背包布局时会把物品点进格子，存进邻近容器。
 */
public class FieldListSlot extends Slot {

    private final FieldListView view;
    private final int cell;

    public FieldListSlot(FieldListView view, int cell) {
        super(view, cell, FieldListView.HIDDEN_X, 0);
        this.view = view;
        this.cell = cell;
    }

    @Override
    public boolean isItemValid(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeStack(EntityPlayer player) {
        return view.isPowered() && !view.isLocked(cell);
    }
}
