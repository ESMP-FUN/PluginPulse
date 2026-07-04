package io.github.darkstarworks.pluginpulse.download;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import io.github.darkstarworks.pluginpulse.source.HttpSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

/**
 * download → verify → backup → stage. All paths are injected so the pipeline
 * is testable without a running server; the {@code updateFolder} is
 * {@code Bukkit.getUpdateFolderFile()} in production, where the server swaps
 * the jar in on the next restart (filename must match the live jar exactly).
 */
public final class DownloadPipeline {

    /** Outcome of a successful staging run. */
    public record StagingResult(Path stagedFile, Path backupFile, HashVerifier.Result hashResult) {
    }

    private final HttpSupport http;
    private final Path tmpDir;
    private final Path updateFolder;
    private final BackupManager backupManager;
    private final boolean requireHash;
    private final Logger logger;

    public DownloadPipeline(HttpSupport http, Path tmpDir, Path updateFolder,
                            BackupManager backupManager, boolean requireHash, Logger logger) {
        this.http = http;
        this.tmpDir = tmpDir;
        this.updateFolder = updateFolder;
        this.backupManager = backupManager;
        this.requireHash = requireHash;
        this.logger = logger;
    }

    /**
     * Download the release described by {@code info}, verify it, back up the
     * current jar and stage the download under the current jar's filename.
     *
     * @param headers        extra request headers (e.g. licence key) — may be empty
     * @param currentJar     the live plugin jar (source of the staged filename)
     * @param currentVersion running version, used to name the backup
     * @throws IOException on download, verification or filesystem failure
     */
    public StagingResult downloadAndStage(UpdateInfo info, Map<String, String> headers,
                                          Path currentJar, String currentVersion)
            throws IOException, InterruptedException {
        if (info.downloadUrl() == null || info.downloadUrl().isBlank()) {
            throw new IOException("Release " + info.version() + " has no direct download URL"
                    + (info.releasePageUrl() != null ? "; download manually: " + info.releasePageUrl() : ""));
        }

        Files.createDirectories(tmpDir);
        String fileName = info.fileName() != null ? info.fileName() : "update-" + info.version() + ".jar";
        Path tmp = tmpDir.resolve(fileName + ".part");
        try {
            download(info.downloadUrl(), headers, tmp);

            long size = Files.size(tmp);
            if (info.sizeBytes() > 0 && size != info.sizeBytes()) {
                throw new IOException("Size mismatch: expected " + info.sizeBytes() + " bytes, got " + size);
            }
            if (size < 1024) {
                throw new IOException("Downloaded file is implausibly small (" + size + " bytes)");
            }

            HashVerifier.Result hashResult = HashVerifier.verify(tmp, info.hashes());
            switch (hashResult) {
                case MISMATCH -> throw new IOException(
                        "Checksum mismatch for " + fileName + " — refusing to stage (corrupted or tampered download)");
                case NO_HASH -> {
                    if (requireHash) {
                        throw new IOException("No checksum published for " + fileName
                                + " and hash verification is required");
                    }
                    logger.warning("Staging " + fileName + " WITHOUT checksum verification (none published)");
                }
                case VERIFIED -> logger.info("Checksum verified for " + fileName);
            }

            Path backup = backupManager.backup(currentJar, currentVersion);

            Files.createDirectories(updateFolder);
            Path staged = updateFolder.resolve(currentJar.getFileName().toString());
            try {
                Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StagingResult(staged, backup, hashResult);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Stage an existing local jar (e.g. a backup) into the update folder under
     * the current jar's filename. Used for one-command rollback.
     */
    public Path stageLocal(Path jar, Path currentJar) throws IOException {
        Files.createDirectories(updateFolder);
        Path staged = updateFolder.resolve(currentJar.getFileName().toString());
        Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
        return staged;
    }

    private void download(String url, Map<String, String> headers, Path target) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", http.userAgent())
                .GET();
        headers.forEach(builder::header);
        HttpResponse<Path> response = http.client().send(builder.build(),
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(target);
            throw new IOException("Download failed: HTTP " + response.statusCode() + " from " + url);
        }
    }
}
