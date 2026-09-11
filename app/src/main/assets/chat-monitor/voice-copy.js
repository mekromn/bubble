/* Bubble Google Voice visible-header copy control.
 * Exact voice.google.com top frame only. This intentionally does one thing:
 * when the active conversation visibly renders a phone-number-only element in the top header band,
 * place a local copy icon immediately after that exact visible number and copy exactly that text.
 * It never scans message history, conversation rows, contacts, call metadata, or hidden values.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://voice.google.com') return;

  const ID = 'bubble-voice-copy-number';
  const STYLE_ID = 'bubble-voice-copy-number-style';
  let timer = 0;

  function visible(node) {
    if (!(node instanceof Element) || !node.isConnected) return false;
    const r = node.getBoundingClientRect();
    if (r.width < 1 || r.height < 1 || r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) return false;
    const s = getComputedStyle(node);
    return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0;
  }

  function phoneOnly(node) {
    if (!(node instanceof Element) || !visible(node)) return null;
    const text = String(node.textContent || '').replace(/\u00a0/gu, ' ').trim();
    if (!text || text.length > 40 || !/^[+\d\s().-]+$/u.test(text)) return null;
    const digits = text.replace(/\D/gu, '');
    if (digits.length < 7 || digits.length > 15) return null;
    return {display: text, digits};
  }

  function activeHeaderPhone() {
    const limit = Math.min(430, Math.max(180, innerHeight * 0.30));
    const selector = 'main span,main div,main p,main a,header span,header div,header p,header a';
    const candidates = [];
    for (const node of document.querySelectorAll(selector)) {
      if (!(node instanceof Element) || node.id === ID || node.closest(`#${ID}`)) continue;
      const phone = phoneOnly(node);
      if (!phone) continue;
      const r = node.getBoundingClientRect();
      if (r.top < 0 || r.top > limit || r.width < 70 || r.width > Math.min(innerWidth * .78, 520)) continue;
      // Prefer the smallest phone-only element rather than an ancestor that happens to contain it.
      const childPhone = [...node.children].some(child => phoneOnly(child)?.digits === phone.digits);
      if (childPhone) continue;
      candidates.push({node, phone, r, area: r.width * r.height});
    }
    candidates.sort((a, b) => a.r.top - b.r.top || a.area - b.area || a.r.left - b.r.left);
    return candidates[0] || null;
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      #${ID} {
        width: 34px !important; height: 34px !important; min-width: 34px !important;
        margin: 0 0 0 8px !important; padding: 0 !important; border: 0 !important;
        border-radius: 50% !important; background: transparent !important;
        color: currentColor !important; opacity: .9 !important;
        display: inline-flex !important; align-items: center !important; justify-content: center !important;
        vertical-align: middle !important; cursor: pointer !important;
        position: relative !important; z-index: 3 !important;
        -webkit-tap-highlight-color: transparent !important;
      }
      #${ID}:active { background: rgba(127,127,127,.20) !important; opacity: 1 !important; }
      #${ID} svg { width: 22px !important; height: 22px !important; fill: none !important;
        stroke: currentColor !important; stroke-width: 2 !important; stroke-linecap: round !important;
        stroke-linejoin: round !important; pointer-events: none !important; }
    `;
    (document.head || document.documentElement).append(style);
  }

  function fallbackCopy(text) {
    try {
      const box = document.createElement('textarea');
      box.value = text; box.readOnly = true;
      box.style.position = 'fixed'; box.style.opacity = '0'; box.style.pointerEvents = 'none';
      document.body.append(box); box.select();
      const ok = document.execCommand('copy'); box.remove(); return ok;
    } catch (_) { return false; }
  }

  function flash(button, ok) {
    const original = button.innerHTML;
    button.innerHTML = ok
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4 10-10"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>';
    setTimeout(() => { if (button.isConnected) button.innerHTML = original; }, 1000);
  }

  async function copyVisibleNumber(button) {
    const found = activeHeaderPhone();
    if (!found || found.phone.digits !== button.dataset.phoneDigits) { flash(button, false); schedule(0); return; }
    let ok = false;
    try { await navigator.clipboard.writeText(found.phone.display); ok = true; }
    catch (_) { ok = fallbackCopy(found.phone.display); }
    flash(button, ok);
  }

  function makeButton(phone) {
    const button = document.createElement('button');
    button.id = ID; button.type = 'button'; button.dataset.phoneDigits = phone.digits;
    button.setAttribute('aria-label', `Copy ${phone.display}`); button.title = `Copy ${phone.display}`;
    button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="10" height="10" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>';
    button.addEventListener('click', event => {
      event.preventDefault(); event.stopPropagation(); event.stopImmediatePropagation(); void copyVisibleNumber(button);
    }, true);
    return button;
  }

  function install() {
    timer = 0;
    const found = activeHeaderPhone();
    const old = document.getElementById(ID);
    if (!found) { old?.remove(); return; }
    ensureStyle();
    if (old && old.parentElement === found.node && old.dataset.phoneDigits === found.phone.digits) return;
    old?.remove();
    found.node.style.setProperty('white-space', 'nowrap');
    found.node.append(makeButton(found.phone));
  }

  function schedule(delay = 80) { clearTimeout(timer); timer = setTimeout(install, delay); }
  new MutationObserver(() => schedule()).observe(document.documentElement, {subtree: true, childList: true, characterData: true});
  window.addEventListener('pageshow', () => schedule(20), {passive: true});
  window.addEventListener('focus', () => schedule(20), {passive: true});
  document.addEventListener('visibilitychange', () => { if (!document.hidden) schedule(20); }, {passive: true});
  setInterval(() => { if (!document.getElementById(ID)) schedule(0); }, 900);
  schedule(0);
})();
