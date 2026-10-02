package mys.zstdnet.reborn.neoforge.client;

import mys.zstdnet.reborn.neoforge.network.DebugPingPayload;
import mys.zstdnet.reborn.neoforge.network.DebugPongPayload;

import mys.zstdnet.reborn.neoforge.network.DebugPingPayload;
import mys.zstdnet.reborn.neoforge.network.DebugPongPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public final class DebugPingClientState {
    private DebugPingClientState() {}

    public static void receive(DebugPingPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> {
            var connection = client.getConnection();
            if (connection != null) {
                connection.send(new ServerboundCustomPayloadPacket(new DebugPongPayload(payload.nonce())));
            }
        });
    }
}
