package io.github.darkstarworks.ppcompanion.velocity;

import io.github.darkstarworks.pluginpulse.engine.ConfigView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionVelocityTest {

    private static ConfigView shippedConfig() throws IOException {
        try (InputStream in = CompanionVelocity.class.getResourceAsStream("/config.yml")) {
            return ConfigView.of(CompanionVelocity.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Test
    void shippedConfigManagesNothingYet() throws IOException {
        ConfigView config = shippedConfig();
        assertNull(config.get("plugins"));
        assertEquals(6L, config.number("check-interval-hours", 0L));
        assertFalse(config.bool("hold-new-updates", true));
    }

    @Test
    void placeholderContactIsNotSent() throws IOException {
        assertEquals("", CompanionVelocity.contact(shippedConfig()));
        assertEquals("me@example.com", CompanionVelocity.contact(ConfigView.of(Map.of("user-agent-contact", "me@example.com"))));
    }

    @Test
    void uncommentedExampleParses() {
        String yaml = """
                plugins:
                     luckperms:
                       modrinth: luckperms
                       mode: notify
                """;
        ConfigView entry = ConfigView.of(CompanionVelocity.parse(yaml)).section("plugins").section("luckperms");
        assertEquals("luckperms", entry.string("modrinth"));
        assertEquals("notify", entry.string("mode"));
    }
}
