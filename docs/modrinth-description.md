<center>

# PluginPulse
**Your plugins stay up to date, and you decide how far it goes.**

Most servers find out a plugin is outdated the hard way, <br>
from a crash, a security warning, or a player telling them.

[![Discord](https://img.shields.io/badge/join_-_Discord-gray?style=flat&logo=discord&logoSize=amd)](https://discord.gg/qwYcTpHsNC)
[![Ko-Fi](https://img.shields.io/badge/support_-_KoFi-gray?style=flat&logo=kofi&logoSize=amd)](https://ko-fi.com/darkstarworks)

PluginPulse checks where your plugins publish their updates, <br>
tells you when one is out, and can install it for you.

| **Without PluginPulse** | **With PluginPulse** |
|---|---|
| Visit every plugin page to see what's new | One list shows what's behind |
| Download, rename and copy jars by hand | One command, or fully automatic |
| A bad download breaks your server | Every download is checked before it's used |
| A broken update means hunting for the old jar | `/pluginpulse restore` puts it back |
| The wrong jar for your Minecraft version | The one made for your server, picked for you |

</center>

---

<details>
<summary><b>Setup</b></summary>

1. Drop the jar into `plugins/` and start the server
2. List the plugins you want kept up to date in `plugins/PluginPulseCompanion/config.yml`
3. Run `/pluginpulse reload`

```yaml
plugins:
  EssentialsX:              # the name /plugins shows
    modrinth: essentialsx   # where its updates come from
    mode: notify            # tell me, download nothing
```

The plugins you list don't need to know PluginPulse exists.
</details>

---

## What it does

**Finds updates**
- Checks Modrinth, GitHub, Hangar, a Jenkins build server, or a web address you host
- Give a plugin more than one place to check, in the order you choose
- When a plugin makes separate downloads for 1.21 and 26.x, you get the one for your server

**Tells you**
- A notice in the console and in game for anyone with `pluginpulse.admin`
- Click the notice to open the download page, or run `/pluginpulse download` to install it

**Installs, only if you let it**
- Each plugin gets its own mode, from "just tell me" to fully automatic
- Every download is checked against the checksum the author published. A damaged or tampered file is never used
- The old jar is backed up first, and the update is applied on your next restart

**Waits out brand-new releases, if you like**
- Sometimes an update is broken and fixed a few hours later
- Switch on `hold-new-updates` and PluginPulse waits until a release has been out for 18 hours
- `/pluginpulse download <plugin>` still installs one right away

---

## Will it work on my server?

| | |
|---|---|
| **Server software** | Paper, Folia, Spigot, or a Paper fork like Purpur |
| **Minecraft** | 1.20.5 to 1.21.x with the plain download, 26.x (including 26.3) with the `-mc26` download |
| **Java** | 21+ for 1.21.x, 25+ for 26.x |
| **Anything else** | Nothing. No dependencies |

On Spigot, notices are plain text with the link written out, instead of clickable.

---

## Reference

<details>
<summary><b>Commands</b></summary>

| Command | What it does |
|---|---|
| `/pluginpulse` | Every managed plugin, and whether it's up to date |
| `/pluginpulse check [plugin\|all]` | Check now |
| `/pluginpulse download [plugin\|all]` | Download the update, ready for the next restart |
| `/pluginpulse apply [plugin\|all]` | Install a downloaded update without a restart (needs `hot-reload: true`) |
| `/pluginpulse restore [plugin\|all]` | Go back to the previous version on the next restart |
| `/pluginpulse reload` | Re-read the settings file |

Also works as `/ppc` or `/pulse`.

</details>

<details>
<summary><b>Permissions</b></summary>

| Permission | What it does | Default |
|---|---|---|
| `pluginpulse.admin` | Use the commands and see update notices | OP |

</details>

<details>
<summary><b>Modes</b></summary>

| Mode | What happens when an update is found |
|---|---|
| `off` | Nothing, this plugin is ignored |
| `check-only` | Nothing, until you run `/pluginpulse` |
| `notify` (default) | You're told in the console and when you join. Nothing is downloaded |
| `download` | It's downloaded and checked, ready for your next restart |
| `auto-stage` | Same as `download`, as soon as the update is found |

</details>

<details>
<summary><b>Settings people change</b></summary>

```yaml
plugins:
  SomePlugin:
    github: owner/repo           # or modrinth, hangar, jenkins
    mode: download
    hot-reload: false            # true = install without a restart, when it's safe
    match-server-version: true   # take the download made for your Minecraft version

user-agent-contact: "you@example.com"   # Modrinth asks for a way to reach you
check-interval-hours: 6
hold-new-updates: false          # true = wait until a release has been out a while
hold-new-updates-hours: 18
```

More info? [Read about it in the guide](https://esmp-fun.github.io/PluginPulse/companion-plugin/)

</details>

<details>
<summary><b>Making your own plugin update itself</b></summary>

PluginPulse is also a library. Add it to your plugin and it updates itself, with the same checks, notices and backups.

```kotlin
dependencies {
    implementation("com.github.ESMP-FUN.PluginPulse:pluginpulse-core:v0.9.0")
}
tasks.shadowJar {
    relocate("io.github.darkstarworks.pluginpulse", "my.plugin.libs.pluginpulse")
}
```

Then either drop a `pluginpulse.yml` into your resources and call `PluginPulse.bootstrap(this)`, or use the builder for full control.

More info? [Read about it in the library guide](https://esmp-fun.github.io/PluginPulse/adopting-the-library/)

</details>

<details>
<summary><b>Adding updates to a jar you can't rebuild</b></summary>

Have a plugin jar but no source code? The [browser tool](https://esmp-fun.github.io/PluginPulse/) adds an updater to it. Pick the jar, say where its updates come from, and download the new jar.

Everything happens on the page. Your jar is never uploaded.

More info? [Read about it in the browser tool guide](https://esmp-fun.github.io/PluginPulse/web-tool/)

</details>

---

## Help

- **[Discord](https://discord.gg/qwYcTpHsNC)** - ask me directly. Tell me "if it did X, I'd use it" and there's a good chance it ships
- **[Guide](https://esmp-fun.github.io/PluginPulse/companion-plugin/)** - setup, plus [where to find a plugin's updates](https://esmp-fun.github.io/PluginPulse/update-sources/) and a [glossary](https://esmp-fun.github.io/PluginPulse/glossary/)
- **[Bug reports](https://github.com/ESMP-FUN/PluginPulse/issues)**, **[Source](https://github.com/ESMP-FUN/PluginPulse)**, **[Changelog](https://github.com/ESMP-FUN/PluginPulse/blob/master/CHANGELOG.md)**

<center>

**Paper, Folia, Spigot** <br>
**Minecraft 1.20.5 to 26.x**, **Java 21+** <br>
**No dependencies**

Free and source available. <br>
Please consider donating: [Ko-Fi](https://ko-fi.com/darkstarworks) or [Patreon](https://patreon.com/cw/darkstarworks)

Did you know I have other plugins? [Check them out here](https://modrinth.com/organization/esmp)

</center>
