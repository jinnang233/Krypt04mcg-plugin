package dev.krypt04mcg.relay;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class Krypt04McgRelayPlugin extends JavaPlugin {
    private EncryptedChatRelay relay;
    private ProtocolLibChatInterceptor protocolLibChatInterceptor;
    private CustomPayloadRelay customPayloadRelay;
    private MessageBundle messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadRelay();
        getLogger().info(messages.text("plugin-enabled"));
        getLogger().warning(messages.text("experimental-warning"));
    }

    @Override
    public void onDisable() {
        if (relay != null) {
            relay.clear();
        }
        if (protocolLibChatInterceptor != null) {
            protocolLibChatInterceptor.unregister();
            protocolLibChatInterceptor = null;
        }
        if (customPayloadRelay != null) {
            customPayloadRelay.unregister();
            customPayloadRelay = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadRelay();
            sender.sendMessage(ChatColor.GREEN + messages.text("config-reloaded"));
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + messages.text("command-usage", "label", label));
        return true;
    }

    private void reloadRelay() {
        RelayConfig config = RelayConfig.from(this);
        messages = MessageBundle.load(this, config.language());
        if (relay != null) {
            HandlerList.unregisterAll(relay);
            relay.clear();
        }
        if (protocolLibChatInterceptor != null) {
            protocolLibChatInterceptor.unregister();
        }
        if (customPayloadRelay != null) {
            customPayloadRelay.unregister();
        }
        relay = new EncryptedChatRelay(this, config, messages);
        getServer().getPluginManager().registerEvents(relay, this);
        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            protocolLibChatInterceptor = new ProtocolLibChatInterceptor(this, config, relay);
            try {
                protocolLibChatInterceptor.register();
            } catch (RuntimeException | LinkageError e) {
                protocolLibChatInterceptor.unregister();
                protocolLibChatInterceptor = null;
                getLogger().warning("ProtocolLib packet interception is unavailable: " + e);
            }
        } else {
            protocolLibChatInterceptor = null;
            getLogger().warning("ProtocolLib is unavailable; encrypted chat relay remains active, but the chat spam-kick bypass is disabled.");
        }
        customPayloadRelay = new CustomPayloadRelay(this);
        customPayloadRelay.register();
        relay.announceToOnlinePlayers();
    }
}
