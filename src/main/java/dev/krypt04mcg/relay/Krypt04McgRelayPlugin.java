package dev.krypt04mcg.relay;

import org.bukkit.plugin.java.JavaPlugin;

public final class Krypt04McgRelayPlugin extends JavaPlugin {
    private CustomPayloadRelay customPayloadRelay;

    @Override
    public void onEnable() {
        customPayloadRelay = new CustomPayloadRelay(this);
        customPayloadRelay.register();
        getLogger().info("Krypt04Mcg plugin messaging relay enabled.");
    }

    @Override
    public void onDisable() {
        if (customPayloadRelay != null) {
            customPayloadRelay.unregister();
            customPayloadRelay = null;
        }
    }
}
