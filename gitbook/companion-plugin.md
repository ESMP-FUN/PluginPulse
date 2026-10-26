# The companion plugin (for server owners)

**Audience:** you run a Minecraft server and want your installed plugins kept up to date. **No code, no building.** You install one plugin and list the others in a config file.

If a term is unfamiliar, see the [glossary](glossary.md).

## What it is

**PluginPulse Companion** is a normal plugin you drop into `plugins/`. It reads a list of your *other* plugins and where each one publishes updates, then checks (and optionally downloads) updates on their behalf, without those plugins needing to know anything about it.

It never swaps a running jar live: downloaded updates are **staged** and applied the next time you restart. Safe by default.

## 1. Download

Get **`PluginPulseCompanion-<version>.jar`** from the [Releases page](https://github.com/ESMP-FUN/PluginPulse/releases).

## 2. Install

1. Stop your server (or have it running, either is fine for the first install).
2. Put the jar in your server's `plugins/` folder.
3. Start (or restart) the server.

On a successful start you'll see in the console:

```
[PluginPulseCompanion] Enabling PluginPulseCompanion vX.Y.Z
[PluginPulseCompanion] No plugins configured yet: edit config.yml and run /pluginpulse reload.
```

That last line is expected on a fresh install. You configure it next.

## 3. Configure

Open `plugins/PluginPulseCompanion/config.yml`. Here's a complete, worked example for three real-world-shaped plugins:

```yaml
plugins:
  # Key = the plugin's EXACT name as shown by /plugins.
  EssentialsX:
    modrinth: essentialsx        # its Modrinth slug
    mode: notify                 # tell me on join; don't download

  WorldGuard:
    github: EngineHub/WorldGuard  # its GitHub owner/repo (uses Releases)
    mode: download                # download + stage; applies on restart

  MyPaidPlugin:
    hangar: Author/My-Plugin      # its Hangar project slug
    mode: check-only              # silent; only shown via /pluginpulse

# Sent with API requests. Modrinth's rules require a way to reach you
# (an email or a website/URL).
user-agent-contact: "you@example.com"

# How often to check each plugin, in hours (minimum 1).
check-interval-hours: 6

# Give brand-new releases time to settle before touching them.
hold-new-updates: false
hold-new-updates-hours: 18
```

**Every field explained:**

| Field | Meaning |
|---|---|
| `plugins:` | the list of plugins to manage; each key is a plugin's exact name |
| `modrinth` / `github` / `hangar` | where *that* plugin's updates come from (see [finding your update source](update-sources.md)). Use exactly one per plugin |
| `mode` | what to do when an update is found (see below) |
| `match-server-version` | *(optional, per plugin, default `true`)* when a plugin publishes separate downloads for different Minecraft versions, for example one for 1.21 and one for 26.3, take the one made for your server. Works with Modrinth and Hangar. Set `false` to always take the newest release. |
| `track` | *(optional, per plugin)* follow a `-<track>` release line, e.g. `track: mc26` |
| `user-agent-contact` | how the update APIs can reach you; required by Modrinth |
| `check-interval-hours` | how often to check (minimum 1) |
| `hold-new-updates` | `true` to leave a release alone until it has been out a while (see below). Default `false`. Can also be set per plugin |
| `hold-new-updates-hours` | how long that wait is, in hours (minimum 1). Default 18. Can also be set per plugin |

### Waiting out brand-new releases

Sometimes an author publishes an update that turns out to be broken and puts out a fix a few hours later. If your server picked up the broken one in the meantime, you are stuck with it until the next check comes round.

Switch on `hold-new-updates` and PluginPulse ignores a release until it has been publicly available for `hold-new-updates-hours` (18 by default), long enough that a quick fix-up release lands first. It uses the release date the plugin's own page publishes, so the clock starts when the author released it, not when your server first noticed.

The wait covers everything PluginPulse does on its own: the notices to admins, the downloads and the automatic installs. You are never locked out: `/pluginpulse download <plugin>` installs the new version straight away, and `/pluginpulse` shows which releases are being waited out and for how much longer.

**What each `mode` does**, in plain terms:

* `off`: ignore this plugin.
* `check-only`: check quietly; the result only shows in `/pluginpulse`.
* `notify`: check and tell admins (console + on join). **Nothing is downloaded.**
* `download`: check, download, verify, and **stage** the update. It applies the next time you restart the server.
* `auto-stage`: like `download`, but staged automatically as soon as it's found.

After editing, either restart or run `/pluginpulse reload`.

## 4. Use

The command is `/pluginpulse` (aliases `/ppc`, `/pulse`), permission `pluginpulse.admin` (ops have it by default).

* `/pluginpulse` or `/pluginpulse list`: show each managed plugin and whether an update is available.
* `/pluginpulse check [plugin|all]`: check now.
* `/pluginpulse download [plugin|all]`: download + stage available updates.
* `/pluginpulse restore [plugin|all]`: stage the previous backup of a plugin (undo a bad update), applied on restart.
* `/pluginpulse reload`: re-read `config.yml`.

**How staged updates apply:** downloading stages the new jar into the server's update folder. **Restart the server** and the server swaps it in on boot. There's no live jar-swapping. That's deliberate, so nothing changes underneath a running server.

## Running a Velocity proxy?

There is a separate companion for Velocity proxies: **`pluginpulse-companion-velocity-<version>.jar`** on the same [Releases page](https://github.com/ESMP-FUN/PluginPulse/releases). It works on Velocity 3.4 up to 4.3. Install it the same way, in the proxy's `plugins/` folder, then open `plugins/pluginpulse/config.yml`. The file walks you through it.

What is different on a proxy:

* **The command is `/pulseproxy`** (alias `/ppp`), with the same actions: `list`, `check`, `download`, `restore` and `reload`. It has its own name so that `/pluginpulse` still reaches the companion on the servers behind the proxy.
* **The name on the left** of each entry is the plugin's id or name as shown by `/velocity plugins`, for example `luckperms`.
* **Updates are installed when the proxy shuts down.** Velocity can't swap a plugin while it runs, so a downloaded update waits until you stop or restart the proxy, then it is put in place and runs from the next start. Restarting the proxy is enough.
* **It never restarts or reloads anything on its own.** PluginPulse doesn't restart the proxy and doesn't reload plugins while it runs, so your permissions plugin (such as LuckPerms) is never switched off underneath your players. You choose when the proxy restarts.
* **On Windows** the running jar is locked, so putting the update in place can fail. The console then tells you which file to move where, with the proxy stopped.
* **Permissions:** Velocity has no "op", so give `pluginpulse.admin` to your staff with a permissions plugin such as LuckPerms. The proxy console can always use `/pulseproxy`.
* **The right file is picked for you.** When a plugin publishes one file for Paper and one for Velocity, the companion takes the Velocity one.
* **No `hot-reload` and no `track`** on a proxy. Everything else (`mode`, `hold-new-updates`, `require-hash`, `github-token`, `jenkins-artifact`) works the same.

## FAQ

* **Does it work on Spigot?** Yes: Paper, Spigot, and Folia. Velocity proxies have their own companion, see above.
* **My plugin isn't on Modrinth/Hangar.** Use `github: owner/repo` if its releases are on GitHub. If it's published nowhere with an API, the companion can't track it.
* **Is it safe?** By default (`notify`), nothing is downloaded. You're only told about it. Even in `download`/`auto-stage`, updates are checksum-verified, the old jar is backed up, and nothing is swapped until you restart.
* **A plugin shows as "skipped".** The companion prints why (not installed, no source configured, `mode: off`, etc.). Check the name matches `/plugins` exactly and it has one source.

