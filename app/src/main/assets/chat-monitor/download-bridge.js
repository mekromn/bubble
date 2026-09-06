/* Generated-file download bridge. This does not read or transport Blob bytes through
 * WebExtension messaging. It forwards only the Blob URL and suggested filename; native
 * Gecko fetches the Blob from the same runtime and streams the resulting WebResponse.
 * Ordinary HTTP(S) downloads remain on ContentDelegate.onExternalResponse. */
(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const anchorFrom = event => {
    const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
    for (const item of path) if (item instanceof HTMLAnchorElement) return item;
    return event.target instanceof Element ? event.target.closest('a') : null;
  };

  document.addEventListener('click', event => {
    const anchor = anchorFrom(event);
    if (!anchor || !anchor.hasAttribute('download')) return;
    const uri = anchor.href;
    if (!uri || !uri.startsWith('blob:')) return;

    // GeckoView currently does not surface page-created Blob downloads through
    // ContentDelegate.onExternalResponse. Take ownership of this download only.
    event.preventDefault();
    event.stopImmediatePropagation();
    const filename = (anchor.getAttribute('download') || 'download').slice(0, 512);
    try {
      browser.runtime.sendNativeMessage('bubble', {
        event: 'blob-download',
        uri,
        filename,
        mime: (anchor.getAttribute('type') || '').slice(0, 128)
      }).catch(() => {});
    } catch (_) {}
  }, true);
})();
