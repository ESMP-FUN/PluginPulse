package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.io.IOException;
import java.util.Map;

/**
 * BuiltByBit (builtbybit.com) via the Ultimate API, check only. Every request
 * needs a token, and {@code GET /v1/resources/{id}/versions/latest} answers
 * {@code {"data":{version_id, update_id, name, release_date, download_count}}}
 * (errors as {@code {"error":{code, message}}}), the shape the official
 * {@code @builtbybit/api-wrapper} unwraps. The API has no
 * file download, so the returned {@link UpdateInfo} carries no download URL and
 * the owner is sent to the resource's updates page instead.
 */
public final class BuiltByBitSource implements UpdateSource {

    private static final String API = "https://api.builtbybit.com/v1";

    private final String resourceId;
    private final String token;

    /** @param token the owner's API key; "Private"/"Shared" prefix optional, Private assumed */
    public BuiltByBitSource(String resourceId, String token) {
        this.resourceId = resourceId.trim();
        this.token = token;
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        if (token == null || token.isBlank()) {
            throw new IOException("builtbybit needs builtbybit-token (the API rejects anonymous requests)");
        }
        String json = ctx.http().get(API + "/resources/" + resourceId + "/versions/latest",
                Map.of("Authorization", authHeader(token)));
        return parse(json, resourceId);
    }

    static String authHeader(String token) {
        String t = token.trim();
        return t.startsWith("Private ") || t.startsWith("Shared ") ? t : "Private " + t;
    }

    static UpdateInfo parse(String json, String resourceId) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonObject()) {
            JsonElement error = root.get("error");
            throw new IOException("BuiltByBit returned no version"
                    + (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")
                    ? ": " + error.getAsJsonObject().get("message").getAsString() : ""));
        }
        JsonObject v = data.getAsJsonObject();
        String version = v.get("name").getAsString().trim();
        long published = PublishTime.UNKNOWN;
        if (v.has("release_date") && !v.get("release_date").isJsonNull()) {
            // Documented only as a number; XenForo dates are Unix seconds, but
            // accept milliseconds too rather than land in the year 50000.
            long raw = v.get("release_date").getAsLong();
            if (raw > 0) published = raw < 100_000_000_000L ? raw * 1000L : raw;
        }
        return new UpdateInfo(version, "", null, null, Map.of(), -1, true,
                "https://builtbybit.com/resources/" + resourceId + "/updates", published);
    }

    @Override
    public String name() {
        return "builtbybit";
    }
}
