package mys.zstdnet.reborn.core.netty;

public interface ZstdDictionaryDownloadListener {
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

    void failed(String message);
}
