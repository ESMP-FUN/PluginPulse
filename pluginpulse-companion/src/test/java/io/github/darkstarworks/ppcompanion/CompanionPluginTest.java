package io.github.darkstarworks.ppcompanion;

import io.github.darkstarworks.pluginpulse.UpdateMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionPluginTest {

    @Test
    void offDisablesTarget() {
        assertNull(CompanionPlugin.parseMode("off"));
    }

    @Test
    void aliasesMapToModes() {
        assertEquals(UpdateMode.CHECK_ONLY, CompanionPlugin.parseMode("check-only"));
        assertEquals(UpdateMode.CHECK_ONLY, CompanionPlugin.parseMode("silent"));
        assertEquals(UpdateMode.DOWNLOAD, CompanionPlugin.parseMode("download"));
        assertEquals(UpdateMode.AUTO_STAGE, CompanionPlugin.parseMode("auto"));
    }

    @Test
    void unknownDefaultsToNotify() {
        assertEquals(UpdateMode.NOTIFY, CompanionPlugin.parseMode("whatever"));
    }
}
