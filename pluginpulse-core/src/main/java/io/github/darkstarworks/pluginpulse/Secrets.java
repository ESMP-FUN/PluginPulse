package io.github.darkstarworks.pluginpulse;

/**
 * Resolves a configured secret value (currently the GitHub token) that may be
 * supplied either literally or as an environment-variable reference.
 *
 * <p>A value of the form <code>${NAME}</code> is read from the environment
 * variable {@code NAME} at runtime, so a token never has to be written into a
 * committed config file — the server sets {@code NAME} and can rotate it without
 * a rebuild. Any other non-blank value is used verbatim (this is what a
 * build-time-embedded token looks like). Blank, missing, or an unresolved
 * environment reference all yield {@code null} (i.e. "no token").</p>
 */
public final class Secrets {

    private Secrets() {
    }

    /** @return the resolved secret, or {@code null} when none is available. */
    public static String resolve(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("${") && v.endsWith("}")) {
            String name = v.substring(2, v.length() - 1).trim();
            if (name.isEmpty()) return null;
            String env = System.getenv(name);
            if (env == null) return null;
            env = env.trim();
            return env.isEmpty() ? null : env;
        }
        return v;
    }
}
