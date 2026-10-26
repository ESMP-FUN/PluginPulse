package io.github.darkstarworks.pluginpulse.state;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Marker written when an update is staged into the server update folder,
 * cleared when the new version successfully boots. If the marker survives
 * several boots, the staged update never applied (or keeps failing) and
 * automatic re-staging should stop.
 */
public final class PendingUpdateStore {

    public record Pending(String stagedVersion, String backupPath, int attempts) {
    }

    private static final Gson GSON = new Gson();

    private final Path file;
    private final Logger logger;

    public PendingUpdateStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized Pending load() {
        if (!Files.exists(file)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new Pending(
                    root.get("stagedVersion").getAsString(),
                    root.has("backupPath") && !root.get("backupPath").isJsonNull()
                            ? root.get("backupPath").getAsString() : null,
                    root.has("attempts") ? root.get("attempts").getAsInt() : 0);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read pending-update marker: " + e.getMessage());
            return null;
        }
    }

    public synchronized void save(Pending pending) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("stagedVersion", pending.stagedVersion());
            root.addProperty("backupPath", pending.backupPath());
            root.addProperty("attempts", pending.attempts());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write pending-update marker: " + e.getMessage());
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not delete pending-update marker: " + e.getMessage());
        }
    }
}
