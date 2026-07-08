package io.github.darkstarworks.pluginpulse;

/**
 * Loads the optional hot-reload engine from the {@code pluginpulse-hotreload}
 * module without a compile-time dependency on it (core stays reload-free).
 *
 * <p>The class name is derived from {@link ReloadEngine}'s own package, so it
 * keeps resolving after a host shades/relocates the library (both types move
 * under the same relocated prefix).</p>
 */
public final class ReloadEngines {

    private ReloadEngines() {
    }

    /**
     * @return a {@link ReloadEngine} from the bundled hotreload module, or
     *         {@code null} when that module isn't on the classpath.
     */
    public static ReloadEngine tryLoad() {
        try {
            String pkg = ReloadEngine.class.getPackageName();
            Class<?> cls = Class.forName(pkg + ".hotreload.HotReloadEngine");
            return (ReloadEngine) cls.getMethod("create").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
