/* Bubble 146 pinch benchmark bridge.
 * Dormant outside an explicitly armed benchmark. It deliberately installs NO touch/pointer listeners,
 * so it cannot make APZ wait on page JavaScript or change preventDefault behavior.
 */
(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  let port = null;
  let reconnect = 0;
  let active = false;
  let run = '';
  let raf = 0;
  let batch = [];

  function flush() {
    if (!port || !batch.length) return;
    const samples = batch;
    batch = [];
    try { port.postMessage({event: 'samples', run, samples}); } catch (_) {}
  }

  function frame(ts) {
    raf = 0;
    if (!active) return;
    const vv = window.visualViewport;
    if (vv) {
      const left = Number.isFinite(vv.pageLeft) ? vv.pageLeft : (window.scrollX + vv.offsetLeft);
      const top = Number.isFinite(vv.pageTop) ? vv.pageTop : (window.scrollY + vv.offsetTop);
      batch.push([ts, vv.scale, left, top, vv.width, vv.height, window.devicePixelRatio || 1]);
      if (batch.length >= 16) flush();
    }
    raf = requestAnimationFrame(frame);
  }

  function stopSampling() {
    active = false;
    if (raf) cancelAnimationFrame(raf);
    raf = 0;
    flush();
  }

  function startSampling(nextRun) {
    stopSampling();
    run = String(nextRun || '').slice(0, 128);
    batch = [];
    active = !!run;
    if (active) raf = requestAnimationFrame(frame);
  }

  function scheduleReconnect() {
    if (reconnect) return;
    reconnect = setTimeout(() => { reconnect = 0; connect(); }, 1000);
  }

  function connect() {
    if (port) return;
    try {
      port = browser.runtime.connectNative('bubblePinchBench');
      port.onMessage.addListener(message => {
        if (!message || typeof message !== 'object') return;
        if (message.cmd === 'arm') {
          startSampling(message.run);
        } else if (message.cmd === 'disarm') {
          if (!message.run || String(message.run) === run) stopSampling();
        } else if (message.cmd === 'sync') {
          try {
            port.postMessage({
              event: 'sync',
              run: String(message.run || '').slice(0, 128),
              seq: Number(message.seq) | 0,
              t0: String(message.t0 || ''),
              jsMs: performance.now()
            });
          } catch (_) {}
        }
      });
      port.onDisconnect.addListener(() => {
        stopSampling();
        port = null;
        scheduleReconnect();
      });
    } catch (_) {
      port = null;
      scheduleReconnect();
    }
  }

  addEventListener('pagehide', () => stopSampling(), {capture: true});
  connect();
})();
