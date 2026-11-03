package com.rtgame.fieldcrafting.station;

import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.item.ItemStackHandler;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.rtgame.fieldcrafting.field.FieldContainer;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.FieldScanner;
import com.rtgame.fieldcrafting.gui.FieldStationGui;

import gregtech.api.interfaces.tileentity.IEnergyConnected;

/**
 * 场域合成站的方块实体：作为 GT 能量接收方持续耗电，并持有 3×3 合成网格。
 * 电量不足（断电）时降级为普通工作台，不识别邻近容器。
 */
public class TileFieldStation extends TileEntity implements IEnergyConnected, IGuiHolder<PosGuiData> {

    /** 3×3 合成网格的格子数。 */
    public static final int GRID_SIZE = 9;

    /** 槽位 0..8 是合成网格，槽位 9 是结果槽（只用于界面，不存档）。 */
    private final ItemStackHandler grid = new ItemStackHandler(GRID_SIZE + 1) {

        @Override
        protected void onContentsChanged(int slot) {
            markDirty();
        }
    };

    private long stored;
    private boolean powered;
    private byte colorization = -1;

    public Tier getTier() {
        Block block = getBlockType();
        return block instanceof BlockFieldStation station ? station.tier : Tier.LV;
    }

    public ItemStackHandler getGrid() {
        return grid;
    }

    /** 是否供电正常（服务端实时值，客户端通过界面同步）。 */
    public boolean isPowered() {
        return powered;
    }

    public long getStoredEnergy() {
        return stored;
    }

    /**
     * 为玩家创建场域库存。供电正常时包含邻近容器；断电时只剩玩家背包，
     * 这样合成、填充都会自动降级为普通工作台的行为。
     */
    public FieldInventory createFieldInventory(EntityPlayer player) {
        FieldContainer backpack = FieldScanner.backpack(player);
        if (!powered || worldObj == null) {
            return new FieldInventory(backpack, Collections.emptyList());
        }
        return new FieldInventory(backpack, FieldScanner.scan(worldObj, xCoord, yCoord, zCoord, getTier().radius));
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;
        long usage = getTier().usage;
        if (stored >= usage) {
            stored -= usage;
            powered = true;
        } else {
            powered = false;
        }
    }

    // ---------------------------------------------------------------- GT 能量

    @Override
    public long injectEnergyUnits(ForgeDirection side, long voltage, long amperage) {
        if (side == ForgeDirection.UNKNOWN || voltage <= 0 || amperage <= 0) return 0;
        Tier tier = getTier();
        if (voltage > tier.voltage) return 0; // 超压不接收
        long room = (tier.capacity() - stored) / voltage;
        long accepted = Math.min(amperage, room);
        if (accepted <= 0) return 0;
        stored += accepted * voltage;
        return accepted;
    }

    @Override
    public boolean inputEnergyFrom(ForgeDirection side) {
        return side != ForgeDirection.UNKNOWN; // 六个面都接收
    }

    @Override
    public boolean outputsEnergyTo(ForgeDirection side) {
        return false;
    }

    @Override
    public byte getColorization() {
        return colorization;
    }

    @Override
    public byte setColorization(byte color) {
        colorization = (byte) (color < 0 || color > 15 ? -1 : color);
        return colorization;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setLong("Stored", stored);
        tag.setBoolean("Powered", powered);
        tag.setByte("Color", colorization);
        tag.setTag("Grid", grid.serializeNBT());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        stored = tag.getLong("Stored");
        powered = tag.getBoolean("Powered");
        colorization = tag.hasKey("Color") ? tag.getByte("Color") : -1;
        if (tag.hasKey("Grid")) grid.deserializeNBT(tag.getCompoundTag("Grid"));
        grid.setStackInSlot(GRID_SIZE, null); // 结果槽不保留
    }

    /** 方块被破坏时掉落网格里的物品。 */
    public void dropContents() {
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = grid.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) {
                BlockFieldStation.dropStack(worldObj, xCoord, yCoord, zCoord, stack);
                grid.setStackInSlot(i, null);
            }
        }
    }

    // ---------------------------------------------------------------- 界面

    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager syncManager, UISettings settings) {
        return FieldStationGui.build(this, data, syncManager, settings);
    }
}
