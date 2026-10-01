package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

final class DebugPingClientState {
    private DebugPingClientState() {}

    static void receive(DebugPingPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> {
            var connection = client.getConnection();
            if (connection != null) {
                connection.send(new ServerboundCustomPayloadPacket(new DebugPongPayload(payload.nonce())));
            }
        });
    }
}
