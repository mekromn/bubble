/* Bubble Continuity Vault composer fallback (isolated world, exact ChatGPT origin).
 *
 * A staged local handoff is inserted only into an empty composer on ChatGPT's New Chat route.
 * This deliberately never clicks Send and never fabricates a trusted user gesture. It exists as a
 * robust editor-compatible fallback for ChatGPT's contenteditable/ProseMirror composer variants.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const HANDOFF_MARKER = '[CONTINUITY HANDOFF — PREVIOUS CHAT]';
  let loadedSourceId = '';
  let timer = 0;
  let busy = false;
  let attempts = 0;

  const send = async message => {
    try { return await browser.runtime.sendNativeMessage('bubbleVault', message); }
    catch (_) { return null; }
  };
  const routeChatId = () => location.pathname.match(/\/c\/([^/?#]+)/u)?.[1] || '';

  function findComposer() {
    const selectors = [
      '#prompt-textarea[contenteditable="true"]',
      'div.ProseMirror#prompt-textarea',
      'textarea[data-testid="prompt-textarea"]',
      'textarea#prompt-textarea',
      'main [contenteditable="true"][role="textbox"]',
      'main textarea'
    ];
    for (const selector of selectors) {
      const node = document.querySelector(selector);
      if (node && node.isConnected && !node.hasAttribute('disabled') && node.getAttribute('aria-disabled') !== 'true') return node;
    }
    return null;
  }

  function composerValue(input) {
    if (!input) return '';
    return ('value' in input ? input.value : input.innerText || input.textContent || '').replace(/\u00a0/gu, ' ');
  }

  function hasHandoff(input) {
    return composerValue(input).includes(HANDOFF_MARKER);
  }

  function focusCaret(input) {
    input.focus({ preventScroll: false });
    if (input instanceof HTMLTextAreaElement) {
      try { input.setSelectionRange(0, input.value.length); } catch (_) {}
      return;
    }
    try {
      const selection = getSelection();
      if (!selection) return;
      const range = document.createRange();
      range.selectNodeContents(input);
      range.collapse(false);
      selection.removeAllRanges();
      selection.addRange(range);
    } catch (_) {}
  }

  const settle = delay => new Promise(resolve => setTimeout(resolve, delay));

  async function insertTextarea(input, text) {
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
    setter ? setter.call(input, text) : (input.value = text);
    input.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(100);
    return hasHandoff(input);
  }

  async function insertContentEditable(input, text) {
    focusCaret(input);

    // ProseMirror handles paste through its editor transaction path on many ChatGPT builds. A local
    // DataTransfer carries only the handoff text; nothing is read from the system clipboard.
    try {
      const transfer = new DataTransfer();
      transfer.setData('text/plain', text);
      input.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, composed: true, clipboardData: transfer
      }));
      await settle(120);
      if (hasHandoff(input)) return true;
    } catch (_) {}

    // Gecko still supports insertText for focused contenteditable editors. This updates editor state
    // more faithfully than assigning innerHTML directly.
    try {
      focusCaret(input);
      document.execCommand('insertText', false, text);
      await settle(120);
      if (hasHandoff(input)) return true;
    } catch (_) {}

    // Some editor revisions consume beforeinput themselves. Give the editor that opportunity before
    // the final DOM-compatible fallback.
    try {
      focusCaret(input);
      input.dispatchEvent(new InputEvent('beforeinput', {
        bubbles: true, cancelable: true, composed: true, inputType: 'insertFromPaste', data: text
      }));
      await settle(100);
      if (hasHandoff(input)) return true;
    } catch (_) {}

    // Last resort: reproduce the paragraph structure ChatGPT's contenteditable expects, then emit the
    // same input/change signals a real edit would produce. Send remains entirely user-controlled.
    try {
      const fragment = document.createDocumentFragment();
      for (const line of text.replace(/\r/gu, '').split('\n')) {
        const paragraph = document.createElement('p');
        line ? paragraph.append(document.createTextNode(line)) : paragraph.append(document.createElement('br'));
        fragment.append(paragraph);
      }
      input.replaceChildren(fragment);
      input.dispatchEvent(new InputEvent('input', {
        bubbles: true, composed: true, inputType: 'insertFromPaste', data: text
      }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await settle(160);
      return hasHandoff(input);
    } catch (_) { return false; }
  }

  async function insert(input, text) {
    if (!input || composerValue(input).trim()) return false;
    focusCaret(input);
    if (input instanceof HTMLTextAreaElement) return insertTextarea(input, text);
    return insertContentEditable(input, text);
  }

  function schedule(delay = 300) {
    clearTimeout(timer);
    timer = setTimeout(() => { void tryLoad(); }, delay);
  }

  async function tryLoad() {
    if (busy || loadedSourceId || routeChatId() || attempts >= 180) return;
    const composer = findComposer();
    if (!composer || composerValue(composer).trim()) return schedule(400);
    busy = true;
    attempts++;
    try {
      const response = await send({ event: 'vault-pending-request' });
      if (!response?.pending || typeof response.text !== 'string' || !response.text.includes(HANDOFF_MARKER)) {
        return schedule(500);
      }
      // Re-check after native IO: the user may have started typing while the request was in flight.
      if (!composer.isConnected || composerValue(composer).trim()) return schedule(500);
      if (await insert(composer, response.text)) {
        loadedSourceId = String(response.sourceId || '');
        await send({
          event: 'vault-handoff-loaded', sourceId: loadedSourceId,
          complete: Boolean(response.complete), insertion: 'editor-fallback'
        });
      } else schedule(550);
    } finally { busy = false; }
  }

  function maybeConsume() {
    if (!loadedSourceId) return;
    const userNodes = document.querySelectorAll('main article [data-message-author-role="user"],main article[data-testid*="user" i]');
    for (const node of userNodes) {
      const article = node.closest?.('article') || node;
      const text = (article.innerText || article.textContent || '').replace(/\u00a0/gu, ' ');
      if (!text.includes(HANDOFF_MARKER)) continue;
      const sourceId = loadedSourceId;
      loadedSourceId = '';
      void send({ event: 'vault-pending-consumed', sourceId });
      return;
    }
  }

  const observer = new MutationObserver(() => {
    maybeConsume();
    if (!loadedSourceId && !routeChatId()) schedule(150);
  });
  observer.observe(document.documentElement, { childList: true, characterData: true, subtree: true });
  window.addEventListener('pageshow', () => schedule(100), { passive: true });
  window.addEventListener('popstate', () => schedule(150), { passive: true });
  window.addEventListener('pagehide', () => clearTimeout(timer), { passive: true });
  schedule(100);
})();
