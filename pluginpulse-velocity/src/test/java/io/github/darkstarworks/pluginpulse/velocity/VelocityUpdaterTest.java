package io.github.darkstarworks.pluginpulse.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityUpdaterTest {

    @Test
    void commandRootIsTheBareLowerCaseName() {
        assertEquals("myplugin", VelocityUpdater.normalize("/MyPlugin update"));
        assertEquals("tcp", VelocityUpdater.normalize(" tcp "));
    }

    @Test
    void buttonFormAndBareFormBothWork() {
        assertArrayEquals(new String[]{"check"}, VelocityUpdater.tail(new String[]{"update", "check"}));
        assertArrayEquals(new String[]{"status"}, VelocityUpdater.tail(new String[]{"status"}));
    }

    @Test
    void descriptorParsesAsAMap() {
        assertEquals("x", VelocityPulse.load("modrinth: x\nmode: notify\n").get("modrinth"));
        assertEquals(0, VelocityPulse.load("").size());
    }
}
