package mys.zstdnet.reborn.core.netty;
import mys.zstdnet.reborn.core.dictionary.DictionaryFixtures;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DictionarySyncTest {
    @Test void fragmentedDownloadAcknowledgesWithoutGameTrafficAndRoundTrips() throws Exception {
        var dictionary = DictionaryFixtures.dictionary();
        var serverSession = ZstdDictionarySession.server(dictionary);
        var progress = new AtomicInteger();
        var completed = new AtomicInteger();
        var clientSession = ZstdDictionarySession.client((id, bytes) -> ZstdDictionary.fromBytes(bytes),
            new ZstdDictionaryDownloadListener() {
                public void started(long id, int total) {}
                public void progress(int received, int total) { progress.incrementAndGet(); }
                public void completed(long id) { completed.incrementAndGet(); }
                public void failed(String message) { fail(message); }
            });
        var server = channel(serverSession);
        var client = channel(clientSession);
        try {
            var raw = DictionaryFixtures.samples()[0];
            server.writeOutbound(Unpooled.wrappedBuffer(raw));
            server.checkException();
            ByteBuf wire = awaitOutbound(server);
            try {
                while (wire.isReadable()) {
                    client.writeInbound(wire.readRetainedSlice(Math.min(97, wire.readableBytes())));
                    client.checkException();
                }
            } finally { wire.release(); }
            assertTrue(progress.get() > 1);
            assertEquals(1, completed.get());
            assertNull(serverSession.activeDictionary());
            assertNotNull(clientSession.activeDictionary());
            assertPayload(client, raw);
            ByteBuf ack = awaitOutbound(client);
            assertTrue(ZstdStreamHeader.read(ack)); // Same-port handler consumes the first client header.
            server.writeInbound(ack);
            server.checkException();
            assertSame(dictionary, serverSession.activeDictionary());
            server.writeOutbound(Unpooled.wrappedBuffer(raw));
            server.checkException();
            client.writeInbound(awaitOutbound(server));
            client.checkException();
            assertPayload(client, raw);
            client.writeOutbound(Unpooled.wrappedBuffer(raw));
            client.checkException();
            server.writeInbound(awaitOutbound(client));
            server.checkException();
            assertPayload(server, raw);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    @Test void noServerDictionaryNeverActivatesLocalDictionary() throws Exception {
        var server = channel(ZstdDictionarySession.server(null));
        var session = ZstdDictionarySession.client((id, bytes) -> { fail("No dictionary was offered"); return null; }, null);
        var client = channel(session);
        try {
            server.writeOutbound(Unpooled.wrappedBuffer(new byte[]{1,2,3}));
            server.checkException();
            client.writeInbound(awaitOutbound(server));
            client.checkException();
            assertPayload(client, new byte[]{1,2,3});
            assertNull(session.activeDictionary());
            assertNull(client.readOutbound());
        } finally { server.finishAndReleaseAll(); client.finishAndReleaseAll(); }
    }

    @Test void rejectsWrongIdDuplicateOfferAndEarlyAck() throws Exception {
        var dictionary = DictionaryFixtures.dictionary();
        var server = ZstdDictionarySession.server(dictionary);
        var client = ZstdDictionarySession.client((id, bytes) -> ZstdDictionary.fromBytes(bytes), null);
            var offer = pollControl(server);
            var wrong = offer.copy();
            wrong.setByte(wrong.readerIndex() + 8, wrong.getUnsignedByte(wrong.readerIndex() + 8) ^ 1);
            assertThrows(java.io.IOException.class, () -> client.receiveControl(wrong));
            wrong.release();
            assertNull(client.activeDictionary());
            client.receiveControl(offer);
            var duplicate = offer.copy();
            offer.release();
            assertThrows(java.io.IOException.class, () -> client.receiveControl(duplicate));
            duplicate.release();
            var ack = pollControl(client);
            var unopened = ZstdDictionarySession.server(dictionary);
            assertThrows(java.io.IOException.class, () -> unopened.receiveControl(ack));
            server.receiveControl(ack);
            var duplicateAck = ack.copy();
            ack.release();
            assertThrows(java.io.IOException.class, () -> server.receiveControl(duplicateAck));
            duplicateAck.release();
    }

    @Test void directionalSessionNegotiatesIndependentPaths() throws Exception {
        var serverOutbound = DictionaryFixtures.dictionary();
        var clientOutbound = DictionaryFixtures.dictionary();
        var server = ZstdDictionarySession.server(serverOutbound, clientOutbound, null);
        var client = ZstdDictionarySession.client((id, bytes) -> ZstdDictionary.fromBytes(bytes), null, clientOutbound);

        sendControl(server, client);
        sendControl(client, server);
        sendControl(client, server);
        sendControl(server, client);

        assertSame(serverOutbound, server.activeOutboundDictionary());
        assertNotNull(server.activeInboundDictionary());
        assertNotNull(client.activeOutboundDictionary());
        assertNotNull(client.activeInboundDictionary());
    }

    @Test void mismatchedClientDictionaryFallsBackToUncompressedUplink() throws Exception {
        var serverDictionary = DictionaryFixtures.dictionary();
        var clientDictionary = DictionaryFixtures.dictionary();
        // A server without an inbound dictionary exercises the rejection protocol.
        var rejectingServer = ZstdDictionarySession.server(serverDictionary, null, null);
        var retry = ZstdDictionarySession.client((id, bytes) -> ZstdDictionary.fromBytes(bytes), null, clientDictionary);
        // Complete the server's own downlink offer first; the following control
        // record must then be the ACK for the rejected client offer.
        var downlink = pollControl(rejectingServer);
        downlink.release();
        var retryOffer = pollControl(retry);
        try {
            rejectingServer.receiveControl(retryOffer);
        } finally {
            retryOffer.release();
        }
        var rejection = Unpooled.buffer();
        assertTrue(rejectingServer.writeOutboundControl(rejection));
        assertEquals(0, readVarInt(rejection));
        var storedTag = readVarInt(rejection);
        var payload = rejection.readRetainedSlice(storedTag >>> 1);
        rejection.release();
        try {
            retry.receiveControl(payload);
            assertNull(retry.activeOutboundDictionary());
        } finally {
            payload.release();
        }
    }

    private static EmbeddedChannel channel(ZstdDictionarySession session) {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast(ZstdNettyPipeline.OUTBOUND_HANDLER, new ZstdNettyEncoder(3, false, null, session));
        channel.pipeline().addLast(ZstdNettyPipeline.INBOUND_HANDLER, new ZstdNettyDecoder(null, session));
        return channel;
    }

    private static void assertPayload(EmbeddedChannel channel, byte[] expected) {
        ByteBuf actual = channel.readInbound();
        assertNotNull(actual);
        try {
            var bytes = new byte[actual.readableBytes()];
            actual.readBytes(bytes);
            assertArrayEquals(expected, bytes);
        } finally { actual.release(); }
    }

    private static ByteBuf awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        ByteBuf result;
        while ((result = channel.readOutbound()) == null && System.nanoTime() < deadline) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            Thread.sleep(1);
        }
        assertNotNull(result, "asynchronous compressed output did not arrive");
        return result;
    }

    private static void sendControl(ZstdDictionarySession sender, ZstdDictionarySession receiver) throws Exception {
        var frame = pollControl(sender);
        try {
            receiver.receiveControl(frame);
        } finally {
            frame.release();
        }
    }

    private static ByteBuf pollControl(ZstdDictionarySession session) {
        var frame = Unpooled.buffer();
        assertTrue(session.writeOutboundControl(frame));
        assertEquals(0, readVarInt(frame));
        var storedTag = readVarInt(frame);
        assertTrue(storedTag > 0 && (storedTag & 1) == 0);
        var payload = frame.readRetainedSlice(storedTag >>> 1);
        frame.release();
        return payload;
    }

    private static int readVarInt(ByteBuf in) {
        var value = 0;
        var shift = 0;
        for (var i = 0; i < 5; i++) {
            var next = in.readUnsignedByte();
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
        throw new AssertionError("varint too large");
    }
}
