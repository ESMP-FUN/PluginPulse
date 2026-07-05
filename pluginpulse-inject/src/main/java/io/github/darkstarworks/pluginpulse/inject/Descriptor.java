package io.github.darkstarworks.pluginpulse.inject;

/**
 * The plugin descriptor we care about, read from {@code plugin.yml} (legacy) or
 * {@code paper-plugin.yml} (Paper). We only touch {@code main:}; a Paper
 * {@code bootstrapper:} is left untouched.
 *
 * @param kind     which descriptor file this came from
 * @param entry    the jar entry path (e.g. {@code "plugin.yml"})
 * @param name     the plugin name
 * @param main     the JavaPlugin subclass FQN (the wiring target)
 * @param version  the plugin version (informational)
 * @param rawText  the full original descriptor text (rewritten by text edit,
 *                 preserving comments/formatting)
 */
public record Descriptor(Kind kind, String entry, String name, String main, String version, String rawText) {

    public enum Kind {
        LEGACY("plugin.yml"),
        PAPER("paper-plugin.yml");

        public final String entry;

        Kind(String entry) {
            this.entry = entry;
        }
    }
}
