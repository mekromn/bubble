(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const STYLE_ID = 'bubble-page-appearance';
  const FILTER = 'invert(1) hue-rotate(180deg)';
  const SURFACE_CLASS = 'bubble-force-dark-surface';
  const VALID = new Set(['default', 'dark', 'amoled', 'light']);
  let requestedMode = 'default';
  let classifyTimer = 0;
  let observer = null;
  let observerStop = 0;
  let installed = false;
  let handshakeGeneration = 0;
  const root = () => document.documentElement;
  const darkLike = () => requestedMode === 'dark' || requestedMode === 'amoled';

  const parseColor = value => {
    const m = String(value || '').match(/rgba?\(\s*(\d+(?:\.\d+)?)\D+(\d+(?:\.\d+)?)\D+(\d+(?:\.\d+)?)(?:\D+(\d*(?:\.\d+)?))?\s*\)/i);
    if (!m) return null;
    const alpha = m[4] === undefined || m[4] === '' ? 1 : Number(m[4]);
    if (!Number.isFinite(alpha) || alpha < 0.18) return null;
    return [Number(m[1]), Number(m[2]), Number(m[3])];
  };
  const luma = color => color ? (0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]) / 255 : null;
  const nodeLuma = node => {
    if (!(node instanceof Element) || node === root()) return null;
    try { return luma(parseColor(getComputedStyle(node).backgroundColor)); } catch (_) { return null; }
  };
  const ancestorLuma = node => {
    let current = node;
    for (let i = 0; current && i < 10; i++, current = current.parentElement) {
      if (current === root()) break;
      const value = nodeLuma(current);
      if (value !== null) return value;
    }
    return null;
  };
  const pageLuma = () => {
    const bodyValue = nodeLuma(document.body);
    if (bodyValue !== null) return bodyValue;
    const w = Math.max(1, innerWidth), h = Math.max(1, innerHeight);
    const samples = [];
    for (const [x, y] of [[.5,.5],[.15,.18],[.85,.18],[.15,.5],[.85,.5],[.15,.82],[.85,.82]]) {
      const value = ancestorLuma(document.elementFromPoint(w * x, h * y));
      if (value !== null) samples.push(value);
    }
    if (!samples.length) return null;
    samples.sort((a, b) => a - b);
    return samples[Math.floor(samples.length / 2)];
  };

  const ensureStyle = () => {
    const host = root();
    if (!host) return null;
    let style = document.getElementById(STYLE_ID);
    if (style) return style;
    style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      html.bubble-force-dark, html.bubble-force-amoled { color-scheme: dark !important; }
      html.bubble-force-light { color-scheme: light !important; }
      html.bubble-force-dark.bubble-needs-invert,
      html.bubble-force-amoled.bubble-needs-invert { background: #fff !important; }
      html.bubble-force-amoled.bubble-needs-invert body { background: #fff !important; }
      html.bubble-force-light.bubble-needs-invert { background: #000 !important; }
      html.bubble-force-dark:not(.bubble-needs-invert) { background: #000 !important; }
      html.bubble-force-amoled:not(.bubble-needs-invert),
      html.bubble-force-amoled:not(.bubble-needs-invert) body { background: #000 !important; }
      html.bubble-force-light:not(.bubble-needs-invert) { background: #fff !important; }
      html.bubble-needs-invert :is(img,picture,video,canvas,iframe,svg) {
        filter: ${FILTER} !important;
      }

      html.bubble-force-dark .${SURFACE_CLASS} {
        background-color: #111315 !important;
        color: #e8eaed !important;
        border-color: #303134 !important;
        box-shadow: none !important;
      }
      html.bubble-force-amoled .${SURFACE_CLASS} {
        background-color: #000 !important;
        color: #e8eaed !important;
        border-color: #303134 !important;
        box-shadow: none !important;
      }
      html:is(.bubble-force-dark,.bubble-force-amoled) .${SURFACE_CLASS} :is(h1,h2,h3,h4,p,span,a,button,[role="button"],[role="link"]) {
        color: #e8eaed !important;
      }
      html.bubble-force-dark .${SURFACE_CLASS} :is(input,textarea,[contenteditable="true"]) {
        background-color: #202124 !important;
        color: #e8eaed !important;
        caret-color: #e8eaed !important;
      }
      html.bubble-force-amoled .${SURFACE_CLASS} :is(input,textarea,[contenteditable="true"]) {
        background-color: #000 !important;
        color: #e8eaed !important;
        caret-color: #e8eaed !important;
      }
      html:is(.bubble-force-dark,.bubble-force-amoled) .${SURFACE_CLASS} :is(svg,path) {
        color: #e8eaed !important;
        fill: currentColor !important;
      }
    `;
    (document.head || host).appendChild(style);
    return style;
  };

  const setInvert = needsInvert => {
    const host = root();
    if (!host) return;
    const wasInvert = host.classList.contains('bubble-needs-invert');
    if (wasInvert !== needsInvert) host.classList.toggle('bubble-needs-invert', needsInvert);
    const current = host.style.getPropertyValue('filter').trim();
    if (needsInvert) {
      if (current !== FILTER || host.style.getPropertyPriority('filter') !== 'important')
        host.style.setProperty('filter', FILTER, 'important');
    } else if (current) host.style.removeProperty('filter');
  };

  const clearRetintedSurfaces = () => {
    document.querySelectorAll(`.${SURFACE_CLASS}`).forEach(node => node.classList.remove(SURFACE_CLASS));
  };

  const retintMixedTopChrome = () => {
    if (!darkLike() || !document.body) { clearRetintedSurfaces(); return; }
    const w = Math.max(1, innerWidth), h = Math.max(1, innerHeight);
    const topLimit = Math.min(240, Math.max(96, h * .24));
    const candidates = new Set();
    document.querySelectorAll('header,[role="banner"],nav[aria-label]').forEach(node => candidates.add(node));
    for (const y of [18, 48, 82, 118, 166, 214]) {
      if (y >= topLimit) continue;
      for (const x of [w * .08, w * .28, w * .5, w * .72, w * .92]) {
        let node = document.elementFromPoint(x, y);
        for (let depth = 0; node instanceof Element && node !== document.body && depth < 7; depth++, node = node.parentElement) candidates.add(node);
      }
    }
    const keep = new Set();
    candidates.forEach(node => {
      if (!(node instanceof Element) || node.matches('img,picture,video,canvas,iframe,svg')) return;
      const rect = node.getBoundingClientRect();
      const geometryEligible = rect.bottom > 0 && rect.top < topLimit && rect.width >= w * .46 && rect.height >= 34 && rect.height <= topLimit * 1.35;
      if (!geometryEligible) return;
      if (node.classList.contains(SURFACE_CLASS)) { keep.add(node); return; }
      const value = nodeLuma(node);
      if (value !== null && value > .78) keep.add(node);
    });
    document.querySelectorAll(`.${SURFACE_CLASS}`).forEach(node => { if (!keep.has(node)) node.classList.remove(SURFACE_CLASS); });
    keep.forEach(node => node.classList.add(SURFACE_CLASS));
  };

  const classify = () => {
    const host = root();
    if (!host || (!darkLike() && requestedMode !== 'light')) return;
    const value = pageLuma();
    if (value === null) { setInvert(darkLike()); clearRetintedSurfaces(); return; }
    const nativeDark = value < 0.48;
    const needsInvert = darkLike() ? !nativeDark : nativeDark;
    setInvert(needsInvert);
    if (darkLike() && nativeDark && !needsInvert) retintMixedTopChrome();
    else clearRetintedSurfaces();
  };

  const scheduleClassify = delay => {
    clearTimeout(classifyTimer);
    classifyTimer = setTimeout(classify, delay);
  };

  const expectedClass = mode => mode === 'amoled' ? 'bubble-force-amoled' : mode === 'dark' ? 'bubble-force-dark' : mode === 'light' ? 'bubble-force-light' : '';

  const reassert = () => {
    const host = root();
    if (!host || !installed) return;
    const expected = expectedClass(requestedMode);
    const wrong = ['bubble-force-dark', 'bubble-force-amoled', 'bubble-force-light'].some(name => name !== expected && host.classList.contains(name));
    if ((expected && !host.classList.contains(expected)) || wrong || (requestedMode !== 'default' && !document.getElementById(STYLE_ID))) {
      install(requestedMode);
      return;
    }
    if (requestedMode === 'default' && (expected || document.getElementById(STYLE_ID))) install('default');
    else if (requestedMode !== 'default') scheduleClassify(0);
  };

  const watchStartupPaint = () => {
    observer?.disconnect(); observer = null;
    observerStop = performance.now() + 12000;
    const host = root();
    if (!host) return;
    observer = new MutationObserver(() => {
      if (performance.now() > observerStop) { observer?.disconnect(); observer = null; return; }
      reassert();
      scheduleClassify(120);
    });
    observer.observe(host, {subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style']});
    setTimeout(() => { observer?.disconnect(); observer = null; }, 12500);
  };

  const install = (mode, domAttempt = 0) => {
    if (!VALID.has(mode)) return false;
    const host = root();
    if (!host) {
      if (domAttempt < 30) setTimeout(() => install(mode, domAttempt + 1), 16);
      return false;
    }
    installed = true;
    requestedMode = mode;
    observer?.disconnect(); observer = null;
    clearRetintedSurfaces();
    host.classList.remove('bubble-force-dark', 'bubble-force-amoled', 'bubble-force-light', 'bubble-needs-invert');
    host.style.removeProperty('filter');
    if (requestedMode === 'default') {
      document.getElementById(STYLE_ID)?.remove();
      return true;
    }
    ensureStyle();
    host.classList.add(expectedClass(requestedMode));
    classify();
    watchStartupPaint();
    const rerun = () => { reassert(); classify(); scheduleClassify(500); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', rerun, {once: true});
    window.addEventListener('load', rerun, {once: true});
    for (const delay of [60, 180, 450, 900, 1800, 3500, 6500, 10000]) setTimeout(reassert, delay);
    return true;
  };

  const acceptMode = mode => {
    if (!VALID.has(mode)) return false;
    if (!installed || requestedMode !== mode) install(mode);
    else reassert();
    return true;
  };

  const requestMode = (attempt = 0, force = false) => {
    const generation = ++handshakeGeneration;
    let answered = false;
    let port = null;
    const accept = response => {
      if (generation !== handshakeGeneration || answered) return;
      const mode = response && typeof response.mode === 'string' ? response.mode : '';
      if (!VALID.has(mode)) return;
      answered = true;
      acceptMode(mode);
      try { port?.disconnect(); } catch (_) {}
    };

    // Use both Gecko native-messaging paths in parallel. A document-start race in one path must not
    // be able to strand a refreshed page on the website's own theme.
    try {
      port = browser.runtime.connectNative('bubbleAppearance');
      port.onMessage.addListener(accept);
    } catch (_) {}
    try {
      browser.runtime.sendNativeMessage('bubbleAppearance', {event: 'appearance'}).then(accept).catch(() => {});
    } catch (_) {}

    setTimeout(() => {
      if (generation !== handshakeGeneration) return;
      try { port?.disconnect(); } catch (_) {}
      if (!answered && attempt < 20) setTimeout(() => requestMode(attempt + 1, true), Math.min(500, 40 + attempt * 30));
      else if (answered || installed || force) reassert();
    }, 180);
  };

  // Re-verify the native source of truth after reload/page-show and every foreground return. This is
  // intentionally not gated by `installed`: the saved per-tab mode remains authoritative forever.
  window.addEventListener('pageshow', () => { reassert(); requestMode(0, true); });
  window.addEventListener('focus', () => { reassert(); requestMode(0, true); });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) { reassert(); requestMode(0, true); }
  }, {passive: true});

  requestMode(0, true);
})();
