# PluginPulse

Keep your Minecraft server plugins up to date. PluginPulse checks where each plugin publishes its releases, tells you when a new version is out, and can download it for you, safely, so it is swapped in the next time the server restarts.

## Which of these are you?

There are three ways to use it. Pick the one that fits you and follow only that guide; each one starts from zero.

| You... | Use | Guide |
|---|---|---|
| **run a server** and just want your installed plugins kept up to date | the companion plugin | [Companion plugin](companion-plugin.md) |
| **have a plugin jar** you can't or won't rebuild | the browser tool | [Browser tool](web-tool.md) |
| **build your own plugin** and want it to update itself | the library | [Shade the library](adopting-the-library.md) |

New to a word? See the [glossary](glossary.md). Not sure where a plugin's updates come from? See [finding your update source](update-sources.md).

## What it does for you

* **Checks several places**: Modrinth, GitHub Releases, Hangar, Jenkins, or a plugin author's own update file, with backups if the first one is down.
* **Tells the right people**: a message in the console and in-game for staff when an update is out, with buttons to click.
* **Installs safely**: every download is checked against the fingerprint the author published, your current jar is backed up first, and nothing changes until you restart. One command puts the previous version back.
* **Waits out bad releases if you want**: it can leave a brand-new release alone for a few hours, so an author's quick fix lands first.
* **Runs on Paper, Spigot and Folia**, 1.20.5 and newer, Java 21 or newer, and on **Velocity proxies** 3.4 to 4.3 with a companion of its own.

## Links

* [Browser tool](https://esmp-fun.github.io/PluginPulse/)
* [Downloads](https://github.com/ESMP-FUN/PluginPulse/releases)
* [Source code](https://github.com/ESMP-FUN/PluginPulse)
