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

    /**
     * Creates a encrypted chat relay with the supplied dependencies and initial state.
     *
     * @param plugin the plugin supplied to this operation
     * @param config the config supplied to this operation
     * @param messages the messages supplied to this operation
     */
    public EncryptedChatRelay(Krypt04McgRelayPlugin plugin, RelayConfig config, MessageBundle messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.collector = new FragmentCollector(config.fragmentTimeout(), config.maxPendingMessages(),
                config.maxFragmentsPerMessage());
        pump = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
    }

    /**
     * Handles the chat callback for the directed encrypted-chat relay.
     *
     * @param event the event supplied to this operation
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!isKrypt04McgMessage(event.getMessage())) {
            return;
        }

        event.setCancelled(true);
        event.getRecipients().clear();
        handleKrypt04McgMessage(event.getPlayer(), event.getMessage());
    }

    /**
     * Reports whether krypt04 mcg message holds for the directed encrypted-chat relay.
     *
     * @param rawMessage the raw message supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    boolean isKrypt04McgMessage(String rawMessage) {
        return rawMessage != null && rawMessage.contains(FragmentService.PREFIX);
    }

    /**
     * Processes krypt04 mcg message using the directed encrypted-chat relay state and checks.
     *
     * @param sender the sender or source associated with this operation
     * @param rawMessage the raw message supplied to this operation
     */
    synchronized void handleKrypt04McgMessage(Player sender, String rawMessage) {
        if (closed || rawMessage == null || rawMessage.length() > 512) return;
        if (!traffic.receive(sender.getUniqueId(), rawMessage.length() * 3, System.nanoTime() / 1_000_000)) return;
        inbox.offer(new Incoming(sender, rawMessage));
    }

    /**
     * Interleaves bounded incoming and outgoing work on the server thread and checks the shared per-tick
     * elapsed budget between operations. A single decode/send can still exceed that budget; this is
     * bounded scheduling, not a hard real-time guarantee.
     */
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

    /**
     * Performs the process operation for the directed encrypted-chat relay.
     *
     * @param sender the sender or source associated with this operation
     * @param rawMessage the raw message supplied to this operation
     */
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

    /**
     * Clears retained state in the directed encrypted-chat relay.
     */
    public synchronized void clear() {
        closed = true;
        inbox.close();
        outbox.clear();
        pump.cancel();
        collector.clear();
        traffic.clear();
    }

    /**
     * Performs the announce to online players operation for the directed encrypted-chat relay.
     */
    public void announceToOnlinePlayers() {
        if (!config.announcePluginInstalled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendPluginInstalledNotice(player);
        }
    }

    /**
     * Handles the join callback for the directed encrypted-chat relay.
     *
     * @param event the event supplied to this operation
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.announcePluginInstalled()) {
            return;
        }
        sendPluginInstalledNotice(event.getPlayer());
    }

    /**
     * Handles the quit callback for the directed encrypted-chat relay.
     *
     * @param event the event supplied to this operation
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        collector.removeSender(event.getPlayer().getUniqueId());
        outbox.removeParticipant(event.getPlayer());
    }

    private record Incoming(Player sender, String message) {}

    /**
     * Decodes routing metadata, optionally enforces packet-sender equality with the authenticated Bukkit
     * player, and queues raw fragments for an online target. The server does not decrypt the body or
     * verify the end-to-end signature; routing is not a proof of the claimed cryptographic identity.
     *
     * @param sender the sender or source associated with this operation
     * @param message the message supplied to this operation
     */
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

    /**
     * Performs the forward operation for the directed encrypted-chat relay.
     *
     * @param outgoing the outgoing supplied to this operation
     */
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

    /**
     * Performs the notify offline operation for the directed encrypted-chat relay.
     *
     * @param senderName the sender name supplied to this operation
     * @param receiverName the receiver name supplied to this operation
     */
    private void notifyOffline(String senderName, String receiverName) {
        if (!config.notifyOfflineReceiver()) {
            return;
        }
        Player sender = Bukkit.getPlayerExact(senderName);
        if (sender != null) {
            sender.sendMessage(ChatColor.RED + messages.text("receiver-offline", "receiver", receiverName));
        }
    }

    /**
     * Returns the recorded false for the directed encrypted-chat relay.
     *
     * @param senderName the sender name supplied to this operation
     * @param reason the reason supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    private boolean logRejected(String senderName, String reason) {
        long now = System.nanoTime();
        if (rejectionLogged && now - lastRejection < 1_000_000_000L) return false;
        rejectionLogged = true;
        lastRejection = now;
        String safeReason = String.valueOf(reason).replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        plugin.getLogger().warning(messages.text("reject-log", "sender", senderName, "reason", safeReason));
        return true;
    }

    /**
     * Submits plugin installed notice through the directed encrypted-chat relay path. Local submission
     * does not by itself acknowledge remote receipt.
     *
     * @param player the player supplied to this operation
     */
    private void sendPluginInstalledNotice(Player player) {
        player.sendMessage(ChatColor.AQUA + messages.text("plugin-installed-notice"));
    }

    /**
     * Performs the vanilla chat line operation for the directed encrypted-chat relay.
     *
     * @param senderName the sender name supplied to this operation
     * @param message the message supplied to this operation
     * @return the result described above
     */
    private static String vanillaChatLine(String senderName, String message) {
        return "<" + senderName + "> " + message;
    }
}
