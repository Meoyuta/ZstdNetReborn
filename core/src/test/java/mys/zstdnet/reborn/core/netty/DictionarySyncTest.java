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
            ByteBuf wire = server.readOutbound();
            try {
                while (wire.isReadable()) client.writeInbound(wire.readRetainedSlice(Math.min(97, wire.readableBytes())));
            } finally { wire.release(); }
            assertTrue(progress.get() > 1);
            assertEquals(1, completed.get());
            assertNull(serverSession.activeDictionary());
            assertNotNull(clientSession.activeDictionary());
            assertPayload(client, raw);
            ByteBuf ack = client.readOutbound();
            assertNotNull(ack, "ACK must not wait for a game packet");
            assertTrue(ZstdStreamHeader.read(ack)); // Same-port handler consumes the first client header.
            server.writeInbound(ack);
            assertSame(dictionary, serverSession.activeDictionary());
            server.writeOutbound(Unpooled.wrappedBuffer(raw));
            client.writeInbound((ByteBuf) server.readOutbound());
            assertPayload(client, raw);
            client.writeOutbound(Unpooled.wrappedBuffer(raw));
            server.writeInbound((ByteBuf) client.readOutbound());
            assertPayload(server, raw);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    @Test void noServerDictionaryNeverActivatesLocalDictionary() {
        var server = channel(ZstdDictionarySession.server(null));
        var session = ZstdDictionarySession.client((id, bytes) -> { fail("No dictionary was offered"); return null; }, null);
        var client = channel(session);
        try {
            server.writeOutbound(Unpooled.wrappedBuffer(new byte[]{1,2,3}));
            client.writeInbound((ByteBuf) server.readOutbound());
            assertPayload(client, new byte[]{1,2,3});
            assertNull(session.activeDictionary());
            assertNull(client.readOutbound());
        } finally { server.finishAndReleaseAll(); client.finishAndReleaseAll(); }
    }

    @Test void rejectsWrongIdDuplicateOfferAndEarlyAck() throws Exception {
        var dictionary = DictionaryFixtures.dictionary();
        var server = ZstdDictionarySession.server(dictionary);
        var client = ZstdDictionarySession.client((id, bytes) -> ZstdDictionary.fromBytes(bytes), null);
        var offer = java.util.Objects.requireNonNull(server.pollOutboundControl());
        var wrong = offer.clone();
        wrong[8] ^= 1;
        assertThrows(java.io.IOException.class, () -> client.receiveControl(wrong));
        assertNull(client.activeDictionary());
        client.receiveControl(offer);
        assertThrows(java.io.IOException.class, () -> client.receiveControl(offer));
        var ack = java.util.Objects.requireNonNull(client.pollOutboundControl());
        var unopened = ZstdDictionarySession.server(dictionary);
        assertThrows(java.io.IOException.class, () -> unopened.receiveControl(ack));
        server.receiveControl(ack);
        assertThrows(java.io.IOException.class, () -> server.receiveControl(ack));
    }

    @Test void directionalSessionNegotiatesIndependentPaths() throws Exception {
        var serverOutbound = DictionaryFixtures.dictionary();
        var clientOutbound = DictionaryFixtures.dictionary();
        var server = ZstdDictionarySession.server(serverOutbound, clientOutbound, null);
        var client = ZstdDictionarySession.client((id, bytes) -> ZstdDictionary.fromBytes(bytes), null, clientOutbound);

        client.receiveControl(server.pollOutboundControl());
        server.receiveControl(client.pollOutboundControl());
        server.receiveControl(client.pollOutboundControl());
        client.receiveControl(server.pollOutboundControl());

        assertSame(serverOutbound, server.activeOutboundDictionary());
        assertNotNull(server.activeInboundDictionary());
        assertNotNull(client.activeOutboundDictionary());
        assertNotNull(client.activeInboundDictionary());
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
}
