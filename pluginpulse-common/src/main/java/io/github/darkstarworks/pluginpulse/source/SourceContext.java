package io.github.darkstarworks.pluginpulse.source;

import java.util.logging.Logger;

/**
 * Everything a source needs to make requests: the shared HTTP support (which
 * carries the mandatory identifying User-Agent), the configured distribution
 * track (may be null), a logger, and the server's Minecraft version when
 * sources should prefer releases built for it (may be null).
 */
public record SourceContext(HttpSupport http, String track, Logger logger, String serverVersion) {

    public SourceContext(HttpSupport http, String track, Logger logger) {
        this(http, track, logger, null);
    }
}
