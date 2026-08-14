package org.ireallywanttosleep.wisteria.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.ireallywanttosleep.wisteria.Wisteria;

@Mod(Wisteria.MOD_ID)
public final class WisteriaForge {
    public WisteriaForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Wisteria.init(FMLPaths.GAMEDIR.get());
        }
    }
}
