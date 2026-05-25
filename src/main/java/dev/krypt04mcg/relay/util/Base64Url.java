package dev.krypt04mcg.relay.util;

import java.util.Base64;

public final class Base64Url {
    private Base64Url() {
    }

    public static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
