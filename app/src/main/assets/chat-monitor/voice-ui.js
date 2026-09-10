/* Bubble Google Voice page enhancements.
 * Exact voice.google.com top frame only. Adds one local copy-number control to the active
 * conversation header without replacing Voice navigation, call, menu, or composer behavior.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://voice.google.com') return;

  const ID = 'bubble-voice-copy-number';
  const STYLE_ID = 'bubble-voice-copy-number-style';
  const SCRIPT_VERSION = '2.6';
  let timer = 0;
  let lastLocation = location.href;

  // Harmless local execution marker. This is intentionally not persisted or sent anywhere; it
  // makes it possible to distinguish "script did not load" from "live DOM matcher missed".
  document.documentElement.dataset.bubbleVoiceUi = SCRIPT_VERSION;

  function visible(node) {
    if (!(node instanceof Element) || !node.isConnected) return false;
    const rect = node.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1 || rect.bottom < 0 || rect.top > innerHeight) return false;
    const style = getComputedStyle(node);
    return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
  }

  function phoneFrom(value) {
    const text = String(value || '').replace(/\u00a0/gu, ' ');
    const matches = text.match(/\+?\s*(?:\(\d{2,4}\)|\d)[\d\s().-]{4,}\d/gu) || [];
    for (const rawValue of matches) {
      const raw = rawValue.trim().replace(/[.,;:]+$/u, '');
      const digits = raw.replace(/\D/gu, '');
      if (digits.length >= 7 && digits.length <= 15) return raw;
    }
    return '';
  }

  function attributeValues(node) {
    if (!(node instanceof Element)) return [];
    return [
      node.getAttribute('aria-label'), node.getAttribute('aria-description'), node.getAttribute('title'),
      node.getAttribute('data-tooltip'), node.getAttribute('data-tooltip-text'), node.getAttribute('data-tooltip-label'),
      node.getAttribute('data-phone-number'), node.getAttribute('data-number'), node.getAttribute('href'),
      node.textContent
    ];
  }

  function nodePhone(node) {
    if (!(node instanceof Element)) return '';
    const href = node.getAttribute('href') || '';
    if (/^tel:/iu.test(href)) {
      const fromTel = phoneFrom(decodeURIComponent(href.slice(4)));
      if (fromTel) return fromTel;
    }
    for (const value of attributeValues(node)) {
      const phone = phoneFrom(value);
      if (phone) return phone;
    }
    const tel = node.querySelector?.('a[href^="tel:" i]');
    if (tel) {
      const phone = nodePhone(tel);
      if (phone) return phone;
    }
    const explicit = node.querySelector?.('[data-phone-number],[data-number]');
    if (explicit) {
      const phone = nodePhone(explicit);
      if (phone) return phone;
    }
    return '';
  }

  function labelOf(node) {
    if (!(node instanceof Element)) return '';
    return attributeValues(node).filter(Boolean).join(' ').replace(/\s+/gu, ' ').trim().toLowerCase();
  }

  function isMenuControl(node) {
    const label = labelOf(node);
    return /\b(more|options|menu|settings|help|history)\b/iu.test(label);
  }

  function isCallControl(node) {
    const label = labelOf(node);
    return /\b(call|calling|phone|dial)\b/iu.test(label) && !/copy|history|settings|help/iu.test(label);
  }

  function controlsIn(root) {
    if (!(root instanceof Element)) return [];
    return [...root.querySelectorAll('button,[role="button"],a[role="button"]')].filter(visible);
  }

  function geometricCallFallback(root, phoneNode) {
    const controls = controlsIn(root);
    if (controls.length < 2) return null;
    const menus = controls.filter(isMenuControl);
    for (const menu of menus) {
      const menuRect = menu.getBoundingClientRect();
      const candidates = controls.filter(node => {
        if (node === menu || isMenuControl(node)) return false;
        const rect = node.getBoundingClientRect();
        return Math.abs(rect.top - menuRect.top) <= Math.max(rect.height, menuRect.height, 24) && rect.right <= menuRect.left + 4;
      }).sort((a, b) => b.getBoundingClientRect().right - a.getBoundingClientRect().right);
      if (candidates[0]) return candidates[0];
    }

    if (phoneNode) {
      const phoneRect = phoneNode.getBoundingClientRect();
      const candidates = controls.filter(node => {
        if (isMenuControl(node)) return false;
        const rect = node.getBoundingClientRect();
        return rect.left > phoneRect.right && Math.abs(rect.top - phoneRect.top) < 90;
      }).sort((a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left);
      if (candidates[0]) return candidates[0];
    }
    return null;
  }

  function callButtonIn(root, phoneNode = null) {
    const controls = controlsIn(root);
    return controls.find(isCallControl) || geometricCallFallback(root, phoneNode);
  }

  function candidatePhoneNodes() {
    const selector = [
      'main a[href^="tel:" i]', 'main [data-phone-number]', 'main [data-number]', 'main [aria-label]',
      'main span', 'main div', 'main button', 'body header a[href^="tel:" i]', 'body header [data-phone-number]',
      'body header [data-number]', 'body header [aria-label]', 'body header span', 'body header div', 'body header button'
    ].join(',');
    return [...document.querySelectorAll(selector)]
      .filter(node => visible(node) && node !== document.body && (node.textContent?.length || 0) <= 220)
      .map(node => ({node, phone: nodePhone(node), rect: node.getBoundingClientRect()}))
      .filter(item => item.phone && item.rect.top >= 32 && item.rect.top <= Math.min(innerHeight * 0.48, 560))
      .sort((a, b) => a.rect.top - b.rect.top || a.rect.left - b.rect.left);
  }

  function conversationHeader() {
    const nodes = candidatePhoneNodes();
    for (const item of nodes) {
      let root = item.node;
      for (let depth = 0; depth < 9 && root; depth++, root = root.parentElement) {
        const rect = root.getBoundingClientRect();
        if (rect.height > 260 || rect.width < 220) continue;
        const call = callButtonIn(root, item.node);
        if (call) return {root, call, phone: item.phone};
      }
    }

    // Reverse search: find a likely call/header action first, then climb until a phone number appears.
    const calls = [...document.querySelectorAll('main button,main [role="button"],main a[role="button"],header button,header [role="button"],header a[role="button"]')]
      .filter(node => visible(node) && isCallControl(node))
      .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
    for (const call of calls) {
      if (call.getBoundingClientRect().top > Math.min(innerHeight * 0.48, 560)) continue;
      let root = call.parentElement;
      for (let depth = 0; depth < 9 && root; depth++, root = root.parentElement) {
        const phone = nodePhone(root);
        if (phone) return {root, call, phone};
      }
    }
    return null;
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      #${ID} {
        width: 40px !important; height: 40px !important; min-width: 40px !important;
        margin: 0 2px !important; padding: 0 !important; border: 0 !important;
        border-radius: 50% !important; background: transparent !important;
        color: inherit !important; opacity: .82; display: inline-flex !important;
        align-items: center !important; justify-content: center !important;
        vertical-align: middle !important; cursor: pointer !important;
        flex: 0 0 40px !important; position: relative !important; z-index: 2 !important;
        -webkit-tap-highlight-color: transparent !important;
      }
      #${ID}:active { background: rgba(127,127,127,.20) !important; opacity: 1; }
      #${ID} svg { width: 21px !important; height: 21px !important; fill: none !important;
        stroke: currentColor !important; stroke-width: 2 !important; stroke-linecap: round !important;
        stroke-linejoin: round !important; pointer-events: none !important; }
    `;
    (document.head || document.documentElement).append(style);
  }

  function copyFallback(text) {
    try {
      const input = document.createElement('textarea');
      input.value = text;
      input.setAttribute('readonly', '');
      input.style.position = 'fixed'; input.style.opacity = '0'; input.style.pointerEvents = 'none';
      document.body.append(input); input.select();
      const ok = document.execCommand('copy'); input.remove(); return ok;
    } catch (_) { return false; }
  }

  async function copyNumber(button) {
    const phone = button.dataset.phone || '';
    if (!phone) return;
    let ok = false;
    try {
      await navigator.clipboard.writeText(phone);
      ok = true;
    } catch (_) { ok = copyFallback(phone); }
    const old = button.innerHTML;
    button.innerHTML = ok
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4 10-10"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>';
    button.setAttribute('aria-label', ok ? 'Phone number copied' : 'Could not copy phone number');
    button.title = button.getAttribute('aria-label');
    setTimeout(() => {
      if (!button.isConnected) return;
      button.innerHTML = old;
      button.setAttribute('aria-label', 'Copy phone number');
      button.title = `Copy ${phone}`;
    }, 1200);
  }

  function createButton(phone) {
    const button = document.createElement('button');
    button.id = ID;
    button.type = 'button';
    button.dataset.phone = phone;
    button.setAttribute('aria-label', 'Copy phone number');
    button.title = `Copy ${phone}`;
    button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="10" height="10" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>';
    button.addEventListener('click', event => {
      event.preventDefault(); event.stopPropagation(); event.stopImmediatePropagation();
      void copyNumber(button);
    }, true);
    return button;
  }

  function install() {
    timer = 0;
    if (location.origin !== 'https://voice.google.com') return;
    document.documentElement.dataset.bubbleVoiceUi = SCRIPT_VERSION;
    const found = conversationHeader();
    const existing = document.getElementById(ID);
    if (!found) { existing?.remove(); return; }
    ensureStyle();
    if (existing) {
      existing.dataset.phone = found.phone;
      existing.title = `Copy ${found.phone}`;
      if (existing.parentElement === found.call.parentElement && existing.nextElementSibling === found.call) return;
      existing.remove();
    }
    const button = createButton(found.phone);
    const actionRow = found.call.parentElement;
    if (actionRow) actionRow.insertBefore(button, found.call);
    else found.root.append(button);
  }

  function schedule(delay = 140) {
    clearTimeout(timer);
    timer = setTimeout(install, delay);
  }

  const observer = new MutationObserver(() => schedule());
  observer.observe(document.documentElement, {subtree: true, childList: true, characterData: true,
    attributes: true, attributeFilter: ['aria-label', 'aria-description', 'title', 'class', 'data-tooltip',
      'data-tooltip-text', 'data-tooltip-label', 'data-phone-number', 'data-number', 'href']});
  window.addEventListener('pageshow', () => schedule(60), {passive: true});
  window.addEventListener('popstate', () => schedule(80), {passive: true});
  setInterval(() => {
    if (location.href !== lastLocation) { lastLocation = location.href; schedule(60); }
    else if (!document.getElementById(ID)) schedule(0);
  }, 900);
  schedule(0);
})();
