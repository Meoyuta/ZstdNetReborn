package mys.zstdnet.reborn.neoforge.network;

/** Connection/service state exposed to diagnostics and the management overlay. */
public enum ZstdState {
    ACTIVE("active"), NOT_INSTALLED("not_installed"), INJECT_FAILED("inject_failed"),
    PROBE_FAILED("probe_failed"), SERVER_DISABLED("server_disabled"), PEER_UNSUPPORTED("peer_unsupported");

    private final String wireName;
    ZstdState(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }
}
