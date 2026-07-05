/* Browser glue for the PluginPulse web tool. All work happens locally; the jar
 * never leaves the page. Requires jszip.min.js, constant-pool.js, injector.js. */
(function () {
  'use strict';

  let assets = null;
  let selectedFile = null;

  const $ = (id) => document.getElementById(id);

  async function loadAssets() {
    if (assets) return assets;
    const [core, tpl] = await Promise.all([
      fetch('engine/pluginpulse-core.jar').then((r) => r.arrayBuffer()),
      fetch('engine/wrapper-template.class').then((r) => r.arrayBuffer()),
    ]);
    assets = { coreJar: new Uint8Array(core), wrapperTemplate: new Uint8Array(tpl) };
    return assets;
  }

  function status(msg, kind) {
    const el = $('status');
    el.textContent = msg;
    el.className = 'status ' + (kind || '');
  }

  function readOptions() {
    return {
      modrinth: $('modrinth').value.trim(),
      github: $('github').value.trim(),
      hangar: $('hangar').value.trim(),
      permission: $('permission').value.trim(),
      commandRoot: $('commandRoot').value.trim(),
      mode: $('mode').value,
      contact: $('contact').value.trim(),
      track: $('track').value.trim(),
      checkIntervalHours: $('interval').value ? parseInt($('interval').value, 10) : null,
      upgrade: $('upgrade').checked,
    };
  }

  function onFile(e) {
    selectedFile = e.target.files[0] || null;
    $('inspectOut').textContent = '';
    if (selectedFile) status('Selected ' + selectedFile.name + '.', '');
  }

  async function fileBytes() {
    return new Uint8Array(await selectedFile.arrayBuffer());
  }

  async function inspect() {
    if (!selectedFile) { status('Choose a plugin jar first.', 'err'); return; }
    try {
      status('Inspecting…', '');
      const info = await window.PPInjector.inspectJar(await fileBytes());
      $('inspectOut').textContent =
        'descriptor : ' + info.descriptor + '\n' +
        'main       : ' + info.main + '\n' +
        'strategy   : ' + info.strategy + '\n' +
        'final main : ' + info.finalMain + '\n' +
        'injected   : ' + info.alreadyInjected;
      status(info.finalMain
        ? 'This jar has a final main — the web tool can\'t process it. Use the command-line tool.'
        : 'Looks good — fill in the fields and Generate.', info.finalMain ? 'err' : 'ok');
    } catch (err) {
      status('Inspect failed: ' + err.message, 'err');
    }
  }

  function download(bytes, name) {
    const blob = new Blob([bytes], { type: 'application/java-archive' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  async function generate() {
    if (!selectedFile) { status('Choose a plugin jar first.', 'err'); return; }
    if (!$('rights').checked) { status('Please confirm you have the right to modify and redistribute this jar.', 'err'); return; }
    const opts = readOptions();
    if (!opts.modrinth && !opts.github && !opts.hangar) {
      status('Provide at least one update source (Modrinth, GitHub, or Hangar).', 'err');
      return;
    }
    try {
      status('Generating…', '');
      const a = await loadAssets();
      const out = await window.PPInjector.injectJar(await fileBytes(), opts, a);
      const base = selectedFile.name.replace(/\.jar$/i, '');
      download(out, base + '-pulse.jar');
      status('Done — downloaded ' + base + '-pulse.jar. Test it on your own server before distributing.', 'ok');
    } catch (err) {
      status('Could not process this jar: ' + err.message, 'err');
    }
  }

  window.addEventListener('DOMContentLoaded', () => {
    $('file').addEventListener('change', onFile);
    $('inspect').addEventListener('click', inspect);
    $('generate').addEventListener('click', generate);
  });
})();
