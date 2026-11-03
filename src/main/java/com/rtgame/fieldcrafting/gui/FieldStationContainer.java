package com.rtgame.fieldcrafting.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.integration.nei.INEIRecipeTransfer;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.widgets.slot.InventoryCraftingWrapper;
import com.cleanroommc.modularui.widgets.slot.ModularCraftingSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * 场域合成站的容器：3×3 合成、结果槽，以及与物品清单、NEI "+" 的联动。
 */
public class FieldStationContainer extends ModularContainer implements INEIRecipeTransfer<GuiContainer> {

    private static final String[] NEI_IDENTS = { "crafting" };
    /** NEI 合成配方里 3×3 网格第一格的相对位置与格距。 */
    private static final int NEI_ORIGIN_X = 25;
    private static final int NEI_ORIGIN_Y = 6;
    private static final int NEI_SPACING = 18;

    private final TileFieldStation tile;
    private final InventoryCraftingWrapper craftMatrix;
    private ModularCraftingSlot craftingSlot;
    private FieldListSync listSync;
    private boolean lastPowered;

    public FieldStationContainer(TileFieldStation tile) {
        this.tile = tile;
        this.craftMatrix = new InventoryCraftingWrapper(this, 3, 3, tile.getGrid(), 0);
    }

    public void setListSync(FieldListSync listSync) {
        this.listSync = listSync;
    }

    // ---------------------------------------------------------------- 合成

    @Override
    public void registerSlot(String panelName, ModularSlot slot) {
        super.registerSlot(panelName, slot);
        if (slot instanceof ModularCraftingSlot crafting) {
            this.craftingSlot = crafting;
            crafting.setCraftMatrix(this.craftMatrix);
        }
    }

    @Override
    public void onModularContainerOpened() {
        super.onModularContainerOpened();
        refreshResult();
    }

    @Override
    public void onModularContainerClosed() {
        super.onModularContainerClosed();
        if (!getPlayer().worldObj.isRemote) {
            tile.getGrid()
                .setStackInSlot(TileFieldStation.GRID_SIZE, null); // 结果槽不保留
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        // 检测合成网格被界面外的操作改动（如 NEI 填充之外的途径）
        craftMatrix.detectChanges();
        // 断电 / 来电时结果槽按当前状态重新计算
        if (!getPlayer().worldObj.isRemote) {
            boolean powered = tile.isPowered();
            if (powered != lastPowered) {
                lastPowered = powered;
                refreshResult();
            }
        }
    }

    private void refreshResult() {
        if (craftingSlot == null || getPlayer() == null || getPlayer().worldObj.isRemote) return;
        craftingSlot.updateResult(
            CraftingManager.getInstance()
                .findMatchingRecipe(craftMatrix, getPlayer().worldObj));
    }

    @Override
    public void onCraftMatrixChanged(@NotNull IInventory inventory) {
        refreshResult();
    }

    // ---------------------------------------------------------------- Shift+点击背包物品存入邻近容器

    @Override
    public ItemStack transferStackInSlot(@NotNull EntityPlayer player, int index) {
        ModularSlot slot = getModularSlot(index);
        boolean powered = player.worldObj.isRemote ? listSync != null && listSync.isPowered() : tile.isPowered();
        // 断电时降级为普通工作台，Shift+点击走默认逻辑
        if (powered && ModularSlot.isPlayerSlot(slot) && listSync != null) {
            if (player.worldObj.isRemote) return null; // 客户端不预测，等服务端同步
            ItemStack stack = slot.getStack();
            if (Stacks.isEmpty(stack)) return null;
            ItemStack rest = listSync.storeFromBackpack(player, stack.copy());
            int moved = stack.stackSize - (rest == null ? 0 : rest.stackSize);
            if (moved <= 0) return null;
            ItemStack copy = stack.copy();
            slot.putStack(rest);
            return copy;
        }
        return super.transferStackInSlot(player, index);
    }

    // ---------------------------------------------------------------- NEI "+"

    @Override
    public String[] getIdents() {
        return NEI_IDENTS;
    }

    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (listSync == null) return 0;
        ItemStack[][] candidates = new ItemStack[TileFieldStation.GRID_SIZE][];
        boolean any = false;
        List<PositionedStack> ingredients = recipe.getIngredientStacks(recipeIndex);
        for (PositionedStack ingredient : ingredients) {
            int col = Math.round((ingredient.relx - NEI_ORIGIN_X) / (float) NEI_SPACING);
            int row = Math.round((ingredient.rely - NEI_ORIGIN_Y) / (float) NEI_SPACING);
            if (col < 0 || col > 2 || row < 0 || row > 2) continue;
            List<ItemStack> options = new ArrayList<>();
            if (ingredient.items != null) {
                for (ItemStack option : ingredient.items) {
                    if (option != null && options.size() < 16) options.add(option.copy());
                }
            }
            if (options.isEmpty()) continue;
            candidates[row * 3 + col] = options.toArray(new ItemStack[0]);
            any = true;
        }
        if (!any) return 0;
        listSync.sendFill(candidates, multiplier);
        return 1;
    }

    /**
     * 服务端：按候选材料填充合成网格。先把网格里原有物品挪走（背包优先，其次邻近容器），
     * 再按"背包优先、邻近容器其次"取材料；断电时邻近容器不参与，只用背包。
     */
    public void fillGrid(EntityPlayer player, ItemStack[][] candidates, int multiplier) {
        if (player.worldObj.isRemote) return;
        FieldInventory field = tile.createFieldInventory(player);
        int size = TileFieldStation.GRID_SIZE;

        // 1. 清空网格；放不下的格子保持原样，不参与填充
        boolean[] blocked = new boolean[size];
        for (int i = 0; i < size; i++) {
            ItemStack inSlot = tile.getGrid()
                .getStackInSlot(i);
            if (Platform.isStackEmpty(inSlot)) continue;
            ItemStack rest = field.giveToPlayerOrField(inSlot.copy());
            tile.getGrid()
                .setStackInSlot(i, rest);
            if (rest != null) blocked[i] = true;
        }

        // 2. 为每格挑选材料：按候选顺序取第一个还有剩余的
        List<ItemStack> samples = new ArrayList<>();
        List<Long> remaining = new ArrayList<>();
        ItemStack[] chosen = new ItemStack[size];
        int[] chosenIndex = new int[size];
        for (int i = 0; i < size; i++) {
            if (blocked[i] || candidates[i] == null) continue;
            for (ItemStack candidate : candidates[i]) {
                if (Stacks.isEmpty(candidate)) continue;
                int idx = indexOf(samples, candidate);
                if (idx < 0) {
                    samples.add(Stacks.copyWithSize(candidate, 1));
                    remaining.add(field.countMaterial(candidate));
                    idx = samples.size() - 1;
                }
                if (remaining.get(idx) >= 1) {
                    remaining.set(idx, remaining.get(idx) - 1);
                    chosen[i] = samples.get(idx);
                    chosenIndex[i] = idx;
                    break;
                }
            }
        }

        // 3. 每格数量：不超过 multiplier 和最大堆叠，同种材料出现在多格时平分库存
        int[] uses = new int[samples.size()];
        for (int i = 0; i < size; i++) if (chosen[i] != null) uses[chosenIndex[i]]++;
        long amount = Math.max(1, multiplier);
        for (int i = 0; i < size; i++) {
            if (chosen[i] == null) continue;
            int idx = chosenIndex[i];
            long total = field.countMaterial(chosen[i]);
            amount = Math.min(amount, Math.max(1, total / uses[idx]));
            amount = Math.min(amount, chosen[i].getMaxStackSize());
        }
        for (int i = 0; i < size; i++) {
            if (chosen[i] == null) continue;
            ItemStack got = field.consumeMaterial(chosen[i], (int) amount);
            if (got != null) tile.getGrid()
                .setStackInSlot(i, got);
        }
        craftMatrix.notifyContainer();
    }

    private static int indexOf(List<ItemStack> samples, ItemStack stack) {
        for (int i = 0; i < samples.size(); i++) {
            if (Stacks.same(samples.get(i), stack)) return i;
        }
        return -1;
    }

    @Override
    public List<GuiOverlayButton.ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler recipe,
        int recipeIndex) {
        // 可用材料 = 背包 + 合成网格 + 场域清单（汇总视图下客户端已知的邻近容器物品）
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack stack : gui.mc.thePlayer.inventory.mainInventory) {
            if (!Stacks.isEmpty(stack)) pool.add(stack.copy());
        }
        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            ItemStack stack = craftMatrix.getStackInSlot(i);
            if (!Stacks.isEmpty(stack)) pool.add(stack.copy());
        }
        if (listSync != null && listSync.isPowered() && listSync.getView() == FieldListSync.VIEW_SUMMARY) {
            for (FieldListSync.Entry entry : listSync.getEntries()) {
                pool.add(Stacks.copyWithSize(entry.stack, (int) Math.min(entry.count, Integer.MAX_VALUE)));
            }
        }
        List<GuiOverlayButton.ItemOverlayState> states = new ArrayList<>();
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            ItemStack found = null;
            for (ItemStack have : pool) {
                if (have.stackSize > 0 && ingredient.contains(have)) {
                    found = have;
                    break;
                }
            }
            states.add(new GuiOverlayButton.ItemOverlayState(ingredient, found != null));
            if (found != null) found.stackSize -= 1;
        }
        return states;
    }
}
