package org.ireallywanttosleep.wisteria.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.ireallywanttosleep.wisteria.Wisteria;

public final class WisteriaFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Wisteria.init();
    }
}
