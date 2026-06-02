package dev.krypt04mcg.relay;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class ChatSpamKickController {
    private static final int CHAT_SPAM_INCREMENT = 20;

    private final Logger logger;
    private boolean warnedUnavailable;

    ChatSpamKickController(Logger logger) {
        this.logger = logger;
    }

    void ignoreCurrentKrypt04McgMessage(Player player) {
        try {
            Object handle = getHandle(player);
            Object connection = getConnection(handle);
            if (connection == null) {
                warnUnavailable();
                return;
            }
            if (!reduceKnownSpamCounter(connection)) {
                warnUnavailable();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable();
        }
    }

    private static Object getHandle(Player player) throws ReflectiveOperationException {
        Method getHandle = player.getClass().getMethod("getHandle");
        return getHandle.invoke(player);
    }

    private static Object getConnection(Object handle) throws IllegalAccessException {
        for (Field field : handle.getClass().getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            String typeName = field.getType().getName().toLowerCase(Locale.ROOT);
            if (name.equals("connection") || name.equals("playerconnection")
                    || typeName.contains("servergamepacketlistenerimpl")
                    || typeName.contains("playerconnection")) {
                field.setAccessible(true);
                Object value = field.get(handle);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean reduceKnownSpamCounter(Object connection) throws IllegalAccessException {
        for (Field field : connection.getClass().getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("chatspamtickcount") && !name.equals("chatthrottle")) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(connection);
            if (value instanceof AtomicInteger counter) {
                counter.updateAndGet(current -> Math.max(0, current - CHAT_SPAM_INCREMENT));
                return true;
            }
            if (field.getType() == int.class) {
                field.setInt(connection, Math.max(0, field.getInt(connection) - CHAT_SPAM_INCREMENT));
                return true;
            }
        }
        return false;
    }

    private void warnUnavailable() {
        if (warnedUnavailable) {
            return;
        }
        warnedUnavailable = true;
        logger.warning("Unable to hook the Minecraft chat spam counter; Krypt04Mcg spam-kick suppression is unavailable on this server build.");
    }
}
