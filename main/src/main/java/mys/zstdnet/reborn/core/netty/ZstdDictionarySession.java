package mys.zstdnet.reborn.core.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;

import java.io.IOException;
import java.util.Objects;

/**
 * Negotiates dictionaries independently for the local outbound and inbound paths.
 * The original factories keep the protocol-v1 single-dictionary behavior.
 */
public final class ZstdDictionarySession {
    private static final int LEGACY_OFFER = 1;
    private static final int LEGACY_ACKNOWLEDGE = 2;
    private static final int DIRECTIONAL_OFFER = 3;
    private static final int DIRECTIONAL_ACKNOWLEDGE = 4;
    private static final int STREAM_RESET = 5;
    private static final int SERVER_TO_CLIENT = 0;
    private static final int CLIENT_TO_SERVER = 1;
    private static final int CONTROL_HEADER_BYTES = 13;

    private final Role role;
    private final ZstdDictionary outboundOffer;
    private final ZstdDictionary expectedInbound;
    private final DictionaryReceiver receiver;
    private final ZstdDictionaryDownloadListener downloadListener;
    private final boolean directional;

    private volatile ZstdDictionary activeOutbound;
    private volatile ZstdDictionary activeInbound;
    private boolean offerSent;
    private boolean inboundOfferSent;
    private long pendingAcknowledgement;
    private int pendingAcknowledgementDirection = -1;
    private boolean announcedDownload;
    private boolean inboundStreamReset;

    private ZstdDictionarySession(Role role, ZstdDictionary outboundOffer, ZstdDictionary expectedInbound,
                                  DictionaryReceiver receiver, ZstdDictionaryDownloadListener listener,
                                  boolean directional) {
        this.role = role;
        this.outboundOffer = outboundOffer;
        this.expectedInbound = expectedInbound;
        this.receiver = receiver;
        this.downloadListener = listener == null ? ZstdDictionaryDownloadListener.NONE : listener;
        this.directional = directional;
    }

    public static ZstdDictionarySession client(DictionaryReceiver receiver, ZstdDictionaryDownloadListener listener) {
        return new ZstdDictionarySession(Role.CLIENT, null, null,
                Objects.requireNonNull(receiver, "receiver"), listener, false);
    }

    /** Creates a directional client session; outbound is offered to the server. */
    public static ZstdDictionarySession client(DictionaryReceiver receiver, ZstdDictionaryDownloadListener listener,
                                               ZstdDictionary outbound) {
        return new ZstdDictionarySession(Role.CLIENT, outbound, null,
                Objects.requireNonNull(receiver, "receiver"), listener, true);
    }

    public static ZstdDictionarySession server(ZstdDictionary dictionary) {
        return new ZstdDictionarySession(Role.SERVER, dictionary, null, null,
                ZstdDictionaryDownloadListener.NONE, false);
    }

    public static ZstdDictionarySession server(ZstdDictionary dictionary, ZstdDictionaryDownloadListener listener) {
        return new ZstdDictionarySession(Role.SERVER, dictionary, null, null, listener, false);
    }

    /** Creates a directional server session; inbound is validated against the expected client dictionary. */
    public static ZstdDictionarySession server(ZstdDictionary outbound, ZstdDictionary inbound,
                                               ZstdDictionaryDownloadListener listener) {
        return new ZstdDictionarySession(Role.SERVER, outbound, inbound, null, listener, true);
    }

    public static ZstdDictionarySession withoutDictionary() {
        return new ZstdDictionarySession(Role.NONE, null, null, null,
                ZstdDictionaryDownloadListener.NONE, false);
    }

    public boolean expectsHeader() {
        return role == Role.CLIENT;
    }

    /** Compatibility accessor: the dictionary used for the local outbound path. */
    public ZstdDictionary activeDictionary() {
        return activeOutbound != null ? activeOutbound : activeInbound;
    }

    public ZstdDictionary activeOutboundDictionary() {
        return activeOutbound;
    }

    public ZstdDictionary activeInboundDictionary() {
        return activeInbound;
    }

    public synchronized byte[] pollOutboundControl() {
        if (outboundOffer != null && !offerSent) {
            if (directional && role == Role.CLIENT && outboundOffer.size() > ZstdDictionary.MAX_UPLINK_BYTES) {
                throw new IllegalStateException("uplink dictionary exceeds 64 KiB limit");
            }
            offerSent = true;
            downloadListener.started(outboundOffer.id(), outboundOffer.size());
            return directional ? offer(outboundOffer, role == Role.SERVER ? SERVER_TO_CLIENT : CLIENT_TO_SERVER)
                    : offer(outboundOffer);
        }
        if (directional && role == Role.SERVER && expectedInbound != null && !inboundOfferSent) {
            // The server does not send the client dictionary; it only accepts and ACKs it.
            inboundOfferSent = true;
        }
        if (pendingAcknowledgement != 0L) {
            long id = pendingAcknowledgement;
            int direction = pendingAcknowledgementDirection;
            pendingAcknowledgement = 0L;
            pendingAcknowledgementDirection = -1;
            return directional ? acknowledgement(id, direction) : acknowledgement(id);
        }
        return null;
    }

    public void observeControlProgress(ByteBuf in, int payloadStart, int payloadLength) {
        if (role != Role.CLIENT || in.writerIndex() <= payloadStart) return;
        int type = in.getUnsignedByte(payloadStart);
        if ((type != LEGACY_OFFER && type != DIRECTIONAL_OFFER)
                || payloadLength < CONTROL_HEADER_BYTES
                || in.writerIndex() - payloadStart < CONTROL_HEADER_BYTES) return;
        int header = type == DIRECTIONAL_OFFER ? CONTROL_HEADER_BYTES + 1 : CONTROL_HEADER_BYTES;
        long id = in.getLong(payloadStart + (type == DIRECTIONAL_OFFER ? 2 : 1));
        int dictionaryBytes = in.getInt(payloadStart + (type == DIRECTIONAL_OFFER ? 10 : 9));
        int maximum = role == Role.SERVER ? ZstdDictionary.MAX_UPLINK_BYTES : ZstdDictionary.MAX_DOWNLINK_BYTES;
        if (dictionaryBytes < ZstdDictionary.MIN_BYTES || dictionaryBytes > maximum) return;
        var downloaded = Math.clamp(dictionaryBytes, 0, in.writerIndex() - payloadStart - header);
        synchronized (this) {
            if (!announcedDownload) {
                announcedDownload = true;
                downloadListener.started(id, dictionaryBytes);
            }
        }
        downloadListener.progress(downloaded, dictionaryBytes);
    }

    public void receiveControl(byte[] payload) throws IOException {
        if (payload.length == 0) throw new IOException("empty ZstdNet dictionary control payload");
        int type = payload[0] & 0xFF;
        if (type == LEGACY_OFFER || type == DIRECTIONAL_OFFER) {
            receiveOffer(payload, type == DIRECTIONAL_OFFER);
        } else if (type == LEGACY_ACKNOWLEDGE || type == DIRECTIONAL_ACKNOWLEDGE) {
            receiveAcknowledgement(payload, type == DIRECTIONAL_ACKNOWLEDGE);
        } else if (type == STREAM_RESET) {
            if (payload.length != 1) throw new IOException("invalid ZstdNet stream reset control record");
            synchronized (this) {
                inboundStreamReset = true;
            }
        } else {
            throw new IOException("unknown ZstdNet dictionary control record: " + type);
        }
    }

    private void receiveOffer(byte[] payload, boolean directionalRecord) throws IOException {
        if (directionalRecord != directional) throw new IOException("dictionary direction mode mismatch");
        if (!directionalRecord && role != Role.CLIENT) {
            throw new IOException("unexpected legacy dictionary offer");
        }
        if (activeInbound != null) {
            throw new IOException("unexpected duplicate ZstdNet dictionary offer");
        }
        if (payload.length < CONTROL_HEADER_BYTES + (directionalRecord ? 1 : 0)) {
            throw new IOException("invalid ZstdNet dictionary offer");
        }
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        try {
            in.readUnsignedByte();
            int direction = directionalRecord ? in.readUnsignedByte() : SERVER_TO_CLIENT;
            if (directionalRecord && direction != (role == Role.SERVER ? CLIENT_TO_SERVER : SERVER_TO_CLIENT)) {
                throw new IOException("unexpected ZstdNet dictionary offer direction");
            }
            long id = in.readLong();
            int size = in.readInt();
            int maximum = role == Role.SERVER ? ZstdDictionary.MAX_UPLINK_BYTES : ZstdDictionary.MAX_DOWNLINK_BYTES;
            if (size < ZstdDictionary.MIN_BYTES || size > maximum || in.readableBytes() != size) {
                throw new IOException("invalid ZstdNet dictionary offer size");
            }
            if (!announcedDownload) {
                announcedDownload = true;
                downloadListener.started(id, size);
            }
            downloadListener.progress(size, size);
            byte[] bytes = new byte[size];
            in.readBytes(bytes);
            ZstdDictionary dictionary;
            if (role == Role.CLIENT) {
                dictionary = receiver.receive(id, bytes);
            } else {
                if (expectedInbound == null || expectedInbound.id() != id) {
                    throw new IOException("client dictionary id does not match server inbound dictionary");
                }
                dictionary = expectedInbound;
            }
            if (dictionary.id() != id) throw new IOException("received dictionary id does not match offer");
            synchronized (this) {
                activeInbound = dictionary;
                if (!directionalRecord) activeOutbound = dictionary;
                pendingAcknowledgement = dictionary.id();
                pendingAcknowledgementDirection = direction;
            }
            downloadListener.completed(id);
        } catch (IOException e) {
            downloadListener.failed(e.getMessage());
            throw e;
        } finally {
            in.release();
        }
    }

    private synchronized void receiveAcknowledgement(byte[] payload, boolean directionalRecord) throws IOException {
        if (directionalRecord != directional || outboundOffer == null || !offerSent || activeOutbound != null) {
            throw new IOException("unexpected ZstdNet dictionary acknowledgement");
        }
        int expected = directionalRecord ? 10 : 9;
        if (payload.length != expected) throw new IOException("invalid ZstdNet dictionary acknowledgement");
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        try {
            in.readUnsignedByte();
            int direction = directionalRecord ? in.readUnsignedByte() : SERVER_TO_CLIENT;
            int expectedDirection = role == Role.SERVER ? SERVER_TO_CLIENT : CLIENT_TO_SERVER;
            if (direction != expectedDirection) throw new IOException("unexpected dictionary acknowledgement direction");
            long id = in.readLong();
            if (id != outboundOffer.id()) throw new IOException("ZstdNet dictionary acknowledgement does not match offer");
            activeOutbound = outboundOffer;
            if (!directionalRecord) activeInbound = outboundOffer;
            downloadListener.completed(id);
        } finally {
            in.release();
        }
    }

    private static byte[] offer(ZstdDictionary dictionary) {
        return offer(dictionary, SERVER_TO_CLIENT, false);
    }

    private static byte[] offer(ZstdDictionary dictionary, int direction) {
        return offer(dictionary, direction, true);
    }

    private static byte[] offer(ZstdDictionary dictionary, int direction, boolean directional) {
        byte[] bytes = dictionary.bytes();
        ByteBuf out = Unpooled.buffer(CONTROL_HEADER_BYTES + bytes.length + (directional ? 1 : 0));
        try {
            out.writeByte(directional ? DIRECTIONAL_OFFER : LEGACY_OFFER);
            if (directional) out.writeByte(direction);
            out.writeLong(dictionary.id());
            out.writeInt(bytes.length);
            out.writeBytes(bytes);
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally { out.release(); }
    }

    private static byte[] acknowledgement(long id) { return acknowledgement(id, SERVER_TO_CLIENT, false); }

    private static byte[] acknowledgement(long id, int direction) { return acknowledgement(id, direction, true); }

    private static byte[] acknowledgement(long id, int direction, boolean directional) {
        ByteBuf out = Unpooled.buffer(directional ? 10 : 9);
        try {
            out.writeByte(directional ? DIRECTIONAL_ACKNOWLEDGE : LEGACY_ACKNOWLEDGE);
            if (directional) out.writeByte(direction);
            out.writeLong(id);
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally { out.release(); }
    }

    @FunctionalInterface
    public interface DictionaryReceiver {
        ZstdDictionary receive(long expectedId, byte[] bytes) throws IOException;
    }

    public synchronized boolean hasPendingControl() {
        return outboundOffer != null && !offerSent || pendingAcknowledgement != 0L;
    }

    public static byte[] streamResetControl() {
        return new byte[]{STREAM_RESET};
    }

    public synchronized boolean consumeInboundStreamReset() {
        var reset = inboundStreamReset;
        inboundStreamReset = false;
        return reset;
    }

    public void disconnected() {
        if (role == Role.CLIENT && announcedDownload && activeInbound == null) {
            downloadListener.failed("Dictionary download interrupted");
        }
    }

    private enum Role { NONE, CLIENT, SERVER }
}
