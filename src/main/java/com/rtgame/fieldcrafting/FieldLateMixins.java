package com.rtgame.fieldcrafting;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

/** 只修改客户端的 NEI，专用服务器不需要。 */
@LateMixin
public class FieldLateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.fieldcrafting.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        return FMLLaunchHandler.side()
            .isClient() ? Collections.singletonList("late.MixinAutoCraftingManager") : Collections.emptyList();
    }
}
