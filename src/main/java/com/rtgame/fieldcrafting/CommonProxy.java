package com.rtgame.fieldcrafting;

import com.rtgame.fieldcrafting.station.BlockFieldStation;
import com.rtgame.fieldcrafting.station.Tier;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        FieldCrafting.LOG.info("Field Crafting Station " + Tags.VERSION);
        for (Tier tier : Tier.values()) {
            BlockFieldStation block = new BlockFieldStation(tier);
            GameRegistry.registerBlock(block, "station_" + tier.id);
            FieldCrafting.STATIONS[tier.ordinal()] = block;
        }
        GameRegistry.registerTileEntity(TileFieldStation.class, FieldCrafting.MODID + ":station");
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {
        StationRecipes.register();
    }
}
