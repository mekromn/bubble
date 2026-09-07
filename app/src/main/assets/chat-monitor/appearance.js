(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const STYLE_ID = 'bubble-page-appearance';
  const FILTER = 'invert(1) hue-rotate(180deg)';
  let requestedMode = 'default';
  let classifyTimer = 0;
  let observer = null;
  let observerStop = 0;
  const root = () => document.documentElement;

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
    // Never sample <html>: Bubble itself may paint that element black/white as a safe backing
    // color, which previously fooled later passes into thinking a light Google Voice page was
    // already dark and immediately removed the inversion.
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
      html.bubble-force-dark { color-scheme: dark !important; }
      html.bubble-force-light { color-scheme: light !important; }
      html.bubble-force-dark.bubble-needs-invert { background: #fff !important; }
      html.bubble-force-light.bubble-needs-invert { background: #000 !important; }
      html.bubble-force-dark:not(.bubble-needs-invert) { background: #000 !important; }
      html.bubble-force-light:not(.bubble-needs-invert) { background: #fff !important; }
      html.bubble-needs-invert :is(img,picture,video,canvas,iframe,svg) {
        filter: ${FILTER} !important;
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
    // Inline !important beats application CSS that places its own filter on <html>. Avoid writing
    // the same value repeatedly so our MutationObserver cannot create a self-sustaining loop.
    const current = host.style.getPropertyValue('filter').trim();
    if (needsInvert) {
      if (current !== FILTER || host.style.getPropertyPriority('filter') !== 'important')
        host.style.setProperty('filter', FILTER, 'important');
    } else if (current) host.style.removeProperty('filter');
  };

  const classify = () => {
    const host = root();
    if (!host || (requestedMode !== 'dark' && requestedMode !== 'light')) return;
    const value = pageLuma();
    if (value === null) {
      // Force-dark must still visibly do something on SPAs whose first paint is all transparent
      // containers (Google Voice is a real example). Reclassification corrects this as soon as a
      // concrete surface appears. Force-light defaults to no inversion for the same reason.
      setInvert(requestedMode === 'dark');
      return;
    }
    const nativeDark = value < 0.48;
    setInvert(requestedMode === 'dark' ? !nativeDark : nativeDark);
  };

  const scheduleClassify = delay => {
    clearTimeout(classifyTimer);
    classifyTimer = setTimeout(classify, delay);
  };

  const watchStartupPaint = () => {
    observer?.disconnect(); observer = null;
    observerStop = performance.now() + 12000;
    const host = root();
    if (!host) return;
    observer = new MutationObserver(() => {
      if (performance.now() > observerStop) { observer?.disconnect(); observer = null; return; }
      scheduleClassify(120);
    });
    observer.observe(host, {subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style']});
    setTimeout(() => { observer?.disconnect(); observer = null; }, 12500);
  };

  const install = (mode, domAttempt = 0) => {
    const host = root();
    if (!host) {
      if (domAttempt < 30) setTimeout(() => install(mode, domAttempt + 1), 16);
      return;
    }
    requestedMode = mode === 'dark' || mode === 'light' ? mode : 'default';
    observer?.disconnect(); observer = null;
    host.classList.remove('bubble-force-dark', 'bubble-force-light', 'bubble-needs-invert');
    host.style.removeProperty('filter');
    if (requestedMode === 'default') {
      document.getElementById(STYLE_ID)?.remove();
      return;
    }
    ensureStyle();
    host.classList.add(requestedMode === 'dark' ? 'bubble-force-dark' : 'bubble-force-light');
    classify();
    watchStartupPaint();
    const rerun = () => { classify(); scheduleClassify(500); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', rerun, {once: true});
    window.addEventListener('load', rerun, {once: true});
    window.addEventListener('pageshow', rerun);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) scheduleClassify(80); });
    for (const delay of [60, 180, 450, 900, 1800, 3500, 6500, 10000]) setTimeout(classify, delay);
  };

  const requestMode = attempt => {
    try {
      browser.runtime.sendNativeMessage('bubbleAppearance', {event: 'appearance'})
        .then(response => install(response?.mode || 'default'))
        .catch(() => { if (attempt < 6) setTimeout(() => requestMode(attempt + 1), 75 * (attempt + 1)); });
    } catch (_) {
      if (attempt < 6) setTimeout(() => requestMode(attempt + 1), 75 * (attempt + 1));
    }
  };
  requestMode(0);
})();
