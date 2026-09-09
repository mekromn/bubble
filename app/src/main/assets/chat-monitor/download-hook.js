/* Main-world download activation ownership.
 *
 * Page-created blob: URLs live in the document/Gecko runtime. The isolated WebExtension world can
 * use native messaging, but Gecko's compartment boundary is not a reliable place to observe
 * programmatic <a download>.click() activation. This MAIN-world hook owns those Blob activations.
 *
 * It also fixes a separate browser UX quirk: pages such as ChatGPT commonly mark GitHub release
 * assets target=_blank. Those URLs are downloads, not useful browser pages, so opening them as a
 * Gecko popup leaves an empty Bubble tab after the external response is handed to Downloads. For a
 * genuine user click on an exact, well-known GitHub download endpoint, Bubble navigates the existing
 * document instead. Gecko's onExternalResponse consumes the file and the current page remains the
 * active document; no child Bubble tab is created. Ordinary GitHub pages keep normal _blank tabs.
 *
 * This hook never reads Blob bytes, cookies, storage, form data, credentials, or page text. */
(() => {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;

  const EVENT = '__bubble_blob_download_v1__';
  const GITHUB_ASSET_HOSTS = new Set([
    'release-assets.githubusercontent.com',
    'github-releases.githubusercontent.com',
    'objects.githubusercontent.com',
    'codeload.github.com'
  ]);

  const anchorFrom = event => {
    const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
    for (const item of path) if (item instanceof HTMLAnchorElement) return item;
    return event.target instanceof Element ? event.target.closest('a') : null;
  };

  function directGitHubDownload(uri) {
    try {
      const url = new URL(uri, location.href);
      if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')) return false;
      const host = url.hostname.toLowerCase();
      if (GITHUB_ASSET_HOSTS.has(host)) return true;
      if (host !== 'github.com') return false;
      const path = url.pathname.toLowerCase();
      if (path.includes('/releases/download/')) return true;
      return path.includes('/archive/refs/') && (path.endsWith('.zip') || path.endsWith('.tar.gz') || path.endsWith('.tgz') || path.endsWith('.tar'));
    } catch (_) { return false; }
  }

  document.addEventListener('click', event => {
    const anchor = anchorFrom(event);
    if (!anchor) return;
    const uri = anchor.href;

    // A trusted _blank GitHub asset click is a download-only navigation. Keep it in the opener so
    // Gecko can deliver the original response to BrowserDownloads without creating a blank tab.
    if (event.isTrusted && anchor.target.toLowerCase() === '_blank' && directGitHubDownload(uri)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      location.assign(uri);
      return;
    }

    if (!anchor.hasAttribute('download') || !uri || !uri.startsWith('blob:')) return;

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
