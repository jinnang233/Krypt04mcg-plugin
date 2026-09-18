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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

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
    private final BoundedInbox<Incoming> inbox = new BoundedInbox<>(1024);
    private final FragmentOutbox<Player> outbox = new FragmentOutbox<>();
    private final RelayTrafficLimiter traffic = new RelayTrafficLimiter();
    private final BukkitTask pump;
    private volatile boolean closed;
    private long lastRejection;
    private boolean rejectionLogged;

    public EncryptedChatRelay(Krypt04McgRelayPlugin plugin, RelayConfig config, MessageBundle messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.collector = new FragmentCollector(config.fragmentTimeout(), config.maxPendingMessages(),
                config.maxFragmentsPerMessage());
        pump = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!isKrypt04McgMessage(event.getMessage())) {
            return;
        }

        event.setCancelled(true);
        event.getRecipients().clear();
        handleKrypt04McgMessage(event.getPlayer(), event.getMessage());
    }

    boolean isKrypt04McgMessage(String rawMessage) {
        return rawMessage != null && rawMessage.contains(FragmentService.PREFIX);
    }

    synchronized void handleKrypt04McgMessage(Player sender, String rawMessage) {
        if (closed || rawMessage == null || rawMessage.length() > 512) return;
        if (!traffic.receive(sender.getUniqueId(), rawMessage.length() * 3, System.nanoTime() / 1_000_000)) return;
        inbox.offer(new Incoming(sender, rawMessage));
    }

    private void drain() {
        if (closed) return;
        collector.cleanupTimedOut();
        long start = System.nanoTime();
        for (int i = 0; i < 64 && System.nanoTime() - start < 2_000_000; i++) {
            // Interleave sends and receives so a large completion cannot monopolize a tick.
            FragmentOutbox.Forward<Player> outgoing = outbox.poll(System.nanoTime());
            if (outgoing != null) forward(outgoing);
            if (System.nanoTime() - start >= 2_000_000) break;
            Incoming incoming = inbox.poll();
            if (incoming == null && outgoing == null) break;
            if (incoming != null && incoming.sender().isOnline()) process(incoming.sender(), incoming.message());
        }
    }

    private void process(Player sender, String rawMessage) {
        Optional<String> fragmentLine = fragmentService.extractFragmentLine(rawMessage);
        if (fragmentLine.isEmpty()) {
            return;
        }
        try {
            Fragment fragment = fragmentService.parse(fragmentLine.get());
            Optional<FragmentCollector.CompleteMessage> complete = collector.accept(sender.getUniqueId(), fragment,
                    fragmentLine.get());
            complete.ifPresent(message -> route(sender, message));
        } catch (Exception e) {
            if (logRejected(sender.getName(), e.getMessage()) && config.notifyMalformedFragment()) {
                sender.sendMessage(ChatColor.RED + messages.text("sender-rejected", "reason", String.valueOf(e.getMessage())));
            }
        }
    }

    public synchronized void clear() {
        closed = true;
        inbox.close();
        outbox.clear();
        pump.cancel();
        collector.clear();
        traffic.clear();
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
        sendPluginInstalledNotice(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        collector.removeSender(event.getPlayer().getUniqueId());
        outbox.removeParticipant(event.getPlayer());
    }

    private record Incoming(Player sender, String message) {}

    private void route(Player sender, FragmentCollector.CompleteMessage message) {
        String senderName = sender.getName();
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
                if (logRejected(senderName, reason)) notifyOffline(senderName, packet.receiver());
                return;
            }

            List<Player> targets = new ArrayList<>();
            targets.add(receiver);
            if (config.echoToSender() && !receiver.getName().equalsIgnoreCase(senderName)) {
                targets.add(sender);
            }

            if (!outbox.offer(sender, targets, message.fragmentsInOrder(), System.nanoTime())) {
                logRejected(senderName, "outgoing chat queue is full");
            }
        } catch (Exception e) {
            logRejected(senderName, e.getMessage());
        }
    }

    private void forward(FragmentOutbox.Forward<Player> outgoing) {
        Player sender = outgoing.sender();
        Player target = outgoing.target();
        if (!sender.isOnline() || !target.isOnline()) return;
        try {
            String line = vanillaChatLine(sender.getName(), outgoing.fragment());
            if (traffic.forward(sender.getUniqueId(), line.length() * 3, System.nanoTime() / 1_000_000)) {
                target.sendMessage(line);
            }
        } catch (RuntimeException e) {
            logRejected(sender.getName(), e.getMessage());
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

    private boolean logRejected(String senderName, String reason) {
        long now = System.nanoTime();
        if (rejectionLogged && now - lastRejection < 1_000_000_000L) return false;
        rejectionLogged = true;
        lastRejection = now;
        String safeReason = String.valueOf(reason).replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        plugin.getLogger().warning(messages.text("reject-log", "sender", senderName, "reason", safeReason));
        return true;
    }

    private void sendPluginInstalledNotice(Player player) {
        player.sendMessage(ChatColor.AQUA + messages.text("plugin-installed-notice"));
    }

    private static String vanillaChatLine(String senderName, String message) {
        return "<" + senderName + "> " + message;
    }
}
