/* Bubble Continuity Vault full-history observer (MAIN world, exact ChatGPT origin).
 *
 * Passive mode clones conversation JSON ChatGPT already fetched. Explicit/manual/automatic full-sync
 * mode performs a same-origin GET for only the active /c/<id> conversation. Authentication remains
 * inside the ChatGPT page world: Bubble never receives, stores, logs, or exports cookies/session
 * tokens. No scrolls, clicks, composer access, or page-form data are used.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const EVENT = '__bubble_vault_history_v1__';
  const REPLAY_REQUEST = '__bubble_vault_history_request_v1__';
  const FULL_REQUEST = '__bubble_vault_full_sync_request_v1__';
  const CHUNK = 40000;
  const MAX_TEXT = 256 * 1024 * 1024;
  const MAX_DEPTH = 7;
  let latest = null;
  let sessionToken = '';
  let fullSyncBusy = false;
  let fullSyncQueued = false;
  let lastRoute = '';

  const routeChatId = () => location.pathname.match(/\/c\/([^/?#]+)/u)?.[1] || '';

  function cleanText(value) {
    return String(value || '').replace(/\u00a0/gu, ' ').trim();
  }

  function partText(part) {
    if (typeof part === 'string') return part;
    if (!part || typeof part !== 'object') return '';
    if (typeof part.text === 'string') return part.text;
    if (typeof part.content === 'string') return part.content;
    return '';
  }

  function messageRecord(message) {
    if (!message || typeof message !== 'object') return null;
    const role = String(message.author?.role || message.role || '').toLowerCase();
    if (role !== 'user' && role !== 'assistant') return null;
    const content = message.content;
    let text = '';
    if (typeof content === 'string') text = content;
    else if (content && typeof content === 'object') {
      if (Array.isArray(content.parts)) text = content.parts.map(partText).filter(Boolean).join('\n');
      else if (typeof content.text === 'string') text = content.text;
      else if (typeof content.result === 'string') text = content.result;
    }
    if (!text && Array.isArray(message.parts)) text = message.parts.map(partText).filter(Boolean).join('\n');
    text = cleanText(text);
    return text ? { role, text } : null;
  }

  function mappingRecords(value) {
    const mapping = value?.mapping;
    if (!mapping || typeof mapping !== 'object' || Array.isArray(mapping)) return null;
    const current = value.current_node || value.currentNode || value.current_message_id || value.currentMessageId;
    const records = [];
    if (current && mapping[current]) {
      const chain = [];
      const seen = new Set();
      let id = current;
      while (id && mapping[id] && !seen.has(id) && chain.length < 200000) {
        seen.add(id);
        const node = mapping[id];
        chain.push(node);
        id = node?.parent || null;
      }
      chain.reverse();
      for (const node of chain) {
        const record = messageRecord(node?.message);
        if (record) records.push(record);
      }
    } else {
      const nodes = Object.values(mapping).filter(node => node?.message);
      nodes.sort((a, b) => Number(a?.message?.create_time || a?.create_time || 0) - Number(b?.message?.create_time || b?.create_time || 0));
      for (const node of nodes) {
        const record = messageRecord(node.message);
        if (record) records.push(record);
      }
    }
    return records.length ? records : null;
  }

  function arrayRecords(value) {
    const list = Array.isArray(value?.messages) ? value.messages : null;
    if (!list?.length) return null;
    const records = list.map(item => messageRecord(item?.message || item)).filter(Boolean);
    return records.length ? records : null;
  }

  function objectConversationId(value, responseUrl) {
    for (const candidate of [value?.conversation_id, value?.conversationId, value?.conversation?.id, value?.id]) {
      if (typeof candidate === 'string' && candidate.length >= 8 && candidate.length <= 256) return candidate;
    }
    try {
      const path = new URL(responseUrl, location.href).pathname;
      return path.match(/\/conversation(?:s)?\/([^/?#]+)/u)?.[1] || '';
    } catch (_) { return ''; }
  }

  function findConversation(value, responseUrl, depth = 0, seen = new Set()) {
    if (!value || typeof value !== 'object' || depth > MAX_DEPTH || seen.has(value)) return null;
    seen.add(value);
    const records = mappingRecords(value) || arrayRecords(value);
    if (records?.length) {
      const id = objectConversationId(value, responseUrl);
      if (id) return { id, title: cleanText(value.title || value.conversation?.title || ''), records };
    }
    if (Array.isArray(value)) {
      for (const item of value) {
        const found = findConversation(item, responseUrl, depth + 1, seen);
        if (found) return found;
      }
      return null;
    }
    let checked = 0;
    for (const key of Object.keys(value)) {
      if (++checked > 256) break;
      const child = value[key];
      if (!child || typeof child !== 'object') continue;
      const found = findConversation(child, responseUrl, depth + 1, seen);
      if (found) return found;
    }
    return null;
  }

  function emitPayload(payload, source = 'passive-full-history') {
    if (!payload?.records?.length || payload.id !== routeChatId()) return false;
    const value = {...payload, source};
    const serialized = JSON.stringify(value);
    if (!serialized.length || serialized.length > MAX_TEXT) return false;
    latest = serialized;
    const transfer = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const total = Math.ceil(serialized.length / CHUNK);
    window.dispatchEvent(new CustomEvent(EVENT, { detail: JSON.stringify({ kind: 'begin', transfer, total, chars: serialized.length }) }));
    for (let index = 0; index < total; index++) {
      window.dispatchEvent(new CustomEvent(EVENT, { detail: JSON.stringify({ kind: 'chunk', transfer, index, data: serialized.slice(index * CHUNK, (index + 1) * CHUNK) }) }));
    }
    window.dispatchEvent(new CustomEvent(EVENT, { detail: JSON.stringify({ kind: 'end', transfer }) }));
    return true;
  }

  function emitSyncFailure(reason) {
    try {
      window.dispatchEvent(new CustomEvent(EVENT, {detail: JSON.stringify({kind: 'sync-failed', reason: String(reason || 'Full history unavailable').slice(0, 512)})}));
    } catch (_) {}
  }

  function replayLatest() {
    if (!latest) return false;
    try {
      const parsed = JSON.parse(latest);
      return emitPayload(parsed, parsed.source || 'passive-full-history');
    } catch (_) { return false; }
  }

  function inspectObject(value, responseUrl, source = 'passive-full-history') {
    const active = routeChatId();
    if (!active) return false;
    const found = findConversation(value, responseUrl);
    if (!found || found.id !== active || !found.records.length) return false;
    return emitPayload({ id: found.id, title: found.title, records: found.records, capturedAt: Date.now() }, source);
  }

  async function inspectResponse(response) {
    try {
      if (!response || !response.ok) return;
      const url = new URL(response.url || location.href, location.href);
      if (url.origin !== location.origin || !routeChatId()) return;
      const type = String(response.headers?.get?.('content-type') || '').toLowerCase();
      if (!type.includes('json')) return;
      const clone = response.clone();
      const text = await clone.text();
      if (!text || text.length > MAX_TEXT) return;
      inspectObject(JSON.parse(text), url.href, 'passive-full-history');
    } catch (_) {}
  }

  const originalFetch = window.fetch;

  async function getSessionToken() {
    if (sessionToken) return sessionToken;
    try {
      const response = await originalFetch.call(window, '/api/auth/session', {
        method: 'GET', credentials: 'include', cache: 'no-store', headers: {accept: 'application/json'}
      });
      if (!response.ok) return '';
      const data = await response.json();
      const token = typeof data?.accessToken === 'string' ? data.accessToken : '';
      if (token.length < 16 || token.length > 16384) return '';
      sessionToken = token;
      return token;
    } catch (_) { return ''; }
  }

  async function requestConversation(id, token = '') {
    const headers = {accept: 'application/json'};
    if (token) headers.Authorization = `Bearer ${token}`;
    return originalFetch.call(window, `/backend-api/conversation/${encodeURIComponent(id)}`, {
      method: 'GET', credentials: 'include', cache: 'no-store', headers
    });
  }

  async function requestFullHistory() {
    const id = routeChatId();
    if (!id || typeof originalFetch !== 'function') return false;
    if (fullSyncBusy) { fullSyncQueued = true; return false; }
    fullSyncBusy = true;
    let success = false;
    try {
      let response = await requestConversation(id);
      if (response.status === 401 || response.status === 403) {
        const token = await getSessionToken();
        if (token) response = await requestConversation(id, token);
      }
      if (!response.ok) throw new Error(`conversation-${response.status}`);
      const type = String(response.headers?.get?.('content-type') || '').toLowerCase();
      if (!type.includes('json')) throw new Error('conversation-not-json');
      const text = await response.text();
      if (!text || text.length > MAX_TEXT) throw new Error('conversation-size');
      success = inspectObject(JSON.parse(text), response.url || location.href, 'full-history');
      if (!success) throw new Error('conversation-parse');
    } catch (error) {
      emitSyncFailure(error?.message || 'Full history unavailable');
    } finally {
      fullSyncBusy = false;
      if (fullSyncQueued) {
        fullSyncQueued = false;
        setTimeout(() => { void requestFullHistory(); }, 250);
      }
    }
    return success;
  }

  if (typeof originalFetch === 'function') {
    window.fetch = function(...args) {
      const result = originalFetch.apply(this, args);
      Promise.resolve(result).then(response => { void inspectResponse(response); }, () => {});
      return result;
    };
  }

  const xhrOpen = XMLHttpRequest.prototype.open;
  const xhrSend = XMLHttpRequest.prototype.send;
  const xhrUrls = new WeakMap();
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    try { xhrUrls.set(this, new URL(String(url), location.href).href); } catch (_) {}
    return xhrOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.send = function(...args) {
    this.addEventListener('load', () => {
      try {
        const responseUrl = xhrUrls.get(this) || this.responseURL || '';
        const url = new URL(responseUrl, location.href);
        if (url.origin !== location.origin || !routeChatId() || this.status < 200 || this.status >= 300) return;
        if (this.responseType === 'json') inspectObject(this.response, url.href, 'passive-full-history');
        else if (this.responseType === '' || this.responseType === 'text') {
          const type = String(this.getResponseHeader('content-type') || '').toLowerCase();
          const text = String(this.responseText || '');
          if (type.includes('json') && text && text.length <= MAX_TEXT) inspectObject(JSON.parse(text), url.href, 'passive-full-history');
        }
      } catch (_) {}
    }, { once: true });
    return xhrSend.apply(this, args);
  };

  function routeChanged() {
    const current = routeChatId();
    if (current === lastRoute) return;
    lastRoute = current;
    latest = null;
    if (current) setTimeout(() => { void requestFullHistory(); }, 700);
  }

  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    if (typeof original !== 'function') continue;
    history[method] = function(...args) {
      const result = original.apply(this, args);
      queueMicrotask(routeChanged);
      return result;
    };
  }
  window.addEventListener('popstate', routeChanged, {passive: true});
  window.addEventListener('pageshow', routeChanged, {passive: true});
  window.addEventListener(REPLAY_REQUEST, replayLatest, false);
  window.addEventListener(FULL_REQUEST, () => { void requestFullHistory(); }, false);

  lastRoute = routeChatId();
  if (lastRoute) setTimeout(() => { void requestFullHistory(); }, 1400);
})();
