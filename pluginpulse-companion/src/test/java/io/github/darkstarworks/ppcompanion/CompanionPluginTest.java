package io.github.darkstarworks.ppcompanion;

import io.github.darkstarworks.pluginpulse.UpdateMode;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void placeholderContactsAreNotSent() {
        assertEquals("", CompanionPlugin.contact("you@example.com"));
        assertEquals("", CompanionPlugin.contact("PASTE-YOUR-EMAIL-HERE"));
        assertEquals("", CompanionPlugin.contact("  "));
        assertEquals("me@example.org", CompanionPlugin.contact(" me@example.org "));
    }

    @Test
    void filterPrefixMatchesCaseInsensitively() {
        List<String> options = List.of("EssentialsX", "LuckPerms", "all");
        assertEquals(List.of("EssentialsX"), CompanionPlugin.filterPrefix(options, "ess"));
        assertEquals(options, CompanionPlugin.filterPrefix(options, ""));
    }
}
