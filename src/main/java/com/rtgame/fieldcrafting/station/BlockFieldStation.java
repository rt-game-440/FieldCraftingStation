package com.rtgame.fieldcrafting.station;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.cleanroommc.modularui.factory.GuiFactories;
import com.rtgame.fieldcrafting.FieldCrafting;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 场域合成站方块，五个档位各一个，没有朝向。 */
public class BlockFieldStation extends BlockContainer {

    public final Tier tier;

    @SideOnly(Side.CLIENT)
    private IIcon top;
    @SideOnly(Side.CLIENT)
    private IIcon side;
    @SideOnly(Side.CLIENT)
    private IIcon bottom;

    public BlockFieldStation(Tier tier) {
        super(Material.wood);
        this.tier = tier;
        setBlockName(FieldCrafting.MODID + ".station." + tier.id);
        setHardness(2.5F);
        setResistance(10F);
        setStepSound(soundTypeWood);
        setHarvestLevel("axe", 0);
        setCreativeTab(FieldCrafting.creativeTab);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileFieldStation();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int sideHit, float hitX,
        float hitY, float hitZ) {
        if (!world.isRemote) {
            GuiFactories.tileEntity()
                .open(player, x, y, z);
        }
        return true;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        if (!world.isRemote) {
            TileEntity te = world.getTileEntity(x, y, z);
            if (te instanceof TileFieldStation station) station.dropContents();
        }
        super.breakBlock(world, x, y, z, block, meta);
    }

    static void dropStack(World world, int x, int y, int z, ItemStack stack) {
        Random rand = world.rand;
        EntityItem item = new EntityItem(
            world,
            x + rand.nextFloat() * 0.8F + 0.1F,
            y + rand.nextFloat() * 0.8F + 0.1F,
            z + rand.nextFloat() * 0.8F + 0.1F,
            stack.copy());
        item.motionX = rand.nextGaussian() * 0.05;
        item.motionY = rand.nextGaussian() * 0.05 + 0.2;
        item.motionZ = rand.nextGaussian() * 0.05;
        world.spawnEntityInWorld(item);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        String base = FieldCrafting.MODID + ":station_" + tier.id;
        top = register.registerIcon(base + "_top");
        side = register.registerIcon(base + "_side");
        bottom = register.registerIcon(base + "_bottom");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int sideIndex, int meta) {
        return sideIndex == 1 ? top : sideIndex == 0 ? bottom : side;
    }
}
