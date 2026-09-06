/* Main-world half of generated Blob download ownership.
 *
 * Page-created blob: URLs live in the document/Gecko runtime. The isolated WebExtension
 * world can use native messaging, but Gecko's compartment boundary is not a reliable place
 * to observe programmatic <a download>.click() activation. This tiny MAIN-world hook owns
 * only top-level blob: download anchors and forwards metadata as a JSON string in a local
 * CustomEvent. It never reads Blob bytes, cookies, storage, form data, or page text. */
(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const EVENT = '__bubble_blob_download_v1__';
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

    // GeckoView does not consistently surface page-generated Blob downloads through
    // ContentDelegate.onExternalResponse. Prevent only that default activation and hand the
    // still-live Blob URL to the isolated extension half before the page can revoke it.
    event.preventDefault();
    event.stopImmediatePropagation();
    const payload = JSON.stringify({
      uri,
      filename: (anchor.getAttribute('download') || 'download').slice(0, 512),
      mime: (anchor.getAttribute('type') || '').slice(0, 128)
    });
    window.dispatchEvent(new CustomEvent(EVENT, {detail: payload}));
  }, true);
})();
