package mys.zstdnet.reborn.core.dictionary;

import mys.zstdnet.reborn.core.utils.ZstdNetLogger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class ZstdDictionaryStore {
    private final Path dictionaryPath;
    private final ZstdNetLogger logger;
    private volatile ZstdDictionary dictionary;
    private volatile ZstdDictionary uplinkDictionary;
    private volatile Path selectedPath;
    private boolean managesSelection;
    private boolean namingEnabled;
    private boolean shuttingDown;
    private final java.util.Map<String, ZstdDictionary> pendingInstances = new java.util.HashMap<>();
    private final java.util.Map<String, ZstdDictionary> pendingUplinkInstances = new java.util.HashMap<>();
    private final java.util.Properties pending = new java.util.Properties();

    public synchronized void enableNaming() throws IOException {
        namingEnabled = true;
        pending.clear();
        var metadata = dictionaryPath.resolveSibling("dictionary-naming.properties");
        if (Files.isRegularFile(metadata)) {
            try (var input = Files.newInputStream(metadata)) { pending.load(input); }
        }
        shuttingDown = false;
    }

    public synchronized void beginShutdown() { shuttingDown = true; }

    public synchronized java.util.List<String> pendingNames() {
        return pending.stringPropertyNames().stream().sorted().toList();
    }

    private void persistPending() throws IOException {
        var metadata = dictionaryPath.resolveSibling("dictionary-naming.properties");
        Files.createDirectories(metadata.getParent());
        var temporary = metadata.resolveSibling(metadata.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary)) { pending.store(output, "Dictionary naming deadlines; 0 = shutdown dictionary"); }
        Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String timestamp() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HH-mm-ss.SSS"));
    }

    public synchronized Path name(String file, String name) throws IOException {
        if (!pending.containsKey(file)) throw new IOException("No pending dictionary: " + file);
        name = name.trim();
        if (name.endsWith(".zdict")) name = name.substring(0, name.length() - 6);
        if (name.isBlank() || name.length() > 100 || name.equals(".") || name.equals("..")
            || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
            || name.endsWith(".") || name.endsWith(" ")) throw new IOException("Invalid dictionary name");
        var source = dictionaryPath.resolveSibling(file);
        var target = dictionaryPath.resolveSibling(name + ".zdict");
        if (Files.exists(target)) throw new IOException("Dictionary name already exists");
        var instance = pendingInstances.get(file);
        var uplinkInstance = pendingUplinkInstances.get(file);
        if (instance == null && source.equals(selectedPath) && dictionary != null) instance = dictionary;
        if (uplinkInstance == null && source.equals(selectedPath) && uplinkDictionary != null) uplinkInstance = uplinkDictionary;
        if (instance == null || uplinkInstance == null) {
            var contents = ZstdDictionaryBundle.unpack(readBundleBounded(source));
            if (instance == null) instance = ZstdDictionary.fromBytes(contents.downlink(), ZstdDictionary.MAX_DOWNLINK_BYTES);
            if (uplinkInstance == null) uplinkInstance = ZstdDictionary.fromBytes(contents.uplink(), ZstdDictionary.MAX_UPLINK_BYTES);
        }
        Files.move(source, target);
        try {
            persistSelection(target.toString());
        } catch (IOException e) {
            Files.move(target, source);
            throw e;
        }
        dictionary = instance;
        uplinkDictionary = uplinkInstance;
        selectedPath = target;
        pending.remove(file);
        pendingInstances.remove(file);
        pendingUplinkInstances.remove(file);
        persistPending();
        logger.info("Dictionary named and applied: " + target);
        return target;
    }

    public synchronized java.util.List<String> expireNames(long now) throws IOException {
        var applied = new java.util.ArrayList<String>();
        for (String file : pendingNames()) {
            var deadline = Long.parseLong(pending.getProperty(file));
            if (deadline > 0 && now >= deadline) {
                applied.add(name(file, "untitled_" + timestamp()).getFileName().toString());
            }
        }
        return applied;
    }

    public ZstdDictionaryStore(Path dictionaryPath, ZstdNetLogger logger) {
        this.dictionaryPath = Objects.requireNonNull(dictionaryPath, "dictionaryPath").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path dictionaryPath() {
        return dictionaryPath;
    }

    public ZstdDictionary dictionary() {
        return dictionary;
    }

    /** The optional client-to-server dictionary contained in the active bundle. */
    public ZstdDictionary uplinkDictionary() {
        return uplinkDictionary;
    }

    public Path selectedPath() { return selectedPath; }

    public java.util.List<String> available() throws IOException {
        var files = new java.util.TreeSet<String>();
        var base = dictionaryPath.getParent();
        if (base != null && Files.isDirectory(base)) {
            try (var stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zdict"))
                    .forEach(p -> files.add(base.relativize(p).toString()));
            }
        }
        return java.util.List.copyOf(files);
    }

    public synchronized boolean loadSelected() {
        managesSelection = true;
        var selection = dictionaryPath.resolveSibling("dictionary-selection.txt");
        try {
            if (Files.isRegularFile(selection)) {
                var value = Files.readString(selection).trim();
                if (value.equals("none")) {
                    dictionary = null;
                    uplinkDictionary = null;
                    selectedPath = null;
                    logger.info("ZstdNet dictionary mode: disabled (saved selection)");
                    return false;
                }
                select(Path.of(value));
                return true;
            }
            if (Files.isRegularFile(dictionaryPath)) {
                select(dictionaryPath);
                return true;
            }
            for (var candidate : available()) {
                try {
                    select(Path.of(candidate));
                    return true;
                } catch (IOException | RuntimeException e) {
                    logger.warn("Ignoring dictionary " + candidate + ": " + e.getMessage());
                }
            }
            logger.info("No server dictionary found in " + dictionaryPath.getParent());
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not restore selected dictionary; current dictionary retained: " + e.getMessage());
        }
        return false;
    }

    public synchronized ZstdDictionary select(Path path) throws IOException {
        var file = path.isAbsolute() ? path : dictionaryPath.getParent().resolve(path);
        file = file.toAbsolutePath().normalize();
        var next = pendingInstances.get(file.getFileName().toString());
        if (next == null) {
            if (file.equals(selectedPath) && dictionary != null) {
                next = dictionary;
            } else {
                byte[] stored = readBundleBounded(file);
                if (ZstdDictionaryBundle.isBundle(stored)) {
                    var contents = ZstdDictionaryBundle.unpack(stored);
                    uplinkDictionary = ZstdDictionary.fromBytes(contents.uplink(), ZstdDictionary.MAX_UPLINK_BYTES);
                    next = ZstdDictionary.fromBytes(contents.downlink(), ZstdDictionary.MAX_DOWNLINK_BYTES);
                    } else throw new IOException("dictionary file must be a directional bundle");
            }
        }
        persistSelection(file.toString());
        dictionary = next;
        selectedPath = file;
        logger.info("Selected dictionary " + file + ", id=" + Long.toUnsignedString(next.id()));
        return next;
    }

    public synchronized void unload() throws IOException {
        persistSelection("none");
        dictionary = null;
        uplinkDictionary = null;
        selectedPath = null;
        logger.info("Dictionary disabled for new connections; existing connections retain negotiated dictionaries");
    }

    private void persistSelection(String value) throws IOException {
        var selection = dictionaryPath.resolveSibling("dictionary-selection.txt");
        Files.createDirectories(selection.getParent());
        var temporary = selection.resolveSibling(selection.getFileName() + ".tmp");
        Files.writeString(temporary, value);
        try {
            Files.move(temporary, selection, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, selection, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized boolean load() {
        if (!Files.isRegularFile(dictionaryPath)) {
            dictionary = null;
            uplinkDictionary = null;
            return false;
        }
        try {
            byte[] stored = readBundleBounded(dictionaryPath);
            if (ZstdDictionaryBundle.isBundle(stored)) {
                var contents = ZstdDictionaryBundle.unpack(stored);
                uplinkDictionary = ZstdDictionary.fromBytes(contents.uplink(), ZstdDictionary.MAX_UPLINK_BYTES);
                dictionary = ZstdDictionary.fromBytes(contents.downlink(), ZstdDictionary.MAX_DOWNLINK_BYTES);
            } else throw new IOException("dictionary file must be a directional bundle");
            logger.info("loaded ZstdNet dictionary id=" + Long.toUnsignedString(dictionary.id()) + " size=" + dictionary.size());
            return true;
        } catch (IOException | RuntimeException e) {
            dictionary = null;
            uplinkDictionary = null;
            logger.warn("ignored invalid ZstdNet dictionary at " + dictionaryPath + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized ZstdDictionary save(byte[] bytes) throws IOException {
        throw new IOException("A directional dictionary bundle is required");
    }

    /** Saves a dictionary using a direction-specific size limit. */
    public synchronized ZstdDictionary save(byte[] bytes, int maximumBytes) throws IOException {
        throw new IOException("A directional dictionary bundle is required");
    }

    /** Writes both directional dictionaries as one compressed bundle and activates its downlink entry. */
    public synchronized ZstdDictionary saveBundle(byte[] uplink, byte[] downlink) throws IOException {
        var nextUp = ZstdDictionary.fromBytes(uplink, ZstdDictionary.MAX_UPLINK_BYTES);
        var nextDown = ZstdDictionary.fromBytes(downlink, ZstdDictionary.MAX_DOWNLINK_BYTES);
        byte[] bundle = ZstdDictionaryBundle.pack(uplink, downlink);
        if (namingEnabled) {
            String prefix = shuttingDown ? "temp_" : "pending_";
            Path file = dictionaryPath.resolveSibling(prefix + timestamp() + ".zdict");
            int suffix = 1;
            while (Files.exists(file)) file = dictionaryPath.resolveSibling(prefix + timestamp() + "_" + suffix++ + ".zdict");
            Files.createDirectories(file.getParent());
            Files.write(file, bundle, java.nio.file.StandardOpenOption.CREATE_NEW);
            String key = file.getFileName().toString();
            pending.setProperty(key, shuttingDown ? "0" : Long.toString(System.currentTimeMillis() + 60_000));
            pendingInstances.put(key, nextDown);
            pendingUplinkInstances.put(key, nextUp);
            persistPending();
            if (shuttingDown) persistSelection(file.toString());
            logger.info("Dictionary bundle saved to " + file + "; awaiting naming");
            return nextDown;
        }
        write(bundle);
        if (managesSelection) persistSelection(dictionaryPath.toString());
        uplinkDictionary = nextUp;
        dictionary = nextDown;
        selectedPath = dictionaryPath;
        logger.info("Dictionary bundle saved: uplink=" + nextUp.size() + " bytes, downlink=" + nextDown.size() + " bytes");
        return nextDown;
    }

    public synchronized ZstdDictionary importFrom(Path source) throws IOException {
        return importFrom(source, ZstdDictionary.MAX_DOWNLINK_BYTES);
    }

    public synchronized ZstdDictionary importFrom(Path source, int maximumBytes) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("dictionary file does not exist: " + normalized);
        }
        byte[] bytes = Files.size(normalized) > ZstdDictionaryBundle.MAX_BUNDLE_BYTES
            ? throwTooLarge() : Files.readAllBytes(normalized);
        var contents = ZstdDictionaryBundle.unpack(bytes);
        return saveBundle(contents.uplink(), contents.downlink());
    }

    public synchronized ZstdDictionary importBundle(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        var contents = ZstdDictionaryBundle.unpack(Files.readAllBytes(normalized));
        return saveBundle(contents.uplink(), contents.downlink());
    }

    public synchronized Path export() throws IOException {
        if (dictionary == null) throw new IOException("No dictionary is loaded; train or import one first");
        Path exported = selectedPath == null ? dictionaryPath : selectedPath;
        if (!Files.isRegularFile(exported)) throw new IOException("Selected dictionary file does not exist: " + exported);
        return exported.toAbsolutePath().normalize();
    }

    public synchronized byte[] exportBundle() throws IOException {
        if (dictionary == null || uplinkDictionary == null) {
            throw new IOException("Both directional dictionaries are required for bundle export");
        }
        return ZstdDictionaryBundle.pack(uplinkDictionary.bytes(), dictionary.bytes());
    }

    private static byte[] readBounded(Path path) throws IOException {
        return readBounded(path, ZstdDictionary.MAX_DOWNLINK_BYTES);
    }

    private static byte[] readBounded(Path path, int maximumBytes) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) throw new IOException("Dictionary is too large");
            return bytes;
        }
    }

    private static byte[] throwTooLarge() throws IOException {
        throw new IOException("Dictionary bundle is too large");
    }

    private static byte[] readBundleBounded(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(ZstdDictionaryBundle.MAX_BUNDLE_BYTES + 1);
            if (bytes.length > ZstdDictionaryBundle.MAX_BUNDLE_BYTES) throw new IOException("Dictionary bundle is too large");
            return bytes;
        }
    }

    private void write(byte[] bytes) throws IOException {
        Path parent = dictionaryPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
            var temporary = dictionaryPath.resolveSibling(dictionaryPath.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, dictionaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, dictionaryPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
