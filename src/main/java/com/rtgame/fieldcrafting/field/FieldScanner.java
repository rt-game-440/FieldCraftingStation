package com.rtgame.fieldcrafting.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.rtgame.fieldcrafting.station.TileFieldStation;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 扫描场域范围，找出邻近容器。
 * <p>
 * 范围是以站为中心的立方体，只识别已加载区块里带物品栏的方块。GT 机器只认总线和箱式缓存器，
 * 机器内部的处理槽位不会被识别。
 */
public final class FieldScanner {

    private static final int PLAYER_MAIN_SLOTS = 36;

    private FieldScanner() {}

    /** 玩家背包（主物品栏 36 格，不含装备栏）。 */
    public static FieldContainer backpack(EntityPlayer player) {
        return new FieldContainer(player.inventory, 0, "", 0, 0, 0, SlotRules.ALL, PLAYER_MAIN_SLOTS);
    }

    public static List<FieldContainer> scan(World world, int cx, int cy, int cz, int radius) {
        List<FieldContainer> result = new ArrayList<>();
        int minChunkX = (cx - radius) >> 4;
        int maxChunkX = (cx + radius) >> 4;
        int minChunkZ = (cz - radius) >> 4;
        int maxChunkZ = (cz + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.getChunkProvider()
                    .chunkExists(chunkX, chunkZ)) continue;
                Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
                if (chunk == null) continue;
                Map<?, TileEntity> tiles = chunk.chunkTileEntityMap;
                for (TileEntity te : new ArrayList<>(tiles.values())) {
                    if (te == null || te.isInvalid()) continue;
                    int dx = te.xCoord - cx;
                    int dy = te.yCoord - cy;
                    int dz = te.zCoord - cz;
                    if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    FieldContainer container = toContainer(world, te, dx * dx + dy * dy + dz * dz);
                    if (container != null) result.add(container);
                }
            }
        }
        return result;
    }

    private static FieldContainer toContainer(World world, TileEntity te, double distanceSq) {
        if (te instanceof TileFieldStation) return null; // 其他站的合成网格不属于邻近容器
        if (!(te instanceof IInventory inventory)) return null;

        SlotRules rules = SlotRules.ALL;
        String label;
        if (te instanceof IGregTechTileEntity gtTile) {
            IMetaTileEntity meta = gtTile.getMetaTileEntity();
            if (!GTCompat.isStorage(meta)) return null; // GT 机器内部处理槽位不算
            rules = GTCompat.rules(gtTile, meta);
            label = inventory.getInventoryName();
        } else {
            if (inventory.getSizeInventory() <= 0) return null;
            if (te instanceof ISidedInventory sided) rules = new SidedRules(sided, null);
            Block block = world.getBlock(te.xCoord, te.yCoord, te.zCoord);
            label = block == null ? "" : block.getLocalizedName();
        }
        return new FieldContainer(
            inventory,
            distanceSq,
            label,
            te.xCoord,
            te.yCoord,
            te.zCoord,
            rules,
            Integer.MAX_VALUE);
    }

    /** 带朝向规则的 ISidedInventory：槽位只要某一面可达就可见，存取只要某一面允许就允许。 */
    public static final class SidedRules implements SlotRules {

        private final ISidedInventory inventory;
        private final boolean[] usable;

        public SidedRules(ISidedInventory inventory, IntPredicate extraUsable) {
            this.inventory = inventory;
            this.usable = new boolean[Math.max(0, inventory.getSizeInventory())];
            for (int side = 0; side < 6; side++) {
                int[] slots = inventory.getAccessibleSlotsFromSide(side);
                if (slots == null) continue;
                for (int slot : slots) {
                    if (slot >= 0 && slot < usable.length) usable[slot] = true;
                }
            }
            if (extraUsable != null) {
                for (int slot = 0; slot < usable.length; slot++) {
                    if (!extraUsable.test(slot)) usable[slot] = false;
                }
            }
        }

        @Override
        public boolean isUsable(int slot) {
            return slot >= 0 && slot < usable.length && usable[slot];
        }

        @Override
        public boolean canExtract(int slot, ItemStack stack) {
            for (int side = 0; side < 6; side++) {
                if (inventory.canExtractItem(slot, stack, side)) return true;
            }
            return false;
        }

        @Override
        public boolean canInsert(int slot, ItemStack stack) {
            for (int side = 0; side < 6; side++) {
                if (inventory.canInsertItem(slot, stack, side)) return true;
            }
            return false;
        }
    }
}
