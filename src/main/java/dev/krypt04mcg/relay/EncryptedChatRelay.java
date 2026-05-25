package dev.krypt04mcg.relay;

import dev.krypt04mcg.relay.fragment.FragmentCollector;
import dev.krypt04mcg.relay.fragment.FragmentService;
import dev.krypt04mcg.relay.model.EncryptedPacket;
import dev.krypt04mcg.relay.model.Fragment;
import dev.krypt04mcg.relay.protocol.PacketCodec;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class EncryptedChatRelay implements Listener {
    private final Krypt04McgRelayPlugin plugin;
    private final RelayConfig config;
    private final MessageBundle messages;
    private final FragmentService fragmentService = new FragmentService();
    private final PacketCodec packetCodec = new PacketCodec();
    private final FragmentCollector collector;

    public EncryptedChatRelay(Krypt04McgRelayPlugin plugin, RelayConfig config, MessageBundle messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.collector = new FragmentCollector(config.fragmentTimeout(), config.maxPendingMessages(),
                config.maxFragmentsPerMessage());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Optional<String> fragmentLine = fragmentService.extractFragmentLine(event.getMessage());
        if (fragmentLine.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        event.getRecipients().clear();

        Player sender = event.getPlayer();
        try {
            collector.cleanupTimedOut();
            Fragment fragment = fragmentService.parse(fragmentLine.get());
            Optional<FragmentCollector.CompleteMessage> complete = collector.accept(sender.getUniqueId(), fragment,
                    fragmentLine.get());
            complete.ifPresent(message -> routeOnMainThread(sender.getName(), message));
        } catch (Exception e) {
            rejectOnMainThread(sender.getName(), e.getMessage(), config.notifyMalformedFragment());
        }
    }

    public void clear() {
        collector.clear();
    }

    public void announceToOnlinePlayers() {
        if (!config.announcePluginInstalled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendPluginInstalledNotice(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.announcePluginInstalled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendPluginInstalledNotice(event.getPlayer()), 1L);
    }

    private void routeOnMainThread(String senderName, FragmentCollector.CompleteMessage message) {
        Bukkit.getScheduler().runTask(plugin, () -> route(senderName, message));
    }

    private void rejectOnMainThread(String senderName, String reason, boolean notifySender) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            logRejected(senderName, reason);
            if (notifySender) {
                Player sender = Bukkit.getPlayerExact(senderName);
                if (sender != null) {
                    sender.sendMessage(ChatColor.RED + messages.text("sender-rejected", "reason", reason));
                }
            }
        });
    }

    private void route(String senderName, FragmentCollector.CompleteMessage message) {
        try {
            EncryptedPacket packet = packetCodec.decode(message.packetBytes());
            if (config.enforceSenderMatch() && !packet.sender().equalsIgnoreCase(senderName)) {
                String reason = messages.text("reason-packet-sender-mismatch", "packet_sender", packet.sender());
                logRejected(senderName, reason);
                return;
            }
            Player receiver = Bukkit.getPlayerExact(packet.receiver());
            if (receiver == null) {
                String reason = messages.text("reason-receiver-offline", "receiver", packet.receiver());
                logRejected(senderName, reason);
                notifyOffline(senderName, packet.receiver());
                return;
            }

            List<Player> targets = new ArrayList<>();
            targets.add(receiver);
            if (config.echoToSender() && !receiver.getName().equalsIgnoreCase(senderName)) {
                Player sender = Bukkit.getPlayerExact(senderName);
                if (sender != null) {
                    targets.add(sender);
                }
            }

            for (String rawFragment : message.fragmentsInOrder()) {
                String forwardedLine = vanillaChatLine(senderName, rawFragment);
                for (Player target : targets) {
                    target.sendMessage(forwardedLine);
                }
            }
            plugin.getLogger().info(messages.text("relay-log", "sender", senderName, "receiver", receiver.getName()));
        } catch (Exception e) {
            logRejected(senderName, e.getMessage());
        }
    }

    private void notifyOffline(String senderName, String receiverName) {
        if (!config.notifyOfflineReceiver()) {
            return;
        }
        Player sender = Bukkit.getPlayerExact(senderName);
        if (sender != null) {
            sender.sendMessage(ChatColor.RED + messages.text("receiver-offline", "receiver", receiverName));
        }
    }

    private void logRejected(String senderName, String reason) {
        plugin.getLogger().warning(messages.text("reject-log", "sender", senderName, "reason", reason));
    }

    private void sendPluginInstalledNotice(Player player) {
        player.sendMessage(ChatColor.AQUA + messages.text("plugin-installed-notice"));
    }

    private static String vanillaChatLine(String senderName, String message) {
        return "<" + senderName + "> " + message;
    }
}
