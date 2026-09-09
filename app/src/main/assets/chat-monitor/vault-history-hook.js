/* Bubble Continuity Vault full-history observer (MAIN world, exact ChatGPT origin).
 *
 * Passive mode clones conversation JSON ChatGPT already fetched. Explicit/manual/automatic full-sync
 * first replays the exact successful conversation GET that the live ChatGPT page itself used in this
 * tab. That keeps Bubble aligned with endpoint/header/auth drift. A legacy backend-api/session-token
 * request remains only as a fallback. Authentication never leaves this page world: Bubble native
 * code receives transcript records only, never cookies, request headers, or bearer tokens.
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
  const FORBIDDEN_REPLAY_HEADERS = /^(?:cookie|host|origin|referer|content-length|connection|user-agent|sec-)/iu;
  let latest = null;
  let sessionToken = '';
  let learnedRequest = null;
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

  function replayableHeaders(source) {
    const result = [];
    try {
      const headers = new Headers(source || undefined);
      headers.forEach((value, name) => {
        if (!FORBIDDEN_REPLAY_HEADERS.test(name) && value.length <= 16384) result.push([name, value]);
      });
    } catch (_) {}
    return result;
  }

  function fetchMeta(args) {
    try {
      const input = args?.[0];
      const init = args?.[1] || {};
      const url = new URL(typeof input === 'string' || input instanceof URL ? String(input) : input?.url || '', location.href);
      if (url.origin !== location.origin) return null;
      const method = String(init.method || input?.method || 'GET').toUpperCase();
      if (method !== 'GET') return null;
      const headers = new Headers(input?.headers || undefined);
      new Headers(init.headers || undefined).forEach((value, name) => headers.set(name, value));
      return { url: url.href, method, headers: replayableHeaders(headers), chatId: routeChatId() };
    } catch (_) { return null; }
  }

  function learnRequest(meta, conversationId) {
    const active = routeChatId();
    if (!meta || meta.method !== 'GET' || !active || conversationId !== active || meta.chatId !== active) return;
    learnedRequest = { url: meta.url, headers: meta.headers, chatId: active };
  }

  async function inspectResponse(response, meta = null) {
    try {
      if (!response || !response.ok) return;
      const url = new URL(response.url || location.href, location.href);
      if (url.origin !== location.origin || !routeChatId()) return;
      const type = String(response.headers?.get?.('content-type') || '').toLowerCase();
      if (!type.includes('json')) return;
      const clone = response.clone();
      const text = await clone.text();
      if (!text || text.length > MAX_TEXT) return;
      const object = JSON.parse(text);
      const found = findConversation(object, url.href);
      if (!found || found.id !== routeChatId() || !found.records.length) return;
      learnRequest(meta, found.id);
      emitPayload({ id: found.id, title: found.title, records: found.records, capturedAt: Date.now() }, 'passive-full-history');
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

  async function replayLearnedRequest(id) {
    if (!learnedRequest || learnedRequest.chatId !== id) return null;
    try {
      const headers = new Headers();
      for (const [name, value] of learnedRequest.headers) headers.set(name, value);
      return await originalFetch.call(window, learnedRequest.url, {
        method: 'GET', credentials: 'include', cache: 'no-store', headers
      });
    } catch (_) { return null; }
  }

  async function requestConversation(id, token = '') {
    const headers = {accept: 'application/json'};
    if (token) headers.Authorization = `Bearer ${token}`;
    return originalFetch.call(window, `/backend-api/conversation/${encodeURIComponent(id)}`, {
      method: 'GET', credentials: 'include', cache: 'no-store', headers
    });
  }

  async function consumeFullResponse(response, id, sourceLabel) {
    if (!response) throw new Error(`${sourceLabel}-no-response`);
    if (!response.ok) throw new Error(`${sourceLabel}-${response.status}`);
    const type = String(response.headers?.get?.('content-type') || '').toLowerCase();
    if (!type.includes('json')) throw new Error(`${sourceLabel}-not-json`);
    const text = await response.text();
    if (!text || text.length > MAX_TEXT) throw new Error(`${sourceLabel}-size`);
    const object = JSON.parse(text);
    const found = findConversation(object, response.url || location.href);
    if (!found || found.id !== id || !found.records.length) throw new Error(`${sourceLabel}-parse`);
    const success = emitPayload({ id: found.id, title: found.title, records: found.records, capturedAt: Date.now() }, 'full-history');
    if (!success) throw new Error(`${sourceLabel}-emit`);
    return true;
  }

  async function requestFullHistory() {
    const id = routeChatId();
    if (!id || typeof originalFetch !== 'function') return false;
    if (fullSyncBusy) { fullSyncQueued = true; return false; }
    fullSyncBusy = true;
    let success = false;
    const failures = [];
    try {
      const learned = await replayLearnedRequest(id);
      if (learned) {
        try { success = await consumeFullResponse(learned, id, 'learned'); }
        catch (error) { failures.push(error?.message || 'learned-failed'); }
      }
      if (!success) {
        try { success = await consumeFullResponse(await requestConversation(id), id, 'cookie'); }
        catch (error) { failures.push(error?.message || 'cookie-failed'); }
      }
      if (!success) {
        const token = await getSessionToken();
        if (!token) failures.push('session-token-unavailable');
        else {
          try { success = await consumeFullResponse(await requestConversation(id, token), id, 'bearer'); }
          catch (error) { failures.push(error?.message || 'bearer-failed'); }
        }
      }
      if (!success) throw new Error(failures.filter(Boolean).join(';') || 'Full history unavailable');
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
      const meta = fetchMeta(args);
      const result = originalFetch.apply(this, args);
      Promise.resolve(result).then(response => { void inspectResponse(response, meta); }, () => {});
      return result;
    };
  }

  const xhrOpen = XMLHttpRequest.prototype.open;
  const xhrSend = XMLHttpRequest.prototype.send;
  const xhrSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
  const xhrUrls = new WeakMap();
  const xhrMethods = new WeakMap();
  const xhrHeaders = new WeakMap();
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    try {
      xhrUrls.set(this, new URL(String(url), location.href).href);
      xhrMethods.set(this, String(method || 'GET').toUpperCase());
      xhrHeaders.set(this, []);
    } catch (_) {}
    return xhrOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    try {
      const headers = xhrHeaders.get(this);
      if (headers && !FORBIDDEN_REPLAY_HEADERS.test(String(name))) headers.push([String(name), String(value).slice(0, 16384)]);
    } catch (_) {}
    return xhrSetRequestHeader.call(this, name, value);
  };
  XMLHttpRequest.prototype.send = function(...args) {
    this.addEventListener('load', () => {
      try {
        const responseUrl = xhrUrls.get(this) || this.responseURL || '';
        const url = new URL(responseUrl, location.href);
        if (url.origin !== location.origin || !routeChatId() || this.status < 200 || this.status >= 300) return;
        let object = null;
        if (this.responseType === 'json') object = this.response;
        else if (this.responseType === '' || this.responseType === 'text') {
          const type = String(this.getResponseHeader('content-type') || '').toLowerCase();
          const text = String(this.responseText || '');
          if (type.includes('json') && text && text.length <= MAX_TEXT) object = JSON.parse(text);
        }
        if (!object) return;
        const found = findConversation(object, url.href);
        if (!found || found.id !== routeChatId() || !found.records.length) return;
        const method = xhrMethods.get(this) || 'GET';
        if (method === 'GET') learnRequest({url: url.href, method, headers: replayableHeaders(xhrHeaders.get(this)), chatId: routeChatId()}, found.id);
        emitPayload({ id: found.id, title: found.title, records: found.records, capturedAt: Date.now() }, 'passive-full-history');
      } catch (_) {}
    }, { once: true });
    return xhrSend.apply(this, args);
  };

  function routeChanged() {
    const current = routeChatId();
    if (current === lastRoute) return;
    lastRoute = current;
    latest = null;
    learnedRequest = null;
    sessionToken = '';
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
