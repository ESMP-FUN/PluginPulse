package io.github.darkstarworks.pluginpulse.inject.fixture;

import org.bukkit.plugin.java.JavaPlugin;

/** Final sample main used as an INSTRUMENT-strategy injection target in tests. */
public final class FinalSamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // A branch so the compiled method carries stack map frames — exercises
        // the instrumenter's EXPAND_FRAMES handling.
        if (System.currentTimeMillis() > 0) {
            getLogger().info("enabled");
        }
    }
}
