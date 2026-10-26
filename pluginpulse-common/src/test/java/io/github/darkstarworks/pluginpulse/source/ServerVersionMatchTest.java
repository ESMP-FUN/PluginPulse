package io.github.darkstarworks.pluginpulse.source;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A project that ships one jar per Minecraft line, the way BetterTrialChambers does. */
class ServerVersionMatchTest {

    private static final String THREE_LINES = """
            [
              {"id": "c", "version_number": "2.2.2-mc263", "version_type": "release",
               "game_versions": ["26.3"],
               "files": [{"url": "u3", "filename": "p-2.2.2-mc263.jar", "primary": true, "hashes": {}}]},
              {"id": "b", "version_number": "2.2.2-mc26", "version_type": "release",
               "game_versions": ["26.1", "26.1.1", "26.1.2", "26.2"],
               "files": [{"url": "u2", "filename": "p-2.2.2-mc26.jar", "primary": true, "hashes": {}}]},
              {"id": "a", "version_number": "2.2.2", "version_type": "release",
               "game_versions": ["1.21.10", "1.21.11"],
               "files": [{"url": "u1", "filename": "p-2.2.2.jar", "primary": true, "hashes": {}}]}
            ]
            """;

    private static String pick(String track, String server) {
        UpdateInfo info = ModrinthSource.parse(THREE_LINES, track, "p", server);
        return info == null ? null : info.version();
    }

    @Test
    void picksTheJarBuiltForTheServerWithoutATrack() {
        assertEquals("2.2.2-mc263", pick(null, "26.3"));
        assertEquals("2.2.2-mc26", pick(null, "26.2"));
        assertEquals("2.2.2", pick(null, "1.21.11"));
    }

    @Test
    void trackStillWins() {
        assertEquals("2.2.2-mc26", pick("mc26", "26.3"));
        assertEquals("2.2.2-mc263", pick("mc263", "26.3"));
    }

    @Test
    void neverFallsBackToAnotherTracksJar() {
        String onlyOtherLines = THREE_LINES.replace("\"2.2.2\"", "\"2.2.2-paper\"");
        assertNull(ModrinthSource.parse(onlyOtherLines, "mc27", "p", "26.3"));
    }

    @Test
    void unknownServerVersionKeepsTheOldChoice() {
        assertEquals("2.2.2", pick(null, "9.9"));
        assertEquals("2.2.2", pick(null, null));
    }

    @Test
    void prefersFullReleasesForTheServer() {
        String withBeta = THREE_LINES.replaceFirst("\"version_type\": \"release\"", "\"version_type\": \"beta\"");
        assertEquals("2.2.2-mc26", pick(null, "26.2"));
        UpdateInfo info = ModrinthSource.parse(withBeta.replace("\"26.3\"]", "\"26.3\", \"26.2\"]"), null, "p", "26.2");
        assertEquals("2.2.2-mc26", info.version());
    }
}
