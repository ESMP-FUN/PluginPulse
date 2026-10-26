package io.github.darkstarworks.pluginpulse.engine;

import io.github.darkstarworks.pluginpulse.Secrets;
import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.source.BuiltByBitSource;
import io.github.darkstarworks.pluginpulse.source.GitHubReleasesSource;
import io.github.darkstarworks.pluginpulse.source.HangarSource;
import io.github.darkstarworks.pluginpulse.source.JenkinsSource;
import io.github.darkstarworks.pluginpulse.source.ModrinthSource;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Turns the source keys shared by {@code pluginpulse.yml} and the companion
 * configs ({@code modrinth}, {@code github}, {@code hangar}, {@code jenkins},
 * {@code builtbybit}, {@code source-order}, ...) into update sources for one platform.
 */
public final class SourceConfig {

    /** Which kind of server the sources should pick downloads for. */
    public enum Platform {
        BUKKIT(List.of("bukkit", "spigot", "paper", "purpur", "folia")),
        VELOCITY(List.of("velocity"));

        private final List<String> modrinthLoaders;

        Platform(List<String> modrinthLoaders) {
            this.modrinthLoaders = modrinthLoaders;
        }

        /** The Modrinth loader tags a server of this kind can run. */
        public List<String> modrinthLoaders() {
            return modrinthLoaders;
        }
    }

    private SourceConfig() {
    }

    /**
     * The configured sources, primary first. The order comes from the optional
     * {@code source-order} list; anything it leaves out follows in the default
     * order modrinth, github, hangar, builtbybit, jenkins (CI snapshots rank
     * last).
     *
     * @param who            log prefix, e.g. "PluginPulse" or the managed plugin's name
     * @param requireHashTip where to set {@code require-hash}, e.g. "in pluginpulse.yml"
     */
    public static List<UpdateSource> sources(ConfigView cfg, Platform platform, Logger logger,
                                             String who, String requireHashTip) {
        boolean velocity = platform == Platform.VELOCITY;
        Map<String, UpdateSource> available = new LinkedHashMap<>();
        String modrinth = cfg.string("modrinth");
        String github = cfg.string("github");
        String hangar = cfg.string("hangar");
        String builtbybit = cfg.string("builtbybit");
        String jenkins = cfg.string("jenkins");
        if (modrinth != null) {
            available.put("modrinth", new ModrinthSource(modrinth, platform.modrinthLoaders(), List.of()));
        }
        if (github != null) {
            // Optional token for a PRIVATE GitHub repo, literal or ${ENV_VAR}; it
            // authenticates the check and the download alike.
            String token = Secrets.resolve(cfg.string("github-token"));
            Predicate<String> preferred = velocity
                    ? name -> name.toLowerCase(Locale.ROOT).contains("velocity") : null;
            available.put("github", new GitHubReleasesSource(github, token, null, preferred));
        }
        if (hangar != null) {
            available.put("hangar", new HangarSource(hangar, velocity ? "VELOCITY" : "PAPER"));
        }
        if (builtbybit != null) {
            // The owner's own API key, best given as ${ENV_VAR}; the API refuses
            // anonymous requests and offers no file download, so check only.
            String token = Secrets.resolve(cfg.string("builtbybit-token"));
            if (token == null) {
                logger.warning(who + ": builtbybit is set but builtbybit-token is missing or its "
                        + "environment variable is empty, so BuiltByBit can't be checked.");
            }
            available.put("builtbybit", new BuiltByBitSource(builtbybit, token));
        }
        if (jenkins != null) {
            available.put("jenkins", new JenkinsSource(jenkins,
                    jenkinsArtifactFilter(cfg.string("jenkins-artifact"), logger, who)));
            // Jenkins archives raw CI artifacts with no checksums; with the
            // require-hash default (true) a download would always be refused.
            if (!cfg.has("require-hash")) {
                logger.info(who + ": the jenkins source publishes no checksums, "
                        + "download/auto modes need require-hash: false " + requireHashTip + ".");
            }
        }

        List<String> ordered = new ArrayList<>();
        for (String raw : cfg.list("source-order")) {
            String key = raw.trim().toLowerCase(Locale.ROOT);
            if (available.containsKey(key) && !ordered.contains(key)) ordered.add(key);
        }
        for (String key : available.keySet()) {
            if (!ordered.contains(key)) ordered.add(key);
        }
        List<UpdateSource> sources = new ArrayList<>();
        for (String key : ordered) sources.add(available.get(key));
        return sources;
    }

    /** @return the mode, or null when it is "off" */
    public static UpdateMode parseMode(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "off" -> null;
            case "check-only", "check", "silent" -> UpdateMode.CHECK_ONLY;
            case "download" -> UpdateMode.DOWNLOAD;
            case "auto-stage", "auto" -> UpdateMode.AUTO_STAGE;
            default -> UpdateMode.NOTIFY;
        };
    }

    /**
     * Compile the optional {@code jenkins-artifact} regex; an invalid pattern
     * logs a warning and falls back to the default jar filter instead of
     * disabling the whole updater.
     */
    private static Predicate<String> jenkinsArtifactFilter(String regex, Logger logger, String who) {
        if (regex == null) return null;
        try {
            return JenkinsSource.artifactRegex(regex);
        } catch (java.util.regex.PatternSyntaxException e) {
            logger.warning(who + ": invalid jenkins-artifact regex '" + regex
                    + "': using the default .jar filter instead.");
            return null;
        }
    }
}
