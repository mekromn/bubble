/* Bubble Continuity Vault full-history bridge (isolated world, exact ChatGPT origin).
 * Receives only local CustomEvents from vault-history-hook.js and forwards validated transcript
 * snapshots to Bubble's app-private Vault. No network, credentials, cookies, storage, or page
 * interaction is performed here. */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const EVENT = '__bubble_vault_history_v1__';
  const REQUEST = '__bubble_vault_history_request_v1__';
  const CHUNK_CHARS = 48000;
  const MAX_EVENT_CHARS = 43000;
  const MAX_SNAPSHOT_CHARS = 256 * 1024 * 1024;
  const incoming = new Map();
  let lastSignature = '';

  const routeChatId = () => location.pathname.match(/\/c\/([^/?#]+)/u)?.[1] || '';
  const send = async message => {
    try { return await browser.runtime.sendNativeMessage('bubbleVault', message); }
    catch (_) { return null; }
  };

  function chunksOf(text) {
    const chunks = [];
    for (let start = 0; start < text.length;) {
      let end = Math.min(text.length, start + CHUNK_CHARS);
      if (end < text.length) {
        const code = text.charCodeAt(end - 1);
        if (code >= 0xD800 && code <= 0xDBFF) end--;
      }
      chunks.push(text.slice(start, end));
      start = end;
    }
    return chunks;
  }

  function normalizePayload(value) {
    if (!value || typeof value !== 'object') return null;
    const id = typeof value.id === 'string' ? value.id : '';
    if (!id || id !== routeChatId()) return null;
    const source = Array.isArray(value.records) ? value.records : [];
    const records = [];
    for (const item of source) {
      const role = item?.role === 'user' || item?.role === 'assistant' ? item.role : '';
      const text = typeof item?.text === 'string' ? item.text.replace(/\u00a0/gu, ' ').trim() : '';
      if (role && text) records.push({ role, text });
      if (records.length > 200000) return null;
    }
    if (!records.length) return null;
    const firstSignature = `${records[0].role}:${records[0].text.slice(0, 240)}`;
    return {
      id,
      title: (typeof value.title === 'string' ? value.title : document.title)
        .replace(/\s*[-–|]\s*ChatGPT\s*$/iu, '').trim().slice(0, 512) || 'Untitled chat',
      url: location.href,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      firstSignature,
      messages: records,
    };
  }

  function signature(snapshot) {
    const last = snapshot.messages.at(-1);
    return `${snapshot.id}|${snapshot.messages.length}|${last?.role || ''}|${last?.text.length || 0}|${last?.text.slice(-320) || ''}`;
  }

  async function persist(snapshot) {
    if (!snapshot) return;
    const fingerprint = signature(snapshot);
    if (fingerprint === lastSignature) return;
    const serialized = JSON.stringify(snapshot);
    if (!serialized.length || serialized.length > MAX_SNAPSHOT_CHARS) return;
    const chunks = chunksOf(serialized);
    const transfer = crypto.randomUUID();
    const begin = await send({
      event: 'vault-snapshot-begin', transfer, chatId: snapshot.id, title: snapshot.title,
      url: snapshot.url, createdAt: snapshot.createdAt, updatedAt: snapshot.updatedAt,
      firstSignature: snapshot.firstSignature, messages: snapshot.messages.length,
      totalChunks: chunks.length, chars: serialized.length, source: 'passive-full-history'
    });
    if (begin?.accepted !== true) {
      // Native remembers rejected transfer IDs so legacy senders that ignore the begin response
      // cannot accidentally stream chunks into the Vault. Close our rejected transfer explicitly.
      await send({ event: 'vault-snapshot-end', transfer });
      return;
    }
    for (let index = 0; index < chunks.length; index++) {
      await send({ event: 'vault-snapshot-chunk', transfer, index, data: chunks[index] });
    }
    await send({ event: 'vault-snapshot-end', transfer });
    lastSignature = fingerprint;
  }

  window.addEventListener(EVENT, event => {
    if (typeof event.detail !== 'string' || event.detail.length > MAX_EVENT_CHARS) return;
    let message;
    try { message = JSON.parse(event.detail); } catch (_) { return; }
    const transfer = typeof message.transfer === 'string' && message.transfer.length <= 128 ? message.transfer : '';
    if (!transfer) return;
    if (message.kind === 'begin') {
      const total = Number(message.total);
      const chars = Number(message.chars);
      if (!Number.isInteger(total) || total < 1 || total > 7000 || !Number.isInteger(chars) || chars < 2 || chars > MAX_SNAPSHOT_CHARS) return;
      incoming.set(transfer, { total, chars, next: 0, data: [] });
      return;
    }
    const state = incoming.get(transfer);
    if (!state) return;
    if (message.kind === 'chunk') {
      const index = Number(message.index);
      const data = typeof message.data === 'string' ? message.data : '';
      if (index !== state.next || index >= state.total || data.length > 41000) { incoming.delete(transfer); return; }
      state.data.push(data); state.next++;
      return;
    }
    if (message.kind === 'end') {
      incoming.delete(transfer);
      if (state.next !== state.total) return;
      const text = state.data.join('');
      if (text.length !== state.chars) return;
      try { void persist(normalizePayload(JSON.parse(text))); } catch (_) {}
    }
  }, false);

  // MAIN-world hook may have captured the initial conversation response before this isolated script
  // or the native Vault index was ready. Replays are local-only and cheap: they ask the hook to emit
  // the already-cloned latest response again and never cause another ChatGPT network request.
  for (const delay of [0, 1200, 3500, 8000]) {
    setTimeout(() => window.dispatchEvent(new CustomEvent(REQUEST)), delay);
  }
})();
