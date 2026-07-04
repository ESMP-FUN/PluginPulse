package io.github.darkstarworks.pluginpulse.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Copies the currently running jar to a backups directory before an update is
 * staged, and prunes old backups beyond the retention count. Copying a locked
 * jar is a read — safe on Windows even while the server runs.
 */
public final class BackupManager {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path backupDir;
    private final int retention;
    private final Logger logger;

    public BackupManager(Path backupDir, int retention, Logger logger) {
        this.backupDir = backupDir;
        this.retention = Math.max(1, retention);
        this.logger = logger;
    }

    /** Copy {@code currentJar} into the backup dir, then prune. Returns the backup path. */
    public Path backup(Path currentJar, String version) throws IOException {
        Files.createDirectories(backupDir);
        String base = stripExtension(currentJar.getFileName().toString());
        Path target = backupDir.resolve(base + "-" + version + "-" + LocalDateTime.now().format(STAMP) + ".jar.bak");
        Files.copy(currentJar, target, StandardCopyOption.REPLACE_EXISTING);
        prune();
        return target;
    }

    /** Newest backup on disk, or null when none exist. */
    public Path latestBackup() throws IOException {
        if (!Files.isDirectory(backupDir)) return null;
        try (Stream<Path> files = Files.list(backupDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar.bak"))
                    .max(Comparator.comparingLong(BackupManager::modified))
                    .orElse(null);
        }
    }

    private void prune() throws IOException {
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> backups = files
                    .filter(p -> p.getFileName().toString().endsWith(".jar.bak"))
                    .sorted(Comparator.comparingLong(BackupManager::modified).reversed())
                    .toList();
            for (int i = retention; i < backups.size(); i++) {
                try {
                    Files.deleteIfExists(backups.get(i));
                } catch (IOException e) {
                    logger.fine("Could not prune backup " + backups.get(i) + ": " + e.getMessage());
                }
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
