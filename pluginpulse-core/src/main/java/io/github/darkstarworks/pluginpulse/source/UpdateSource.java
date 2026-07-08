package io.github.darkstarworks.pluginpulse.source;

import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.util.Map;

/**
 * A place releases are published (Modrinth, GitHub Releases, Hangar, a custom
 * JSON manifest, ...). Implementations are called from an async thread and may
 * block on network I/O.
 */
public interface UpdateSource {

    /**
     * Fetch the latest published release.
     *
     * @throws Exception on any network/parse failure; the updater will fall
     *                   back to the next configured source
     */
    UpdateInfo fetchLatest(SourceContext ctx) throws Exception;

    /** Short human-readable name for logging, e.g. {@code "modrinth"}. */
    String name();

    /**
     * Extra HTTP headers the download step must reuse to fetch this source's
     * artifact — e.g. an {@code Authorization} header for a private GitHub repo
     * or a self-hosted store's licence key. Empty for anonymous public sources.
     *
     * <p>The updater carries these from the check that produced the
     * {@link UpdateInfo} over to {@code DownloadPipeline}, so an authenticated
     * check is followed by an authenticated download.</p>
     */
    default Map<String, String> downloadHeaders() {
        return Map.of();
    }
}
