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
    void urlsLoseQuotesAndTags() {
        assertEquals("https://example.com/ab", UpdateNotifier.safeUrl("https://example.com/a'<b>"));
    }
}
