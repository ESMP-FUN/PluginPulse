package io.github.darkstarworks.pluginpulse.inject;

/** How PluginPulse is wired into a target. */
public enum Strategy {
    /** Generate a {@code <Main>__Pulse extends <Main>} subclass and re-point {@code main:}. */
    WRAPPER,
    /** Rewrite the existing (final) main class's onEnable/onDisable in place. */
    INSTRUMENT,
    /** Velocity only: add an injected field and an init handler to the main class in place. */
    VELOCITY
}
