package com.rtgame.fieldcrafting.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.rtgame.fieldcrafting.station.TileFieldStation;

import cpw.mods.fml.common.network.IGuiHandler;

/** 打开场域合成站界面：服务端给容器，客户端给界面。 */
public class FieldGuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileFieldStation tile ? new FieldStationContainer(player, tile) : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileFieldStation tile ? new FieldStationScreen(new FieldStationContainer(player, tile))
            : null;
    }
}
