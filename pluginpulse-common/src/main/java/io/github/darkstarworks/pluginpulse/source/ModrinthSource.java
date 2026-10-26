package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Modrinth (api.modrinth.com/v2). Rate limit is 300 requests/minute per IP;
 * a unique identifying User-Agent is mandatory and supplied by {@link HttpSupport}.
 */
public final class ModrinthSource implements UpdateSource {

    private static final String API = "https://api.modrinth.com/v2";

    private final String projectSlug;
    private final List<String> loaders;
    private final List<String> gameVersions;

    public ModrinthSource(String projectSlug) {
        this(projectSlug, List.of(), List.of());
    }

    /**
     * @param loaders      optional loader filter, e.g. {@code ["paper"]}
     * @param gameVersions optional game-version filter, e.g. {@code ["1.21.1"]}
     */
    public ModrinthSource(String projectSlug, List<String> loaders, List<String> gameVersions) {
        this.projectSlug = projectSlug;
        this.loaders = List.copyOf(loaders);
        this.gameVersions = List.copyOf(gameVersions);
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        StringBuilder url = new StringBuilder(API)
                .append("/project/").append(projectSlug).append("/version");
        String sep = "?";
        if (!loaders.isEmpty()) {
            url.append(sep).append("loaders=").append(encodeJsonList(loaders));
            sep = "&";
        }
        if (!gameVersions.isEmpty()) {
            url.append(sep).append("game_versions=").append(encodeJsonList(gameVersions));
        }
        String json = ctx.http().get(url.toString());
        UpdateInfo info = parse(json, ctx.track(), projectSlug, ctx.serverVersion());
        if (info == null) {
            throw new IllegalStateException("Modrinth project " + projectSlug + " has no matching versions"
                    + (ctx.track() != null ? " on the -" + ctx.track() + " release line" : ""));
        }
        return info;
    }

    static UpdateInfo parse(String json, String track, String projectSlug) {
        return parse(json, track, projectSlug, null);
    }

    /**
     * Pick from the version list (newest first).
     *
     * <p>When {@code serverVersion} is given and some versions list it among
     * their game versions, only those are considered: that is the jar built for
     * this server. A track still narrows the choice to {@code -<track>}
     * versions; when the track has none it falls back to a plain version
     * without any suffix, never to another track's jar. Without a track,
     * the newest full release built for this server wins; when no version lists
     * the server's game version, versions without a {@code -} suffix are
     * preferred, as before.</p>
     */
    static UpdateInfo parse(String json, String track, String projectSlug, String serverVersion) {
        List<JsonObject> all = new ArrayList<>();
        JsonParser.parseString(json).getAsJsonArray().forEach(el -> all.add(el.getAsJsonObject()));
        List<JsonObject> forServer = serverVersion == null ? List.of()
                : all.stream().filter(v -> listsGameVersion(v, serverVersion)).toList();
        boolean matched = !forServer.isEmpty();
        List<JsonObject> pool = matched ? forServer : all;

        JsonObject chosen = null;
        if (track != null && !track.isBlank()) {
            String suffix = "-" + track.toLowerCase(Locale.ROOT);
            chosen = first(pool, v -> number(v).toLowerCase(Locale.ROOT).endsWith(suffix));
            if (chosen == null && matched) {
                chosen = first(all, v -> number(v).toLowerCase(Locale.ROOT).endsWith(suffix));
            }
            // A project that publishes one plain version for every server is fine;
            // another line's "-something" jar never is.
            if (chosen == null) chosen = first(pool, v -> !number(v).contains("-"));
        } else if (matched) {
            chosen = first(pool, ModrinthSource::isRelease);
            if (chosen == null) chosen = pool.get(0);
        } else {
            // No track and nothing tagged for this server: skip suffixed builds
            // (other tracks, pre-releases), or a dual-track project's newest
            // "-mc26" upload would be served to every server.
            chosen = first(pool, v -> !number(v).contains("-"));
            if (chosen == null && !pool.isEmpty()) chosen = pool.get(0);
        }
        if (chosen == null) return null;

        JsonObject file = pickPrimaryFile(chosen.getAsJsonArray("files"));
        Map<String, String> hashes = new HashMap<>();
        String downloadUrl = null;
        String fileName = null;
        long size = -1;
        if (file != null) {
            downloadUrl = file.get("url").getAsString();
            fileName = file.get("filename").getAsString();
            if (file.has("size")) size = file.get("size").getAsLong();
            JsonObject hashObj = file.getAsJsonObject("hashes");
            if (hashObj != null) {
                for (String algo : hashObj.keySet()) {
                    hashes.put(algo.toLowerCase(Locale.ROOT), hashObj.get(algo).getAsString());
                }
            }
        }
        String changelog = chosen.has("changelog") && !chosen.get("changelog").isJsonNull()
                ? chosen.get("changelog").getAsString() : "";
        String versionId = chosen.get("id").getAsString();
        return new UpdateInfo(
                chosen.get("version_number").getAsString(),
                changelog,
                downloadUrl,
                fileName,
                hashes,
                size,
                true,
                "https://modrinth.com/project/" + projectSlug + "/version/" + versionId,
                PublishTime.from(chosen, "date_published")
        );
    }

    private static String number(JsonObject v) {
        return v.get("version_number").getAsString();
    }

    private static boolean isRelease(JsonObject v) {
        return !v.has("version_type") || "release".equalsIgnoreCase(v.get("version_type").getAsString());
    }

    private static boolean listsGameVersion(JsonObject v, String gameVersion) {
        JsonArray list = v.getAsJsonArray("game_versions");
        if (list == null) return false;
        for (JsonElement el : list) {
            if (gameVersion.equals(el.getAsString())) return true;
        }
        return false;
    }

    private static JsonObject first(List<JsonObject> versions, Predicate<JsonObject> test) {
        for (JsonObject v : versions) {
            if (test.test(v)) return v;
        }
        return null;
    }

    private static JsonObject pickPrimaryFile(JsonArray files) {
        if (files == null || files.isEmpty()) return null;
        for (JsonElement el : files) {
            JsonObject f = el.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
        }
        return files.get(0).getAsJsonObject();
    }

    private static String encodeJsonList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i)).append('"');
        }
        sb.append(']');
        return URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "modrinth";
    }
}
