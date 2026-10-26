package io.github.darkstarworks.pluginpulse.source;

import java.util.List;
import java.util.logging.Logger;

/**
 * Everything a source needs to make requests: the shared HTTP support (which
 * carries the mandatory identifying User-Agent), the configured distribution
 * track (may be null), a logger, the server's Minecraft version when sources
 * should prefer releases built for it (may be null), and the Modrinth loaders
 * this server can run (empty for any).
 */
public record SourceContext(HttpSupport http, String track, Logger logger, String serverVersion,
                            List<String> loaders) {

    public SourceContext {
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
    }

    public SourceContext(HttpSupport http, String track, Logger logger, String serverVersion) {
        this(http, track, logger, serverVersion, List.of());
    }

    public SourceContext(HttpSupport http, String track, Logger logger) {
        this(http, track, logger, null, List.of());
    }
}
