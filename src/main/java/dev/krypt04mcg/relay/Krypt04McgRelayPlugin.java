package dev.krypt04mcg.relay;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class Krypt04McgRelayPlugin extends JavaPlugin {
    private EncryptedChatRelay relay;
    private ProtocolLibChatInterceptor protocolLibChatInterceptor;
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
        relay = new EncryptedChatRelay(this, config, messages);
        getServer().getPluginManager().registerEvents(relay, this);
        protocolLibChatInterceptor = new ProtocolLibChatInterceptor(this, config, relay);
        protocolLibChatInterceptor.register();
        relay.announceToOnlinePlayers();
    }
}
