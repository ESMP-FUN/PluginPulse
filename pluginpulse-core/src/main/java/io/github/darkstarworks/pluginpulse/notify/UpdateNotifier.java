package io.github.darkstarworks.pluginpulse.notify;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Builds and sends update notices. On Paper these render as rich MiniMessage
 * (colours, hover, clickable links); on Spigot, which does not bundle
 * Adventure: they fall back to plain text with the download URL spelled out,
 * so the updater still works everywhere.
 *
 * <p>Every MiniMessage template can be overridden by the host plugin, see
 * {@link NoticeTemplates} for the placeholders. Overrides only affect the
 * Adventure path; the plain fallback uses a fixed, readable format.</p>
 */
public final class UpdateNotifier {

    public static final String KEY_CONSOLE = NoticeTemplates.KEY_CONSOLE;
    public static final String KEY_PLAYER = NoticeTemplates.KEY_PLAYER;
    public static final String KEY_BUTTONS = NoticeTemplates.KEY_BUTTONS;
    public static final String KEY_DOWNLOAD = NoticeTemplates.KEY_DOWNLOAD;
    public static final String KEY_STAGED_RELOAD = NoticeTemplates.KEY_STAGED_RELOAD;
    public static final String KEY_STAGED_RESTART = NoticeTemplates.KEY_STAGED_RESTART;

    /** Whether Adventure/MiniMessage is available (Paper yes, Spigot no). */
    private static final boolean ADVENTURE_PRESENT = detectAdventure();

    private static boolean detectAdventure() {
        try {
            Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private final NoticeTemplates templates;

    public UpdateNotifier(String prefix, String commandRoot, Map<String, String> overrides) {
        this.templates = new NoticeTemplates(prefix, commandRoot, Map.of(), overrides);
    }

    public void notifyConsole(String pluginName, String current, UpdateInfo info) {
        if (ADVENTURE_PRESENT) {
            AdventureText.console(templates.render(KEY_CONSOLE, current, info));
            for (String line : info.changelog().split("\n")) {
                if (!line.isBlank()) AdventureText.console(line);
            }
        } else {
            Bukkit.getConsoleSender().sendMessage(templates.plainConsole(current, info));
            for (String line : info.changelog().split("\n")) {
                if (!line.isBlank()) Bukkit.getConsoleSender().sendMessage(stripTags(line));
            }
        }
    }

    /** Player/CommandSender notice with clickable download link (+ optional buttons). */
    public void notifySender(CommandSender sender, String current, UpdateInfo info) {
        if (ADVENTURE_PRESENT) {
            AdventureText.send(sender, templates.playerNotice(current, info));
        } else {
            sender.sendMessage(templates.plainPlayer(current, info));
        }
    }

    /**
     * Post-download "ready to install" notice. When {@code canReload} (a hot-reload
     * engine is present, the environment is safe, and a command root exists to run
     * the command) it offers a no-restart [Install Now]; otherwise it says a restart
     * is needed.
     */
    public void notifyStaged(CommandSender sender, UpdateInfo info, boolean canReload) {
        if (ADVENTURE_PRESENT) {
            AdventureText.send(sender, templates.render(templates.stagedKey(canReload), "", info));
        } else {
            sender.sendMessage(templates.plainStaged(info, canReload));
        }
    }

    /** Console form of the post-download notice. */
    public void notifyStagedConsole(String pluginName, UpdateInfo info, boolean canReload) {
        if (ADVENTURE_PRESENT) {
            AdventureText.console(templates.render(templates.stagedKey(canReload), "", info));
        } else {
            Bukkit.getConsoleSender().sendMessage(templates.plainStaged(info, canReload));
        }
    }

    /** Rendered MiniMessage string for {@code key} (Adventure path only). */
    public String render(String key, String current, UpdateInfo info) {
        return templates.render(key, current, info);
    }

    static String safeVersion(String version) {
        return NoticeTemplates.safeVersion(version);
    }

    static String safeUrl(String url) {
        return NoticeTemplates.safeUrl(url);
    }

    static String stripTags(String s) {
        return NoticeTemplates.stripTags(s);
    }
}
