# The browser tool (for jar-only users)

**Audience:** you have a compiled plugin `.jar` but can't or won't rebuild it from source, and you want to add auto-updates to it.

If a term is unfamiliar, see the [glossary](glossary.md).

## What it does

The tool adds PluginPulse to an existing plugin jar and hands you a new jar. It runs **entirely in your web browser**: **your jar is never uploaded to any server.** There's nothing to install and no account.

**Open it:** [esmp-fun.github.io/PluginPulse](https://esmp-fun.github.io/PluginPulse/)

## Step by step

1. **Open** the tool (link above).
2. **Choose your plugin jar**: drag it onto the drop zone or click to browse, and, optionally, click **Preview jar**. Preview reads the jar on your own computer and shows the plugin's name, version, author and whether the tool can update it, without changing anything.
3. **Fill in the fields:**

| Field | What to enter | Where to find it |
|---|---|---|
| Modrinth / GitHub / Hangar / Jenkins | at least one update source | [finding your update source](update-sources.md) |
| Jenkins artifact filter | *(optional)* case-insensitive regex picking one jar when a build archives several, e.g. `Paper` | the job's build artifact list |
| GitHub pass key | *(optional)* only for a **private** GitHub repository. Prefer `${GITHUB_TOKEN}` and set that on the server; a key typed in full is written into the jar, so only do that for a jar that stays on your own server | your repository's settings |
| Update mode | how it should behave (see below) | your choice |
| Command root | *(optional)* e.g. `/myplugin`, a command to manage updates | your choice |
| Permission | *(optional)* who sees notices, e.g. `myplugin.admin` | your choice |
| Contact | *(recommended)* email or URL | required by Modrinth's API |
| Release track | *(optional)* e.g. `mc26` | only if the plugin ships parallel builds |
| Check interval | *(optional)* hours between checks | default is fine |
| Settle time | *(optional)* ignore a release until it has been publicly available for this many hours, so an author's own quick fix-up release lands before the server takes the update | off unless ticked; 18 hours if you leave the box blank |
| Install without restarting | *(optional)* swap the plugin over while the server runs. Checks first and falls back to a restart-install when that would be unsafe (Folia, other plugins depending on this one, a bundled native library) | leave off if unsure |

**`mode` for beginners:**

  * `notify`: tells admins when an update exists; the notice has a **[Download & Install]** button so they can pull it in the background without visiting a page.
  * `check-only`: silent; the result shows only via the command.
  * `download`: downloads and stages the update; it applies when you restart.
  * `auto-stage`: downloads automatically as soon as an update appears (and, with hot-reload on a safe server, applies it with no restart).

**Jenkins note:** CI builds are dev snapshots and Jenkins publishes no checksums. If you pick `download` or `auto-stage` with a Jenkins source, the tool writes `require-hash: false` into the generated config so staging accepts the unverified artifact, otherwise CI updates would be notify-only.

4. **Tick the rights checkbox**: confirm you're allowed to modify and redistribute this jar. (You're altering someone's compiled software; only do this for jars you have the right to change.)
5. **Generate**: the tool builds `<yourplugin>-pulse.jar` and your browser downloads it.
6. **Test it on your own server first**: drop the new jar in `plugins/`, start the server, and confirm the plugin still enables and logs an update check. The tool can't verify the jar boots; that's on you before you share it.

## Limits (read these)

* **Wrapper strategy.** If the plugin's main class is `final` (the default for Kotlin plugins), the tool clears that flag so the wrapper can subclass it, removing `final` only permits subclassing and is safe for a plugin main class. If you'd rather edit a final main in place, the command-line tool `pluginpulse-inject` uses the instrument strategy instead.
* **It can't confirm the jar runs.** Always test before distributing.
* **Any class-file version works,** including Java 25 / mc26 jars, and `plugin.yml`, `paper-plugin.yml` and `velocity-plugin.json` descriptors.
* **Velocity plugins** get the proxy version of the updater. The tool adds two small pieces to the plugin's main class and leaves its `velocity-plugin.json` as it is. A downloaded update is installed when the proxy shuts down; the updater never restarts the proxy or reloads plugins on its own. A jar made for both Paper and Velocity gets both.

## "It said my jar can't be processed"

Common reasons and what to do:

* **"main class is final"**: expected for some plugins; use the CLI tool (`pluginpulse-inject`), which handles this case.
* **"already contains PluginPulse"**: it was injected before. Tick the **re-inject** box only if you mean to redo it.
* **"No plugin.yml, paper-plugin.yml or velocity-plugin.json with a main class"**. The file isn't a Paper, Spigot or Velocity plugin jar (or its descriptor is missing/renamed). Double-check you selected the right file.

