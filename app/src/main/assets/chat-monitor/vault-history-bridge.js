/* Bubble Continuity Vault full-history bridge (isolated world, exact ChatGPT origin).
 * Receives local transcript CustomEvents and exposes a persistent per-session native control port.
 * Network/authentication stays in the MAIN-world hook; this isolated bridge sees transcript records
 * only. Heartbeats never focus, scroll, click, reload, or edit page content.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const EVENT = '__bubble_vault_history_v1__';
  const REPLAY_REQUEST = '__bubble_vault_history_request_v1__';
  const FULL_REQUEST = '__bubble_vault_full_sync_request_v1__';
  const CHUNK_CHARS = 48000;
  const MAX_EVENT_CHARS = 43000;
  const MAX_SNAPSHOT_CHARS = 256 * 1024 * 1024;
  const incoming = new Map();
  let lastSignature = '';
  let lastSnapshot = null;
  let archiveRequestId = '';
  let archiveTimeout = 0;

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
    const sourceItems = Array.isArray(value.records) ? value.records : [];
    const records = [];
    for (const item of sourceItems) {
      const role = item?.role === 'user' || item?.role === 'assistant' ? item.role : '';
      const text = typeof item?.text === 'string' ? item.text.replace(/\u00a0/gu, ' ').trim() : '';
      if (role && text) records.push({ role, text });
      if (records.length > 200000) return null;
    }
    if (!records.length) return null;
    const firstSignature = `${records[0].role}:${records[0].text.slice(0, 240)}`;
    const source = value.source === 'full-history' ? 'full-history' : 'passive-full-history';
    return {
      id,
      title: (typeof value.title === 'string' ? value.title : document.title)
        .replace(/\s*[-–|]\s*ChatGPT\s*$/iu, '').trim().slice(0, 512) || 'Untitled chat',
      url: location.href,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      firstSignature,
      source,
      messages: records,
    };
  }

  function signature(snapshot) {
    const last = snapshot.messages.at(-1);
    return `${snapshot.source}|${snapshot.id}|${snapshot.messages.length}|${last?.role || ''}|${last?.text.length || 0}|${last?.text.slice(-320) || ''}`;
  }

  async function persist(snapshot) {
    if (!snapshot) return { ok: false, reason: 'No complete conversation snapshot is available' };
    lastSnapshot = snapshot;
    const fingerprint = signature(snapshot);
    if (fingerprint === lastSignature) return { ok: true, chatId: snapshot.id, messages: snapshot.messages.length };
    const serialized = JSON.stringify(snapshot);
    if (!serialized.length || serialized.length > MAX_SNAPSHOT_CHARS) return { ok: false, reason: 'Conversation snapshot is too large' };
    const chunks = chunksOf(serialized);
    const transfer = crypto.randomUUID();
    const begin = await send({
      event: 'vault-snapshot-begin', transfer, chatId: snapshot.id, title: snapshot.title,
      url: snapshot.url, createdAt: snapshot.createdAt, updatedAt: snapshot.updatedAt,
      firstSignature: snapshot.firstSignature, messages: snapshot.messages.length,
      totalChunks: chunks.length, chars: serialized.length, source: snapshot.source
    });
    if (begin?.accepted !== true) {
      await send({ event: 'vault-snapshot-end', transfer });
      return { ok: false, reason: 'Native Vault is not ready for this snapshot' };
    }
    for (let index = 0; index < chunks.length; index++) {
      await send({ event: 'vault-snapshot-chunk', transfer, index, data: chunks[index] });
    }
    await send({ event: 'vault-snapshot-end', transfer });
    lastSignature = fingerprint;
    return { ok: true, chatId: snapshot.id, messages: snapshot.messages.length };
  }

  function finishArchive(result) {
    if (!archiveRequestId) return;
    const requestId = archiveRequestId;
    archiveRequestId = '';
    clearTimeout(archiveTimeout); archiveTimeout = 0;
    try {
      control.postMessage({
        event: 'archive-full-history-result', requestId,
        ok: Boolean(result?.ok), chatId: result?.chatId || '', messages: Number(result?.messages || 0),
        reason: result?.reason || ''
      });
    } catch (_) {}
  }

  window.addEventListener(EVENT, event => {
    if (typeof event.detail !== 'string' || event.detail.length > MAX_EVENT_CHARS) return;
    let message;
    try { message = JSON.parse(event.detail); } catch (_) { return; }

    if (message.kind === 'sync-failed') {
      if (archiveRequestId) finishArchive({ok: false, reason: message.reason || 'Full history unavailable'});
      return;
    }

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
      try {
        const snapshot = normalizePayload(JSON.parse(text));
        if (!snapshot) return;
        lastSnapshot = snapshot;
        void persist(snapshot).then(result => { if (archiveRequestId && snapshot.source === 'full-history') finishArchive(result); });
      } catch (_) {}
    }
  }, false);

  function activityFingerprint() {
    const turns = [...document.querySelectorAll('main article[data-testid^="conversation-turn-"]')];
    const last = turns.at(-1);
    const lastId = last?.getAttribute('data-testid') || last?.getAttribute('data-message-id') || last?.id || '';
    const busy = Boolean(document.querySelector('button[data-testid="stop-button"],button[aria-label*="Stop generating" i],[data-is-streaming="true"]'));
    return {
      fingerprint: `${routeChatId()}|${turns.length}|${lastId}|${busy ? 1 : 0}`.slice(0, 512),
      busy
    };
  }

  const control = browser.runtime.connectNative('bubbleVault');
  control.onMessage.addListener(message => {
    const requestId = typeof message?.requestId === 'string' ? message.requestId : '';
    if (!requestId) return;
    if (message.event === 'heartbeat') {
      const state = activityFingerprint();
      try { control.postMessage({ event: 'heartbeat-result', requestId, ok: true, fingerprint: state.fingerprint, busy: state.busy }); } catch (_) {}
      return;
    }
    if (message.event === 'archive-full-history') {
      archiveRequestId = requestId;
      clearTimeout(archiveTimeout);
      archiveTimeout = setTimeout(() => finishArchive({ ok: false, reason: 'Full-history request timed out' }), 40000);
      window.dispatchEvent(new CustomEvent(FULL_REQUEST));
    }
  });

  // Local replay retries cover the short native-index cold-start window without another request.
  for (const delay of [0, 1200, 3500, 8000]) setTimeout(() => window.dispatchEvent(new CustomEvent(REPLAY_REQUEST)), delay);
})();
