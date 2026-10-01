package mys.zstdnet.reborn.core.netty;

public interface ZstdFrameStats {
    ZstdFrameStats NONE = new ZstdFrameStats() {
        @Override
        public void inbound(long rawBytes, long wireBytes) {
        }

        @Override
        public void outbound(long rawBytes, long wireBytes) {
        }
    };

    void inbound(long rawBytes, long wireBytes);

    void outbound(long rawBytes, long wireBytes);

    default void inboundSample(byte[] raw) {
    }

    default void outboundSample(byte[] raw) {
    }
}
