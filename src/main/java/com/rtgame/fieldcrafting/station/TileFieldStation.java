package com.rtgame.fieldcrafting.station;

import java.util.Arrays;
import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;

import com.rtgame.fieldcrafting.field.FieldContainer;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.FieldScanner;

import gregtech.api.interfaces.tileentity.IEnergyConnected;

/**
 * 场域合成站的方块实体：作为 GT 能量接收方持续耗电，并持有 3×3 合成网格。
 * 电量不足（断电）时降级为普通工作台，不识别邻近容器。
 */
public class TileFieldStation extends TileEntity implements IEnergyConnected {

    /** 3×3 合成网格的格子数。 */
    public static final int GRID_SIZE = 9;

    private final ItemStack[] grid = new ItemStack[GRID_SIZE];

    private long stored;
    private boolean powered;
    private byte colorization = -1;

    public Tier getTier() {
        Block block = getBlockType();
        return block instanceof BlockFieldStation station ? station.tier : Tier.LV;
    }

    public ItemStack getGridStack(int slot) {
        return grid[slot];
    }

    public void setGridStack(int slot, ItemStack stack) {
        grid[slot] = stack;
        markDirty();
    }

    /** 是否供电正常（服务端实时值，客户端通过界面同步）。 */
    public boolean isPowered() {
        return powered;
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
        NBTTagList items = new NBTTagList();
        for (int i = 0; i < GRID_SIZE; i++) {
            if (grid[i] == null) continue;
            NBTTagCompound item = new NBTTagCompound();
            item.setInteger("Slot", i);
            grid[i].writeToNBT(item);
            items.appendTag(item);
        }
        NBTTagCompound gridTag = new NBTTagCompound();
        gridTag.setTag("Items", items);
        tag.setTag("Grid", gridTag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        stored = tag.getLong("Stored");
        powered = tag.getBoolean("Powered");
        colorization = tag.hasKey("Color") ? tag.getByte("Color") : -1;
        Arrays.fill(grid, null);
        NBTTagList items = tag.getCompoundTag("Grid")
            .getTagList("Items", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            int slot = item.getInteger("Slot");
            if (slot >= 0 && slot < GRID_SIZE) grid[slot] = ItemStack.loadItemStackFromNBT(item);
        }
    }

    /** 方块被破坏时掉落网格里的物品。 */
    public void dropContents() {
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = grid[i];
            if (stack != null && stack.stackSize > 0) {
                BlockFieldStation.dropStack(worldObj, xCoord, yCoord, zCoord, stack);
                setGridStack(i, null);
            }
        }
    }

}
