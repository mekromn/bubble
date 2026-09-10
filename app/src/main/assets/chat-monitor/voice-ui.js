/* Bubble Google Voice page enhancements.
 * Exact voice.google.com top frame only. Keeps one local copy-number control integrated into the
 * active conversation header and provides a conservative local fallback that opens a newly-unread
 * conversation when Voice is brought to the foreground from a notification.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://voice.google.com') return;

  const ID = 'bubble-voice-copy-number';
  const STYLE_ID = 'bubble-voice-copy-number-style';
  const SCRIPT_VERSION = '2.7';
  const ROUTE_COOLDOWN_MS = 2600;
  let timer = 0;
  let routeTimer = 0;
  let lastLocation = location.href;
  let lastRouteAt = 0;
  let listRevealAttempted = false;

  document.documentElement.dataset.bubbleVoiceUi = SCRIPT_VERSION;

  function visible(node) {
    if (!(node instanceof Element) || !node.isConnected) return false;
    const rect = node.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1 || rect.bottom < 0 || rect.top > innerHeight || rect.right < 0 || rect.left > innerWidth) return false;
    const style = getComputedStyle(node);
    return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
  }

  function normalize(value) {
    return String(value || '').replace(/\u00a0/gu, ' ').replace(/\s+/gu, ' ').trim().toLowerCase();
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

  function attributeValues(node, includeText = true) {
    if (!(node instanceof Element)) return [];
    const values = [
      node.getAttribute('aria-label'), node.getAttribute('aria-description'), node.getAttribute('title'),
      node.getAttribute('data-tooltip'), node.getAttribute('data-tooltip-text'), node.getAttribute('data-tooltip-label'),
      node.getAttribute('data-phone-number'), node.getAttribute('data-number'), node.getAttribute('data-value'),
      node.getAttribute('data-contact'), node.getAttribute('href')
    ];
    if (includeText) values.push(node.textContent);
    return values;
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
    return '';
  }

  function deepPhone(root) {
    if (!(root instanceof Element)) return '';
    const direct = nodePhone(root);
    if (direct) return direct;
    const selectors = [
      'a[href^="tel:" i]', '[data-phone-number]', '[data-number]', '[data-value]',
      '[aria-label]', '[aria-description]', '[title]'
    ].join(',');
    for (const node of root.querySelectorAll(selectors)) {
      const value = nodePhone(node);
      if (value) return value;
    }
    return '';
  }

  function labelOf(node) {
    return normalize(attributeValues(node).filter(Boolean).join(' '));
  }

  function isMenuControl(node) {
    return /\b(more|options|menu|settings|help|history)\b/iu.test(labelOf(node));
  }

  function isBackControl(node) {
    const label = labelOf(node);
    return /(^|\b)(back|go back|previous)(\b|$)/iu.test(label) && !/backspace|background/iu.test(label);
  }

  function isCallControl(node) {
    const label = labelOf(node);
    return /\b(call|calling|phone|dial)\b/iu.test(label) && !/copy|history|settings|help/iu.test(label);
  }

  function controlsIn(root) {
    if (!(root instanceof Element)) return [];
    return [...root.querySelectorAll('button,[role="button"],a[role="button"]')].filter(visible);
  }

  function geometricCallFallback(root, phoneNode = null) {
    const controls = controlsIn(root);
    if (controls.length < 2) return null;
    const menus = controls.filter(isMenuControl);
    for (const menu of menus) {
      const menuRect = menu.getBoundingClientRect();
      const candidates = controls.filter(node => {
        if (node === menu || isMenuControl(node) || isBackControl(node)) return false;
        const rect = node.getBoundingClientRect();
        return Math.abs(rect.top - menuRect.top) <= Math.max(rect.height, menuRect.height, 28) && rect.right <= menuRect.left + 6;
      }).sort((a, b) => b.getBoundingClientRect().right - a.getBoundingClientRect().right);
      if (candidates[0]) return candidates[0];
    }
    if (phoneNode) {
      const phoneRect = phoneNode.getBoundingClientRect();
      const candidates = controls.filter(node => {
        if (isMenuControl(node) || isBackControl(node)) return false;
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

  function selectedThreadRoots() {
    const selectors = [
      '[aria-selected="true"]', '[aria-current="true"]', '[aria-current="page"]',
      '[data-selected="true"]', '[data-active="true"]'
    ].join(',');
    return [...document.querySelectorAll(selectors)].filter(node => node instanceof Element);
  }

  function headerName(root) {
    if (!(root instanceof Element)) return '';
    const controls = new Set(controlsIn(root));
    const chunks = [];
    for (const node of root.querySelectorAll('h1,h2,h3,[role="heading"],span,div')) {
      if (!(node instanceof Element) || controls.has(node) || !visible(node)) continue;
      const text = normalize(node.textContent);
      if (!text || text.length > 100 || /^\+?[\d\s().-]+$/u.test(text)) continue;
      if (/^(messages?|calls?|voicemail|more|menu|back|call)$/iu.test(text)) continue;
      chunks.push(text);
    }
    return chunks.sort((a, b) => a.length - b.length)[0] || '';
  }

  function phoneForHeader(root) {
    const direct = deepPhone(root);
    if (direct) return direct;
    for (const selected of selectedThreadRoots()) {
      const phone = deepPhone(selected);
      if (phone) return phone;
    }
    const name = headerName(root);
    if (name) {
      const candidates = [...document.querySelectorAll('[role="listitem"],[role="option"],[role="row"],a,button,[tabindex]')];
      for (const node of candidates) {
        if (!(node instanceof Element)) continue;
        const text = normalize(node.textContent);
        if (!text || !text.includes(name)) continue;
        const phone = deepPhone(node);
        if (phone) return phone;
        let parent = node.parentElement;
        for (let depth = 0; depth < 4 && parent; depth++, parent = parent.parentElement) {
          const found = deepPhone(parent);
          if (found) return found;
        }
      }
    }
    return '';
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
      .filter(item => item.phone && item.rect.top >= 24 && item.rect.top <= Math.min(innerHeight * 0.48, 560))
      .sort((a, b) => a.rect.top - b.rect.top || a.rect.left - b.rect.left);
  }

  function conversationHeader() {
    const nodes = candidatePhoneNodes();
    for (const item of nodes) {
      let root = item.node;
      for (let depth = 0; depth < 9 && root; depth++, root = root.parentElement) {
        const rect = root.getBoundingClientRect();
        if (rect.height > 280 || rect.width < 220) continue;
        const call = callButtonIn(root, item.node);
        if (call) return {root, call, phone: item.phone || phoneForHeader(root)};
      }
    }

    // Contact names often replace the raw number in Voice. Find the top action row first and do not
    // require a visible phone number before exposing Bubble's copy control.
    const calls = [...document.querySelectorAll('main button,main [role="button"],main a[role="button"],header button,header [role="button"],header a[role="button"]')]
      .filter(node => visible(node) && (isCallControl(node) || (!isMenuControl(node) && !isBackControl(node))))
      .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top || b.getBoundingClientRect().right - a.getBoundingClientRect().right);
    for (const call of calls) {
      const callRect = call.getBoundingClientRect();
      if (callRect.top > Math.min(innerHeight * 0.34, 360) || callRect.right < innerWidth * 0.45) continue;
      let root = call.parentElement;
      let fallback = null;
      for (let depth = 0; depth < 8 && root; depth++, root = root.parentElement) {
        const rect = root.getBoundingClientRect();
        if (rect.width < 220 || rect.height > 260) continue;
        fallback = fallback || root;
        const phone = phoneForHeader(root);
        if (phone) return {root, call, phone};
        if (controlsIn(root).some(isMenuControl)) return {root, call, phone: ''};
      }
      if (fallback) return {root: fallback, call, phone: phoneForHeader(fallback)};
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
      #${ID}[data-resolved="false"] { opacity: .58; }
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

  function flash(button, ok) {
    const old = button.innerHTML;
    button.innerHTML = ok
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4 10-10"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>';
    button.setAttribute('aria-label', ok ? 'Phone number copied' : 'Phone number not found yet');
    button.title = button.getAttribute('aria-label');
    setTimeout(() => {
      if (!button.isConnected) return;
      button.innerHTML = old;
      button.setAttribute('aria-label', 'Copy phone number');
      button.title = button.dataset.phone ? `Copy ${button.dataset.phone}` : 'Find and copy phone number';
    }, 1200);
  }

  async function copyNumber(button) {
    const found = conversationHeader();
    const phone = button.dataset.phone || found?.phone || (found ? phoneForHeader(found.root) : '');
    if (!phone) { flash(button, false); return; }
    button.dataset.phone = phone;
    button.dataset.resolved = 'true';
    let ok = false;
    try { await navigator.clipboard.writeText(phone); ok = true; }
    catch (_) { ok = copyFallback(phone); }
    flash(button, ok);
  }

  function createButton(phone) {
    const button = document.createElement('button');
    button.id = ID; button.type = 'button'; button.dataset.phone = phone || '';
    button.dataset.resolved = phone ? 'true' : 'false';
    button.setAttribute('aria-label', 'Copy phone number');
    button.title = phone ? `Copy ${phone}` : 'Find and copy phone number';
    button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="10" height="10" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>';
    button.addEventListener('click', event => {
      event.preventDefault(); event.stopPropagation(); event.stopImmediatePropagation(); void copyNumber(button);
    }, true);
    return button;
  }

  function install() {
    timer = 0;
    if (location.origin !== 'https://voice.google.com') return;
    document.documentElement.dataset.bubbleVoiceUi = SCRIPT_VERSION;
    const found = conversationHeader();
    const existing = document.getElementById(ID);
    if (!found) return; // Keep an already-integrated button during transient Voice rerenders.
    ensureStyle();
    const phone = found.phone || phoneForHeader(found.root);
    if (existing) {
      if (phone) existing.dataset.phone = phone;
      existing.dataset.resolved = existing.dataset.phone ? 'true' : 'false';
      existing.title = existing.dataset.phone ? `Copy ${existing.dataset.phone}` : 'Find and copy phone number';
      if (existing.parentElement === found.call.parentElement && existing.nextElementSibling === found.call) return;
      existing.remove();
    }
    const button = createButton(phone);
    const actionRow = found.call.parentElement;
    if (actionRow) actionRow.insertBefore(button, found.call);
    else found.root.append(button);
  }

  function clickableConversationAncestor(node) {
    let current = node instanceof Element ? node : null;
    for (let depth = 0; depth < 8 && current; depth++, current = current.parentElement) {
      const rect = current.getBoundingClientRect();
      const role = normalize(current.getAttribute('role'));
      const clickable = current.matches('a,button,[tabindex],[role="listitem"],[role="option"],[role="row"],[role="button"]');
      if (clickable && rect.height >= 38 && rect.height <= 180 && rect.width >= 180) return current;
    }
    return null;
  }

  function unreadScore(row) {
    if (!(row instanceof Element)) return 0;
    const text = labelOf(row);
    let score = 0;
    if (/\bunread\b/iu.test(text)) score += 140;
    if (row.querySelector('[aria-label*="unread" i],[title*="unread" i],[data-unread="true"],[aria-description*="unread" i]')) score += 160;
    if (/\bunread\b/iu.test(String(row.className || ''))) score += 90;
    if (row.getAttribute('aria-selected') === 'false') score += 5;
    const style = getComputedStyle(row);
    const weight = Number.parseInt(style.fontWeight, 10);
    if (Number.isFinite(weight) && weight >= 600) score += 12;
    return score;
  }

  function unreadRows() {
    const seeds = [...document.querySelectorAll(
      '[aria-label*="unread" i],[aria-description*="unread" i],[title*="unread" i],[data-unread="true"],*[class*="unread" i]'
    )];
    const rows = new Set();
    for (const seed of seeds) {
      const row = clickableConversationAncestor(seed);
      if (row && row !== document.body && !row.closest('header') && !row.closest('[contenteditable="true"]')) rows.add(row);
    }
    return [...rows].filter(visible).map(row => ({row, score: unreadScore(row), rect: row.getBoundingClientRect()}))
      .filter(item => item.score >= 80)
      .sort((a, b) => b.score - a.score || a.rect.top - b.rect.top || a.rect.left - b.rect.left);
  }

  function revealConversationList() {
    if (listRevealAttempted) return false;
    const backs = [...document.querySelectorAll('button,[role="button"],a[role="button"]')]
      .filter(node => visible(node) && isBackControl(node))
      .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top || a.getBoundingClientRect().left - b.getBoundingClientRect().left);
    const back = backs[0];
    if (!back || back.getBoundingClientRect().top > 220) return false;
    listRevealAttempted = true;
    back.click();
    setTimeout(() => routeNewestUnread(true), 260);
    return true;
  }

  function routeNewestUnread(fromReveal = false) {
    routeTimer = 0;
    if (document.hidden || location.origin !== 'https://voice.google.com') return;
    const now = Date.now();
    if (!fromReveal && now - lastRouteAt < ROUTE_COOLDOWN_MS) return;
    const rows = unreadRows();
    if (rows.length) {
      const target = rows[0].row;
      // If Google already selected the target, do nothing. Otherwise use Voice's real row click.
      const selected = target.getAttribute('aria-selected') === 'true' || target.getAttribute('aria-current') === 'true';
      if (!selected) target.click();
      lastRouteAt = now;
      listRevealAttempted = false;
      schedule(100);
      return;
    }
    if (!fromReveal) revealConversationList();
  }

  function schedule(delay = 140) {
    clearTimeout(timer); timer = setTimeout(install, delay);
  }

  function scheduleRoute(delay = 120) {
    clearTimeout(routeTimer); routeTimer = setTimeout(() => routeNewestUnread(false), delay);
  }

  const observer = new MutationObserver(() => schedule());
  observer.observe(document.documentElement, {subtree: true, childList: true, characterData: true,
    attributes: true, attributeFilter: ['aria-label', 'aria-description', 'aria-selected', 'aria-current', 'title', 'class',
      'data-tooltip', 'data-tooltip-text', 'data-tooltip-label', 'data-phone-number', 'data-number', 'data-unread', 'href']});

  window.addEventListener('pageshow', () => { schedule(60); scheduleRoute(180); }, {passive: true});
  window.addEventListener('focus', () => { schedule(40); scheduleRoute(120); }, {passive: true});
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) { listRevealAttempted = false; schedule(40); scheduleRoute(120); }
  }, {passive: true});
  window.addEventListener('popstate', () => schedule(80), {passive: true});

  setInterval(() => {
    if (location.href !== lastLocation) { lastLocation = location.href; listRevealAttempted = false; schedule(60); }
    else if (!document.getElementById(ID)) schedule(0);
  }, 900);

  schedule(0);
})();
