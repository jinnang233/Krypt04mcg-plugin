package dev.krypt04mcg.relay;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import static dev.krypt04mcg.relay.StreamControl.Kind.*;

/** One pair per pre-registered channel. Raw records are forwarded without decoding. */
final class RawStreamRelay implements PluginMessageListener, Listener {
    static final String CONTROL = "krypt04mcg_stream:control";
    static final String DATA_PREFIX = "krypt04mcg_stream:data/";
    private final Plugin plugin;
    private final Route[] routes;
    private final DataChannel[] dataChannels;
    private final RelayTrafficLimiter controlTraffic = new RelayTrafficLimiter();
    private BukkitTask cleanup;

    /**
     * Creates a raw stream relay with the supplied dependencies and initial state.
     *
     * @param plugin the plugin supplied to this operation
     * @param count the required number of frame components
     */
    RawStreamRelay(Plugin plugin, int count) {
        this.plugin = plugin;
        routes = new Route[Math.clamp(count, 1, 256)];
        dataChannels = new DataChannel[routes.length];
        for (int slot = 0; slot < routes.length; slot++) dataChannels[slot] = new DataChannel(slot);
    }

    /**
     * Registers the supported callbacks and channels for the opaque raw-stream relay.
     */
    void register() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, CONTROL, this);
        messenger.registerOutgoingPluginChannel(plugin, CONTROL);
        for (DataChannel data : dataChannels) {
            messenger.registerIncomingPluginChannel(plugin, data.channel, data);
            messenger.registerOutgoingPluginChannel(plugin, data.channel);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        cleanup = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> expire(now()), 20, 20);
    }

    /**
     * Removes the registered callbacks and channels from the opaque raw-stream relay.
     */
    void unregister() {
        if (cleanup != null) cleanup.cancel();
        for (int slot = 0; slot < routes.length; slot++) release(slot);
        HandlerList.unregisterAll(this);
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, CONTROL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, CONTROL);
        for (DataChannel data : dataChannels) {
            messenger.unregisterIncomingPluginChannel(plugin, data.channel, data);
            messenger.unregisterOutgoingPluginChannel(plugin, data.channel);
        }
        controlTraffic.clear();
    }

    /**
     * Dispatches bounded control input or data for the statically registered slot. Source/route checks use
     * the Bukkit connection identity; data records are forwarded opaquely and do not become trusted
     * plaintext at the server.
     *
     * @param channel the business or transport channel identifier
     * @param source the source supplied to this operation
     * @param bytes the bytes supplied to this operation
     */
    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] bytes) {
        if (!CONTROL.equals(channel)) return;
        long now = now();
        if (bytes.length > 31104) {
            disconnect(source);
            return;
        }
        var result = controlTraffic.receiveResult(source.getUniqueId(), bytes.length, now);
        if (result != RelayTrafficLimiter.ReceiveResult.ACCEPTED) {
            // Shared overload must not tear down an otherwise compliant player's routes.
            if (result == RelayTrafficLimiter.ReceiveResult.SOURCE_LIMIT) disconnect(source);
            return;
        }
        try { control(source, StreamControl.decode(bytes), now); }
        catch (IllegalArgumentException e) { plugin.getLogger().fine("Rejected malformed stream control"); }
    }

    /** Messenger dispatches directly to the slot bound at registration. */
    private final class DataChannel implements PluginMessageListener {
        private final int slot;
        private final String channel;

        /**
         * Creates a data channel with the supplied dependencies and initial state.
         *
         * @param slot the slot supplied to this operation
         */
        DataChannel(int slot) {
            this.slot = slot;
            channel = DATA_PREFIX + slot;
        }

        /**
         * Dispatches bounded control input or data for the statically registered slot. Source/route checks use
         * the Bukkit connection identity; data records are forwarded opaquely and do not become trusted
         * plaintext at the server.
         *
         * @param ignored the ignored supplied to this operation
         * @param source the source supplied to this operation
         * @param bytes the bytes supplied to this operation
         */
        @Override
        public void onPluginMessageReceived(String ignored, Player source, byte[] bytes) {
            Route route = routes[slot];
            if (route == null || !route.ready) return;
            Player target;
            if (source == route.source && !route.sourceEnded) target = route.target;
            else if (source == route.target && !route.targetEnded) target = route.source;
            else return;
            // No channel parsing, subscriptions, player lookup, allocation or data envelope.
            target.sendPluginMessage(plugin, channel, bytes);
            route.used = now();
        }
    }

    /**
     * Handles structurally validated stream control using the authenticated Bukkit source and assigned
     * route state. Server-only control kinds cannot be injected by clients. End-to-end control tags and
     * encrypted record contents are verified by clients, not by this opaque relay.
     *
     * @param source the source supplied to this operation
     * @param p the p supplied to this operation
     * @param now the now supplied to this operation
     */
    private void control(Player source, StreamControl p, long now) {
        if (source.getName().equalsIgnoreCase(p.peer())) return;
        if (p.kind() == EXCHANGE) {
            Player target = plugin.getServer().getPlayerExact(p.peer());
            if (supports(target, CONTROL)) send(target, p, source.getName(), EXCHANGE, -1);
            return;
        }
        // ASSIGNED and ABORT belong to the relay; clients cannot inject them.
        if (p.kind() == ASSIGNED || p.kind() == ABORT || p.body().length != 32) return;
        if (p.kind() == OPEN) {
            if (p.slot() != -1) return;
            for (Route route : routes) if (route != null && route.open.id().equals(p.id())) return;
            Player target = plugin.getServer().getPlayerExact(p.peer());
            if (supports(source, CONTROL) && supports(target, CONTROL)) {
                for (int slot = 0; slot < routes.length; slot++) {
                    if (routes[slot] != null || !supports(source, dataChannels[slot].channel)
                            || !supports(target, dataChannels[slot].channel)) continue;
                    routes[slot] = new Route(source, target, p, now);
                    send(source, p, target.getName(), ASSIGNED, slot);
                    send(target, p, source.getName(), OPEN, slot);
                    return;
                }
            }
            send(source, p, p.peer(), ABORT, -1);
            return;
        }
        int slot = p.slot();
        if (slot < 0 || slot >= routes.length) return;
        Route route = routes[slot];
        if (route == null || !route.contains(source) || !route.open.id().equals(p.id())
                || !route.other(source).getName().equalsIgnoreCase(p.peer())
                || !route.open.channel().equals(p.channel()) || !route.open.sessionId().equals(p.sessionId())) return;
        Player target = route.other(source);
        if (!supports(target, CONTROL)) { release(slot); return; }
        switch (p.kind()) {
            case READY -> {
                if (source != route.target || route.ready) return;
                route.ready = true;
            }
            case END -> {
                if (!route.ready || route.ended(source)) return;
                if (source == route.source) route.sourceEnded = true;
                else route.targetEnded = true;
            }
            case RESET -> { }
            default -> { return; }
        }
        send(target, p, source.getName(), p.kind(), slot);
        route.used = now;
        if (p.kind() == RESET || route.sourceEnded && route.targetEnded) routes[slot] = null;
    }

    /**
     * Performs the supports operation for the opaque raw-stream relay.
     *
     * @param player the player supplied to this operation
     * @param channel the business or transport channel identifier
     * @return whether the condition or operation described above succeeds
     */
    private static boolean supports(Player player, String channel) {
        return player != null && player.isOnline() && player.getListeningPluginChannels().contains(channel);
    }

    /**
     * Submits the supplied data through the opaque raw-stream relay path. Local submission does not by
     * itself acknowledge remote receipt.
     *
     * @param target the target supplied to this operation
     * @param p the p supplied to this operation
     * @param peer the peer identifier associated with this operation
     * @param kind the kind supplied to this operation
     * @param slot the slot supplied to this operation
     */
    private void send(Player target, StreamControl p, String peer, StreamControl.Kind kind, int slot) {
        if (plugin.isEnabled() && supports(target, CONTROL))
            target.sendPluginMessage(plugin, CONTROL, p.encode(peer, kind, slot));
    }

    /**
     * Handles the quit callback for the opaque raw-stream relay.
     *
     * @param event the event supplied to this operation
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) { disconnect(event.getPlayer()); }

    /**
     * Handles the unregister channel callback for the opaque raw-stream relay.
     *
     * @param event the event supplied to this operation
     */
    @EventHandler
    public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
        if (CONTROL.equals(event.getChannel())) {
            disconnect(event.getPlayer());
            return;
        }
        for (int slot = 0; slot < routes.length; slot++) {
            Route route = routes[slot];
            if (route != null && route.contains(event.getPlayer())
                    && dataChannels[slot].channel.equals(event.getChannel())) release(slot);
        }
    }

    /**
     * Performs the disconnect operation for the opaque raw-stream relay.
     *
     * @param player the player supplied to this operation
     */
    private void disconnect(Player player) {
        for (int slot = 0; slot < routes.length; slot++) {
            if (routes[slot] != null && routes[slot].contains(player)) release(slot);
        }
    }

    /**
     * Expires inactive or incomplete routes according to their stored deadlines and releases their slots.
     * Ordinary raw-stream liveness is distinct from fixed application-level chat/file completion
     * deadlines.
     *
     * @param now the now supplied to this operation
     */
    void expire(long now) {
        for (int slot = 0; slot < routes.length; slot++) {
            Route route = routes[slot];
            if (route != null && (now - route.used >= 60000
                    || !supports(route.source, CONTROL) || !supports(route.target, CONTROL)
                    || !supports(route.source, dataChannels[slot].channel)
                    || !supports(route.target, dataChannels[slot].channel))) release(slot);
        }
    }

    /**
     * Releases the assigned slot and associated route/control state so stale route bindings cannot remain
     * available after close, abort or expiry.
     *
     * @param slot the slot supplied to this operation
     */
    private void release(int slot) {
        Route route = routes[slot];
        if (route == null) return;
        routes[slot] = null;
        send(route.source, route.open, route.target.getName(), ABORT, slot);
        send(route.target, route.open, route.source.getName(), ABORT, slot);
    }

    /**
     * Returns the now value used by the opaque raw-stream relay.
     *
     * @return the result described above
     */
    private static long now() { return System.nanoTime() / 1000000; }

    private static final class Route {
        final Player source, target;
        final StreamControl open;
        boolean ready, sourceEnded, targetEnded;
        long used;

        /**
         * Creates a route with the supplied dependencies and initial state.
         *
         * @param source the source supplied to this operation
         * @param target the target supplied to this operation
         * @param open the open supplied to this operation
         * @param now the now supplied to this operation
         */
        Route(Player source, Player target, StreamControl open, long now) {
            this.source = source; this.target = target; this.open = open; used = now;
        }

        /**
         * Performs the contains operation for the opaque raw-stream relay.
         *
         * @param player the player supplied to this operation
         * @return whether the condition or operation described above succeeds
         */
        boolean contains(Player player) { return player == source || player == target; }
        /**
         * Performs the other operation for the opaque raw-stream relay.
         *
         * @param player the player supplied to this operation
         * @return the result described above
         */
        Player other(Player player) { return player == source ? target : source; }
        /**
         * Performs the ended operation for the opaque raw-stream relay.
         *
         * @param player the player supplied to this operation
         * @return whether the condition or operation described above succeeds
         */
        boolean ended(Player player) { return player == source ? sourceEnded : targetEnded; }
    }
}
