# Changelog

## [0.9.0] - 2026-09-23

Tested on Paper 26.3 (build 35) and Spigot 26.3: update checks, downloads, installs on restart, installs without a restart, and the companion plugin.

### Added
- **Picks the download made for your server's Minecraft version.** When a plugin publishes separate jars (for example 1.21, 26.1 and 26.3), PluginPulse takes the one Modrinth or Hangar lists for your server, with no `track` needed. Turn it off with `match-server-version: false`.
- **Releases are built and published automatically** when a version is tagged, with both companion jars attached.

### Fixed
- **Stopping the server during an update check no longer prints errors.** It used to show a "nag the author" error, or a "zip file closed" error against the plugin that bundles PluginPulse.
- **The companion plugin now manages plugins that start after it.** Before, it skipped them with "Managing 0 plugin(s)", depending on the order the server enabled plugins.
- **After an install without a restart, the update command works again.** It kept talking to the old version and failed until the next restart.
- **Installing without a restart works on Spigot.** It used to fail halfway, leaving the plugin switched off with no commands until a restart.
- **Installing without a restart no longer runs the plugin's startup step twice** on Paper.
- **Update notices on Spigot read cleanly.** Part of the button text used to show up as raw markup.
- **Installing without a restart now respects plugins that depend on it** through `paper-plugin.yml`, not only `plugin.yml`.
- **`update status` tells the truth before the first check and after a failed one.** It used to say "up to date" in both cases.
- **The self-registered update command now tells players they lack permission** instead of saying nothing.
- **A track that has no release never falls back to another track's jar** on Modrinth. A plain version with no suffix is still accepted.
- **Servers with a Turkish system language get the right default permission** (`plugin.update`, not a dotless-i spelling).
- **Version text from an update source can no longer change what a clickable message does.**
