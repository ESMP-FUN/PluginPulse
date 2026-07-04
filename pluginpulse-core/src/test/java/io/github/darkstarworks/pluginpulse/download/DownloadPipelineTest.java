package io.github.darkstarworks.pluginpulse.download;

import com.sun.net.httpserver.HttpServer;
import io.github.darkstarworks.pluginpulse.UpdateInfo;
import io.github.darkstarworks.pluginpulse.source.HttpSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadPipelineTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @TempDir
    Path root;

    private HttpServer server;
    private byte[] jarBytes;
    private String baseUrl;

    private Path tmpDir;
    private Path updateFolder;
    private Path pluginsDir;
    private Path currentJar;

    @BeforeEach
    void setUp() throws IOException {
        jarBytes = new byte[4096];
        new Random(42).nextBytes(jarBytes);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dl/plugin-2.0.0.jar", ex -> {
            ex.sendResponseHeaders(200, jarBytes.length);
            ex.getResponseBody().write(jarBytes);
            ex.close();
        });
        server.createContext("/missing.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        tmpDir = root.resolve("tmp");
        updateFolder = root.resolve("plugins/update");
        pluginsDir = root.resolve("plugins");
        Files.createDirectories(pluginsDir);
        currentJar = pluginsDir.resolve("MyPlugin-1.0.0.jar");
        Files.write(currentJar, "old-version".getBytes());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private DownloadPipeline pipeline(boolean requireHash) {
        BackupManager backups = new BackupManager(root.resolve("backups"), 3, LOGGER);
        return new DownloadPipeline(new HttpSupport("test/1.0"), tmpDir, updateFolder,
                backups, requireHash, LOGGER);
    }

    private UpdateInfo info(String sha256) throws IOException {
        Map<String, String> hashes = sha256 != null ? Map.of("sha256", sha256) : Map.of();
        return new UpdateInfo("2.0.0", "", baseUrl + "/dl/plugin-2.0.0.jar",
                "plugin-2.0.0.jar", hashes, jarBytes.length, true, null);
    }

    @Test
    void stagesUnderCurrentJarFilename() throws Exception {
        Path digestSource = Files.write(root.resolve("digest.bin"), jarBytes);
        String sha256 = HashVerifier.digest(digestSource, "sha256");

        DownloadPipeline.StagingResult result = pipeline(true)
                .downloadAndStage(info(sha256), Map.of(), currentJar, "1.0.0");

        // Staged under the LIVE jar's name, not the release's name.
        assertEquals("MyPlugin-1.0.0.jar", result.stagedFile().getFileName().toString());
        assertEquals(updateFolder, result.stagedFile().getParent());
        org.junit.jupiter.api.Assertions.assertArrayEquals(jarBytes, Files.readAllBytes(result.stagedFile()));
        assertEquals(HashVerifier.Result.VERIFIED, result.hashResult());
        // Backup of the old jar exists.
        assertNotNull(result.backupFile());
        assertEquals("old-version", Files.readString(result.backupFile()));
        // Temp file cleaned up.
        try (var files = Files.list(tmpDir)) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    @Test
    void refusesChecksumMismatch() throws Exception {
        IOException e = assertThrows(IOException.class, () -> pipeline(true)
                .downloadAndStage(info("0".repeat(64)), Map.of(), currentJar, "1.0.0"));
        assertTrue(e.getMessage().contains("mismatch"));
        assertFalse(Files.exists(updateFolder.resolve("MyPlugin-1.0.0.jar")));
    }

    @Test
    void refusesMissingHashWhenRequired() {
        IOException e = assertThrows(IOException.class, () -> pipeline(true)
                .downloadAndStage(info(null), Map.of(), currentJar, "1.0.0"));
        assertTrue(e.getMessage().contains("No checksum"));
    }

    @Test
    void allowsMissingHashWhenNotRequired() throws Exception {
        DownloadPipeline.StagingResult result = pipeline(false)
                .downloadAndStage(info(null), Map.of(), currentJar, "1.0.0");
        assertEquals(HashVerifier.Result.NO_HASH, result.hashResult());
        assertTrue(Files.exists(result.stagedFile()));
    }

    @Test
    void refusesSizeMismatch() throws IOException {
        Path digestSource = Files.write(root.resolve("digest.bin"), jarBytes);
        String sha256 = HashVerifier.digest(digestSource, "sha256");
        UpdateInfo wrongSize = new UpdateInfo("2.0.0", "", baseUrl + "/dl/plugin-2.0.0.jar",
                "plugin-2.0.0.jar", Map.of("sha256", sha256), 999999, true, null);
        IOException e = assertThrows(IOException.class, () -> pipeline(true)
                .downloadAndStage(wrongSize, Map.of(), currentJar, "1.0.0"));
        assertTrue(e.getMessage().contains("Size mismatch"));
    }

    @Test
    void failsCleanlyOnHttpError() {
        assertThrows(IOException.class, () -> pipeline(true).downloadAndStage(
                new UpdateInfo("2.0.0", "", baseUrl + "/missing.jar", "x.jar", Map.of(), -1, true, null),
                Map.of(), currentJar, "1.0.0"));
    }

    @Test
    void stageLocalCopiesBackupIntoUpdateFolder() throws Exception {
        Path backup = Files.write(root.resolve("MyPlugin-0.9.0.jar.bak"), "previous".getBytes());
        Path staged = pipeline(true).stageLocal(backup, currentJar);
        assertEquals("MyPlugin-1.0.0.jar", staged.getFileName().toString());
        assertEquals("previous", Files.readString(staged));
    }
}
