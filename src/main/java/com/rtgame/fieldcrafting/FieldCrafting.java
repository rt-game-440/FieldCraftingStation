package com.rtgame.fieldcrafting;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rtgame.fieldcrafting.station.BlockFieldStation;
import com.rtgame.fieldcrafting.station.Tier;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = FieldCrafting.MODID,
    version = Tags.VERSION,
    name = "Field Crafting Station",
    dependencies = "required-after:gregtech; required-after:gtnhlib; required-after:NotEnoughItems",
    acceptedMinecraftVersions = "[1.7.10]")
public class FieldCrafting {

    public static final String MODID = "fieldcrafting";
    public static final Logger LOG = LogManager.getLogger(MODID);

    /** 五档场域合成站方块，下标即 {@link Tier#ordinal()}。 */
    public static final BlockFieldStation[] STATIONS = new BlockFieldStation[Tier.values().length];

    public static final CreativeTabs creativeTab = new CreativeTabs(MODID) {

        @Override
        public Item getTabIconItem() {
            return Item.getItemFromBlock(STATIONS[0]);
        }
    };

    @Mod.Instance(MODID)
    public static FieldCrafting instance;

    @SidedProxy(
        clientSide = "com.rtgame.fieldcrafting.ClientProxy",
        serverSide = "com.rtgame.fieldcrafting.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}
