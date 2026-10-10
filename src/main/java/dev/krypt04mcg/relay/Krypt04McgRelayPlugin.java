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
    private RawStreamRelay rawStreamRelay;
    private MessageBundle messages;

    /**
     * Handles the enable callback for the krypt04 mcg relay plugin.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadRelay();
        getLogger().info(messages.text("plugin-enabled"));
        getLogger().warning(messages.text("experimental-warning"));
    }

    /**
     * Handles the disable callback for the krypt04 mcg relay plugin.
     */
    @Override
    public void onDisable() {
        if (rawStreamRelay != null) {
            rawStreamRelay.unregister();
            rawStreamRelay = null;
        }
        if (relay != null) {
            HandlerList.unregisterAll(relay);
            relay.clear();
            relay = null;
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

    /**
     * Handles the command callback for the krypt04 mcg relay plugin.
     *
     * @param sender the sender or source associated with this operation
     * @param command the command supplied to this operation
     * @param label the label supplied to this operation
     * @param args the args supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
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

    /**
     * Performs the reload relay operation for the krypt04 mcg relay plugin.
     */
    private void reloadRelay() {
        if (rawStreamRelay != null) rawStreamRelay.unregister();
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
            protocolLibChatInterceptor = null;
            try {
                protocolLibChatInterceptor = new ProtocolLibChatInterceptor(this, config, relay);
                protocolLibChatInterceptor.register();
            } catch (RuntimeException | LinkageError e) {
                if (protocolLibChatInterceptor != null) protocolLibChatInterceptor.unregister();
                protocolLibChatInterceptor = null;
                getLogger().warning("ProtocolLib packet interception is unavailable: " + e);
            }
        } else {
            protocolLibChatInterceptor = null;
            getLogger().warning("ProtocolLib is unavailable; encrypted chat relay remains active, but the chat spam-kick bypass is disabled.");
        }
        customPayloadRelay = new CustomPayloadRelay(this);
        customPayloadRelay.register();
        rawStreamRelay = new RawStreamRelay(this, getConfig().getInt("api-channel-count", 16));
        rawStreamRelay.register();
        relay.announceToOnlinePlayers();
    }
}
