package mys.zstdnet.reborn.core.io;

@FunctionalInterface
public interface Counter {
    void add(long bytes);
}
