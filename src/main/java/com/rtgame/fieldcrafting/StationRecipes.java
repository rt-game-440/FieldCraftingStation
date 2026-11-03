package com.rtgame.fieldcrafting;

import net.minecraft.item.ItemStack;

import com.rtgame.fieldcrafting.station.Tier;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.objects.OreDictItemStack;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTOreDictUnificator;

/**
 * 五档场域合成站的 GT 组装机配方（每档独立，不套娃）。
 * 30 秒，EU/t 与对应档位的站耗电一致，另需 144L 焊锡。
 */
final class StationRecipes {

    private static final int DURATION_TICKS = 30 * 20;
    private static final int SOLDER_MB = 144;

    private StationRecipes() {}

    static void register() {
        // LV：MV 电路板（矿辞 circuitGood，兼容各种基板做出来的 MV 电路）、镓板、红石合金导线
        add(
            Tier.LV,
            ItemList.Hull_LV.get(1),
            new OreDictItemStack("circuitGood", 4),
            plate(Materials.Gallium),
            wire(Materials.RedstoneAlloy));
        add(
            Tier.MV,
            ItemList.Hull_MV.get(1),
            ItemList.Field_Generator_LV.get(2),
            plate(Materials.Tantalum),
            wire(Materials.SuperconductorMV));
        add(
            Tier.HV,
            ItemList.Hull_HV.get(1),
            ItemList.Field_Generator_MV.get(2),
            plate(Materials.VibrantAlloy),
            wire(Materials.SuperconductorHV));
        add(
            Tier.EV,
            ItemList.Hull_EV.get(1),
            ItemList.Field_Generator_HV.get(2),
            plate(Materials.Indium),
            wire(Materials.SuperconductorEV));
        add(
            Tier.IV,
            ItemList.Hull_IV.get(1),
            ItemList.Field_Generator_EV.get(2),
            plate(Materials.Cerium),
            wire(Materials.SuperconductorIV));
    }

    private static ItemStack plate(Materials material) {
        return GTOreDictUnificator.get(OrePrefixes.plate, material, 8);
    }

    private static ItemStack wire(Materials material) {
        return GTOreDictUnificator.get(OrePrefixes.wireGt01, material, 9);
    }

    private static void add(Tier tier, Object hull, Object part, ItemStack plate, ItemStack wire) {
        if (hull == null || part == null || plate == null || wire == null) {
            FieldCrafting.LOG
                .error("场域合成站 {} 档配方材料缺失，已跳过：hull={} part={} plate={} wire={}", tier.id, hull, part, plate, wire);
            return;
        }
        GTValues.RA.stdBuilder()
            .itemInputs(hull, part, plate, wire)
            .fluidInputs(Materials.SolderingAlloy.getMolten(SOLDER_MB))
            .itemOutputs(new ItemStack(FieldCrafting.STATIONS[tier.ordinal()]))
            .duration(DURATION_TICKS)
            .eut(tier.usage)
            .addTo(RecipeMaps.assemblerRecipes);
    }
}
