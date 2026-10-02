package mys.zstdnet.reborn.core.dictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryLifecycleTest {
    @Test void namingAndTimeoutKeepSavedInstance() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        store.enableNaming();
        var bytes = DictionaryFixtures.dictionary().bytes();
        var saved = store.saveBundle(bytes, bytes);
        assertNull(store.dictionary());
        var pending = store.pendingNames().getFirst();
        store.name(pending, "my_dictionary");
        assertSame(saved, store.dictionary());
        assertTrue(store.pendingNames().isEmpty());
        var second = store.saveBundle(bytes, bytes);
        store.expireNames(System.currentTimeMillis() + 61_000);
        assertSame(second, store.dictionary());
        assertTrue(store.selectedPath().getFileName().toString().startsWith("untitled_"));
    }

    @Test void shutdownDictionaryAppliesOnRestartAndRenameKeepsInstance() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        store.enableNaming();
        store.beginShutdown();
        var bytes = DictionaryFixtures.dictionary().bytes();
        store.saveBundle(bytes, bytes);
        assertNull(store.dictionary());
        var restarted = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        restarted.enableNaming();
        assertTrue(restarted.loadSelected());
        var selected = restarted.dictionary();
        var file = restarted.pendingNames().getFirst();
        assertTrue(file.startsWith("temp_"));
        assertTrue(restarted.expireNames(Long.MAX_VALUE).isEmpty());
        restarted.name(file, "after_restart");
        assertSame(selected, restarted.dictionary());
        restarted.enableNaming();
        assertTrue(restarted.pendingNames().isEmpty());
    }
    @TempDir Path directory;
    @Test void exportsImportsAndPreservesDictionaryOnInvalidImport() throws Exception {
        var original = DictionaryFixtures.dictionary();
        var store = new ZstdDictionaryStore(directory.resolve("config/dictionary.zdict"), DictionaryFixtures.LOGGER);
        store.saveBundle(original.bytes(), original.bytes());
        var exported = store.export();
        var contents = ZstdDictionaryBundle.unpack(Files.readAllBytes(exported));
        assertArrayEquals(original.bytes(), contents.downlink());
        assertArrayEquals(original.bytes(), contents.uplink());
        var imported = new ZstdDictionaryStore(directory.resolve("other/dictionary.zdict"), DictionaryFixtures.LOGGER);
        imported.importBundle(exported);
        assertTrue(imported.load());
        assertArrayEquals(original.bytes(), imported.dictionary().bytes());
        var invalid = directory.resolve("invalid.zdict");
        Files.write(invalid, new byte[300]);
        assertThrows(java.io.IOException.class, () -> imported.importBundle(invalid));
        assertArrayEquals(original.bytes(), imported.dictionary().bytes());
        var corruptTables = new byte[300];
        System.arraycopy(original.bytes(), 0, corruptTables, 0, 8);
        assertThrows(java.io.IOException.class, () -> ZstdDictionary.fromBytes(corruptTables));
    }

    @Test void trainingFinalizesAndReportsResult() {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        try (var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER)) {
            assertTrue(trainer.start(null, 3));
            assertTrue(trainer.status().remainingMillis() > 590_000);
            assertFalse(trainer.start(null, 3));
            for (byte[] sample : DictionaryFixtures.samples()) {
                trainer.capture(true, sample);
                trainer.capture(false, sample);
            }
            assertEquals(600, trainer.status().sampleCount());
            assertTrue(trainer.stopAndFinalize());
            trainer.finishAndClose();
            assertFalse(trainer.status().training());
            assertNotNull(store.dictionary(), trainer.status().result());
            assertTrue(Files.exists(store.dictionaryPath()));
        }
    }

    @Test void shutdownFinalizesCollectionAndCannotRestart() {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER);
        assertTrue(trainer.start(Duration.ofMinutes(10), 3));
        for (byte[] sample : DictionaryFixtures.samples()) {
            trainer.capture(true, sample);
            trainer.capture(false, sample);
        }
        trainer.finishAndClose();
        assertFalse(trainer.status().training());
        assertFalse(trainer.start(null, 3));
        assertFalse(trainer.stopAndFinalize());
        assertTrue(Files.exists(store.dictionaryPath()), trainer.status().result());
        assertNotNull(store.dictionary());
        assertTrue(store.dictionary().size() <= 128 * 1024);
    }

    @Test void shutdownWaitsForAlreadyQueuedTraining() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER);
        trainer.start(null, 3);
        for (byte[] sample : DictionaryFixtures.samples()) {
            trainer.capture(true, sample);
            trainer.capture(false, sample);
        }
        assertTrue(trainer.stopAndFinalize());
        trainer.finishAndClose();
        assertNotNull(store.dictionary(), trainer.status().result());
        var saved = ZstdDictionaryBundle.unpack(Files.readAllBytes(store.dictionaryPath()));
        assertArrayEquals(store.dictionary().bytes(), saved.downlink());
    }

    @Test void insufficientSamplesDoNotReplaceExistingDictionary() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        var bytes = DictionaryFixtures.dictionary().bytes();
        var dictionary = store.saveBundle(bytes, bytes);
        try (var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER)) {
            trainer.start(Duration.ofMinutes(1), 3);
            trainer.capture(true, new byte[]{1});
            trainer.capture(false, new byte[]{1});
            trainer.stopAndFinalize();
            trainer.finishAndClose();
            assertFalse(trainer.status().training());
            assertTrue(trainer.status().result().startsWith("failed:"));
            assertSame(dictionary, store.dictionary());
        }
    }
}
