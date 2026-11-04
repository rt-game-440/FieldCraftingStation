package com.rtgame.fieldcrafting.mixins.late;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.rtgame.fieldcrafting.gui.FieldStationContainer;

import codechicken.nei.ItemStackAmount;
import codechicken.nei.recipe.AutoCraftingManager;

/**
 * NEI 合成链只数槽位里的物品。清单只显示当前这一屏，屏外的材料会被当成没有，
 * 所以场域合成站的界面改为按背包、网格和邻近容器的全部物品统计。
 */
@Mixin(value = AutoCraftingManager.class, remap = false)
public class MixinAutoCraftingManager {

    @Inject(method = "getInventoryItems", at = @At("HEAD"), cancellable = true)
    private static void fieldcrafting$countWholeField(GuiContainer gui, CallbackInfoReturnable<ItemStackAmount> cir) {
        if (gui.inventorySlots instanceof FieldStationContainer container) {
            ItemStackAmount amount = new ItemStackAmount();
            for (ItemStack stack : container.materials()) amount.add(stack);
            cir.setReturnValue(amount);
        }
    }
}
