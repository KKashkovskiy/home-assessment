/* Anna console. The server renders every page and fragment; this script adds the theme toggle, the status strip's
   test controls posting on change, the start panel as a dialog, filters that apply on change, the composer's
   optimistic send, the inspector's toggles, prompt dialog and live log, Monaco over the prompt editor's form, and
   the analytics charts over their tables. Every action is also a plain form post or link, so the console works
   without it. */
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
    const query = queryOf(form);
    location.assign(form.action + (query ? '?' + query : ''));
  }

  /* A form's fields as a query string, leaving out the empty ones. */
  function queryOf(form) {
    const params = new URLSearchParams();
    new FormData(form).forEach((value, key) => { if (value) params.append(key, value); });
    return params.toString();
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

  /* ---------- Analytics ---------- */

  /* The server renders the tiles, every chart's table and the comparison. This draws each chart from the rows
     /analytics/data.json gives for the same filter, in one hue with the booked-anyway part in the second, and puts
     the table one click away. Without the script, or when the data does not load, the tables stay. */
  function initAnalytics() {
    const panels = $$('.chart-panel');
    if (!panels.length) return;
    request('/analytics/data.json' + location.search)
      .then(({ ok, body }) => { if (!ok) throw new Error('chart data failed'); return JSON.parse(body).charts; })
      .then((charts) => panels.forEach((panel) => drawPanel(panel, charts[$('.chart', panel).dataset.chart])))
      .catch(() => { /* the tables the server rendered stay */ });
    document.addEventListener('mousemove', moveTooltip);
    initCompare();
  }

  /* How each chart draws, by its data-chart key: bars with an optional share of the first bar and a booked part,
     or a line whose axis reads in dollars or seconds to the step's precision. */
  const CHARTS = {
    funnel: (rows, width) => hbars(rows, width, { share: true }),
    endReasons: (rows, width) => hbars(rows, width, {}),
    objections: (rows, width) => hbars(rows, width, {}),
    cost: (rows, width) => lines(rows, width, (v, digits) => '$' + v.toFixed(digits)),
    latency: (rows, width) => lines(rows, width, (v, digits) => v.toFixed(digits) + ' s'),
  };

  /* The chart is drawn at its box's width, so its text stays at its set size on a phone as on a wide screen, and
     is named by its panel's heading. */
  function drawPanel(panel, rows) {
    if (!rows || !rows.length) return;
    const box = $('.chart', panel);
    const toggle = $('.segmented', panel);
    setView(panel, 'chart');
    const style = getComputedStyle(box);
    const width = box.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    box.innerHTML = CHARTS[box.dataset.chart](rows, Math.max(280, Math.round(width)));
    $('svg', box).setAttribute('aria-labelledby', panel.getAttribute('aria-labelledby'));
    toggle.hidden = false;
    $$('button', toggle).forEach((button) => button.addEventListener('click', () => setView(panel, button.dataset.view)));
  }

  function setView(panel, view) {
    $$('.segmented button', panel).forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.view === view)));
    $$('[data-shows]', panel).forEach((el) => { el.hidden = el.dataset.shows !== view; });
  }

  const esc = (text) => String(text == null ? '' : text)
    .replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const tip = (row) => row.label + ': ' + row.shown + (row.note ? ', ' + row.note : '');

  /* What a bar says at its end: the value, its share of the first bar (the funnel's started), the booked part. */
  function barLabel(row, first, opts) {
    const share = opts.share && first ? ' (' + Math.round(row.value / first * 100) + '%)' : '';
    return row.shown + share + (row.segment != null ? ', ' + row.segment + ' booked' : '');
  }

  /* Horizontal bars over rows {label, value, segment, shown, note, href}, each a link to its conversations; the
     segment sits inset inside its bar, so the whole bar stays visible. */
  function hbars(rows, W, opts) {
    const max = Math.max(1, ...rows.map((row) => row.value));
    const LW = Math.min(130, Math.round(W * 0.36)), BH = 22, GAP = 8, INSET = 5;
    const x = (v) => LW + (v / max) * (W - LW - 110);
    const bar = (row, i) => {
      const y = i * (BH + GAP);
      const segment = row.segment ? `<rect class="bar recovered" x="${LW}" y="${y + INSET}" width="${Math.max(2, x(row.segment) - LW)}" height="${BH - 2 * INSET}" rx="2"/>` : '';
      return `<a href="${esc(row.href)}" aria-label="${esc(tip(row))}"><g data-tip="${esc(tip(row))}">`
        + `<text x="${LW - 8}" y="${y + BH / 2 + 4}" text-anchor="end">${esc(row.label)}</text>`
        + `<rect class="hit" x="0" y="${y}" width="${W}" height="${BH}"/>`
        + `<rect class="bar" x="${LW}" y="${y}" width="${Math.max(2, x(row.value) - LW)}" height="${BH}" rx="3"/>${segment}`
        + `<text class="label-value" x="${x(row.value) + 8}" y="${y + BH / 2 + 4}">${esc(barLabel(row, rows[0].value, opts))}</text></g></a>`;
    };
    return `<svg viewBox="0 0 ${W} ${rows.length * (BH + GAP)}" role="group">${rows.map(bar).join('')}</svg>`;
  }

  /* Axis ticks at a 1, 2 or 5 step, with the decimals that step needs. */
  function ticks(max) {
    const rough = max / 4;
    const magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
    const step = [1, 2, 5, 10].map((m) => m * magnitude).find((candidate) => candidate >= rough);
    const digits = Math.max(0, -Math.floor(Math.log10(step)));
    const values = [];
    for (let v = 0; v <= max + step / 2; v += step) values.push(v);
    return { values, digits, top: values[values.length - 1] };
  }

  /* One line over rows {label, value, shown, note}, a row a day; a day with no value breaks the line. fmt labels
     the axis; day labels thin out to fit. */
  function lines(rows, W, fmt) {
    const H = 220, L = 56, R = 16, T = 12, B = 32, n = rows.length;
    const measured = rows.filter((row) => row.value != null);
    const axis = ticks(Math.max(...measured.map((row) => row.value)) * 1.1 || 1);
    const x = (i) => (n === 1 ? (L + W - R) / 2 : L + i * (W - L - R) / (n - 1));
    const y = (v) => T + (1 - v / axis.top) * (H - T - B);
    const every = Math.ceil(n / Math.max(2, Math.floor((W - L) / 60)));
    const grid = axis.values.map((t) => `<line class="grid" x1="${L}" x2="${W - R}" y1="${y(t)}" y2="${y(t)}"/>`
      + `<text x="${L - 6}" y="${y(t) + 4}" text-anchor="end">${fmt(t, axis.digits)}</text>`).join('');
    const days = rows.map((row, i) => (i % every ? '' : `<text x="${x(i)}" y="${H - 8}" text-anchor="middle">${esc(row.label)}</text>`)).join('');
    const path = rows.map((row, i) => (row.value == null ? '' : ((i && rows[i - 1].value != null) ? 'L' : 'M') + x(i) + ' ' + y(row.value))).join(' ');
    const dots = rows.map((row, i) => (row.value == null ? '' : `<g data-tip="${esc(tip(row))}"><circle class="hit" cx="${x(i)}" cy="${y(row.value)}" r="12"/>`
      + `<circle class="dot" cx="${x(i)}" cy="${y(row.value)}" r="3.5"/></g>`)).join('');
    return `<svg viewBox="0 0 ${W} ${H}" role="img">${grid}${days}<path class="line" d="${path}"/>${dots}</svg>`;
  }

  function moveTooltip(e) {
    const box = $('#tooltip');
    const target = e.target.closest && e.target.closest('[data-tip]');
    if (!box) return;
    box.hidden = !target;
    if (!target) return;
    box.textContent = target.dataset.tip;
    box.style.left = (e.clientX + 12) + 'px';
    box.style.top = (e.clientY + 12) + 'px';
  }

  /* A version pick swaps the comparison in place and keeps the pair in the address and the filter form; without
     the script, or when the swap fails, the form's button reloads the page. */
  function initCompare() {
    const form = $('#cmp-form');
    if (!form) return;
    $('#cmp-apply').hidden = true;
    form.addEventListener('change', () => swapCompare(form));
  }

  function swapCompare(form) {
    const query = queryOf(form);
    request('/analytics/compare?' + query)
      .then(({ ok, body }) => {
        const fresh = ok ? parseFragment(body) : null;
        if (!fresh) throw new Error('compare failed');
        $('#cmp-body').replaceWith(fresh);
        ['a', 'b'].forEach((name) => { $('#filters [name="' + name + '"]').value = form.elements[name].value; });
        history.replaceState(null, '', form.action + '?' + query);
      })
      .catch(() => {
        $('#cmp-caption').textContent = 'The comparison did not load. Press Compare to reload the page.';
        $('#cmp-apply').hidden = false;
      });
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
  initAnalytics();
})();
