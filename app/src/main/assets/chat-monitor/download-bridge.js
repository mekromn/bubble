/* Isolated-world half of generated Blob download ownership.
 *
 * download-hook.js observes only top-level blob: download anchors in the page's MAIN world
 * and emits a local metadata-only CustomEvent. This isolated half can use WebExtension native
 * messaging. Blob bytes never cross the page/extension/native messaging boundary: native Gecko
 * resolves the still-live Blob URL in the same runtime and BrowserDownloads streams the returned
 * WebResponse exactly once. Ordinary HTTP(S) downloads remain on ContentDelegate.onExternalResponse. */
(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const EVENT = '__bubble_blob_download_v1__';
  window.addEventListener(EVENT, event => {
    if (typeof event.detail !== 'string' || event.detail.length > 18000) return;
    let detail;
    try { detail = JSON.parse(event.detail); } catch (_) { return; }
    const uri = typeof detail.uri === 'string' ? detail.uri : '';
    if (!uri.startsWith('blob:') || uri.length > 16384) return;
    const filename = typeof detail.filename === 'string' ? detail.filename.slice(0, 512) : 'download';
    const mime = typeof detail.mime === 'string' ? detail.mime.slice(0, 128) : '';
    try {
      browser.runtime.sendNativeMessage('bubble', {
        event: 'blob-download', uri, filename, mime
      }).catch(() => {});
    } catch (_) {}
  }, false);
})();
