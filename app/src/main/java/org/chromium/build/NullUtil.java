package org.chromium.build;

public class NullUtil {
    private NullUtil() {}

    public static <T> T assertNonNull(T object) {
        assert object != null;
        return object;
    }

    public static <T> T assumeNonNull(T object) {
        return object;
    }
}
