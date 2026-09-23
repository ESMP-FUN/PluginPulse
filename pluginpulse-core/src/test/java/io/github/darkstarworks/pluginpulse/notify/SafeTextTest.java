package io.github.darkstarworks.pluginpulse.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeTextTest {

    @Test
    void versionTextCannotBreakOutOfAClickCommand() {
        assertEquals("2.2.2-mc263", UpdateNotifier.safeVersion("2.2.2-mc263"));
        assertEquals("1.0clickrun_commandopx", UpdateNotifier.safeVersion("1.0'><click:run_command:'/op x'>"));
    }

    @Test
    void plainTextDropsHoverTextWithItsNestedTags() {
        assertEquals("Update 1.0 downloaded. [Install Now]", UpdateNotifier.stripTags(
                "<green>Update 1.0 downloaded.</green> <click:run_command:'/x update apply'>"
                        + "<hover:show_text:'<gray>Install it now, no restart needed'><aqua>[Install Now]</aqua></hover></click>"));
    }

    @Test
    void urlsLoseQuotesAndTags() {
        assertEquals("https://example.com/ab", UpdateNotifier.safeUrl("https://example.com/a'<b>"));
    }
}
