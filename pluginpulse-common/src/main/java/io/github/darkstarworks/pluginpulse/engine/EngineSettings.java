package io.github.darkstarworks.pluginpulse.engine;

import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;

import java.time.Duration;
import java.util.List;

/** Everything a platform builder collects for an {@link UpdateEngine}. */
public record EngineSettings(
        String pluginName,
        String currentVersion,
        List<UpdateSource> sources,
        UpdateMode mode,
        Duration checkInterval,
        Duration minimumReleaseAge,
        String permission,
        String commandRoot,
        String track,
        String changelogUrl,
        String userAgentContact,
        boolean requireHash,
        int backupRetention,
        String serverVersion) {

    public EngineSettings {
        sources = List.copyOf(sources);
        if (sources.isEmpty()) {
            throw new IllegalStateException("At least one update source is required");
        }
    }
}
