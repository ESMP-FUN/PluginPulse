package io.github.darkstarworks.pluginpulse;

import java.util.Map;

/**
 * Normalized description of the latest available release, whatever the source.
 *
 * @param version         version string as published (may include a track suffix)
 * @param changelog       release notes, plain text or MiniMessage; may be empty
 * @param downloadUrl     direct file download URL; may be null when the source
 *                        only exposes a landing page
 * @param fileName        published file name; may be null
 * @param hashes          algorithm (lowercase, e.g. "sha512") to hex digest; may be empty
 * @param sizeBytes       published file size, or -1 when unknown
 * @param restartRequired whether the publisher flagged this update as needing a
 *                        full restart (defaults to true when unknown)
 * @param releasePageUrl  human-facing page for this release; may be null
 * @param publishedEpochMs when the release was published, in epoch milliseconds,
 *                        or -1 when the source doesn't say. Feeds the
 *                        "let a release settle first" hold in {@link Updater}.
 */
public record UpdateInfo(
        String version,
        String changelog,
        String downloadUrl,
        String fileName,
        Map<String, String> hashes,
        long sizeBytes,
        boolean restartRequired,
        String releasePageUrl,
        long publishedEpochMs
) {
    public UpdateInfo {
        hashes = hashes == null ? Map.of() : Map.copyOf(hashes);
        changelog = changelog == null ? "" : changelog;
        if (publishedEpochMs <= 0) publishedEpochMs = -1L;
    }

    /** Without a publication time: the source doesn't publish one. */
    public UpdateInfo(String version, String changelog, String downloadUrl, String fileName,
                      Map<String, String> hashes, long sizeBytes, boolean restartRequired,
                      String releasePageUrl) {
        this(version, changelog, downloadUrl, fileName, hashes, sizeBytes, restartRequired,
                releasePageUrl, -1L);
    }
}
