(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const STYLE_ID = 'bubble-page-appearance';
  const ROOT = document.documentElement;
  const rgb = value => {
    const m = String(value || '').match(/rgba?\((\d+)[, ]+(\d+)[, ]+(\d+)/i);
    return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
  };
  const luma = color => color ? (0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]) / 255 : null;
  const pageLuma = () => {
    for (const node of [document.body, ROOT]) {
      if (!node) continue;
      const color = rgb(getComputedStyle(node).backgroundColor);
      const value = luma(color);
      if (value !== null) return value;
    }
    return 1;
  };

  const install = mode => {
    document.getElementById(STYLE_ID)?.remove();
    ROOT.classList.remove('bubble-force-dark', 'bubble-force-light', 'bubble-native-dark');
    if (mode !== 'dark' && mode !== 'light') return;

    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      html.bubble-force-dark { color-scheme: dark !important; }
      html.bubble-force-light { color-scheme: light !important; }
      html.bubble-force-dark:not(.bubble-native-dark),
      html.bubble-force-light.bubble-native-dark {
        filter: invert(1) hue-rotate(180deg) !important;
      }
      html.bubble-force-dark:not(.bubble-native-dark) :is(img,picture,video,canvas,iframe,svg),
      html.bubble-force-light.bubble-native-dark :is(img,picture,video,canvas,iframe,svg) {
        filter: invert(1) hue-rotate(180deg) !important;
      }
      html.bubble-force-dark:not(.bubble-native-dark) { background: #fff !important; }
      html.bubble-force-light.bubble-native-dark { background: #000 !important; }
    `;
    (document.head || ROOT).appendChild(style);
    ROOT.classList.add(mode === 'dark' ? 'bubble-force-dark' : 'bubble-force-light');

    const classify = () => {
      if (pageLuma() < 0.45) ROOT.classList.add('bubble-native-dark');
      else ROOT.classList.remove('bubble-native-dark');
    };
    classify();
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', classify, {once: true});
    window.addEventListener('load', classify, {once: true});
    setTimeout(classify, 700);
  };

  try {
    browser.runtime.sendNativeMessage('bubbleAppearance', {event: 'appearance'})
      .then(response => install(response?.mode || 'default'))
      .catch(() => {});
  } catch (_) {}
})();
