package org.ireallywanttosleep.wisteria.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.ireallywanttosleep.wisteria.Wisteria;

@Mod(value = Wisteria.MOD_ID, dist = Dist.CLIENT)
public final class WisteriaNeoForge {
    public WisteriaNeoForge() {
        Wisteria.init(FMLPaths.GAMEDIR.get());
    }
}
