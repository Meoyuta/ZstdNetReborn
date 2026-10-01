package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

record DictionaryStatusPayload(
        String mode,
        String description,
        String selectedPath,
        String trainingState,
        int sampleCount,
        int sampleBytes,
        long remainingSeconds,
        List<String> available
) implements CustomPacketPayload {
    static final Type<DictionaryStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "dictionary_status"));
    static final StreamCodec<RegistryFriendlyByteBuf, DictionaryStatusPayload> STREAM_CODEC =
            StreamCodec.of(DictionaryStatusPayload::write, DictionaryStatusPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, DictionaryStatusPayload payload) {
        buffer.writeUtf(payload.mode, 16);
        buffer.writeUtf(payload.description, 512);
        buffer.writeUtf(payload.selectedPath, 1024);
        buffer.writeUtf(payload.trainingState, 128);
        buffer.writeVarInt(payload.sampleCount);
        buffer.writeVarInt(payload.sampleBytes);
        buffer.writeVarLong(payload.remainingSeconds);
        buffer.writeVarInt(payload.available.size());
        for (String value : payload.available) buffer.writeUtf(value, 512);
    }

    private static DictionaryStatusPayload read(RegistryFriendlyByteBuf buffer) {
        String mode = buffer.readUtf(16);
        String description = buffer.readUtf(512);
        String selectedPath = buffer.readUtf(1024);
        String trainingState = buffer.readUtf(128);
        int sampleCount = buffer.readVarInt();
        int sampleBytes = buffer.readVarInt();
        long remainingSeconds = buffer.readVarLong();
        int count = buffer.readVarInt();
        var available = new java.util.ArrayList<String>(Math.max(0, count));
        for (int i = 0; i < count; i++) available.add(buffer.readUtf(512));
        return new DictionaryStatusPayload(
                mode, description, selectedPath, trainingState, sampleCount, sampleBytes,
                remainingSeconds, List.copyOf(available));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
