/* ChatGPT-only foreground compatibility, in MAIN world, before page scripts run.
 * This exposes NO native/extension API, reads NO conversation data and sends NO messages.
 * Native Gecko setActive(true) is still required: these getters alone cannot keep a process alive.
 * Element focus/blur events remain untouched so typing, validation and accessibility still work. */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://chatgpt.com') return;
  const pin = (name, descriptor) => {
    try { Object.defineProperty(document, name, {configurable: true, ...descriptor}); } catch (_) {}
  };
  pin('hidden', {get: () => false});
  pin('visibilityState', {get: () => 'visible'});
  pin('hasFocus', {value: () => true, writable: false});
  window.addEventListener('visibilitychange', event => {
    if (event.target === document) event.stopImmediatePropagation();
  }, true);
  window.addEventListener('blur', event => {
    if (event.target === window) event.stopImmediatePropagation();
  }, true);
})();
