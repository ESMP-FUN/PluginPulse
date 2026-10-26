package io.github.darkstarworks.pluginpulse.notify;

import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * The MiniMessage templates behind every update notice, and their plain-text
 * fallbacks. Placeholders are literal tokens: {@code <prefix>}, {@code <current>},
 * {@code <latest>}, {@code <page>}, {@code <cmdroot>}.
 */
public final class NoticeTemplates {

    public static final String KEY_CONSOLE = "console";
    public static final String KEY_PLAYER = "player";
    public static final String KEY_BUTTONS = "buttons";
    public static final String KEY_DOWNLOAD = "download-button";
    public static final String KEY_STAGED_RELOAD = "staged-reload";
    public static final String KEY_STAGED_RESTART = "staged-restart";

    private static final Map<String, String> DEFAULTS = Map.of(
            KEY_CONSOLE, "<prefix> <green>Update available:</green> <yellow><latest></yellow> <gray>(current: <current>)</gray>",
            KEY_PLAYER, "<prefix> <gray>You are using version <yellow><current></yellow>, "
                    + "latest version is <green><latest></green>."
                    + "<newline><click:open_url:'<page>'><hover:show_text:'<gray>Open the download page'>"
                    + "<aqua>[Download Latest Version]</aqua></hover></click>",
            // In-game background download (needs a command root to run the command).
            KEY_DOWNLOAD, " <click:run_command:'<cmdroot> update download'>"
                    + "<hover:show_text:'<gray>Download it now, in the background'>"
                    + "<green>[Download & Install]</green></hover></click>",
            KEY_BUTTONS, " <click:run_command:'<cmdroot> update ignore <latest>'>"
                    + "<hover:show_text:'<gray>Hide notifications for this version'><gray>[Ignore]</gray></hover></click>",
            // Post-download "ready" notices. RELOAD offers a no-restart install;
            // RESTART is shown when hot reload isn't available/safe.
            KEY_STAGED_RELOAD, "<prefix> <green>Update <latest> downloaded.</green> "
                    + "<click:run_command:'<cmdroot> update apply'>"
                    + "<hover:show_text:'<gray>Install it now, no restart needed'><aqua>[Install Now]</aqua></hover></click>",
            KEY_STAGED_RESTART, "<prefix> <green>Update <latest> downloaded and staged.</green> "
                    + "<gray>Restart the server to apply it.</gray>"
    );

    private final String prefix;
    private final String commandRoot;
    private final Map<String, String> messages;

    /**
     * @param platformDefaults replaces built-in defaults for one platform (the
     *                         proxy says "restart the proxy"); host overrides win
     */
    public NoticeTemplates(String prefix, String commandRoot,
                           Map<String, String> platformDefaults, Map<String, String> overrides) {
        this.prefix = prefix;
        this.commandRoot = commandRoot;
        this.messages = new HashMap<>(DEFAULTS);
        this.messages.putAll(platformDefaults);
        this.messages.putAll(overrides);
    }

    public String prefix() {
        return prefix;
    }

    public String commandRoot() {
        return commandRoot;
    }

    public boolean hasCommandRoot() {
        return commandRoot != null && !commandRoot.isBlank();
    }

    /** Rendered MiniMessage string for {@code key}. */
    public String render(String key, String current, UpdateInfo info) {
        return render(current, info, messages.get(key));
    }

    /** The player notice, with the download and ignore buttons when a command root exists. */
    public String playerNotice(String current, UpdateInfo info) {
        String template = messages.get(KEY_PLAYER);
        if (hasCommandRoot()) {
            template += messages.get(KEY_DOWNLOAD) + messages.get(KEY_BUTTONS);
        }
        return render(current, info, template);
    }

    /** Key of the post-download notice: the no-restart one only when it can be run. */
    public String stagedKey(boolean canReload) {
        return canReload && hasCommandRoot() ? KEY_STAGED_RELOAD : KEY_STAGED_RESTART;
    }

    private String render(String current, UpdateInfo info, String template) {
        return template
                .replace("<prefix>", prefix)
                .replace("<current>", safeVersion(current))
                .replace("<latest>", safeVersion(info.version()))
                .replace("<page>", safeUrl(pageUrl(info)))
                .replace("<cmdroot>", commandRoot == null ? "" : commandRoot);
    }

    // ==== Plain text ====

    public String plainConsole(String current, UpdateInfo info) {
        return stripTags(prefix) + " Update available: " + info.version() + " (current: " + current + ")";
    }

    public String plainPlayer(String current, UpdateInfo info) {
        String page = pageUrl(info);
        StringBuilder msg = new StringBuilder(stripTags(prefix))
                .append(" Update available: ").append(info.version())
                .append(" (current: ").append(current).append(").");
        if (!page.isEmpty()) msg.append(" Download: ").append(page);
        if (hasCommandRoot()) {
            msg.append("  Run '").append(commandRoot).append(" update download' to install.");
        }
        return msg.toString();
    }

    /** Plain-text staged notice: the [Install Now] button can't be clicked, so name the command. */
    public String plainStaged(UpdateInfo info, boolean canReload) {
        String key = stagedKey(canReload);
        String text = stripTags(render(key, "", info)).replace(" [Install Now]", "");
        return KEY_STAGED_RELOAD.equals(key) ? text + " Run '" + commandRoot + " update apply' to install it now." : text;
    }

    public static String pageUrl(UpdateInfo info) {
        return info.releasePageUrl() != null ? info.releasePageUrl()
                : info.downloadUrl() != null ? info.downloadUrl() : "";
    }

    /**
     * Version text goes inside clickable commands, so anything that could close
     * the quote or open a tag is dropped rather than trusted from the source.
     */
    public static String safeVersion(String version) {
        return version == null ? "" : version.replaceAll("[^A-Za-z0-9._+\\-]", "");
    }

    public static String safeUrl(String url) {
        return url.replaceAll("['<>\\\\\\s]", "");
    }

    /** Drop MiniMessage tags for the plain-text fallback. */
    public static String stripTags(String s) {
        // Hover text is quoted and carries its own tags, so drop it whole first.
        return s.replace("<newline>", " ")
                .replaceAll("<hover:show_text:'[^']*'>", "")
                .replaceAll("<[^>]*>", "")
                .trim();
    }
}
