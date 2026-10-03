package mys.zstdnet.reborn.neoforge.network;

/** Connection/service state exposed to diagnostics and the management overlay. */
public enum ZstdState {
    ACTIVE("active"), INJECT_FAILED("inject_failed"), ACCESSOR_MISSING("accessor_missing"),
    PROBE_FAILED("probe_failed"), SERVER_DISABLED("server_disabled");

    private final String wireName;
    ZstdState(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }
}
