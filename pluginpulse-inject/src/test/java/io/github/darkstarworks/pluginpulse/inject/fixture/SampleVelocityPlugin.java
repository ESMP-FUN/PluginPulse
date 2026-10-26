package io.github.darkstarworks.pluginpulse.inject.fixture;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;

/** Velocity main with an injected constructor, used as a hook target in tests. */
public class SampleVelocityPlugin {
    private final ProxyServer server;

    @Inject
    public SampleVelocityPlugin(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        // no-op
    }

    public ProxyServer server() {
        return server;
    }
}
