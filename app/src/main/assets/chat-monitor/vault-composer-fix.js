/* Bubble Continuity Vault composer fallback helper (isolated world, exact ChatGPT origin).
 *
 * The primary staged-continuity owner is vault.js, which first attaches the complete previous-chat
 * Markdown transcript. This helper no longer requests pending handoffs on its own, so it cannot race
 * the attachment path. vault.js invokes it explicitly only after attachment fails and only for the
 * legacy size-aware text fallback. It never clicks or submits anything.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;

  const HANDOFF_MARKER = '[CONTINUITY HANDOFF — PREVIOUS CHAT]';
  const REQUEST = '__bubble_vault_text_fallback_request_v1__';
  const RESULT = '__bubble_vault_text_fallback_result_v1__';
  let busy = false;

  const routeChatId = () => location.pathname.match(/\/c\/([^/?#]+)/u)?.[1] || '';
  const settle = delay => new Promise(resolve => setTimeout(resolve, delay));

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

    try {
      const transfer = new DataTransfer();
      transfer.setData('text/plain', text);
      input.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, composed: true, clipboardData: transfer
      }));
      await settle(120);
      if (hasHandoff(input)) return true;
    } catch (_) {}

    try {
      focusCaret(input);
      document.execCommand('insertText', false, text);
      await settle(120);
      if (hasHandoff(input)) return true;
    } catch (_) {}

    try {
      focusCaret(input);
      input.dispatchEvent(new InputEvent('beforeinput', {
        bubbles: true, cancelable: true, composed: true, inputType: 'insertFromPaste', data: text
      }));
      await settle(100);
      if (hasHandoff(input)) return true;
    } catch (_) {}

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

  function reply(requestId, ok) {
    try {
      window.dispatchEvent(new CustomEvent(RESULT, {
        detail: JSON.stringify({requestId, ok: Boolean(ok)})
      }));
    } catch (_) {}
  }

  window.addEventListener(REQUEST, event => {
    if (busy || routeChatId() || typeof event.detail !== 'string' || event.detail.length > 80_000) return;
    let payload;
    try { payload = JSON.parse(event.detail); } catch (_) { return; }
    const requestId = typeof payload?.requestId === 'string' ? payload.requestId : '';
    const text = typeof payload?.text === 'string' ? payload.text : '';
    if (!requestId || !text.includes(HANDOFF_MARKER)) { reply(requestId, false); return; }
    const composer = findComposer();
    if (!composer || composerValue(composer).trim()) { reply(requestId, false); return; }
    busy = true;
    void insert(composer, text).then(ok => reply(requestId, ok), () => reply(requestId, false)).finally(() => { busy = false; });
  }, false);
})();
