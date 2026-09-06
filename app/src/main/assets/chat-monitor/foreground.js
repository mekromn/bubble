/* Workspace-wide foreground compatibility. MAIN world, document_start, matching web frames.
 * No extension/native API, timers, fake clicks, conversation access or networking.
 * Real Gecko activity is still required. This is compatibility, not undetectability.
 * Do not spoof userActivation, permissions, trusted events or hardware idle state. */
(() => {
  'use strict';
  // Google Voice deliberately receives REAL visibility/focus state. Voice uses background/hidden
  // state as part of deciding when to emit browser notifications; pretending that its page is
  // always visible can suppress exactly the message/call/voicemail alerts Bubble is trying to
  // preserve. Its GeckoSession is already kept active/high-priority natively, so it does not need
  // this compatibility shim to remain resident.
  if (location.protocol === 'https:' && location.hostname === 'voice.google.com' &&
      (location.port === '' || location.port === '443')) return;

  // about/data/blob documents are eligible only when the browser matched their web initiator.
  // Privileged browser, file and extension pages are never modified by this script.
  if (!['http:', 'https:', 'about:', 'blob:', 'data:'].includes(location.protocol)) return;
  const pin = (name, descriptor) => {
    try { Object.defineProperty(document, name, {configurable: true, ...descriptor}); } catch (_) {}
  };
  pin('hidden', {get: () => false});
  pin('visibilityState', {get: () => 'visible'});
  pin('hasFocus', {value: () => true, writable: false});
  for (const name of ['webkitHidden', 'mozHidden']) if (name in document) pin(name, {get: () => false});
  for (const name of ['webkitVisibilityState', 'mozVisibilityState']) if (name in document) pin(name, {get: () => 'visible'});
  const ignoreDocumentVisibility = event => {
    if (event.target === document) event.stopImmediatePropagation();
  };
  for (const name of ['visibilitychange', 'webkitvisibilitychange', 'mozvisibilitychange']) {
    window.addEventListener(name, ignoreDocumentVisibility, true);
  }
  window.addEventListener('blur', event => {
    if (event.target === window) event.stopImmediatePropagation();
  }, true);
  // Element focus/blur, pagehide/pageshow, unload and network events remain real. Cleanup,
  // BFCache, autosave and form validation must not be broken by a pretend immortal document.
})();
