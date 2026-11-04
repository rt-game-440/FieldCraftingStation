package com.rtgame.fieldcrafting;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import com.rtgame.fieldcrafting.gui.FieldOverlayHandler;

import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        FieldOverlayHandler.register();
    }

    @Override
    public EntityPlayer clientPlayer() {
        return Minecraft.getMinecraft().thePlayer;
    }
}
