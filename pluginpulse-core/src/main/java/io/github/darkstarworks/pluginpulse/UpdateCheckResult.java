package io.github.darkstarworks.pluginpulse;

/**
 * Outcome of one update check.
 */
public record UpdateCheckResult(Status status, UpdateInfo info, Throwable error) {

    /**
     * {@code HELD} means a newer version exists but hasn't been out long enough
     * yet for the configured settle-in time — see
     * {@link Updater.Builder#minimumReleaseAge(java.time.Duration)}.
     */
    public enum Status { UP_TO_DATE, UPDATE_AVAILABLE, HELD, IGNORED, FAILED }

    public static UpdateCheckResult upToDate() {
        return new UpdateCheckResult(Status.UP_TO_DATE, null, null);
    }

    public static UpdateCheckResult available(UpdateInfo info) {
        return new UpdateCheckResult(Status.UPDATE_AVAILABLE, info, null);
    }

    /** Newer, but still inside its settle-in window. */
    public static UpdateCheckResult held(UpdateInfo info) {
        return new UpdateCheckResult(Status.HELD, info, null);
    }

    public static UpdateCheckResult ignored(UpdateInfo info) {
        return new UpdateCheckResult(Status.IGNORED, info, null);
    }

    public static UpdateCheckResult failed(Throwable error) {
        return new UpdateCheckResult(Status.FAILED, null, error);
    }
}
