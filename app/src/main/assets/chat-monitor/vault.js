/* Bubble Continuity Vault: exact ChatGPT origin, top frame, local native storage only.
 * Reads rendered user/assistant turns because the user explicitly enabled a local chat archive.
 * No cookies, bearer tokens, account identifiers, prompts-in-progress, or network requests are read.
 *
 * Explicit staged continuity prefers a complete Markdown transcript attachment in a fresh ChatGPT
 * composer. The older size-aware text handoff remains only as a fallback if the live page exposes
 * no usable upload control. This script never submits the composer or fabricates a trusted click.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const MAX_HANDOFF_CHARS = 70000; // Kept in sync with native ChatVault for fallback release-contract tests.
  const MAX_EXPORT_BYTES = 256 * 1024 * 1024;
  const CHUNK_CHARS = 48000;
  const HANDOFF_MARKER = '[CONTINUITY HANDOFF — PREVIOUS CHAT]';
  const TEXT_FALLBACK_REQUEST = '__bubble_vault_text_fallback_request_v1__';
  const TEXT_FALLBACK_RESULT = '__bubble_vault_text_fallback_result_v1__';
  const STOP = 'button[data-testid="stop-button"],button[aria-label*="Stop generating" i],button[aria-label*="Stop streaming" i],button[aria-label="Stop" i]';

  let visibleMessages = [];
  let activeSnapshot = null;
  let lastLocation = location.href;
  let lastSavedSignature = '';
  let refreshTimer = 0;
  let saveTimer = 0;
  let pendingRequestTimer = 0;
  let pendingAttempts = 0;
  let loadedSourceId = '';
  let goEligible = false;
  let goArmTimer = 0;
  let goLoadedFingerprint = '';

  const send = async message => {
    try { return await browser.runtime.sendNativeMessage('bubbleVault', message); }
    catch (_) { return null; }
  };
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  function messageRole(article) {
    const role = article.querySelector('[data-message-author-role]')?.getAttribute('data-message-author-role')?.toLowerCase();
    if (role === 'user' || role === 'assistant') return role;
    return /user/iu.test(article.getAttribute('data-testid') || '') ? 'user' : 'assistant';
  }

  function messageText(article) {
    const source = article.querySelector('[data-message-author-role]') || article;
    const copy = source.cloneNode(true);
    copy.querySelectorAll?.('.cxe-message-tools').forEach(node => node.remove());
    return (copy.innerText || copy.textContent || '').replace(/\u00a0/gu, ' ').trim();
  }

  function findMessages() {
    let nodes = [...document.querySelectorAll('main article[data-testid^="conversation-turn-"]')];
    if (!nodes.length) nodes = [...document.querySelectorAll('main article')].filter(node => node.querySelector('[data-message-author-role]'));
    if (!nodes.length) {
      const unique = new Set();
      for (const node of document.querySelectorAll('main [data-message-author-role]')) unique.add(node.closest('article') || node.parentElement);
      nodes = [...unique].filter(Boolean);
    }
    return nodes.filter(node => node.isConnected);
  }

  function routeChatId() { return location.pathname.match(/\/c\/([^/?#]+)/u)?.[1] || ''; }

  function makeSnapshot() {
    const records = visibleMessages.map(article => ({ role: messageRole(article), text: messageText(article) })).filter(record => record.text);
    if (!records.length) return null;
    const firstSignature = `${records[0].role}:${records[0].text.slice(0, 240)}`;
    const sameConversation = activeSnapshot?.firstSignature === firstSignature;
    const routeId = routeChatId();
    return {
      id: sameConversation ? activeSnapshot.id : (routeId || `local-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`),
      title: document.title.replace(/\s*[-–|]\s*ChatGPT\s*$/iu, '').trim() || 'Untitled chat',
      url: location.href,
      createdAt: sameConversation ? activeSnapshot.createdAt : Date.now(),
      updatedAt: Date.now(),
      firstSignature,
      messages: records,
    };
  }

  function snapshotSignature(snapshot) {
    const last = snapshot.messages.at(-1);
    return `${snapshot.id}|${snapshot.title}|${snapshot.url}|${snapshot.messages.length}|${last?.role || ''}|${last?.text.length || 0}|${last?.text.slice(-320) || ''}`;
  }

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

  async function persistSnapshot(snapshot) {
    if (!snapshot?.messages?.length) return false;
    const payload = JSON.stringify(snapshot);
    const chunks = chunksOf(payload);
    const transfer = crypto.randomUUID();
    await send({
      event: 'vault-snapshot-begin', transfer, chatId: snapshot.id, title: snapshot.title, url: snapshot.url,
      createdAt: snapshot.createdAt, updatedAt: snapshot.updatedAt, firstSignature: snapshot.firstSignature,
      messages: snapshot.messages.length, totalChunks: chunks.length, chars: payload.length,
    });
    for (let index = 0; index < chunks.length; index++) {
      await send({ event: 'vault-snapshot-chunk', transfer, index, data: chunks[index] });
    }
    await send({ event: 'vault-snapshot-end', transfer });
    lastSavedSignature = snapshotSignature(snapshot);
    return true;
  }

  function scheduleSnapshotSave() {
    clearTimeout(saveTimer);
    const snapshot = activeSnapshot;
    if (!snapshot || snapshotSignature(snapshot) === lastSavedSignature) return;
    saveTimer = setTimeout(() => { void persistSnapshot(snapshot); }, 700);
  }

  async function secureAndStage(snapshot) {
    if (!snapshot?.messages?.length) return;
    await persistSnapshot(snapshot);
    await send({ event: 'vault-stage', chatId: snapshot.id });
  }

  function isNewChatControl(target) {
    const control = target.closest?.('a,button,[role="button"]');
    if (!control) return false;
    const label = `${control.getAttribute('aria-label') || ''} ${control.getAttribute('title') || ''} ${control.textContent || ''}`;
    if (/\bnew chat\b/iu.test(label)) return true;
    if (control.tagName !== 'A' || !control.href) return false;
    try {
      const url = new URL(control.href, location.href);
      return url.origin === location.origin && (url.pathname === '/' || /\/new(?:\/|$)/u.test(url.pathname));
    } catch (_) { return false; }
  }

  function findComposer() {
    return document.querySelector('textarea[data-testid="prompt-textarea"],textarea#prompt-textarea,#prompt-textarea[contenteditable="true"],main textarea,main [contenteditable="true"][role="textbox"]');
  }

  function composerValue(input) { return !input ? '' : ('value' in input ? input.value : input.innerText || input.textContent || ''); }

  function selectComposerContents(input) {
    const selection = getSelection();
    if (!selection) return;
    const range = document.createRange();
    range.selectNodeContents(input); selection.removeAllRanges(); selection.addRange(range);
  }

  async function setComposerText(input, text) {
    if (!input || composerValue(input).trim()) return false;
    input.focus({ preventScroll: false });
    if (input instanceof HTMLTextAreaElement) {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter ? setter.call(input, text) : (input.value = text);
      input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await sleep(100);
      return composerValue(input).includes(text.slice(0, Math.min(64, text.length)));
    }
    try {
      selectComposerContents(input);
      document.execCommand('insertText', false, text);
    } catch (_) {}
    await sleep(100);
    if (composerValue(input).includes(text.slice(0, Math.min(64, text.length)))) return true;
    try {
      const fragment = document.createDocumentFragment();
      for (const line of text.split('\n')) {
        const paragraph = document.createElement('p');
        line ? paragraph.append(document.createTextNode(line)) : paragraph.append(document.createElement('br'));
        fragment.append(paragraph);
      }
      input.replaceChildren(fragment);
      input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste' }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (_) { return false; }
    await sleep(120);
    return composerValue(input).includes(text.slice(0, Math.min(64, text.length)));
  }

  function isGeneralFileInput(input) {
    if (!input || input.disabled || input.files?.length) return false;
    const identity = `${input.id || ''} ${input.getAttribute('data-testid') || ''} ${input.getAttribute('aria-label') || ''}`.toLowerCase();
    if (/upload-(?:photos|camera)|photo|camera/u.test(identity)) return false;
    const accept = String(input.accept || '').trim().toLowerCase();
    if (!accept) return true;
    const tokens = accept.split(',').map(value => value.trim()).filter(Boolean);
    if (!tokens.length) return true;
    const mediaOnly = tokens.every(value => /^(?:image|video|audio)\//u.test(value) || /^(?:image|video|audio)\/\*$/u.test(value));
    return !mediaOnly;
  }

  function fileInputs() {
    const all = [...document.querySelectorAll('input[type="file"]')].filter(isGeneralFileInput);
    const preferred = [
      document.querySelector('input#upload-files'),
      document.querySelector('input[data-testid="file-upload"]'),
      document.querySelector('input[data-testid="composer-file-input"]')
    ].filter(isGeneralFileInput);
    return [...new Set([...preferred, ...all])];
  }

  function decodeBase64(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  async function readTranscript(meta) {
    if (!meta?.available || typeof meta.transfer !== 'string' || typeof meta.filename !== 'string') return null;
    const total = Number(meta.bytes);
    if (!Number.isFinite(total) || total <= 0 || total > MAX_EXPORT_BYTES) return null;
    const parts = [];
    let offset = 0;
    while (offset < total) {
      const chunk = await send({event: 'vault-transcript-chunk', transfer: meta.transfer, offset});
      if (!chunk?.ok || typeof chunk.data !== 'string') return null;
      const bytes = decodeBase64(chunk.data);
      const next = Number(chunk.next);
      if (!Number.isFinite(next) || next <= offset || next > total || bytes.length !== next - offset) return null;
      parts.push(bytes); offset = next;
      if (chunk.done && offset !== total) return null;
    }
    return new File(parts, meta.filename, {type: 'text/markdown;charset=utf-8', lastModified: Date.now()});
  }

  function filenameVisible(name) {
    for (const node of document.querySelectorAll('button,[role="button"],[aria-label],span,div')) {
      const text = (node.textContent || '').trim();
      if (text === name || node.getAttribute?.('aria-label')?.includes(name)) return true;
    }
    return false;
  }

  async function waitForFilename(name, timeoutMs = 2200) {
    const end = performance.now() + timeoutMs;
    while (performance.now() < end) {
      if (filenameVisible(name)) return true;
      await sleep(120);
    }
    return filenameVisible(name);
  }

  function assignFiles(input, files) {
    try {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'files')?.set;
      if (!setter) return false;
      setter.call(input, files);
      input.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
      input.dispatchEvent(new Event('change', {bubbles: true, composed: true}));
      return true;
    } catch (_) { return false; }
  }

  async function injectIntoInput(input, file) {
    try {
      const transfer = new DataTransfer();
      transfer.items.add(file);
      if (!assignFiles(input, transfer.files)) return false;
      if (await waitForFilename(file.name)) return true;
      const empty = new DataTransfer();
      assignFiles(input, empty.files);
    } catch (_) {}
    return false;
  }

  async function injectByDrop(file) {
    const composer = findComposer();
    if (!composer) return false;
    const target = composer.closest('form') || composer;
    try {
      const transfer = new DataTransfer();
      transfer.items.add(file);
      for (const type of ['dragenter', 'dragover', 'drop']) {
        target.dispatchEvent(new DragEvent(type, {bubbles: true, cancelable: true, composed: true, dataTransfer: transfer}));
      }
      return await waitForFilename(file.name);
    } catch (_) { return false; }
  }

  async function injectByPaste(file) {
    const composer = findComposer();
    if (!composer) return false;
    try {
      const transfer = new DataTransfer();
      transfer.items.add(file);
      composer.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, composed: true, clipboardData: transfer
      }));
      return await waitForFilename(file.name);
    } catch (_) { return false; }
  }

  async function injectFile(file) {
    for (const input of fileInputs()) {
      if (await injectIntoInput(input, file)) return true;
    }
    if (await injectByDrop(file)) return true;
    return injectByPaste(file);
  }

  async function waitAttachmentReady(name) {
    const end = performance.now() + 12_000;
    let stableSince = 0;
    while (performance.now() < end) {
      if (filenameVisible(name)) {
        if (!stableSince) stableSince = performance.now();
        if (performance.now() - stableSince >= 600) return true;
      } else stableSince = 0;
      await sleep(200);
    }
    return false;
  }

  function attachmentPrompt(filename) {
    return `${HANDOFF_MARKER}\nThe complete previous ChatGPT conversation is attached as "${filename}". Read the entire attachment before responding. Treat it as authoritative prior conversation context: preserve its decisions, constraints, terminology, experiments, failures, and unfinished work. Continue from the latest unfinished point without asking me to repeat context already present in the attachment.`;
  }

  async function robustTextFallback(text) {
    if (typeof text !== 'string' || !text.includes(HANDOFF_MARKER) || text.length > MAX_HANDOFF_CHARS + 1024) return false;
    const requestId = crypto.randomUUID();
    return await new Promise(resolve => {
      let timeout = 0;
      const finish = ok => {
        window.removeEventListener(TEXT_FALLBACK_RESULT, onResult, false);
        clearTimeout(timeout);
        resolve(Boolean(ok));
      };
      const onResult = event => {
        if (typeof event.detail !== 'string' || event.detail.length > 1024) return;
        try {
          const result = JSON.parse(event.detail);
          if (result?.requestId === requestId) finish(result.ok === true);
        } catch (_) {}
      };
      window.addEventListener(TEXT_FALLBACK_RESULT, onResult, false);
      timeout = setTimeout(() => finish(false), 5000);
      try {
        window.dispatchEvent(new CustomEvent(TEXT_FALLBACK_REQUEST, {
          detail: JSON.stringify({requestId, text})
        }));
      } catch (_) { finish(false); }
    });
  }

  function schedulePendingRequest(delay = 500) {
    clearTimeout(pendingRequestTimer);
    if (loadedSourceId || visibleMessages.length || pendingAttempts >= 40) return;
    pendingRequestTimer = setTimeout(() => { void requestPending(); }, delay);
  }

  async function requestPending() {
    if (loadedSourceId || visibleMessages.length) return;
    const composer = findComposer();
    if (!composer || composerValue(composer).trim()) return schedulePendingRequest(500);
    pendingAttempts++;
    const response = await send({ event: 'vault-pending-request' });
    const sourceId = typeof response?.sourceId === 'string' ? response.sourceId : '';
    if (!response?.pending || !sourceId) return schedulePendingRequest(650);

    // Primary path: attach the complete native Vault transcript. The live ChatGPT composer currently
    // exposes separate generic-file, photos and camera inputs, so only a generic input may receive
    // the Markdown transcript. Drag/drop and paste handlers are bounded fallbacks if that DOM moves.
    let transfer = '';
    try {
      const meta = await send({event: 'vault-pending-transcript-begin', sourceId});
      transfer = typeof meta?.transfer === 'string' ? meta.transfer : '';
      const file = await readTranscript(meta);
      if (file && await injectFile(file) && await waitAttachmentReady(file.name) &&
          await setComposerText(composer, attachmentPrompt(file.name))) {
        loadedSourceId = sourceId;
        if (transfer) await send({event: 'vault-transcript-complete', transfer});
        await send({ event: 'vault-handoff-loaded', sourceId: loadedSourceId, complete: true,
          attached: true, filename: file.name });
        return;
      }
    } catch (_) {}
    if (transfer) await send({event: 'vault-transcript-cancel', transfer});

    // Emergency compatibility fallback: vault.js explicitly invokes the robust ProseMirror helper
    // only after attachment fails. The helper never requests pending state itself, so it cannot race
    // this attachment-first path.
    if (typeof response.text !== 'string' || !response.text.includes(HANDOFF_MARKER)) {
      return schedulePendingRequest(650);
    }
    let inserted = await robustTextFallback(response.text);
    if (!inserted && !composerValue(composer).trim()) inserted = await setComposerText(composer, response.text);
    if (inserted) {
      loadedSourceId = sourceId;
      await send({ event: 'vault-handoff-loaded', sourceId: loadedSourceId, complete: Boolean(response.complete), attached: false });
    } else schedulePendingRequest(650);
  }

  function pendingWasSubmitted() {
    return loadedSourceId && visibleMessages.some(article => messageRole(article) === 'user' && messageText(article).includes(HANDOFF_MARKER));
  }

  function assistantIsStreaming() { return Boolean(document.querySelector(STOP)); }

  function endsWithAgentGo(article, text) {
    const lines = text.replace(/\r/gu, '').trimEnd().split('\n');
    if (lines.at(-1)?.trim() !== 'Go') return false;
    const source = article.querySelector('[data-message-author-role]') || article;
    const blocks = [...source.querySelectorAll('p,li,blockquote,pre,h1,h2,h3,h4,h5,h6')].filter(node => (node.innerText || node.textContent || '').trim());
    const last = blocks.at(-1);
    if (last?.closest('pre,code')) return false;
    return !last || (last.innerText || last.textContent || '').trim() === 'Go';
  }

  function scanAgentGo() {
    if (assistantIsStreaming()) { goEligible = true; clearTimeout(goArmTimer); return; }
    if (!goEligible) return;
    const assistants = visibleMessages.filter(article => messageRole(article) === 'assistant');
    const article = assistants.at(-1);
    if (!article) { goEligible = false; return; }
    const text = messageText(article);
    if (!endsWithAgentGo(article, text)) { goEligible = false; return; }
    const fingerprint = `${routeChatId()}|${text.length}|${text.slice(-180)}`;
    if (fingerprint === goLoadedFingerprint) { goEligible = false; return; }
    clearTimeout(goArmTimer);
    goArmTimer = setTimeout(async () => {
      if (assistantIsStreaming()) return scanAgentGo();
      const current = findMessages().filter(node => messageRole(node) === 'assistant').at(-1);
      const currentText = current ? messageText(current) : '';
      if (!current || !endsWithAgentGo(current, currentText)) { goEligible = false; return; }
      const composer = findComposer();
      if (!composer || composerValue(composer).trim()) { goEligible = false; return; }
      if (await setComposerText(composer, 'go')) {
        goLoadedFingerprint = fingerprint;
        await send({ event: 'vault-agent-go-loaded' });
      }
      goEligible = false;
    }, 1400);
  }

  function scheduleRefresh() {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(refresh, 120);
  }

  function refresh() {
    const previousLocation = lastLocation;
    const moved = location.href !== lastLocation;
    lastLocation = location.href;
    visibleMessages = findMessages();

    if (moved && /\/c\//u.test(new URL(previousLocation).pathname) && !/\/c\//u.test(location.pathname) && activeSnapshot) {
      void secureAndStage(activeSnapshot);
    }
    const snapshot = makeSnapshot();
    if (snapshot && (!activeSnapshot || activeSnapshot.firstSignature === snapshot.firstSignature || !activeSnapshot.firstSignature)) {
      activeSnapshot = snapshot;
      scheduleSnapshotSave();
    } else if (snapshot) {
      activeSnapshot = snapshot;
      scheduleSnapshotSave();
    }

    if (pendingWasSubmitted()) {
      const consumed = loadedSourceId; loadedSourceId = '';
      void send({ event: 'vault-pending-consumed', sourceId: consumed });
    }
    if (!visibleMessages.length && !loadedSourceId) schedulePendingRequest();
    scanAgentGo();
  }

  document.addEventListener('click', event => {
    if (activeSnapshot && isNewChatControl(event.target)) void secureAndStage(activeSnapshot);
  }, true);
  window.addEventListener('popstate', scheduleRefresh, { passive: true });
  window.addEventListener('pagehide', () => {
    clearTimeout(refreshTimer); clearTimeout(saveTimer); clearTimeout(pendingRequestTimer); clearTimeout(goArmTimer);
  });
  const observer = new MutationObserver(scheduleRefresh);
  observer.observe(document.documentElement, { childList: true, characterData: true, subtree: true });
  refresh();
  // Keep route-only SPA transitions observable without faking page lifecycle/visibility.
  setInterval(() => { if (location.href !== lastLocation) scheduleRefresh(); }, 500);
})();