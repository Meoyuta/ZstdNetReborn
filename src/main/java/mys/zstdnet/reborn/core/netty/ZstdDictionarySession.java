package mys.zstdnet.reborn.core.netty;

import io.netty.buffer.ByteBuf;
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
    private static final int DIRECTIONAL_REJECT = 6;
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
    private int pendingAcknowledgementType = DIRECTIONAL_ACKNOWLEDGE;
    private boolean acknowledgementPending;
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

    /** Writes one complete raw control frame directly to {@code out}. */
    public synchronized boolean writeOutboundControl(ByteBuf out) {
        if (outboundOffer != null && !offerSent) {
            if (directional && role == Role.CLIENT && outboundOffer.size() > ZstdDictionary.MAX_UPLINK_BYTES) {
                throw new IllegalStateException("uplink dictionary exceeds 64 KiB limit");
            }
            offerSent = true;
            downloadListener.started(outboundOffer.id(), outboundOffer.size());
            var direction = role == Role.SERVER ? SERVER_TO_CLIENT : CLIENT_TO_SERVER;
            var payloadLength = outboundOffer.size() + CONTROL_HEADER_BYTES + (directional ? 1 : 0);
            writeVarInt(out, 0);
            writeVarInt(out, payloadLength << 1);
            writeOfferPayload(out, outboundOffer, direction, directional);
            return true;
        }
        if (directional && role == Role.SERVER && expectedInbound != null && !inboundOfferSent) {
            // The server does not send the client dictionary; it only accepts and ACKs it.
            inboundOfferSent = true;
        }
        if (acknowledgementPending) {
            long id = pendingAcknowledgement;
            int direction = pendingAcknowledgementDirection;
            pendingAcknowledgement = 0L;
            pendingAcknowledgementDirection = -1;
            int acknowledgementType = pendingAcknowledgementType;
            pendingAcknowledgementType = DIRECTIONAL_ACKNOWLEDGE;
            acknowledgementPending = false;
            var payloadLength = directional ? 10 : 9;
            writeVarInt(out, 0);
            writeVarInt(out, payloadLength << 1);
            writeAcknowledgementPayload(out, id, direction, directional, acknowledgementType);
            return true;
        }
        return false;
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

    public void receiveControl(ByteBuf payload) throws IOException {
        if (payload == null || !payload.isReadable()) throw new IOException("empty ZstdNet dictionary control payload");
        var in = payload.duplicate();
        int type = in.getUnsignedByte(in.readerIndex());
        if (type == LEGACY_OFFER || type == DIRECTIONAL_OFFER) {
            receiveOffer(in, type == DIRECTIONAL_OFFER);
        } else if (type == LEGACY_ACKNOWLEDGE || type == DIRECTIONAL_ACKNOWLEDGE || type == DIRECTIONAL_REJECT) {
            receiveAcknowledgement(in, type == DIRECTIONAL_ACKNOWLEDGE || type == DIRECTIONAL_REJECT,
                type == DIRECTIONAL_REJECT);
        } else if (type == STREAM_RESET) {
            if (in.readableBytes() != 1) throw new IOException("invalid ZstdNet stream reset control record");
            synchronized (this) {
                inboundStreamReset = true;
            }
        } else {
            throw new IOException("unknown ZstdNet dictionary control record: " + type);
        }
    }

    private void receiveOffer(ByteBuf in, boolean directionalRecord) throws IOException {
        if (directionalRecord != directional) throw new IOException("dictionary direction mode mismatch");
        if (!directionalRecord && role != Role.CLIENT) {
            throw new IOException("unexpected legacy dictionary offer");
        }
        if (activeInbound != null) {
            throw new IOException("unexpected duplicate ZstdNet dictionary offer");
        }
        if (in.readableBytes() < CONTROL_HEADER_BYTES + (directionalRecord ? 1 : 0)) {
            throw new IOException("invalid ZstdNet dictionary offer");
        }
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
                    synchronized (this) {
                        pendingAcknowledgement = id;
                        pendingAcknowledgementDirection = direction;
                        pendingAcknowledgementType = DIRECTIONAL_REJECT;
                        acknowledgementPending = true;
                    }
                    downloadListener.failed("client dictionary id does not match server inbound dictionary; continuing without uplink dictionary");
                    return;
                }
                dictionary = expectedInbound;
            }
            if (dictionary.id() != id) throw new IOException("received dictionary id does not match offer");
            synchronized (this) {
                activeInbound = dictionary;
                if (!directionalRecord) activeOutbound = dictionary;
                pendingAcknowledgement = dictionary.id();
                pendingAcknowledgementDirection = direction;
                pendingAcknowledgementType = directionalRecord ? DIRECTIONAL_ACKNOWLEDGE : LEGACY_ACKNOWLEDGE;
                acknowledgementPending = true;
            }
            downloadListener.completed(id);
        } catch (IOException e) {
            downloadListener.failed(e.getMessage());
            throw e;
        }
    }

    private synchronized void receiveAcknowledgement(ByteBuf in, boolean directionalRecord, boolean rejected) throws IOException {
        if (directionalRecord != directional || outboundOffer == null || !offerSent || activeOutbound != null) {
            throw new IOException("unexpected ZstdNet dictionary acknowledgement");
        }
        int expected = directionalRecord ? 10 : 9;
        if (in.readableBytes() != expected) throw new IOException("invalid ZstdNet dictionary acknowledgement");
        {
            in.readUnsignedByte();
            int direction = directionalRecord ? in.readUnsignedByte() : SERVER_TO_CLIENT;
            int expectedDirection = role == Role.SERVER ? SERVER_TO_CLIENT : CLIENT_TO_SERVER;
            if (direction != expectedDirection) throw new IOException("unexpected dictionary acknowledgement direction");
            long id = in.readLong();
            if (rejected) {
                if (!directionalRecord || role != Role.CLIENT || id != outboundOffer.id()) {
                    throw new IOException("invalid ZstdNet dictionary rejection");
                }
                activeOutbound = null;
                downloadListener.failed("server rejected uplink dictionary; continuing without uplink dictionary");
                return;
            }
            if (id != outboundOffer.id()) throw new IOException("ZstdNet dictionary acknowledgement does not match offer");
            activeOutbound = outboundOffer;
            if (!directionalRecord) activeInbound = outboundOffer;
            downloadListener.completed(id);
        }
    }

    private static void writeOfferPayload(ByteBuf out, ZstdDictionary dictionary, int direction, boolean directional) {
        byte[] bytes = dictionary.bytes();
        out.writeByte(directional ? DIRECTIONAL_OFFER : LEGACY_OFFER);
        if (directional) out.writeByte(direction);
        out.writeLong(dictionary.id());
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeAcknowledgementPayload(ByteBuf out, long id, int direction, boolean directional, int type) {
        out.writeByte(type);
        if (directional) out.writeByte(direction);
        out.writeLong(id);
    }

    @FunctionalInterface
    public interface DictionaryReceiver {
        ZstdDictionary receive(long expectedId, byte[] bytes) throws IOException;
    }

    public synchronized boolean hasPendingControl() {
        return outboundOffer != null && !offerSent || acknowledgementPending;
    }

    public static void writeStreamResetControl(ByteBuf out) {
        writeVarInt(out, 0);
        writeVarInt(out, 2);
        out.writeByte(STREAM_RESET);
    }

    public synchronized boolean consumeInboundStreamReset() {
        var reset = inboundStreamReset;
        inboundStreamReset = false;
        return reset;
    }

    private static void writeVarInt(ByteBuf out, int value) {
        var remaining = value;
        do {
            var next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) next |= 0x80;
            out.writeByte(next);
        } while (remaining != 0);
    }

    public void disconnected() {
        if (role == Role.CLIENT && announcedDownload && activeInbound == null) {
            downloadListener.failed("Dictionary download interrupted");
        }
    }

    private enum Role { NONE, CLIENT, SERVER }
}
