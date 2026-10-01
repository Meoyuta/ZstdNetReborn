package mys.zstdnet.reborn.core.utils;

public interface ZstdNetLogger {
    default void debug(String message) {
    }

    void info(String message);

    void warn(String message);

    void error(String message);
}
