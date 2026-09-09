/* Bubble agent transcript command.
 *
 * Exact completed assistant messages GET_CHAT_TRANSCIPT (legacy spelling requested by the user) or
 * GET_CHAT_TRANSCRIPT ask Bubble to full-sync, upload, and submit this same conversation's native
 * Continuity Vault as a Markdown attachment. This runs independently in every resident ChatGPT
 * GeckoSession; the tab does not need to be selected or visible.
 *
 * Native provides transcript bytes in bounded chunks. No cookie/token/account data is read here.
 * The automation refuses to overwrite a non-empty draft or existing pending file selection. A
 * completed command fingerprint is persisted natively so refresh cannot resend the same turn.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const COMMANDS = new Set(['GET_CHAT_TRANSCIPT', 'GET_CHAT_TRANSCRIPT']);
  const STOP = 'button[data-testid="stop-button"],button[aria-label*="Stop generating" i],button[aria-label*="Stop streaming" i],button[aria-label="Stop" i]';
  const SEND = 'button[data-testid="send-button"],button[aria-label*="Send prompt" i],button[aria-label="Send" i],form button[type="submit"]';
  const CAPTION = 'Current chat transcript attached.';
  const MAX_EXPORT_BYTES = 256 * 1024 * 1024;
  let scanTimer = 0;
  let processing = false;
  let retryTimer = 0;
  let localCompleted = '';
  let attempts = 0;

  const native = async message => {
    try { return await browser.runtime.sendNativeMessage('bubbleVault', message); }
    catch (_) { return null; }
  };
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const routeChatId = () => location.pathname.match(/\/c\/([^/?#]+)/u)?.[1] || '';
  const streaming = () => Boolean(document.querySelector(STOP));

  function assistantNodes() {
    let nodes = [...document.querySelectorAll('main article[data-testid^="conversation-turn-"]')]
      .filter(node => node.querySelector('[data-message-author-role="assistant"]'));
    if (!nodes.length) {
      nodes = [...document.querySelectorAll('main [data-message-author-role="assistant"]')]
        .map(node => node.closest('article') || node).filter(Boolean);
    }
    return nodes;
  }

  function articleText(article) {
    const source = article?.querySelector?.('[data-message-author-role="assistant"]') || article;
    return (source?.innerText || source?.textContent || '').replace(/\u00a0/gu, ' ').trim();
  }

  function commandOf(article) {
    const text = articleText(article);
    if (!COMMANDS.has(text)) return '';
    const source = article?.querySelector?.('[data-message-author-role="assistant"]') || article;
    if ([...source.querySelectorAll?.('pre,code') || []].some(node =>
      (node.innerText || node.textContent || '').trim() === text)) return '';
    return text;
  }

  function fingerprint(article, command) {
    const turn = article?.closest?.('[data-testid^="conversation-turn-"],article') || article;
    const stable = turn?.getAttribute?.('data-testid') ||
      turn?.getAttribute?.('data-message-id') ||
      turn?.id || `${assistantNodes().length}`;
    return `${routeChatId()}|${stable}|${command}`.slice(0, 512);
  }

  function findComposer() {
    return document.querySelector('textarea[data-testid="prompt-textarea"],textarea#prompt-textarea,#prompt-textarea[contenteditable="true"],main textarea,main [contenteditable="true"][role="textbox"]');
  }

  function composerValue(input) {
    return !input ? '' : ('value' in input ? input.value : input.innerText || input.textContent || '');
  }

  async function setComposerText(input, text) {
    if (!input || composerValue(input).trim()) return false;
    if (input instanceof HTMLTextAreaElement) {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter ? setter.call(input, text) : (input.value = text);
      input.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: text}));
      input.dispatchEvent(new Event('change', {bubbles: true}));
      await sleep(100);
      return composerValue(input).includes(text);
    }
    try {
      input.replaceChildren(document.createElement('p'));
      input.firstElementChild.append(document.createTextNode(text));
      input.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: text}));
      input.dispatchEvent(new Event('change', {bubbles: true}));
    } catch (_) { return false; }
    await sleep(120);
    return composerValue(input).includes(text);
  }

  function fileInput() {
    return [...document.querySelectorAll('input[type="file"]')]
      .find(input => !input.disabled && (!input.files || input.files.length === 0)) || null;
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
      const chunk = await native({event: 'vault-transcript-chunk', transfer: meta.transfer, offset});
      if (!chunk?.ok || typeof chunk.data !== 'string') return null;
      const bytes = decodeBase64(chunk.data);
      const next = Number(chunk.next);
      if (!Number.isFinite(next) || next <= offset || next > total || bytes.length !== next - offset) return null;
      parts.push(bytes); offset = next;
      if (chunk.done && offset !== total) return null;
    }
    return new File(parts, meta.filename, {type: 'text/markdown;charset=utf-8', lastModified: Date.now()});
  }

  async function injectFile(file) {
    let input = fileInput();
    const end = performance.now() + 15_000;
    while (!input && performance.now() < end) { await sleep(250); input = fileInput(); }
    if (!input) return false;
    try {
      const transfer = new DataTransfer();
      transfer.items.add(file);
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'files')?.set;
      setter ? setter.call(input, transfer.files) : (input.files = transfer.files);
      input.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
      input.dispatchEvent(new Event('change', {bubbles: true, composed: true}));
      return input.files?.length === 1 && input.files[0]?.name === file.name;
    } catch (_) { return false; }
  }

  function filenameVisible(name) {
    for (const node of document.querySelectorAll('button,[role="button"],[aria-label],span,div')) {
      const text = (node.textContent || '').trim();
      if (text === name || node.getAttribute?.('aria-label')?.includes(name)) return true;
    }
    return false;
  }

  function sendButton() {
    return [...document.querySelectorAll(SEND)].find(button =>
      !button.disabled && button.getAttribute('aria-disabled') !== 'true' && !button.matches(STOP)) || null;
  }

  async function waitUntilReady(filename) {
    const end = performance.now() + 120_000;
    let stableSince = 0;
    while (performance.now() < end) {
      if (!routeChatId() || streaming()) { stableSince = 0; await sleep(350); continue; }
      const ready = filenameVisible(filename) && sendButton();
      if (ready) {
        if (!stableSince) stableSince = performance.now();
        if (performance.now() - stableSince >= 1200) return true;
      } else stableSince = 0;
      await sleep(300);
    }
    return false;
  }

  async function submit() {
    const button = sendButton();
    const form = button?.closest('form');
    if (!button || !form?.requestSubmit) return false;
    try { form.requestSubmit(button); }
    catch (_) { return false; }
    const end = performance.now() + 15_000;
    while (performance.now() < end) {
      const composer = findComposer();
      if (composer && !composerValue(composer).trim()) return true;
      await sleep(200);
    }
    return false;
  }

  async function execute(article, command) {
    const fp = fingerprint(article, command);
    if (!fp || fp === localCompleted || processing) return;
    processing = true;
    let transfer = '';
    try {
      await sleep(900);
      if (streaming() || commandOf(assistantNodes().at(-1)) !== command) return;
      const composer = findComposer();
      if (!composer || composerValue(composer).trim()) return scheduleRetry();
      const existing = [...document.querySelectorAll('input[type="file"]')].some(input => input.files?.length);
      if (existing) return scheduleRetry();

      // Native first asks this exact tab to perform a complete same-origin Vault sync, then exports
      // the committed result. The command therefore never knowingly uploads a virtualized tail.
      const meta = await native({event: 'vault-transcript-begin', fingerprint: fp, sync: true});
      if (meta?.duplicate) { localCompleted = fp; return; }
      if (!meta?.available) return scheduleRetry();
      transfer = String(meta.transfer || '');
      const file = await readTranscript(meta);
      if (!file) throw new Error('transcript-bytes');
      if (!await injectFile(file)) throw new Error('file-input');
      if (!await setComposerText(composer, CAPTION)) throw new Error('composer');
      if (!await waitUntilReady(file.name)) throw new Error('upload-ready');
      if (!await submit()) throw new Error('submit');
      await native({event: 'vault-transcript-complete', transfer});
      localCompleted = fp;
      attempts = 0;
    } catch (_) {
      if (transfer) await native({event: 'vault-transcript-cancel', transfer});
      scheduleRetry();
    } finally {
      processing = false;
    }
  }

  function scheduleRetry() {
    attempts++;
    if (attempts > 20) return;
    clearTimeout(retryTimer);
    retryTimer = setTimeout(scan, Math.min(5000, 700 + attempts * 250));
  }

  function scan() {
    scanTimer = 0;
    if (processing || streaming()) return;
    const article = assistantNodes().at(-1);
    if (!article) return;
    const command = commandOf(article);
    if (!command) { attempts = 0; return; }
    void execute(article, command);
  }

  function queue() {
    if (!scanTimer) scanTimer = setTimeout(scan, 220);
  }

  const observer = new MutationObserver(queue);
  observer.observe(document.documentElement, {childList: true, characterData: true, subtree: true,
    attributes: true, attributeFilter: ['disabled', 'aria-disabled', 'data-testid', 'aria-label']});
  window.addEventListener('pageshow', queue, {passive: true});
  window.addEventListener('pagehide', () => {
    clearTimeout(scanTimer); clearTimeout(retryTimer); scanTimer = 0; retryTimer = 0;
  });
  scan();
})();
