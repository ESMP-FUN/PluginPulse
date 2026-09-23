package io.github.darkstarworks.pluginpulse.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVersionTest {

    @Test
    void readsTheMinecraftVersionFromBothNumberings() {
        assertEquals("1.21.11", ServerVersion.fromBukkitVersion("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("26.3", ServerVersion.fromBukkitVersion("26.3.build.35-alpha"));
        assertEquals("26.3", ServerVersion.fromBukkitVersion("26.3"));
        assertNull(ServerVersion.fromBukkitVersion("unknown"));
        assertNull(ServerVersion.fromBukkitVersion(null));
    }

    @Test
    void tellsTheNumberingsApart() {
        assertTrue(ServerVersion.isModernNumbering("26.3"));
        assertFalse(ServerVersion.isModernNumbering("1.21.11"));
        assertFalse(ServerVersion.isModernNumbering(null));
    }
}
