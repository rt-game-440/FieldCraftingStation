package com.rtgame.fieldcrafting.field;

import net.minecraft.inventory.ISidedInventory;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.common.tileentities.automation.MTEChestBuffer;

/** GT 相关的容器判定：只有总线和箱式缓存器算邻近容器，机器自己的处理槽位不算。 */
final class GTCompat {

    private GTCompat() {}

    static boolean isStorage(IMetaTileEntity meta) {
        return meta instanceof MTEHatchInputBus || meta instanceof MTEHatchOutputBus || meta instanceof MTEChestBuffer;
    }

    static SlotRules rules(IGregTechTileEntity tile, IMetaTileEntity meta) {
        // 电路槽等非物品槽位由 isValidSlot 排除；存取权限沿用 GT 自己按面判定的规则
        return new FieldScanner.SidedRules((ISidedInventory) tile, meta::isValidSlot);
    }
}
