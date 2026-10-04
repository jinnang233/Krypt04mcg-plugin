package dev.krypt04mcg.relay;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private final RelayTrafficLimiter traffic = new RelayTrafficLimiter();
    private BukkitTask cleanup;

    RawStreamRelay(Plugin plugin, int count) {
        this.plugin = plugin;
        routes = new Route[Math.clamp(count, 1, 256)];
    }

    void register() {
        for (int slot = -1; slot < routes.length; slot++) {
            String channel = slot < 0 ? CONTROL : DATA_PREFIX + slot;
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, this);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        cleanup = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> expire(now()), 20, 20);
    }

    void unregister() {
        if (cleanup != null) cleanup.cancel();
        for (int slot = 0; slot < routes.length; slot++) release(slot);
        HandlerList.unregisterAll(this);
        for (int slot = -1; slot < routes.length; slot++) {
            String channel = slot < 0 ? CONTROL : DATA_PREFIX + slot;
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
        }
        traffic.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] bytes) {
        long now = now();
        if (CONTROL.equals(channel)) {
            if (bytes.length > 31104 || !traffic.receive(source.getUniqueId(), bytes.length, now)) {
                disconnect(source);
                return;
            }
            try { control(source, StreamControl.decode(bytes), now); }
            catch (IllegalArgumentException e) { plugin.getLogger().fine("Rejected malformed stream control"); }
            return;
        }
        if (!channel.startsWith(DATA_PREFIX)) return;
        int slot;
        try { slot = Integer.parseInt(channel.substring(DATA_PREFIX.length())); }
        catch (NumberFormatException e) { return; }
        if (slot < 0 || slot >= routes.length || !channel.equals(DATA_PREFIX + slot)) return;
        Route route = routes[slot];
        if (route == null || !route.contains(source) || !route.ready || route.ended(source)) return;
        Player target = route.other(source);
        if (bytes.length < 16 || bytes.length > 16400 || !supports(target, channel)
                || !traffic.receive(source.getUniqueId(), bytes.length, now)
                || !traffic.forward(source.getUniqueId(), bytes.length, now)) {
            release(slot); // Losing a record must fail the stream, never masquerade as EOF.
            return;
        }
        target.sendPluginMessage(plugin, channel, bytes);
        route.used = now;
    }

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
                    if (routes[slot] != null || !supports(source, DATA_PREFIX + slot)
                            || !supports(target, DATA_PREFIX + slot)) continue;
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

    private static boolean supports(Player player, String channel) {
        return player != null && player.isOnline() && player.getListeningPluginChannels().contains(channel);
    }

    private void send(Player target, StreamControl p, String peer, StreamControl.Kind kind, int slot) {
        if (plugin.isEnabled() && supports(target, CONTROL))
            target.sendPluginMessage(plugin, CONTROL, p.encode(peer, kind, slot));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { disconnect(event.getPlayer()); }

    private void disconnect(Player player) {
        for (int slot = 0; slot < routes.length; slot++) {
            if (routes[slot] != null && routes[slot].contains(player)) release(slot);
        }
    }

    void expire(long now) {
        for (int slot = 0; slot < routes.length; slot++) {
            Route route = routes[slot];
            if (route != null && (now - route.used >= 60000
                    || !supports(route.source, CONTROL) || !supports(route.target, CONTROL)
                    || !supports(route.source, DATA_PREFIX + slot) || !supports(route.target, DATA_PREFIX + slot))) release(slot);
        }
    }

    private void release(int slot) {
        Route route = routes[slot];
        if (route == null) return;
        routes[slot] = null;
        send(route.source, route.open, route.target.getName(), ABORT, slot);
        send(route.target, route.open, route.source.getName(), ABORT, slot);
    }

    private static long now() { return System.nanoTime() / 1000000; }

    private static final class Route {
        final Player source, target;
        final StreamControl open;
        boolean ready, sourceEnded, targetEnded;
        long used;

        Route(Player source, Player target, StreamControl open, long now) {
            this.source = source; this.target = target; this.open = open; used = now;
        }

        boolean contains(Player player) { return player == source || player == target; }
        Player other(Player player) { return player == source ? target : source; }
        boolean ended(Player player) { return player == source ? sourceEnded : targetEnded; }
    }
}
