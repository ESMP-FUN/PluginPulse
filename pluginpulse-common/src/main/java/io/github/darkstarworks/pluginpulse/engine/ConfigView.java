package io.github.darkstarworks.pluginpulse.engine;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read-only view of one level of a YAML-style config, whatever parsed it. */
@FunctionalInterface
public interface ConfigView {

    /** The raw value at {@code key}, or null. */
    Object get(String key);

    static ConfigView of(Map<?, ?> map) {
        return map == null ? key -> null : map::get;
    }

    default boolean has(String key) {
        return get(key) != null;
    }

    /** Trimmed text, or null when absent or blank. */
    default String string(String key) {
        Object o = get(key);
        if (o == null) return null;
        String t = String.valueOf(o).trim();
        return t.isEmpty() ? null : t;
    }

    default String string(String key, String def) {
        String s = string(key);
        return s != null ? s : def;
    }

    default boolean bool(String key, boolean def) {
        Object o = get(key);
        if (o instanceof Boolean b) return b;
        if (o != null) {
            String s = String.valueOf(o).trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return def;
    }

    default long number(String key, long def) {
        Object o = get(key);
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return def;
    }

    default List<String> list(String key) {
        Object o = get(key);
        if (!(o instanceof Collection<?> c)) return List.of();
        return c.stream().filter(e -> e != null).map(String::valueOf).toList();
    }

    /** The nested section at {@code key}, or null when it isn't one. */
    default ConfigView section(String key) {
        return get(key) instanceof Map<?, ?> m ? of(m) : null;
    }
}
