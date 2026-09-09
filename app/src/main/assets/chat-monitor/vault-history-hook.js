/* Bubble Continuity Vault full-history observer (MAIN world, exact ChatGPT origin).
 *
 * This does not make a network request. It only clones same-origin responses ChatGPT itself already
 * fetched for the active /c/<id> route, extracts user/assistant conversation content, and emits it
 * through a local chunked CustomEvent. It never reads request headers, cookies, storage, bearer
 * tokens, account identifiers, composer drafts, or form data, and it never clicks or scrolls.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const EVENT = '__bubble_vault_history_v1__';
  const REQUEST = '__bubble_vault_history_request_v1__';
  const CHUNK = 40000;
  const MAX_TEXT = 256 * 1024 * 1024;
  const MAX_DEPTH = 7;
  let latest = null;

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

  function emitPayload(payload) {
    if (!payload?.records?.length || payload.id !== routeChatId()) return;
    const serialized = JSON.stringify(payload);
    if (!serialized.length || serialized.length > MAX_TEXT) return;
    latest = serialized;
    const transfer = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const total = Math.ceil(serialized.length / CHUNK);
    window.dispatchEvent(new CustomEvent(EVENT, { detail: JSON.stringify({ kind: 'begin', transfer, total, chars: serialized.length }) }));
    for (let index = 0; index < total; index++) {
      window.dispatchEvent(new CustomEvent(EVENT, { detail: JSON.stringify({ kind: 'chunk', transfer, index, data: serialized.slice(index * CHUNK, (index + 1) * CHUNK) }) }));
    }
    window.dispatchEvent(new CustomEvent(EVENT, { detail: JSON.stringify({ kind: 'end', transfer }) }));
  }

  function replayLatest() {
    if (!latest) return;
    try { emitPayload(JSON.parse(latest)); } catch (_) {}
  }

  function inspectObject(value, responseUrl) {
    const active = routeChatId();
    if (!active) return;
    const found = findConversation(value, responseUrl);
    if (!found || found.id !== active || !found.records.length) return;
    emitPayload({ id: found.id, title: found.title, records: found.records, capturedAt: Date.now() });
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
      inspectObject(JSON.parse(text), url.href);
    } catch (_) {}
  }

  const originalFetch = window.fetch;
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
        if (this.responseType === 'json') inspectObject(this.response, url.href);
        else if (this.responseType === '' || this.responseType === 'text') {
          const type = String(this.getResponseHeader('content-type') || '').toLowerCase();
          const text = String(this.responseText || '');
          if (type.includes('json') && text && text.length <= MAX_TEXT) inspectObject(JSON.parse(text), url.href);
        }
      } catch (_) {}
    }, { once: true });
    return xhrSend.apply(this, args);
  };

  window.addEventListener(REQUEST, replayLatest, false);
})();
