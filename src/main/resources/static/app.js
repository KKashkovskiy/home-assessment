/* Anna console. The server renders every page and fragment; this script adds the theme toggle, the start
   panel as a dialog, filters that apply on change and the composer's optimistic send. Every action is also
   a plain form post, so the console works without it. */
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
      .then(({ ok, status, html }) => {
        const root = fragmentRoot(html);
        pending.forEach((el) => el.remove());
        if (ok && root) { thread.append(...root.children); hint.textContent = root.dataset.hint; hint.dataset.kind = root.dataset.hintKind; refreshState(); return; }
        failed(root ? root.dataset.hint : 'Send failed with HTTP ' + status + '. Reload to see what was stored.');
      })
      .catch(() => {
        pending.forEach((el) => el.remove());
        failed('Send failed: no answer from the server. Reload to see what was stored.');
      })
      .finally(() => { setBusy(form, false); scrollToEnd(thread); box.focus(); });
  }

  /* A turn changes the list row, the lead line, the state header and the spend meter: take them from a
     fresh copy of this page. */
  function refreshState() {
    request(location.pathname + location.search)
      .then(({ ok, html }) => {
        if (!ok) return;
        const page = new DOMParser().parseFromString(html, 'text/html');
        ['#conv-list', '#conv-count', '.state-head', '#status-strip .meter'].forEach((sel) => {
          const fresh = $(sel, page);
          const stale = $(sel);
          if (fresh && stale) stale.replaceWith(fresh);
        });
      })
      .catch(() => { /* the next page load shows the fresh state */ });
  }

  /* The one fetch helper. The header tells the server a script is asking, so a send answers with a fragment. */
  function request(url, options) {
    return fetch(url, Object.assign({ headers: { 'X-Requested-With': 'fetch' } }, options))
      .then((res) => res.text().then((html) => ({ ok: res.ok, status: res.status, html })));
  }

  /* A send answers with one element carrying data-hint: the turn's items, or a problem line. */
  function fragmentRoot(html) {
    const template = document.createElement('template');
    template.innerHTML = html.trim();
    const root = template.content.firstElementChild;
    return root && root.dataset.hint !== undefined ? root : null;
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

  initTheme();
  trackChromeHeight();
  initCopyButtons();
  initFilters();
  initStartPanel();
  initComposer();
})();
