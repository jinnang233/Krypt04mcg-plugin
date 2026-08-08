package dev.krypt04mcg.relay.fragment;

import dev.krypt04mcg.relay.model.Fragment;

import java.util.Optional;
import java.util.regex.Pattern;

public final class FragmentService {
    public static final String PREFIX = "[KRYPT04MCG]";
    public static final int MAX_CHAT_MESSAGE_LENGTH = 256;
    private static final Pattern HEX_16_BYTES = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+={0,2}");

    public Optional<String> extractFragmentLine(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int index = raw.indexOf(PREFIX);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(raw.substring(index).trim());
    }

    public Fragment parse(String message) {
        if (message == null || !message.startsWith(PREFIX + " ")) {
            throw new IllegalArgumentException("not a Krypt04Mcg fragment");
        }
        if (message.length() > MAX_CHAT_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("fragment exceeds Minecraft chat limit");
        }
        String[] parts = message.split(" ", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException("malformed fragment");
        }
        if (!HEX_16_BYTES.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException("invalid message id");
        }
        int index = parseInt(parts[2], "fragment index");
        int total = parseInt(parts[3], "fragment total");
        if (index < 0 || total <= 0 || index >= total) {
            throw new IllegalArgumentException("invalid fragment index " + index + "/" + total);
        }
        if (!BASE64_URL.matcher(parts[4]).matches()) {
            throw new IllegalArgumentException("invalid fragment payload");
        }
        return new Fragment(parts[1].toLowerCase(), index, total, parts[4]);
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + field, e);
        }
    }
}
