# Bubble 0.7.3 — file transfers are the release priority

The user reported the browser unusable without real ChatGPT attachments and file downloads. This update addresses native file transfer plumbing before more theme/toolbar/feature work.

## Uploads

The prior FilePrompt handler passed content:// URIs directly to Gecko. Gecko's native URI resolver relies on file paths / legacy _data for ordinary file selection; document and cloud providers can return no such path or a scoped-storage-inaccessible path. The new handoff opens the actual user-granted ContentResolver stream off the main thread, copies unmodified bytes into app-private no-backup staging, and confirms Gecko's original prompt with readable local file URIs. There are no broad storage permissions, automatic file enumeration, whole-file byte arrays or JavaScript binary bridges. Original display names and extensions are retained subject to path/control-character safety; duplicate names occupy distinct private directories.

A request belongs to its original tab/GeckoSession. Changing the selected tab does not redirect the files to another account. Page navigation/renderer loss/tab closing cancel pending selection. Successful staged files stay readable while their session can use them and are cleaned at tab close/renderer loss or next process initialization. Do not delete them immediately after confirmation: the webpage may not have read/uploaded them yet. App-private staging temporarily needs storage; provider/read/no-space failures are visible and never counted as successful attachments. Nothing auto-submits a ChatGPT prompt.

The Android picker is displayed above the floating browser: the exact floating window is temporarily invisible and non-focusable, then restored without reopening fullscreen. A process-owned request survives picker Activity recreation. Home handling recognizes file UI, so launching a picker does not create a competing bubble. The photo/document/cloud-provider choices are Android's normal file chooser, not a web fake picker. Live Google Drive or account-specific provider behavior still requires device verification.

**Bubble deliberately exposes every Android-openable file type in the attachment picker (`*/*`).** Web `<input accept>` / Gecko MIME hints no longer hide APKs, ZIPs, archives, source files or other user-selected documents. This only controls chooser visibility: the file's real bytes/name/type are preserved, and ChatGPT/the destination site can still reject formats it does not support after selection.

## Downloads

ContentDelegate.onExternalResponse consumes the ORIGINAL authenticated Gecko WebResponse/InputStream. Never send the link to Android DownloadManager or independently refetch it with a default cookie jar: that can lose a profile-specific login, POST payload, one-time signed URL or generated Blob. Existing profile and URL/TLS policy boundaries are unchanged. HTTP response downloads and native generated Blob downloads use the same streaming save path.

A real ACTION_CREATE_DOCUMENT Save As picker lets the user choose filename/location. A finite dataSync foreground service streams to the granted destination with 64KiB buffers. Filenames support RFC filename*=UTF-8 and are sanitized. Cancellation, short bodies, read stalls and output errors are not success. Newly-created partial documents are deleted when possible; errors explain if a provider prevented removal. No arbitrary APK installation is triggered. Downloaded files are opened only by an explicit user action with a narrow content-URI grant.

Downloads menu and notifications show pending/progress/completed/failed records. Small local history persists filename, MIME, bytes, state and destination URI, never credential-bearing download URLs. History is capped at 100 entries, not downloaded bytes or open tabs. It does not delete completed files. Sources abandoned before destination selection close after ten minutes. Interrupted process-owned transfers are not resumable; use the original site link again. Failed/incomplete records are never relabeled Saved.

## Acceptance gate

Added tests use a real test-APK-only DocumentsProvider without a _data column and Android DocumentsUI touch selection. They verify multipart HTTP receipt of exact selected text/binary bytes and original names, cancellation then retry, floating upload with a separate WORK profile, authenticated redirect download fetched once, exact saved binary/UTF-8 Blob bytes, and Save As cancellation. The provider is NOT compiled into the phone APK. The full existing runtime suite remains enabled; fixtures use synthetic local data, not user credentials or ChatGPT chats.

At submission, the new build/runtime tests have not run. Do not announce support as verified until actual byte-level tests pass. Signed-in ChatGPT server acceptance and account-specific file limits remain separate from browser plumbing. No claim of physical 120fps or universal foreground immunity is made.

Build/storage: preserve the permanent public DEBUG signing identity, package, site profiles and workspace schema. Use one consolidated build initially and the existing GitHub Release publisher; no Actions artifact uploads. This update does not claim the previously requested backdrop blur, icon swap, text-only quick menu, swipe-hold redesign or sidebar popup is complete.