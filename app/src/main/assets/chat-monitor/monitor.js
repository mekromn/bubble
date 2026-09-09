/* Exact-origin, local lifecycle detection. No cookies, prompts or account IDs are read or sent.
 * DOM markers are a compatibility heuristic, not a server-side completion API. The Vault watchdog
 * receives only a bounded progress fingerprint (turn id + text length/tail), never the response text.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;
  const STOP = 'button[data-testid="stop-button"],button[aria-label="Stop generating"]';
  const STREAM = '[data-message-author-role="assistant"] .result-streaming,[data-message-author-role="assistant"][data-is-streaming="true"]';
  const ACTION = 'button[data-testid="copy-turn-action-button"],button[data-testid="good-response-turn-action-button"],button[aria-label="Copy"]';
  const FULL_SYNC = '__bubble_vault_full_sync_request_v1__';
  let run = '', busy = false, cancelled = false, progressed = false;
  let timer = 0, settling = 0, baseline = null, active = null, conversation = '', stopped = false;
  let outputSignature = '';

  const last = () => { const nodes = document.querySelectorAll('[data-message-author-role="assistant"]'); return nodes.length ? nodes[nodes.length - 1] : null; };
  const key = () => location.pathname.match(/\/c\/([^/?#]+)/)?.[1] || '';
  const generating = () => !!document.querySelector(STOP) || !!document.querySelector(STREAM);
  const emit = event => { try { browser.runtime.sendNativeMessage('bubble', {event, run}).catch(() => {}); } catch (_) {} };
  const emitVault = (event, extra = {}) => {
    try { browser.runtime.sendNativeMessage('bubbleVault', {event, run, ...extra}).catch(() => {}); } catch (_) {}
  };

  function turnKey(answer) {
    const turn = answer?.closest?.('[data-testid^="conversation-turn-"],article') || answer;
    return (turn?.getAttribute?.('data-testid') || turn?.getAttribute?.('data-message-id') || turn?.id || '').slice(0, 256);
  }

  function outputFingerprint(answer) {
    if (!answer) return '';
    const text = String(answer.innerText || answer.textContent || '').replace(/\u00a0/gu, ' ');
    if (!text) return '';
    return `${text.length}|${text.slice(-420)}`.slice(0, 512);
  }

  function reportOutput(answer) {
    const fingerprint = outputFingerprint(answer);
    if (!fingerprint || fingerprint === outputSignature) return;
    outputSignature = fingerprint;
    emitVault('vault-generation-progress', {turn: turnKey(answer), fingerprint});
  }

  const abort = () => {
    if (busy) {
      emit('aborted');
      emitVault('vault-generation-ended', {turn: turnKey(active)});
    }
    busy = false; outputSignature = '';
    clearTimeout(settling); settling = 0; baseline = last();
  };

  const settle = () => {
    settling = 0;
    if (!busy || generating()) return;
    if (cancelled) { abort(); return; }
    const answer = last();
    const turn = answer?.closest('[data-testid^="conversation-turn-"],article') || answer;
    const failed = !!turn?.querySelector('[data-testid="conversation-turn-error"],[data-testid="error-message"],[role="alert"]');
    if (failed) { abort(); return; }
    if (answer && progressed && turn?.querySelector(ACTION)) {
      reportOutput(answer);
      emit('finished');
      emitVault('vault-generation-ended', {turn: turnKey(answer)});
      busy = false; baseline = answer; active = answer; outputSignature = '';
      // A completed reply is the best time to refresh the authoritative local Vault copy.
      window.dispatchEvent(new CustomEvent(FULL_SYNC));
    }
    // Missing markers remain indeterminate. Do not claim success from a vanished stop button.
  };

  const scan = () => {
    timer = 0;
    if (stopped) return;
    const current = key();
    if (busy && conversation && current !== conversation) abort();
    if (!conversation || !busy) conversation = current;
    const answer = last();
    if (generating()) {
      clearTimeout(settling); settling = 0;
      if (!busy) {
        busy = true; cancelled = false; progressed = answer !== baseline;
        active = answer; conversation = current; outputSignature = '';
        run = crypto.randomUUID(); emit('started');
        emitVault('vault-generation-started', {turn: turnKey(answer)});
      }
      if (answer && answer !== active) { active = answer; progressed = true; }
      if (document.querySelector(STREAM)) progressed = true;
      reportOutput(answer);
    } else if (busy && !settling) {
      if (answer && answer !== baseline) progressed = true;
      reportOutput(answer);
      settling = setTimeout(settle, 900);
    } else if (!busy) baseline = answer;
  };

  const queue = records => {
    if (busy && active && records?.some(record => active.contains(record.target))) progressed = true;
    if (!timer && !stopped) timer = setTimeout(scan, 160);
  };
  const observer = new MutationObserver(queue);
  const start = () => {
    stopped = false; baseline = last(); conversation = key();
    observer.observe(document.documentElement, {childList: true, characterData: true, subtree: true,
      attributes: true, attributeFilter: ['data-testid', 'data-is-streaming', 'aria-label', 'aria-busy', 'class', 'disabled']});
    scan();
  };
  document.addEventListener('click', event => {
    const button = event.target instanceof Element ? event.target.closest('button') : null;
    if (busy && button && button === document.querySelector(STOP)) cancelled = true;
  }, true);
  window.addEventListener('pagehide', () => {
    abort(); stopped = true; observer.disconnect(); clearTimeout(timer); clearTimeout(settling); timer = 0; settling = 0;
  });
  window.addEventListener('pageshow', event => { if (event.persisted && stopped) start(); });
  start();
})();
