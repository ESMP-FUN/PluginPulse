package io.github.darkstarworks.pluginpulse.source;

import io.github.darkstarworks.pluginpulse.engine.SourceConfig;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthLoaderTest {

    private static String url(ModrinthSource source, List<String> platformLoaders) {
        SourceContext ctx = new SourceContext(null, null, null, null, platformLoaders);
        return URLDecoder.decode(source.versionsUrl(ctx), StandardCharsets.UTF_8);
    }

    @Test
    void plainSourceAsksForWhatTheServerRuns() {
        String u = url(new ModrinthSource("luckperms"), SourceConfig.Platform.BUKKIT.modrinthLoaders());
        assertTrue(u.contains("loaders=[\"bukkit\",\"spigot\",\"paper\",\"purpur\",\"folia\"]"), u);
        assertTrue(url(new ModrinthSource("luckperms"), SourceConfig.Platform.VELOCITY.modrinthLoaders())
                .endsWith("loaders=[\"velocity\"]"));
    }

    @Test
    void explicitLoadersWin() {
        String u = url(new ModrinthSource("x", List.of("fabric"), List.of()),
                SourceConfig.Platform.BUKKIT.modrinthLoaders());
        assertTrue(u.endsWith("loaders=[\"fabric\"]"), u);
    }

    @Test
    void noPlatformMeansNoFilter() {
        String u = url(new ModrinthSource("x"), List.of());
        assertFalse(u.contains("loaders"), u);
        assertEquals("https://api.modrinth.com/v2/project/x/version", u);
    }
}
