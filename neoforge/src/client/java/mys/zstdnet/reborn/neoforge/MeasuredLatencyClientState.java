package mys.zstdnet.reborn.neoforge;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MeasuredLatencyClientState {
    private static final ConcurrentHashMap<UUID, Double> VALUES = new ConcurrentHashMap<>();

    private MeasuredLatencyClientState() {}

    public static void receive(UUID playerId, double millis) {
        Minecraft.getInstance().execute(() -> {
            if (millis < 0.0D) VALUES.remove(playerId);
            else VALUES.put(playerId, millis);
        });
    }

    public static UUID profileId(Object profile) {
        if (profile == null) return null;
        for (String methodName : new String[]{"id", "getId"}) {
            try {
                Method method = profile.getClass().getMethod(methodName);
                Object value = method.invoke(profile);
                if (value instanceof UUID id) return id;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    public static double get(UUID playerId) {
        return VALUES.getOrDefault(playerId, Double.NaN);
    }

    public static void clear() {
        VALUES.clear();
    }
}
