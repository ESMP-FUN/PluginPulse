package io.github.darkstarworks.pluginpulse.inject;

/**
 * User-supplied wiring for the injected {@code pluginpulse.yml}. At least one of
 * modrinth/github/hangar must be present.
 */
public record InjectOptions(
        String modrinth,
        String github,
        String hangar,
        String permission,
        String commandRoot,
        String mode,
        String userAgentContact,
        String track,
        Integer checkIntervalHours,
        boolean upgrade,
        String githubToken,
        boolean hotReload) {

    boolean hasSource() {
        return notBlank(modrinth) || notBlank(github) || notBlank(hangar);
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
