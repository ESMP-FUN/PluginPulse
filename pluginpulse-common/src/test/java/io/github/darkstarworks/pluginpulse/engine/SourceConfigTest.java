package io.github.darkstarworks.pluginpulse.engine;

import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceConfigTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    private static List<String> names(Map<String, ?> cfg) {
        List<UpdateSource> sources = SourceConfig.sources(ConfigView.of(cfg), SourceConfig.Platform.BUKKIT,
                LOG, "test", "here");
        return sources.stream().map(UpdateSource::name).toList();
    }

    @Test
    void defaultOrderPutsJenkinsLast() {
        List<String> n = names(Map.of("jenkins", "https://ci.example.com/job/x/", "modrinth", "x", "github", "a/b"));
        assertEquals(3, n.size());
        assertTrue(n.get(0).toLowerCase().contains("modrinth"), n.toString());
        assertTrue(n.get(2).toLowerCase().contains("jenkins"), n.toString());
    }

    @Test
    void sourceOrderWins() {
        List<String> n = names(Map.of("modrinth", "x", "hangar", "y", "source-order", List.of("hangar")));
        assertTrue(n.get(0).toLowerCase().contains("hangar"), n.toString());
        assertTrue(n.get(1).toLowerCase().contains("modrinth"), n.toString());
    }

    @Test
    void noSourcesIsEmpty() {
        assertTrue(names(Map.of("mode", "notify")).isEmpty());
    }

    @Test
    void modes() {
        assertNull(SourceConfig.parseMode("off"));
        assertEquals(UpdateMode.CHECK_ONLY, SourceConfig.parseMode("silent"));
        assertEquals(UpdateMode.AUTO_STAGE, SourceConfig.parseMode("auto"));
        assertEquals(UpdateMode.NOTIFY, SourceConfig.parseMode("anything"));
    }

    @Test
    void viewReadsLooseTypes() {
        ConfigView v = ConfigView.of(Map.of("n", "12", "b", "true", "s", "  "));
        assertEquals(12L, v.number("n", 0L));
        assertTrue(v.bool("b", false));
        assertNull(v.string("s"));
    }
}
