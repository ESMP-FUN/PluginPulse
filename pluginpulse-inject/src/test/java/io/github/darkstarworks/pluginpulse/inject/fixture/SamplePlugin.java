package io.github.darkstarworks.pluginpulse.inject.fixture;

import org.bukkit.plugin.java.JavaPlugin;

/** Non-final sample main used as a WRAPPER-strategy injection target in tests. */
public class SamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // no-op
    }

    @Override
    public void onDisable() {
        // no-op
    }
}
