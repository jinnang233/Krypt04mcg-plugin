package dev.krypt04mcg.relay.util;

import java.util.Base64;

public final class Base64Url {
    /**
     * Prevents direct instantiation of this stateless utility.
     */
    private Base64Url() {
    }

    /**
     * Decodes the supplied input using the format expected by the Base64URL encoding.
     *
     * @param value the value supplied to this operation
     * @return the resulting array produced by this operation
     */
    public static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
