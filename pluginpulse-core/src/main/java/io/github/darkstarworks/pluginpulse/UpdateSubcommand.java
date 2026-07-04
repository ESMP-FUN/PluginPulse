package io.github.darkstarworks.pluginpulse;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * Drop-in handler for a host plugin's {@code /<plugin> update ...} subcommand.
 * Hosts delegate the argument tail (everything after "update") here:
 *
 * <pre>{@code
 * case "update" -> updateSubcommand.handle(sender, Arrays.copyOfRange(args, 1, args.length));
 * }</pre>
 *
 * <p>Supported actions: {@code check} (default), {@code download}/{@code install},
 * {@code ignore <version>}, {@code unignore <version>}, {@code restore},
 * {@code status}.</p>
 */
public final class UpdateSubcommand {

    private final Updater updater;

    public UpdateSubcommand(Updater updater) {
        this.updater = updater;
    }

    /** @return false when the sender lacks the updater permission */
    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission(updater.permission())) {
            return false;
        }
        String action = args.length == 0 ? "check" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "check" -> updater.checkNow(sender);
            case "download", "install" -> updater.downloadAndStage(sender);
            case "ignore" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: update ignore <version>");
                } else {
                    updater.ignoreVersion(args[1]);
                    sender.sendMessage("Ignoring version " + args[1] + " (until a newer release).");
                }
            }
            case "unignore" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: update unignore <version>");
                } else {
                    updater.unignoreVersion(args[1]);
                    sender.sendMessage("No longer ignoring version " + args[1] + ".");
                }
            }
            case "restore" -> updater.restoreBackup(sender);
            case "apply", "reload" -> updater.applyNow(sender);
            case "status" -> {
                UpdateInfo pending = updater.pendingUpdate();
                sender.sendMessage(pending != null
                        ? "Update available: " + pending.version() + " (current: " + updater.currentVersion() + ")"
                        : "Up to date (" + updater.currentVersion() + ") as of the last check.");
            }
            default -> sender.sendMessage(
                    "Unknown update action. Try: check, download, ignore <v>, unignore <v>, restore, status");
        }
        return true;
    }

    /** Tab-completion options for the first argument after "update". */
    public List<String> tabComplete(String[] args) {
        if (args.length <= 1) {
            return List.of("check", "download", "apply", "ignore", "unignore", "restore", "status");
        }
        return List.of();
    }
}
