/* Anna console. The server renders every page and fragment; this script adds the theme toggle, the status strip's
   test controls posting on change, the start panel as a dialog, filters that apply on change, the composer's
   optimistic send, the inspector's toggles, prompt dialog and live log, and Monaco over the prompt editor's form.
   Every action is also a plain form post or link, so the console works without it. */
(function () {
  'use strict';
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  /* ---------- Shell ---------- */

  /* The saved theme is applied in the page head, before paint; this only cycles and labels it. */
  function initTheme() {
    const root = document.documentElement;
    const toggle = $('#theme-toggle');
    if (!toggle) return;
    const label = () => { toggle.textContent = 'Theme: ' + (root.dataset.theme || 'auto'); };
    toggle.hidden = false;
    label();
    toggle.addEventListener('click', () => {
      const next = { auto: 'dark', dark: 'light', light: 'auto' }[root.dataset.theme || 'auto'];
      if (next === 'auto') delete root.dataset.theme; else root.dataset.theme = next;
      try { if (next === 'auto') localStorage.removeItem('theme'); else localStorage.setItem('theme', next); } catch (e) { /* private window */ }
      label();
      document.dispatchEvent(new Event('themechange'));
    });
  }

  /* The console fills the viewport below the top bar and banners, whose height wraps with the width. */
  function trackChromeHeight() {
    const measure = () => {
      const height = ($('.topbar') ? $('.topbar').offsetHeight : 0) + ($('#banners') ? $('#banners').offsetHeight : 0);
      document.documentElement.style.setProperty('--chrome-h', height + 'px');
    };
    window.addEventListener('resize', measure);
    measure();
  }

  /* The status strip's test controls post as a select changes, so their buttons are only for pages without the script. */
  function initAutosubmit() {
    $$('[data-autosubmit]').forEach((select) => select.addEventListener('change', () => select.form.requestSubmit()));
    $$('[data-autosubmit-button]').forEach((button) => { button.hidden = true; });
  }

  /* The full id is the copy button's: the page shows only its start. */
  function initCopyButtons() {
    $$('[data-copy]').forEach((button) => {
      button.hidden = false;
      button.addEventListener('click', () => {
        navigator.clipboard.writeText(button.dataset.copy)
          .then(() => { button.textContent = 'copied'; })
          .catch(() => { button.textContent = 'copy failed'; });
      });
    });
  }

  /* ---------- List filters ---------- */

  /* A filter applies as soon as it changes, and only the filters that are set go into the URL. */
  function initFilters() {
    const form = $('#filter-controls');
    if (!form) return;
    $('.apply-filters', form).hidden = true;
    form.addEventListener('change', (e) => { if (e.target.matches('select, input[type=checkbox]')) applyFilters(form); });
    form.addEventListener('submit', (e) => { e.preventDefault(); applyFilters(form); });
  }

  function applyFilters(form) {
    const params = new URLSearchParams();
    new FormData(form).forEach((value, key) => { if (value) params.append(key, value); });
    const query = params.toString();
    location.assign(form.action + (query ? '?' + query : ''));
  }

  /* ---------- Start panel ---------- */

  /* Without script the start link loads the page with the panel open; with it the panel opens in place. */
  function initStartPanel() {
    const panel = $('#start-panel');
    if (!panel) return;
    const lead = $('#start-lead');
    const custom = $('#custom-lead');
    let opener = null;
    const showCustom = () => { custom.hidden = lead.value !== 'custom'; };
    const close = () => { panel.hidden = true; if (opener) opener.focus(); };
    $$('[data-opens="start-panel"]').forEach((link) => link.addEventListener('click', (e) => {
      e.preventDefault();
      opener = e.currentTarget;
      panel.hidden = false;
      lead.focus();
    }));
    $('#start-cancel').addEventListener('click', (e) => { e.preventDefault(); close(); });
    panel.addEventListener('click', (e) => { if (e.target === panel) close(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !panel.hidden) close(); });
    lead.addEventListener('change', showCustom);
    showCustom();
    if (!panel.hidden) ($('.field.invalid input, .field.invalid select', panel) || lead).focus();
  }

  /* ---------- Composer ---------- */

  function initComposer() {
    const form = $('#composer-form');
    if (!form) return;
    const text = $('#composer-text');
    text.addEventListener('input', updateCount);
    text.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(form, text.value); }
    });
    $('#quick-replies').addEventListener('click', (e) => {
      const chip = e.target.closest('button');
      if (!chip) return;
      e.preventDefault();
      if (e.altKey) { text.value = chip.value; updateCount(); text.focus(); return; }
      send(form, chip.value);
    });
    form.addEventListener('submit', (e) => {
      if (e.submitter && e.submitter.id === 'reset-btn') {
        if (!confirm('Start over with this lead? This conversation is kept for history.')) e.preventDefault();
        return;
      }
      e.preventDefault();
      send(form, text.value);
    });
    scrollToEnd($('#thread'));
  }

  /* The counter turns red past the platform's inbound limit but never blocks: the platform would deliver it. */
  function updateCount() {
    const text = $('#composer-text');
    const count = $('#composer-count');
    const max = Number(count.dataset.max);
    count.textContent = text.value.length + ' / ' + max;
    count.classList.toggle('over', text.value.length > max);
  }

  /* Optimistic send: the lead's bubble, marked as sending, and a typing bubble appear at once, the box
     empties and the composer locks; the server's fragment for the turn replaces them. A failed send puts
     the text back. The composer unlocks whatever happened, an ended conversation included, so silence
     can be verified. */
  function send(form, raw) {
    const message = raw.trim();
    if (!message || form.getAttribute('aria-busy') === 'true') return;
    const thread = $('#thread');
    const hint = $('#composer-hint');
    const box = $('#composer-text');
    const typing = bubble('bubble agent typing', 'Anna is thinking…');
    typing.setAttribute('aria-hidden', 'true');
    const pending = [bubble('bubble lead', message), bubble('bubble-meta lead', 'sending…'), typing];
    thread.append(...pending);
    scrollToEnd(thread);
    const typed = box.value;
    box.value = '';
    updateCount();
    setBusy(form, true);
    hint.textContent = 'Waiting for the agent. Another message for this conversation waits for this turn or gets 409.';
    const failed = (text) => { box.value = typed; updateCount(); hint.textContent = text; };
    request(form.action, { method: 'POST', body: new URLSearchParams({ text: message }) })
      .then(({ ok, status, body }) => {
        const root = fragmentRoot(body);
        pending.forEach((el) => el.remove());
        if (ok && root) {
          thread.append(...root.children);
          hint.textContent = root.dataset.hint;
          hint.dataset.kind = root.dataset.hintKind;
          refreshState();
          loadTurn(root.dataset.turn);
          return;
        }
        failed(root ? root.dataset.hint : 'Send failed with HTTP ' + status + '. Reload to see what was stored.');
      })
      .catch(() => {
        pending.forEach((el) => el.remove());
        failed('Send failed: no answer from the server. Reload to see what was stored.');
      })
      .finally(() => { setBusy(form, false); scrollToEnd(thread); box.focus(); });
  }

  /* A turn changes the list row, the thread, the state header, the spend meter and the inspector's totals:
     take them from a fresh copy of this page. */
  function refreshState() {
    request(location.pathname + location.search)
      .then(({ ok, body }) => {
        if (!ok) return;
        const page = new DOMParser().parseFromString(body, 'text/html');
        ['#conv-list', '#conv-count', '#thread', '.state-head', '#status-strip .meter', '#conv-footer'].forEach((sel) => {
          const fresh = $(sel, page);
          const stale = $(sel);
          if (fresh && stale) stale.replaceWith(fresh);
        });
        scrollToEnd($('#thread'));
      })
      .catch(() => { /* the next page load shows the fresh state */ });
  }

  /* The one fetch helper. The header tells the server a script is asking, so a send answers with a fragment. */
  function request(url, options) {
    return fetch(url, Object.assign({ headers: { 'X-Requested-With': 'fetch' } }, options))
      .then((res) => res.text().then((body) => ({ ok: res.ok, status: res.status, body })));
  }

  /* A send answers with one element carrying data-hint: the turn's items, or a problem line. */
  function fragmentRoot(html) {
    const root = parseFragment(html);
    return root && root.dataset.hint !== undefined ? root : null;
  }

  function parseFragment(html) {
    const template = document.createElement('template');
    template.innerHTML = html.trim();
    return template.content.firstElementChild;
  }

  function bubble(className, text) {
    const el = document.createElement('div');
    el.className = className;
    el.textContent = text;
    return el;
  }

  function setBusy(form, busy) {
    form.setAttribute('aria-busy', String(busy));
    $$('textarea, button', form).forEach((el) => { el.disabled = busy; });
  }

  function scrollToEnd(thread) {
    if (thread) thread.scrollTop = thread.scrollHeight;
  }

  /* ---------- Inspector ---------- */

  /* Expand and collapse need the script, so they show only with it; the raw JSON toggle is also a form post. */
  function initInspector() {
    const turns = $('#turns');
    if (!turns) return;
    $$('#expand-all, #collapse-all').forEach((button) => { button.hidden = false; });
    $$('#expand-all').forEach((button) => button.addEventListener('click', () => setTurnsOpen(true)));
    $$('#collapse-all').forEach((button) => button.addEventListener('click', () => setTurnsOpen(false)));
    $$('#raw-form').forEach((form) => form.addEventListener('submit', toggleRawJson));
    document.addEventListener('click', (e) => {
      const link = e.target.closest('a[data-turn]');
      if (link && focusTurn(link.dataset.turn)) e.preventDefault();
    });
    if (turns.dataset.live === 'true') subscribeTurns();
  }

  function setTurnsOpen(open) {
    $$('#turns .turn').forEach((turn) => { turn.open = open; });
  }

  /* The blocks toggle in place and the session keeps the choice for the next load. */
  function toggleRawJson(e) {
    e.preventDefault();
    const form = e.currentTarget;
    const on = form.elements.on.value === 'true';
    $$('#turns .raw-json').forEach((pre) => { pre.hidden = !on; });
    $('#raw-toggle').setAttribute('aria-pressed', String(on));
    form.elements.on.value = String(!on);
    request(form.action, { method: 'POST', body: new URLSearchParams({ on }) })
      .catch(() => { /* this page still toggled; the next load shows the session's last choice */ });
  }

  /* A turn link in the thread opens that turn's card; without the script it is a plain anchor. */
  function focusTurn(n) {
    const card = $('#turn-' + n);
    if (!card) return false;
    card.open = true;
    card.scrollIntoView({ block: 'start', behavior: 'smooth' });
    $('summary', card).focus({ preventScroll: true });
    return true;
  }

  /* Live log: the server sends ids only, and a 'turn' event once a turn has committed, so its card and a fresh
     copy of the page are fetched; this tab's own send fetches them itself. Three failures in a row close the
     log, and a reload still shows everything. The chip's words are its data attributes. */
  function subscribeTurns() {
    const chip = $('#live-chip');
    const source = new EventSource($('#turns').dataset.base + '/events');
    let failures = 0;
    chip.hidden = false;
    source.onopen = () => { failures = 0; chip.textContent = chip.dataset.on; };
    source.addEventListener('turn', (e) => {
      if ($('#composer-form[aria-busy="true"]')) return;
      loadTurn(JSON.parse(e.data).turn).then((shown) => { if (shown) refreshState(); });
    });
    source.onerror = () => {
      failures += 1;
      if (failures < 3 && source.readyState !== EventSource.CLOSED) { chip.textContent = chip.dataset.reconnecting; return; }
      source.close();
      chip.textContent = chip.dataset.off;
      chip.classList.replace('ghost', 'warn');
    };
  }

  /* Puts turn n's card in place, open, the others closed. */
  function loadTurn(n) {
    return request($('#turns').dataset.base + '/turns/' + n).then(({ ok, body }) => {
      const card = ok ? parseFragment(body) : null;
      if (!card) return false;
      setTurnsOpen(false);
      const stale = $('#turn-' + n);
      if (stale) { stale.replaceWith(card); return true; }
      const empty = $('#turns > .empty');
      if (empty) empty.remove();
      $('#turns').append(card);
      return true;
    }).catch(() => false);
  }

  /* ---------- Prompt dialog ---------- */

  /* The prompt opens in the page's modal dialog from its server-rendered fragment; without the script the link
     shows the fragment alone. Close, Escape or a click on the backdrop closes it, and focus goes back to the link. */
  function initPromptDialog() {
    const dialog = $('#prompt-view');
    if (!dialog) return;
    let opener = null;
    document.addEventListener('click', (e) => {
      const link = e.target.closest('a[data-dialog]');
      if (!link) return;
      e.preventDefault();
      opener = link;
      request(link.href).then(({ ok, body }) => {
        const content = ok ? parseFragment(body) : null;
        if (!content) { location.assign(link.href); return; }
        dialog.replaceChildren(content);
        $('#prompt-view-close', dialog).hidden = false;
        dialog.showModal();
      }).catch(() => location.assign(link.href));
    });
    dialog.addEventListener('click', (e) => { if (e.target === dialog) dialog.close(); });
    dialog.addEventListener('close', () => { dialog.replaceChildren(); if (opener) opener.focus(); });
  }

  /* ---------- Configuration ---------- */

  const MONACO = 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.52.2/min/vs';

  /* The form posts the five textareas. Monaco mounts over them with one model per file, so switching files
     keeps each file's edits and undo, and every edit is written back into its textarea. Without the CDN the
     textareas stay and the page still saves. The diff compares saved versions in Monaco's diff editor. */
  function initConfiguration() {
    const form = $('#prompt-form');
    if (!form) return;
    const editor = { monaco: null, code: null, diff: null, models: {}, diffModels: [], diffTicket: 0 };
    $('#prompt-file').addEventListener('change', () => openFile(editor));
    form.addEventListener('input', (e) => { if (e.target.matches('.editor-fallback')) countCharacters(); });
    $$('#version-list a').forEach((link) => link.addEventListener('click', (e) => { if (!discardEdits(form)) e.preventDefault(); }));
    $('#activate-version').addEventListener('click', (e) => { if (!discardEdits(form)) e.preventDefault(); });
    $('#mode-edit').addEventListener('click', () => setMode(editor, false));
    $('#mode-diff').addEventListener('click', () => setMode(editor, true));
    $$('#diff-a, #diff-b').forEach((pick) => pick.addEventListener('change', () => renderDiff(editor)));
    showSelectedVersion();
    const notice = $('#prompt-notice');
    if (notice) notice.focus();
    loadMonaco((monaco) => {
      editor.monaco = monaco;
      mountCodeEditor(editor);
      document.addEventListener('themechange', () => monaco.editor.setTheme(monacoTheme()));
      matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => monaco.editor.setTheme(monacoTheme()));
      $('#editor-mode').hidden = $('#editor-mode').dataset.diffable !== 'true';
    }, () => { $('#editor-unavailable').hidden = false; });
  }

  function loadMonaco(onReady, onFail) {
    const loader = document.createElement('script');
    loader.src = MONACO + '/loader.min.js';
    loader.onerror = onFail;
    loader.onload = () => {
      const worker = `self.MonacoEnvironment={baseUrl:'${MONACO}/../'};importScripts('${MONACO}/base/worker/workerMain.js');`;
      window.MonacoEnvironment = { getWorkerUrl: () => URL.createObjectURL(new Blob([worker], { type: 'text/javascript' })) };
      window.require.config({ paths: { vs: MONACO } });
      window.require(['vs/editor/editor.main'], () => onReady(window.monaco), onFail);
    };
    document.head.append(loader);
  }

  /* The list scrolls on its own, newest first, so an older selected version is scrolled to. */
  function showSelectedVersion() {
    const list = $('#version-list');
    const selected = $('li.selected', list);
    if (selected) list.scrollTop = selected.offsetTop - (list.clientHeight - selected.offsetHeight) / 2;
  }

  const openKey = () => $('#prompt-file').value;
  const openLabel = () => $('#prompt-file').selectedOptions[0].textContent;
  const fileText = (key) => $('#text-' + key);
  const monacoTheme = () => {
    const theme = document.documentElement.dataset.theme;
    return theme === 'dark' || (!theme && matchMedia('(prefers-color-scheme: dark)').matches) ? 'vs-dark' : 'vs';
  };

  function mountCodeEditor(editor) {
    const box = $('#editor');
    box.hidden = false;
    editor.code = editor.monaco.editor.create(box, Object.assign({
      model: modelFor(editor, openKey()), ariaLabel: openLabel(), scrollBeyondLastLine: false
    }, editorOptions()));
    $$('.editor-fallback').forEach((area) => { area.hidden = true; });
    $('#editor-keys').hidden = false;
  }

  /* What the code and diff editors share; a phone gets narrower gutters. */
  function editorOptions() {
    const phone = matchMedia('(max-width: 720px)').matches;
    return Object.assign(
      { theme: monacoTheme(), wordWrap: 'on', minimap: { enabled: false }, fontSize: 13, automaticLayout: true },
      phone ? { lineNumbersMinChars: 3, folding: false } : {});
  }

  /* One model per file, made on first open from its textarea, which then follows every edit. */
  function modelFor(editor, key) {
    if (!editor.models[key]) {
      const option = $(`#prompt-file option[value="${key}"]`);
      const model = editor.monaco.editor.createModel(fileText(key).value, option.dataset.language);
      model.onDidChangeContent(() => { fileText(key).value = model.getValue(); countCharacters(); });
      editor.models[key] = model;
    }
    return editor.models[key];
  }

  function openFile(editor) {
    const key = openKey();
    if (editor.diff) { renderDiff(editor); return; }
    if (editor.code) {
      editor.code.setModel(modelFor(editor, key));
      editor.code.updateOptions({ ariaLabel: openLabel() });
    } else {
      $$('.editor-fallback').forEach((area) => { area.hidden = area !== fileText(key); });
    }
    $('#meta-file').textContent = openLabel();
    countCharacters();
  }

  function countCharacters() {
    $('#meta-chars').textContent = fileText(openKey()).value.length.toLocaleString('en-AU');
  }

  /* Leaving with edits that are not saved, or with a refused save's texts, asks first. */
  function discardEdits(form) {
    const edited = form.dataset.unsaved === 'true' || $$('.editor-fallback', form).some((area) => area.value !== area.defaultValue);
    return !edited || confirm('Discard unsaved edits?');
  }

  /* Edit and diff share the box: one editor is disposed for the other; the file models outlive both. */
  function setMode(editor, diffing) {
    $('#mode-edit').setAttribute('aria-pressed', String(!diffing));
    $('#mode-diff').setAttribute('aria-pressed', String(diffing));
    $('#diff-pick').hidden = !diffing;
    $('#edit-actions').hidden = diffing;
    $('#editor-meta').hidden = diffing;
    if (diffing && !editor.diff) {
      editor.code.dispose();
      editor.code = null;
      editor.diff = editor.monaco.editor.createDiffEditor($('#editor'), Object.assign({
        readOnly: true, renderSideBySide: true, hideUnchangedRegions: { enabled: true }
      }, editorOptions()));
      renderDiff(editor);
    } else if (!diffing && editor.diff) {
      editor.diff.dispose();
      editor.diff = null;
      disposeDiffModels(editor);
      mountCodeEditor(editor);
      openFile(editor);
    }
  }

  /* Only the latest request draws, so picks made in quick succession cannot end on an older pair. The view
     opens on the first change; the old pair is disposed only once the editor holds the new one. */
  function renderDiff(editor) {
    const ticket = ++editor.diffTicket;
    const query = new URLSearchParams({ a: $('#diff-a').value, b: $('#diff-b').value, file: openKey() });
    $('#diff-failed').hidden = true;
    $('#diff-same').hidden = true;
    request('/configuration/diff?' + query)
      .then(({ ok, body }) => { if (!ok) throw new Error('diff failed'); return JSON.parse(body); })
      .then((texts) => {
        if (ticket !== editor.diffTicket || !editor.diff) return;
        const [original, modified] = [texts.a.text, texts.b.text].map((text) => editor.monaco.editor.createModel(text, texts.language));
        const shown = editor.diff.onDidUpdateDiff(() => { shown.dispose(); editor.diff.revealFirstDiff(); });
        editor.diff.setModel({ original, modified });
        disposeDiffModels(editor);
        editor.diffModels = [original, modified];
        $('#diff-same').hidden = texts.a.text !== texts.b.text;
      })
      .catch(() => { if (ticket === editor.diffTicket) $('#diff-failed').hidden = false; });
  }

  function disposeDiffModels(editor) {
    editor.diffModels.forEach((model) => model.dispose());
    editor.diffModels = [];
  }

  initTheme();
  initAutosubmit();
  trackChromeHeight();
  initCopyButtons();
  initFilters();
  initStartPanel();
  initComposer();
  initInspector();
  initPromptDialog();
  initConfiguration();
})();
