<!-- Banner: add a PluginPulse banner image here, e.g. an <img> like the other pages. -->

<div align="center">

# PluginPulse

### Keep your plugins up to date — the way that suits you
Drop-in companion plugin · shade-in library · in-browser tool. Paper · Spigot · Folia.

<br>

**Got a "if it did [THING], I'd use it" idea? Tell me!** [<img src="https://raw.githubusercontent.com/darkstarworks/TrialChamberPro/master/dc.png" width="20" alt="Join Discord Server">](https://discord.gg/qwYcTpHsNC)

Donating is Free! (for me): [ [Ko-Fi](https://ko-fi.com/darkstarworks) ]

</div>

<br>

Most servers find out a plugin is outdated the hard way — a crash, a security
advisory, or a player pointing it out. PluginPulse checks the places your plugins
actually publish releases (Modrinth, GitHub, Hangar, or a self-hosted manifest),
tells you when something's behind, and — if you let it — downloads the update,
verifies its checksum, backs up the old jar, and stages the new one to apply on
your next restart.

It's **three tools in one project**, so you pick the one that matches you:

| You… | Use | 
|---|---|
| **run a server** and want your installed plugins kept current | the **companion plugin** (the download here) |
| **build your own plugin** and want it to self-update | the **library** — shade it in |
| **have a jar** you can't or won't rebuild | the **browser tool** — add updates to it in your browser |

---

## Compatibility

| | |
|---|---|
| **Server software** | Paper, Spigot, Folia, and Paper-compatible forks (Purpur, Pufferfish, etc.) |
| **Minecraft versions** | 1.20.5 – 1.21.x (plain jar) · 26.x (`-mc26` jar) |
| **Java** | 21+ for 1.20.5–1.21.x · 25+ for 26.x |
| **Dependencies** | None. Nothing to install alongside it. |

On Spigot (no Adventure), rich clickable notices automatically fall back to plain
text with the download link spelled out — everything else works identically.
Folia is detected at runtime and scheduler calls are routed to the regional
schedulers automatically.

---

## The companion plugin (the download)

**PluginPulse Companion** is a normal plugin you drop into `plugins/`. It reads a
list of your *other* plugins and where each one publishes updates, then checks
(and optionally downloads) updates on their behalf — those plugins don't need to
know anything about it.

```yaml
# plugins/PluginPulseCompanion/config.yml
plugins:
  # Key = the plugin's EXACT name as shown by /plugins
  EssentialsX:
    modrinth: essentialsx        # its Modrinth slug
    mode: notify                 # tell me on join; download nothing

  WorldGuard:
    github: EngineHub/WorldGuard  # GitHub owner/repo (uses Releases)
    mode: download                # download + stage; applies on restart

  SomePaidPlugin:
    hangar: Author/My-Plugin      # Hangar project slug
    mode: check-only              # silent; only shown in /pluginpulse

user-agent-contact: "you@example.com"   # required by Modrinth's API rules
check-interval-hours: 6
```

Edit, then `/pluginpulse reload`. That's the whole setup.

### Update sources

Point each plugin at wherever *it* publishes releases:

- **Modrinth** — project slug (`modrinth: my-plugin`)
- **GitHub Releases** — `github: owner/repo`
- **Hangar** — project slug
- **Custom JSON manifest** — a self-hosted URL (headers can carry auth, e.g. licence keys)

List more than one as fallbacks. You choose the check order — a **priority
setting** (in the browser tool, a #1/#2/#3 dropdown; in YAML, a `source-order`
list) decides which is tried first.

### Update modes — you're always in control

| Mode | What happens when an update is found |
|---|---|
| `off` | ignore this plugin |
| `check-only` | check quietly; result only in `/pluginpulse` |
| `notify` *(default)* | check + tell admins (console & on join). **Nothing downloaded.** |
| `download` | check, download, checksum-verify, and **stage** for the next restart |
| `auto-stage` | like `download`, but staged automatically as soon as it's found |

### Safe by design

Nothing is ever swapped under a running server. Downloads are **checksum-verified**
(sha512 → sha256 → sha1; unsigned downloads are refused unless you opt in), the
current jar is **backed up**, and the new jar is **staged** into the server's
update folder to apply on your next restart. `/pluginpulse restore` stages the
previous backup to roll a bad update back.

### Commands

`/pluginpulse` (aliases `/ppc`, `/pulse`), permission `pluginpulse.admin` (ops by default):

| Command | What it does |
|---|---|
| `/pluginpulse` · `list` | show each managed plugin and whether an update is available |
| `/pluginpulse check [plugin\|all]` | check now |
| `/pluginpulse download [plugin\|all]` | download + stage available updates |
| `/pluginpulse restore [plugin\|all]` | stage the previous backup (undo a bad update) |
| `/pluginpulse reload` | re-read `config.yml` |

---

## For plugin developers — the library

Want your *own* plugin to self-update? Shade the library in via
[JitPack](https://jitpack.io) and relocate it:

```kotlin
dependencies {
    implementation("com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.6.0")
}
tasks.shadowJar {
    relocate("io.github.darkstarworks.pluginpulse", "my.plugin.libs.pluginpulse")
}
```

Then it's either **one file** (drop a `pluginpulse.yml` in your resources and call
`PluginPulse.bootstrap(this)` / `shutdown(this)`) or the full **builder** for
custom sources, message re-branding, tracks, and more. You get MiniMessage
console + in-game notices, semver-ish version comparison with pre-release
awareness, distribution **tracks** for parallel builds (e.g. `1.7.3` and
`1.7.3-mc26`), rate-limit-friendly checks, and verified one-command installs with
rollback — all dependency-free. See the
[library guide](https://esmp-fun.github.io/PluginPulse/adopting-the-library/).

*(Optional `pluginpulse-hotreload` add-on can apply a staged update without a
restart — with deliberate hard limits: refused on Folia and when other plugins
depend on yours. Restart-install stays the recommended default.)*

---

## The browser tool — for a jar you can't rebuild

Have a plugin jar but no source and no build tools? The
[browser tool](https://esmp-fun.github.io/PluginPulse/web-tool/)
adds an auto-updater to a compiled jar **entirely in your browser** — pick the
jar, choose where its updates come from, generate a new jar. Your jar is never
uploaded anywhere; all the work happens locally on the page.

---

## 100% Free & Source Available

No licence key, no telemetry, no "premium" gating. Source-available licence, full source on
[GitHub](https://github.com/ESMP-FUN/PluginPulse) — issues and PRs welcome.

---

## Links

- **Source / issues**: [github.com/ESMP-FUN/PluginPulse](https://github.com/ESMP-FUN/PluginPulse)
- **Server owners — companion guide**: [Companion plugin guide](https://esmp-fun.github.io/PluginPulse/companion-plugin/)
- **Developers — library guide**: [Library guide](https://esmp-fun.github.io/PluginPulse/adopting-the-library/)
- **Jar-only — browser tool guide**: [Browser tool guide](https://esmp-fun.github.io/PluginPulse/web-tool/)
- **Finding your update source**: [Finding your update source](https://esmp-fun.github.io/PluginPulse/update-sources/)

<div align="center">

---

**Paper · Spigot · Folia** · **MC 1.20.5 – 1.21.x / 26.x** · **Java 21+**

Made by [darkstarworks](https://github.com/darkstarworks)

Did you know I have other plugins? [ [Check them out here](https://modrinth.com/organization/esmp) ]

Donating is free! (for me): [Ko-Fi](https://ko-fi.com/darkstarworks)

</div>
