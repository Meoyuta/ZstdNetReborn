package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record ManagementStatusPayload(
        String state,
        int port,
        long wireUpBytes,
        long rawUpBytes,
        long wireDownBytes,
        long rawDownBytes,
        long wireUpRate,
        long rawUpRate,
        long wireDownRate,
        long rawDownRate,
        double ratioPercent,
        int connections,
        int compressionLevel,
        String dictionary,
        int dictionaryConnections,
        int selectedDictionaryConnections,
        double latencyMillis,
        long uptimeSeconds,
        int benchmarkRuns
) implements CustomPacketPayload {
    static final Type<ManagementStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "management_status"));
    static final StreamCodec<RegistryFriendlyByteBuf, ManagementStatusPayload> STREAM_CODEC =
            StreamCodec.of(ManagementStatusPayload::write, ManagementStatusPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, ManagementStatusPayload payload) {
        buffer.writeUtf(payload.state, 32);
        buffer.writeVarInt(payload.port);
        buffer.writeVarLong(payload.wireUpBytes);
        buffer.writeVarLong(payload.rawUpBytes);
        buffer.writeVarLong(payload.wireDownBytes);
        buffer.writeVarLong(payload.rawDownBytes);
        buffer.writeVarLong(payload.wireUpRate);
        buffer.writeVarLong(payload.rawUpRate);
        buffer.writeVarLong(payload.wireDownRate);
        buffer.writeVarLong(payload.rawDownRate);
        buffer.writeDouble(payload.ratioPercent);
        buffer.writeVarInt(payload.connections);
        buffer.writeVarInt(payload.compressionLevel);
        buffer.writeUtf(payload.dictionary, 512);
        buffer.writeVarInt(payload.dictionaryConnections);
        buffer.writeVarInt(payload.selectedDictionaryConnections);
        buffer.writeDouble(payload.latencyMillis);
        buffer.writeVarLong(payload.uptimeSeconds);
        buffer.writeVarInt(payload.benchmarkRuns);
    }

    private static ManagementStatusPayload read(RegistryFriendlyByteBuf buffer) {
        return new ManagementStatusPayload(
                buffer.readUtf(32), buffer.readVarInt(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readDouble(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readUtf(512), buffer.readVarInt(), buffer.readVarInt(), buffer.readDouble(),
                buffer.readVarLong(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
