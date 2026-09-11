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
  const SCRIPT_VERSION = '2.8';
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
      if (digits.length >= 7 && digits.length <= 15) return {display: raw, digits};
    }
    return null;
  }

  function phoneOnlyText(node) {
    if (!(node instanceof Element) || !visible(node)) return null;
    const raw = String(node.textContent || '').replace(/\u00a0/gu, ' ').trim();
    if (!raw || raw.length > 40 || !/^[+\d\s().-]+$/u.test(raw)) return null;
    return phoneFrom(raw);
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
    if (!(node instanceof Element) || !visible(node)) return false;
    const href = node.getAttribute('href') || '';
    if (/^tel:/iu.test(href)) return true;
    const label = normalize([
      node.getAttribute('aria-label'), node.getAttribute('aria-description'), node.getAttribute('title'),
      node.getAttribute('data-tooltip'), node.getAttribute('data-tooltip-text'), node.getAttribute('data-tooltip-label')
    ].filter(Boolean).join(' '));
    return /(^|\b)(call|calling|dial)(\b|$)/iu.test(label) && !/copy|history|settings|help/iu.test(label);
  }

  function headerCallControl() {
    const selector = [
      'main a[href^="tel:" i]', 'main button', 'main [role="button"]', 'main a[role="button"]',
      'header a[href^="tel:" i]', 'header button', 'header [role="button"]', 'header a[role="button"]'
    ].join(',');
    return [...document.querySelectorAll(selector)]
      .filter(isCallControl)
      .map(node => ({node, rect: node.getBoundingClientRect()}))
      .filter(item => item.rect.top >= 0 && item.rect.top <= Math.min(innerHeight * 0.42, 460) && item.rect.left >= innerWidth * 0.42)
      .sort((a, b) => a.rect.top - b.rect.top || b.rect.right - a.rect.right)[0]?.node || null;
  }

  function visibleHeaderPhone(call) {
    if (!(call instanceof Element)) return null;
    const callRect = call.getBoundingClientRect();
    const callY = (callRect.top + callRect.bottom) / 2;
    const band = Math.max(56, callRect.height * 1.8);
    const selector = [
      'main span', 'main div', 'main p', 'main a',
      'header span', 'header div', 'header p', 'header a'
    ].join(',');
    const candidates = [];
    for (const node of document.querySelectorAll(selector)) {
      if (!(node instanceof Element) || node.contains(call) || call.contains(node)) continue;
      const phone = phoneOnlyText(node);
      if (!phone) continue;
      const rect = node.getBoundingClientRect();
      const y = (rect.top + rect.bottom) / 2;
      if (Math.abs(y - callY) > band) continue;
      if (rect.left >= callRect.left || rect.right > callRect.left + 24) continue;
      if (rect.top > Math.min(innerHeight * 0.42, 460)) continue;
      candidates.push({node, phone, rect, distance: Math.abs(y - callY)});
    }
    candidates.sort((a, b) =>
      a.distance - b.distance ||
      (a.rect.width * a.rect.height) - (b.rect.width * b.rect.height) ||
      b.rect.right - a.rect.right
    );
    return candidates[0] || null;
  }

  function callMetadataPhones(call) {
    if (!(call instanceof Element)) return [];
    const nodes = [call];
    let parent = call.parentElement;
    for (let depth = 0; depth < 3 && parent; depth++, parent = parent.parentElement) nodes.push(parent);
    const unique = new Map();
    for (const node of nodes) {
      const href = node.getAttribute('href');
      const values = [
        href && /^tel:/iu.test(href) ? decodeURIComponent(href.slice(4)) : null,
        node.getAttribute('data-phone-number'), node.getAttribute('data-number'), node.getAttribute('data-value'),
        node.getAttribute('aria-label'), node.getAttribute('aria-description'), node.getAttribute('title')
      ];
      for (const value of values) {
        const phone = phoneFrom(value);
        if (phone) unique.set(phone.digits, phone);
      }
    }
    return [...unique.values()];
  }

  /**
   * Resolve only from the active header. Message history and other conversation rows are deliberately
   * excluded. A visible header number is authoritative; call-control metadata may corroborate it or
   * provide a fallback when Voice shows only a contact name. If the two disagree, refuse to copy.
   */
  function resolveHeaderNumber() {
    const call = headerCallControl();
    if (!call) return null;
    const visiblePhone = visibleHeaderPhone(call);
    const metadata = callMetadataPhones(call);
    if (visiblePhone) {
      if (metadata.length && metadata.some(item => item.digits !== visiblePhone.phone.digits)) return null;
      return {call, phoneNode: visiblePhone.node, phone: visiblePhone.phone, source: 'visible-header'};
    }
    if (metadata.length === 1) return {call, phoneNode: null, phone: metadata[0], source: 'call-metadata'};
    return null;
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      #${ID} {
        width: 32px !important; height: 32px !important; min-width: 32px !important;
        margin: 0 0 0 7px !important; padding: 0 !important; border: 0 !important;
        border-radius: 50% !important; background: transparent !important;
        color: inherit !important; opacity: .90; display: inline-flex !important;
        align-items: center !important; justify-content: center !important;
        vertical-align: middle !important; cursor: pointer !important;
        flex: 0 0 32px !important; position: relative !important; z-index: 2 !important;
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

  function flash(button, ok) {
    const old = button.innerHTML;
    button.innerHTML = ok
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4 10-10"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>';
    button.setAttribute('aria-label', ok ? 'Phone number copied' : 'Could not verify active phone number');
    button.title = button.getAttribute('aria-label');
    setTimeout(() => {
      if (!button.isConnected) return;
      button.innerHTML = old;
      button.setAttribute('aria-label', 'Copy phone number');
      button.title = button.dataset.phone ? `Copy ${button.dataset.phone}` : 'Copy phone number';
    }, 1100);
  }

  async function copyNumber(button) {
    // Re-resolve on every tap so a stale button from the previous conversation can never copy its number.
    const current = resolveHeaderNumber();
    if (!current) { flash(button, false); schedule(0); return; }
    button.dataset.phone = current.phone.display;
    button.dataset.phoneDigits = current.phone.digits;
    let ok = false;
    try { await navigator.clipboard.writeText(current.phone.display); ok = true; }
    catch (_) { ok = copyFallback(current.phone.display); }
    flash(button, ok);
  }

  function createButton(info) {
    const button = document.createElement('button');
    button.id = ID; button.type = 'button';
    button.dataset.phone = info.phone.display;
    button.dataset.phoneDigits = info.phone.digits;
    button.dataset.source = info.source;
    button.setAttribute('aria-label', 'Copy phone number');
    button.title = `Copy ${info.phone.display}`;
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
    const info = resolveHeaderNumber();
    const existing = document.getElementById(ID);
    if (!info) { existing?.remove(); return; }
    ensureStyle();

    const expectedParent = info.phoneNode && !info.phoneNode.matches('a,button,[role="button"]')
      ? info.phoneNode
      : info.phoneNode?.parentElement || info.call.parentElement;
    if (!expectedParent) { existing?.remove(); return; }

    if (existing) {
      const sameNumber = existing.dataset.phoneDigits === info.phone.digits;
      const sameParent = existing.parentElement === expectedParent;
      if (sameNumber && sameParent) {
        existing.dataset.phone = info.phone.display;
        existing.dataset.source = info.source;
        existing.title = `Copy ${info.phone.display}`;
        return;
      }
      existing.remove();
    }

    const button = createButton(info);
    if (info.phoneNode && expectedParent === info.phoneNode) {
      // Exact mockup behavior: icon is inline immediately after the visible active-header number.
      info.phoneNode.append(button);
    } else if (info.phoneNode?.parentElement) {
      info.phoneNode.parentElement.insertBefore(button, info.phoneNode.nextSibling);
    } else if (info.call.parentElement) {
      // Name-only header: only allowed when the call control itself exposes one unambiguous number.
      info.call.parentElement.insertBefore(button, info.call);
    }
  }

  function clickableConversationAncestor(node) {
    let current = node instanceof Element ? node : null;
    for (let depth = 0; depth < 8 && current; depth++, current = current.parentElement) {
      const rect = current.getBoundingClientRect();
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
      'data-tooltip', 'data-tooltip-text', 'data-tooltip-label', 'data-phone-number', 'data-number', 'data-value', 'data-unread', 'href']});

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