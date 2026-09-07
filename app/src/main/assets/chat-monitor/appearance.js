(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const STYLE_ID = 'bubble-page-appearance';
  const ROOT = document.documentElement;
  let requestedMode = 'default';
  let classifyTimer = 0;

  const parseColor = value => {
    const m = String(value || '').match(/rgba?\(\s*(\d+(?:\.\d+)?)\D+(\d+(?:\.\d+)?)\D+(\d+(?:\.\d+)?)(?:\D+(\d*(?:\.\d+)?))?\s*\)/i);
    if (!m) return null;
    const alpha = m[4] === undefined || m[4] === '' ? 1 : Number(m[4]);
    if (!Number.isFinite(alpha) || alpha < 0.18) return null;
    return [Number(m[1]), Number(m[2]), Number(m[3])];
  };
  const luma = color => color ? (0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]) / 255 : null;
  const nodeLuma = node => {
    if (!(node instanceof Element)) return null;
    try { return luma(parseColor(getComputedStyle(node).backgroundColor)); } catch (_) { return null; }
  };
  const ancestorLuma = node => {
    let current = node;
    for (let i = 0; current && i < 8; i++, current = current.parentElement) {
      const value = nodeLuma(current);
      if (value !== null) return value;
    }
    return null;
  };
  const pageLuma = () => {
    for (const node of [document.body, ROOT]) {
      const value = nodeLuma(node);
      if (value !== null) return value;
    }
    const w = Math.max(1, innerWidth), h = Math.max(1, innerHeight);
    const samples = [];
    for (const [x, y] of [[.5,.5],[.18,.22],[.82,.22],[.18,.72],[.82,.72]]) {
      const value = ancestorLuma(document.elementFromPoint(w * x, h * y));
      if (value !== null) samples.push(value);
    }
    if (!samples.length) return null;
    samples.sort((a, b) => a - b);
    return samples[Math.floor(samples.length / 2)];
  };

  const ensureStyle = () => {
    let style = document.getElementById(STYLE_ID);
    if (style) return style;
    style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      html.bubble-force-dark { color-scheme: dark !important; }
      html.bubble-force-light { color-scheme: light !important; }
      html.bubble-force-dark.bubble-needs-invert,
      html.bubble-force-light.bubble-needs-invert {
        filter: invert(1) hue-rotate(180deg) !important;
      }
      html.bubble-force-dark.bubble-needs-invert :is(img,picture,video,canvas,iframe,svg),
      html.bubble-force-light.bubble-needs-invert :is(img,picture,video,canvas,iframe,svg) {
        filter: invert(1) hue-rotate(180deg) !important;
      }
    `;
    (document.head || ROOT).appendChild(style);
    return style;
  };

  const classify = () => {
    if (requestedMode !== 'dark' && requestedMode !== 'light') return;
    const value = pageLuma();
    if (value === null) return;
    const nativeDark = value < 0.48;
    const needsInvert = requestedMode === 'dark' ? !nativeDark : nativeDark;
    ROOT.classList.toggle('bubble-needs-invert', needsInvert);
    ROOT.style.setProperty('background-color', requestedMode === 'dark' ? '#000' : '#fff', 'important');
  };

  const install = mode => {
    requestedMode = mode === 'dark' || mode === 'light' ? mode : 'default';
    ROOT.classList.remove('bubble-force-dark', 'bubble-force-light', 'bubble-needs-invert');
    ROOT.style.removeProperty('background-color');
    if (requestedMode === 'default') {
      document.getElementById(STYLE_ID)?.remove();
      return;
    }
    ensureStyle();
    ROOT.classList.add(requestedMode === 'dark' ? 'bubble-force-dark' : 'bubble-force-light');
    classify();
    clearTimeout(classifyTimer);
    const rerun = () => { classify(); classifyTimer = setTimeout(classify, 700); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', rerun, {once: true});
    window.addEventListener('load', rerun, {once: true});
    setTimeout(classify, 80);
    setTimeout(classify, 300);
    setTimeout(classify, 1200);
  };

  const requestMode = attempt => {
    try {
      browser.runtime.sendNativeMessage('bubbleAppearance', {event: 'appearance'})
        .then(response => install(response?.mode || 'default'))
        .catch(() => { if (attempt < 4) setTimeout(() => requestMode(attempt + 1), 80 * (attempt + 1)); });
    } catch (_) {
      if (attempt < 4) setTimeout(() => requestMode(attempt + 1), 80 * (attempt + 1));
    }
  };
  requestMode(0);
})();
