package org.ireallywanttosleep.wisteria.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.ireallywanttosleep.wisteria.Wisteria;

public final class WisteriaFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Wisteria.init(FabricLoader.getInstance().getGameDir());
    }
}
