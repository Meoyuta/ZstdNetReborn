package mys.zstdnet.reborn.core.dictionary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Packs the two directional dictionaries into one portable compressed file. */
public final class ZstdDictionaryBundle {
    public static final String UPLINK_ENTRY = "uplink.zdict";
    public static final String DOWNLINK_ENTRY = "downlink.zdict";
    public static final int MAX_BUNDLE_BYTES = ZstdDictionary.MAX_UPLINK_BYTES
            + ZstdDictionary.MAX_DOWNLINK_BYTES + 64 * 1024;

    private ZstdDictionaryBundle() {
    }

    public static byte[] pack(byte[] uplink, byte[] downlink) throws IOException {
        Objects.requireNonNull(uplink, "uplink");
        Objects.requireNonNull(downlink, "downlink");
        ZstdDictionary.fromBytes(uplink, ZstdDictionary.MAX_UPLINK_BYTES);
        ZstdDictionary.fromBytes(downlink, ZstdDictionary.MAX_DOWNLINK_BYTES);
        var output = new ByteArrayOutputStream(uplink.length + downlink.length);
        try (var zip = new ZipOutputStream(output)) {
            write(zip, UPLINK_ENTRY, uplink);
            write(zip, DOWNLINK_ENTRY, downlink);
        }
        return output.toByteArray();
    }

    public static boolean isBundle(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && (bytes[0] & 0xff) == 0x50 && (bytes[1] & 0xff) == 0x4b
                && (bytes[2] & 0xff) == 0x03 && (bytes[3] & 0xff) == 0x04;
    }

    public static Contents unpack(byte[] bytes) throws IOException {
        if (!isBundle(bytes) || bytes.length > MAX_BUNDLE_BYTES) {
            throw new IOException("invalid dictionary bundle");
        }
        byte[] uplink = null;
        byte[] downlink = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!UPLINK_ENTRY.equals(entry.getName()) && !DOWNLINK_ENTRY.equals(entry.getName()))
                    throw new IOException("unexpected dictionary bundle entry");
                var limit = UPLINK_ENTRY.equals(entry.getName())
                        ? ZstdDictionary.MAX_UPLINK_BYTES : ZstdDictionary.MAX_DOWNLINK_BYTES;
                var data = zip.readNBytes(limit + 1);
                if (data.length > limit) throw new IOException("dictionary bundle entry is too large");
                if (UPLINK_ENTRY.equals(entry.getName())) uplink = data;
                else downlink = data;
            }
        }
        if (uplink == null || downlink == null) throw new IOException("dictionary bundle is missing a direction");
        ZstdDictionary.fromBytes(uplink, ZstdDictionary.MAX_UPLINK_BYTES);
        ZstdDictionary.fromBytes(downlink, ZstdDictionary.MAX_DOWNLINK_BYTES);
        return new Contents(uplink, downlink);
    }

    private static void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    public record Contents(byte[] uplink, byte[] downlink) {
        public Contents {
            uplink = Arrays.copyOf(uplink, uplink.length);
            downlink = Arrays.copyOf(downlink, downlink.length);
        }
    }
}
