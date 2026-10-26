package io.github.darkstarworks.pluginpulse;

import io.github.darkstarworks.pluginpulse.engine.UpdateEngine;
import org.bukkit.command.CommandSender;

import java.util.List;

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
        return updater.handle(sender, args);
    }

    /** Tab-completion options for the first argument after "update". */
    public List<String> tabComplete(String[] args) {
        return args.length <= 1 ? UpdateEngine.ACTIONS : List.of();
    }
}
