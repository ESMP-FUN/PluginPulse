package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Reads the moment a release was published out of a source payload. Every API
 * spells it differently ({@code date_published}, {@code published_at},
 * {@code createdAt}, a raw build {@code timestamp}), so callers just hand over
 * the candidate field names.
 *
 * <p>The result drives the updater's optional "let a release settle before
 * acting on it" hold, so an unparseable or missing value must be harmless:
 * everything falls back to {@link #UNKNOWN}.</p>
 */
final class PublishTime {

    /** No usable publication time in the payload. */
    static final long UNKNOWN = -1L;

    private PublishTime() {
    }

    /** The first present, parseable field of {@code keys}, or {@link #UNKNOWN}. */
    static long from(JsonObject obj, String... keys) {
        if (obj == null) return UNKNOWN;
        for (String key : keys) {
            if (!obj.has(key) || obj.get(key).isJsonNull()) continue;
            long ms = parse(obj.get(key).getAsString());
            if (ms > 0) return ms;
        }
        return UNKNOWN;
    }

    /** ISO-8601 ({@code 2026-07-20T10:15:30Z}) or epoch milliseconds. */
    static long parse(String value) {
        if (value == null) return UNKNOWN;
        String v = value.trim();
        if (v.isEmpty()) return UNKNOWN;
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                long ms = Long.parseLong(v);
                return ms > 0 ? ms : UNKNOWN;
            } catch (NumberFormatException e) {
                return UNKNOWN;
            }
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // Not offset-qualified; try the plain instant form before giving up.
        }
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }
}
