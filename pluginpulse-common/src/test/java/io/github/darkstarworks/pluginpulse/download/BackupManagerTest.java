package io.github.darkstarworks.pluginpulse.download;

import io.github.darkstarworks.pluginpulse.state.PendingUpdateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @TempDir
    Path root;

    @Test
    void backupCopiesAndPrunesBeyondRetention() throws IOException {
        Path jar = Files.write(root.resolve("MyPlugin.jar"), "content".getBytes());
        BackupManager manager = new BackupManager(root.resolve("backups"), 2, LOGGER);

        // Stamp each backup's mtime immediately: prune runs inside backup(),
        // and same-second timestamps would make deletion order arbitrary.
        Path b1 = manager.backup(jar, "1.0.0");
        Files.setLastModifiedTime(b1, FileTime.fromMillis(1000));
        Path b2 = manager.backup(jar, "1.0.1");
        Files.setLastModifiedTime(b2, FileTime.fromMillis(2000));
        Path b3 = manager.backup(jar, "1.0.2"); // prunes b1
        Files.setLastModifiedTime(b3, FileTime.fromMillis(3000));
        manager.backup(jar, "1.0.3"); // prunes b2

        try (Stream<Path> files = Files.list(root.resolve("backups"))) {
            assertEquals(2, files.count());
        }
        assertTrue(Files.readString(manager.latestBackup()).equals("content"));
    }

    @Test
    void latestBackupNullWhenEmpty() throws IOException {
        BackupManager manager = new BackupManager(root.resolve("nothing"), 3, LOGGER);
        assertNull(manager.latestBackup());
    }

    @Test
    void pendingUpdateStoreRoundTrip() {
        PendingUpdateStore store = new PendingUpdateStore(root.resolve("pending.json"), LOGGER);
        assertNull(store.load());
        store.save(new PendingUpdateStore.Pending("2.0.0", "/backups/x.jar.bak", 1));
        PendingUpdateStore.Pending loaded = store.load();
        assertEquals("2.0.0", loaded.stagedVersion());
        assertEquals("/backups/x.jar.bak", loaded.backupPath());
        assertEquals(1, loaded.attempts());
        store.clear();
        assertNull(store.load());
    }
}
