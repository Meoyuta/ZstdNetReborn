package mys.zstdnet.reborn.core.netty;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

public final class ZstdStreamHeader {
    public static final int PROTOCOL_VERSION = 2;
    public static final int BYTES = 1;

    private ZstdStreamHeader() {
    }

    public static void write(ByteBuf out) {
        out.writeByte(PROTOCOL_VERSION);
    }

    public static boolean read(ByteBuf in) throws IOException {
        if (in.readableBytes() < BYTES) {
            return false;
        }
        int version = in.readUnsignedByte();
        if (version != PROTOCOL_VERSION) {
            throw new IOException("unsupported ZstdNet protocol version: " + version);
        }
        return true;
    }
}
