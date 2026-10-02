package mys.zstdnet.reborn.core.netty;

public interface ZstdDictionaryDownloadListener {
    enum DictionaryFailure { INBOUND_ID_MISMATCH, OFFER_REJECTED, INVALID, IO }
    ZstdDictionaryDownloadListener NONE = new ZstdDictionaryDownloadListener() {
        @Override
        public void started(long dictionaryId, int dictionaryBytes) {
        }

        @Override
        public void progress(int downloadedBytes, int dictionaryBytes) {
        }

        @Override
        public void completed(long dictionaryId) {
        }

        @Override
        public void failed(String message) {
        }
    };

    void started(long dictionaryId, int dictionaryBytes);

    void progress(int downloadedBytes, int dictionaryBytes);

    void completed(long dictionaryId);

    @Deprecated(forRemoval = false)
    void failed(String message);

    default void failed(DictionaryFailure reason, String detail) {
        failed(detail);
    }
}
