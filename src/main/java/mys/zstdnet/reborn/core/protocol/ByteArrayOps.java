package mys.zstdnet.reborn.core.protocol;

import java.util.Arrays;

public final class ByteArrayOps {
    private ByteArrayOps() {
    }

    public static byte[] concat(byte[]... arrays) {
        var total = 0;
        for (var array : arrays) {
            total += array.length;
        }
        var out = new byte[total];
        var offset = 0;
        for (var array : arrays) {
            System.arraycopy(array, 0, out, offset, array.length);
            offset += array.length;
        }
        return out;
    }

    public static byte[] slice(byte[] data, int start, int end) {
        return Arrays.copyOfRange(data, start, end);
    }
}
