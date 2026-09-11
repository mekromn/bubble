/* Bubble Google Voice dark-mode cleanup.
 * Exact voice.google.com top frame only. This pass fixes isolated bright Material controls/surfaces
 * that can remain after Voice otherwise renders in dark mode. It never recolors media, avatars,
 * message content, or already-dark surfaces.
 */
(() => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://voice.google.com') return;

  const ROOT = 'bubble-voice-dark-fix';
  const CONTROL = 'bubble-voice-bright-control';
  const SURFACE = 'bubble-voice-bright-surface';
  const INPUT = 'bubble-voice-bright-input';
  const STYLE_ID = 'bubble-voice-dark-fix-style';
  let timer = 0;

  function parseColor(value) {
    const match = String(value || '').match(/rgba?\(\s*(\d+(?:\.\d+)?)\D+(\d+(?:\.\d+)?)\D+(\d+(?:\.\d+)?)(?:\D+(\d*(?:\.\d+)?))?\s*\)/iu);
    if (!match) return null;
    const alpha = match[4] === undefined || match[4] === '' ? 1 : Number(match[4]);
    if (!Number.isFinite(alpha) || alpha < 0.18) return null;
    return [Number(match[1]), Number(match[2]), Number(match[3])];
  }

  function luma(color) {
    return color ? (0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]) / 255 : null;
  }

  function backgroundLuma(node) {
    if (!(node instanceof Element)) return null;
    try { return luma(parseColor(getComputedStyle(node).backgroundColor)); }
    catch (_) { return null; }
  }

  function visible(node) {
    if (!(node instanceof Element) || !node.isConnected) return false;
    const rect = node.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1 || rect.bottom < 0 || rect.right < 0 || rect.top > innerHeight || rect.left > innerWidth) return false;
    const style = getComputedStyle(node);
    return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
  }

  function hasMedia(node) {
    if (!(node instanceof Element)) return false;
    if (node.matches('img,picture,video,canvas,iframe,[role="img"]')) return true;
    if (node.querySelector('img,picture,video,canvas,iframe,[role="img"]')) return true;
    try {
      const bg = getComputedStyle(node).backgroundImage;
      return !!bg && bg !== 'none';
    } catch (_) { return false; }
  }

  function pageIsDark() {
    const root = document.documentElement;
    if (!root) return false;
    if (root.classList.contains('bubble-force-light')) return false;
    if (root.classList.contains('bubble-force-dark')) return true;
    const values = [];
    for (const node of [document.body, document.querySelector('main'), document.querySelector('[role="main"]')]) {
      const value = backgroundLuma(node);
      if (value !== null) values.push(value);
    }
    if (values.some(value => value < 0.42)) return true;
    try { return matchMedia('(prefers-color-scheme: dark)').matches; }
    catch (_) { return false; }
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      html.${ROOT} { color-scheme: dark !important; }
      html.${ROOT} .${CONTROL} {
        background-color: #303134 !important;
        border-color: #3c4043 !important;
        color: #e8eaed !important;
        box-shadow: none !important;
      }
      html.${ROOT} .${SURFACE} {
        background-color: #202124 !important;
        border-color: #3c4043 !important;
        color: #e8eaed !important;
        box-shadow: none !important;
      }
      html.${ROOT} .${SURFACE} :is(button,[role="button"],a,[role="menuitem"],[role="option"],span,div,p) {
        color: inherit !important;
      }
      html.${ROOT} .${INPUT} {
        background-color: #111315 !important;
        border-color: #3c4043 !important;
        color: #e8eaed !important;
        caret-color: #e8eaed !important;
        box-shadow: none !important;
      }
      html.${ROOT} .${INPUT}::placeholder { color: #9aa0a6 !important; opacity: 1 !important; }
    `;
    (document.head || document.documentElement).append(style);
  }

  function clear() {
    document.documentElement?.classList.remove(ROOT);
    document.querySelectorAll(`.${CONTROL}`).forEach(node => node.classList.remove(CONTROL));
    document.querySelectorAll(`.${SURFACE}`).forEach(node => node.classList.remove(SURFACE));
    document.querySelectorAll(`.${INPUT}`).forEach(node => node.classList.remove(INPUT));
  }

  function tagBrightControls() {
    const topLimit = Math.min(320, innerHeight * 0.34);
    const selector = 'button,[role="button"],a[role="button"],[role="switch"],[role="checkbox"]';
    for (const node of document.querySelectorAll(selector)) {
      if (!visible(node) || hasMedia(node)) { node.classList.remove(CONTROL); continue; }
      const rect = node.getBoundingClientRect();
      const geometry = rect.top < topLimit && rect.width >= 20 && rect.width <= 96 && rect.height >= 20 && rect.height <= 96;
      const value = backgroundLuma(node);
      node.classList.toggle(CONTROL, geometry && value !== null && value > 0.62);
    }
  }

  function tagBrightSurfaces() {
    const selector = '[role="dialog"],[role="menu"],[role="listbox"],[role="tooltip"],[role="alertdialog"]';
    for (const node of document.querySelectorAll(selector)) {
      if (!visible(node) || hasMedia(node)) { node.classList.remove(SURFACE); continue; }
      const value = backgroundLuma(node);
      node.classList.toggle(SURFACE, value !== null && value > 0.62);
    }
  }

  function tagBrightInputs() {
    const selector = 'input,textarea,[contenteditable="true"],[role="textbox"]';
    for (const node of document.querySelectorAll(selector)) {
      if (!visible(node)) { node.classList.remove(INPUT); continue; }
      const value = backgroundLuma(node);
      node.classList.toggle(INPUT, value !== null && value > 0.62);
    }
  }

  function apply() {
    timer = 0;
    if (!pageIsDark()) { clear(); return; }
    ensureStyle();
    document.documentElement.classList.add(ROOT);
    tagBrightControls();
    tagBrightSurfaces();
    tagBrightInputs();
  }

  function schedule(delay = 80) {
    clearTimeout(timer);
    timer = setTimeout(apply, delay);
  }

  const observer = new MutationObserver(() => schedule());
  observer.observe(document.documentElement, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ['class', 'style', 'role', 'aria-checked', 'aria-expanded', 'aria-selected']
  });

  document.addEventListener('visibilitychange', () => { if (!document.hidden) schedule(20); }, {passive: true});
  window.addEventListener('pageshow', () => schedule(20), {passive: true});
  window.addEventListener('focus', () => schedule(20), {passive: true});
  try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => schedule(0)); } catch (_) {}

  for (const delay of [0, 100, 350, 900, 1800, 3500]) setTimeout(apply, delay);
})();
