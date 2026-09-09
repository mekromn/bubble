/* Bubble Continuity Vault: exact ChatGPT origin, top frame, local native storage only.
 * Reads rendered user/assistant turns because the user explicitly enabled a local chat archive.
 * No cookies, bearer tokens, account identifiers, prompts-in-progress, or network requests are read.
 * Continuity text may be loaded into an empty composer after an explicit New Chat action, but this
 * script NEVER clicks Send or fabricates a trusted user gesture. */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const MAX_HANDOFF_CHARS = 70000; // Kept in sync with native ChatVault for release-contract tests.
  const CHUNK_CHARS = 48000;
  const HANDOFF_MARKER = '[CONTINUITY HANDOFF — PREVIOUS CHAT]';
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
    try { return await browser.runtime.sendNativeMessage('bubble', message); }
    catch (_) { return null; }
  };

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
      await new Promise(resolve => setTimeout(resolve, 100));
      return composerValue(input).includes(text.slice(0, Math.min(64, text.length)));
    }
    try {
      selectComposerContents(input);
      document.execCommand('insertText', false, text);
    } catch (_) {}
    await new Promise(resolve => setTimeout(resolve, 100));
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
    await new Promise(resolve => setTimeout(resolve, 120));
    return composerValue(input).includes(text.slice(0, Math.min(64, text.length)));
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
    if (!response?.pending || typeof response.text !== 'string' || !response.text.includes(HANDOFF_MARKER)) {
      return schedulePendingRequest(650);
    }
    if (await setComposerText(composer, response.text)) {
      loadedSourceId = String(response.sourceId || '');
      await send({ event: 'vault-handoff-loaded', sourceId: loadedSourceId, complete: Boolean(response.complete) });
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
