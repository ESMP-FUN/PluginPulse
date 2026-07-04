package io.github.darkstarworks.pluginpulse.hotreload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Exercises the bookkeeping-holder walk against fakes shaped like both known
 * server layouts (fields matter, classes don't).
 */
class PluginManagerAccessTest {

    /** Legacy layout: the manager itself holds plugins + lookupNames. */
    static class FakeSimplePluginManager {
        final List<Object> plugins = new ArrayList<>();
        final Map<String, Object> lookupNames = new HashMap<>();
    }

    /** Paper 1.20.5+ layout: manager -> paperPluginManager -> instanceManager. */
    static class FakeInstanceManager {
        final List<Object> plugins = new ArrayList<>();
        final Map<String, Object> lookupNames = new HashMap<>();
    }

    static class FakePaperPluginManagerImpl {
        final FakeInstanceManager instanceManager = new FakeInstanceManager();
    }

    static class FakeDelegatingSimplePluginManager {
        final FakePaperPluginManagerImpl paperPluginManager = new FakePaperPluginManagerImpl();
    }

    static class FakeUnknownManager {
        final String somethingElse = "nope";
    }

    @Test
    void findsLegacyLayoutDirectly() {
        FakeSimplePluginManager manager = new FakeSimplePluginManager();
        assertSame(manager, PluginManagerAccess.findBookkeepingHolder(manager));
    }

    @Test
    void walksPaperDelegationChain() {
        FakeDelegatingSimplePluginManager manager = new FakeDelegatingSimplePluginManager();
        assertSame(manager.paperPluginManager.instanceManager,
                PluginManagerAccess.findBookkeepingHolder(manager));
    }

    @Test
    void unknownLayoutReturnsNull() {
        assertNull(PluginManagerAccess.findBookkeepingHolder(new FakeUnknownManager()));
    }
}
