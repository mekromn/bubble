/* Coarse, local lifecycle only. Never send prompt/answer text, cookies, account IDs or URLs. */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;
  let run = '', busy = false, cancelled = false, timer = 0, finishTimer = 0;
  let previousAnswer = null, answerAtStart = null;
  const stop = () => document.querySelector('button[data-testid="stop-button"], button[aria-label="Stop generating"]');
  const answers = () => document.querySelectorAll('[data-message-author-role="assistant"]');
  const lastAnswer = () => { const items = answers(); return items.length ? items[items.length - 1] : null; };
  const send = event => {
    try { browser.runtime.sendNativeMessage('bubble', {event, run}).catch(() => {}); } catch (_) { }
  };
  const scan = () => {
    timer = 0;
    const generating = !!stop() || !!document.querySelector('[data-message-author-role="assistant"] .result-streaming');
    if (generating) {
      clearTimeout(finishTimer); finishTimer = 0;
      if (!busy) {
        busy = true; cancelled = false; answerAtStart = previousAnswer;
        run = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
        send('started');
      }
    } else if (busy && !finishTimer) {
      // A disappearing stop button alone is not sufficient evidence of successful completion.
      finishTimer = setTimeout(() => {
        finishTimer = 0;
        if (stop()) return;
        const answer = lastAnswer();
        const actions = answer && (answer.querySelector('button[data-testid="copy-turn-action-button"],button[aria-label="Copy"]') || answer.closest('article')?.querySelector('button[data-testid="copy-turn-action-button"]'));
        if (cancelled) { send('aborted'); busy = false; }
        else if (answer && answer !== answerAtStart && actions) { send('finished'); busy = false; }
        // Otherwise remain indeterminate rather than sounding a false completion notification.
        previousAnswer = answer;
      }, 1300);
    }
    if (!busy) previousAnswer = lastAnswer();
  };
  const queue = () => { if (!timer) timer = setTimeout(scan, 250); };
  previousAnswer = lastAnswer();
  const observer = new MutationObserver(queue);
  observer.observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['data-testid', 'aria-label', 'class']});
  document.addEventListener('click', event => {
    const button = event.target instanceof Element ? event.target.closest('button') : null;
    if (busy && button && button === stop()) cancelled = true;
  }, true);
  window.addEventListener('pagehide', () => {
    if (busy) send('aborted');
    observer.disconnect(); clearTimeout(timer); clearTimeout(finishTimer);
  }, {once: true});
  scan();
})();
