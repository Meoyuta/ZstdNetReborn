package mys.zstdnet.reborn.neoforge.helper;

import mys.zstdnet.reborn.client.ZstdNetClient;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;
import net.neoforged.fml.ModList;

public final class SableCompat {
    private SableCompat() {
    }

    public static boolean isLoaded() {
        var modList = ModList.get();
        boolean loaded = modList != null && modList.isLoaded("sable");
        ZstdNetClient.logger().debug("Sable detection: modList=" + (modList == null ? "null" : "ready")
            + ", loaded=" + loaded);
        return loaded;
    }

    public static boolean isSableUdpPipeline(ChannelPipeline pipeline) {
        if (pipeline == null) {
            ZstdNetClient.logger().debug("Sable UDP detection: pipeline=null");
            return false;
        }
        boolean loaded = isLoaded();
        boolean datagram = pipeline.channel() instanceof DatagramChannel;
        if (!loaded || !datagram) {
            ZstdNetClient.logger().debug("Sable UDP detection: loaded=" + loaded
                + ", channel=" + pipeline.channel().getClass().getName()
                + ", datagram=" + datagram + ", result=false");
            return false;
        }
        for (String name : pipeline.names()) {
            ChannelHandler handler = pipeline.get(name);
            if (handler == null) {
                continue;
            }
            String className = handler.getClass().getName();
            if (className.startsWith("dev.ryanhcode.sable.")
                || className.contains("SableUDP")) {
                ZstdNetClient.logger().debug("Sable UDP detection: result=true, handler=" + name
                    + ", class=" + className + ", pipeline=" + pipeline.names());
                return true;
            }
        }
        ZstdNetClient.logger().debug("Sable UDP detection: result=false, no Sable handler, pipeline="
            + pipeline.names());
        return false;
    }
}
