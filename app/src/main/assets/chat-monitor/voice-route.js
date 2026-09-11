/* Bubble Google Voice notification routing bridge.
 * Exact voice.google.com top frame only. Receives already-local notification identity from native,
 * scores Voice conversation rows by phone/name/message, and opens the best matching conversation.
 * Clipboard/copy-number logic is intentionally NOT implemented here.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://voice.google.com') return;

  const NATIVE = 'bubbleVoice';
  const RECONNECT_MS = 700;
  let port = null;
  let reconnectTimer = 0;
  let pendingRoute = null;
  let revealAttempted = false;

  const normalize = value => String(value || '').replace(/\u00a0/gu, ' ').replace(/\s+/gu, ' ').trim().toLowerCase();
  const digits = value => String(value || '').replace(/\D/gu, '');

  function visible(node) {
    if (!(node instanceof Element) || !node.isConnected) return false;
    const rect = node.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1 || rect.bottom < 0 || rect.right < 0 || rect.top > innerHeight || rect.left > innerWidth) return false;
    const style = getComputedStyle(node);
    return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
  }

  function values(node, includeText = true) {
    if (!(node instanceof Element)) return [];
    const list = [
      node.getAttribute('aria-label'), node.getAttribute('aria-description'), node.getAttribute('title'),
      node.getAttribute('data-phone-number'), node.getAttribute('data-number'), node.getAttribute('href')
    ];
    if (includeText) list.push(node.textContent);
    return list.filter(Boolean);
  }

  function phoneFrom(value) {
    const matches = String(value || '').match(/\+?\s*(?:\(\d{2,4}\)|\d)[\d\s().-]{4,}\d/gu) || [];
    for (const raw of matches) {
      const cleaned = raw.trim().replace(/[.,;:]+$/u, '');
      const number = digits(cleaned);
      if (number.length >= 7 && number.length <= 15) return {display: cleaned, digits: number};
    }
    return null;
  }

  // For phone enrichment, accept only fields intended to identify the contact. Never mine message
  // body text for a phone number because a message can mention somebody else's number.
  function explicitPhone(root) {
    if (!(root instanceof Element)) return null;
    const nodes = [root, ...root.querySelectorAll('a[href^="tel:" i],[data-phone-number],[data-number]')];
    for (const node of nodes) {
      const href = node.getAttribute?.('href') || '';
      const candidates = [
        /^tel:/iu.test(href) ? decodeURIComponent(href.slice(4)) : null,
        node.getAttribute?.('data-phone-number'), node.getAttribute?.('data-number')
      ];
      for (const value of candidates) {
        const phone = phoneFrom(value);
        if (phone) return phone;
      }
    }
    return null;
  }

  function clickableAncestor(node) {
    let current = node instanceof Element ? node : null;
    for (let depth = 0; depth < 8 && current; depth++, current = current.parentElement) {
      const rect = current.getBoundingClientRect();
      const clickable = current.matches('a,button,[tabindex],[role="listitem"],[role="option"],[role="row"],[role="button"]');
      if (clickable && rect.height >= 38 && rect.height <= 190 && rect.width >= 180) return current;
    }
    return null;
  }

  function candidateRows() {
    const rows = new Set();
    document.querySelectorAll('[role="listitem"],[role="option"],[role="row"],a[href],[tabindex]').forEach(node => {
      const row = clickableAncestor(node);
      if (!row || row === document.body || !visible(row)) return;
      if (row.closest('header') || row.closest('[contenteditable="true"]')) return;
      const rect = row.getBoundingClientRect();
      if (rect.top < 48 || rect.height > 190) return;
      rows.add(row);
    });
    return [...rows];
  }

  function words(value) {
    const generic = new Set(['the','and','that','this','with','from','have','your','you','for','are','was','but','not','google','voice','message','text']);
    return normalize(value).split(/[^\p{L}\p{N}]+/u).filter(word => word.length >= 3 && !generic.has(word)).slice(0, 24);
  }

  function targetFrom(message) {
    return {
      name: normalize(message?.name).slice(0, 160),
      phone: digits(message?.phone).slice(0, 15),
      message: normalize(message?.message).slice(0, 2048)
    };
  }

  function scoreRow(row, target) {
    const text = normalize(values(row, true).join(' '));
    const explicit = explicitPhone(row);
    let score = 0;
    let identity = false;

    if (target.phone && explicit?.digits === target.phone) { score += 360; identity = true; }
    if (target.name) {
      if (text === target.name) { score += 220; identity = true; }
      else if (text.startsWith(target.name + ' ') || text.includes(' ' + target.name + ' ') || text.includes(target.name)) {
        score += 150; identity = true;
      }
    }

    const tokens = words(target.message);
    if (tokens.length) {
      let hits = 0;
      for (const token of tokens) if (text.includes(token)) hits++;
      score += Math.min(140, hits * 24);
      if (!identity && hits >= Math.min(3, tokens.length)) identity = true;
    }

    if (/\bunread\b/iu.test(text) || row.querySelector('[aria-label*="unread" i],[aria-description*="unread" i],[title*="unread" i],[data-unread="true"]')) score += 45;
    if (row.getAttribute('aria-selected') === 'true' || row.getAttribute('aria-current') === 'true') score += 10;
    return {row, score, identity, explicit, rect: row.getBoundingClientRect()};
  }

  function bestRow(target) {
    return candidateRows().map(row => scoreRow(row, target))
      .filter(item => item.identity && item.score >= 120)
      .sort((a, b) => b.score - a.score || a.rect.top - b.rect.top)[0] || null;
  }

  function headerMatches(target) {
    const top = [...document.querySelectorAll('main h1,main h2,main h3,main [role="heading"],header h1,header h2,header h3,header [role="heading"],main [aria-label],header [aria-label]')]
      .filter(visible)
      .filter(node => node.getBoundingClientRect().top < Math.min(innerHeight * .36, 380));
    const text = normalize(top.map(node => values(node, true).join(' ')).join(' '));
    if (target.phone) {
      const explicit = top.map(explicitPhone).find(Boolean);
      if (explicit?.digits === target.phone) return true;
    }
    if (target.name && text.includes(target.name)) return true;
    return false;
  }

  function backControl() {
    return [...document.querySelectorAll('button,[role="button"],a[role="button"]')]
      .filter(visible)
      .filter(node => /(^|\b)(back|go back|previous)(\b|$)/iu.test(normalize(values(node, false).join(' '))))
      .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top || a.getBoundingClientRect().left - b.getBoundingClientRect().left)[0] || null;
  }

  function route(target, allowReveal = true) {
    if (!target || document.hidden) return false;
    if (headerMatches(target)) { revealAttempted = false; return true; }
    const best = bestRow(target);
    if (best) {
      const selected = best.row.getAttribute('aria-selected') === 'true' || best.row.getAttribute('aria-current') === 'true';
      if (!selected) best.row.click();
      revealAttempted = false;
      return true;
    }
    if (allowReveal && !revealAttempted) {
      const back = backControl();
      if (back && back.getBoundingClientRect().top < 240) {
        revealAttempted = true;
        back.click();
        setTimeout(() => route(target, false), 280);
      }
    }
    return false;
  }

  function lookupPhone(target) {
    const best = bestRow(target);
    return best?.explicit?.display || '';
  }

  function post(payload) {
    try { port?.postMessage(payload); } catch (_) {}
  }

  function onNative(message) {
    if (!message || typeof message !== 'object') return;
    const target = targetFrom(message);
    if (message.event === 'open-notification') {
      pendingRoute = target;
      revealAttempted = false;
      route(target, true);
      for (const delay of [180, 420, 850, 1500]) setTimeout(() => pendingRoute && route(pendingRoute, true), delay);
      return;
    }
    if (message.event === 'lookup-notification') {
      post({event: 'lookup-result', requestId: String(message.requestId || ''), phone: lookupPhone(target)});
    }
  }

  function connect() {
    clearTimeout(reconnectTimer);
    try {
      const next = browser.runtime.connectNative(NATIVE);
      port = next;
      next.onMessage.addListener(onNative);
      next.onDisconnect.addListener(() => {
        if (port === next) port = null;
        reconnectTimer = setTimeout(connect, RECONNECT_MS);
      });
    } catch (_) {
      port = null;
      reconnectTimer = setTimeout(connect, RECONNECT_MS);
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && pendingRoute) setTimeout(() => route(pendingRoute, true), 60);
  }, {passive: true});
  window.addEventListener('focus', () => { if (pendingRoute) setTimeout(() => route(pendingRoute, true), 40); }, {passive: true});
  window.addEventListener('pageshow', () => { if (pendingRoute) setTimeout(() => route(pendingRoute, true), 80); }, {passive: true});

  connect();
})();
