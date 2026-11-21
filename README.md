# Media Picker

Source preview for Despia V3. This snapshot requires the runtime update currently awaiting release and an approved extension-enabled native build. Uploading these files does not change an installed app.

Native file selection, video transcoding and persistent uploads. Experimental preview, compatibility work is in progress.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `mediapicker.policy.json`: the approval policy. It is a file you send to Despia with your approval request, together with the extension files. It is not served from your site and does not go into `public/despia/`.

## Requirements

- A Despia V3 app with V3 Extensions enabled, and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- An upload endpoint for the files you upload from the page.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/mediapicker/` in your web app. The folder name is the extension id, so pages call `extension://mediapicker/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"mediapicker"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Request approval and a new native build in Despia, and send `mediapicker.policy.json` with the request. Despia reviews and approves these exact files before a build includes them, and any change to the manifest or to a native file needs a new approval and a new native build. It cannot be applied over the air.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Copy `example/src/lib/despia-extension.js` to your app's `src/lib/` and adjust the import path if needed. It imports `despia` from `despia-native`, calls the SDK and returns the native answer's `data`. It checks native error envelopes and throws an error with their `code`. The SDK itself resolves native `ok: false` envelopes; it does not reject them. A single watched value becomes `undefined` after 30 seconds, which this helper reports as `sdk_timeout`.

Each helper call forwards a unique native callback into a separate watched value, so overlapping calls do not share response globals. `onExtensionEvent` registers the declared native event callbacks and returns a cleanup function. Events are callbacks, not values you can poll from the SDK proxy.

Importing the SDK is safe during server rendering. Run calls and event subscriptions only from browser click handlers or mounted client hooks. React and Vue can use the browser APIs used by this helper; in Next.js and other server-rendered apps, keep these calls in client code. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features.

## Calling it

```javascript
import { extensionCall } from './lib/despia-extension.js';

const answer = await extensionCall('mediapicker', 'files');
console.log(answer);
```

Calls use `extension://mediapicker/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension replaces the web view's `<input type="file">` with the platform's own pickers for every kind of file. Each picked file reaches your page as a local URL at once, with no base64 and no bytes through the bridge. The extension implements native video transcoding and persisted upload jobs.

- **Pick:** photos and videos, audio, documents and any MIME type, from the library, the camera or the Files app.
- **Use:** play, show or `fetch` the file straight from its local URL.
- **Transcode:** video to a preset size, codec, frame rate and audio setting, with progress.
- **Upload:** to your https URL with `PUT` or `POST`, with retries, also after a relaunch.

---

## Values to set before you build

Before you build:

- In `manifest.json`, in the `upload` route's `hosts`, replace `upload.example.com` with the host your uploads go to.
- In Despia, turn on the camera and microphone purpose strings (iOS) and `CAMERA` (Android), which the manifest lists under `requires`.

The Android dependencies must be the versions your app's base build already resolves. The build refuses others. 
---

## Using it from your web app

```javascript
import { extensionCall, onExtensionEvent } from './lib/despia-extension.js';
const picker = (route, params) => extensionCall('mediapicker', route, params);
const stopFiles = onExtensionEvent('onMediaPickerFileEvent', file => {
  if (file.state !== 'ready') return;
  if (file.kind === 'video') document.querySelector('video').src = file.url;
  if (file.kind === 'image') document.querySelector('img').src = file.url;
});
const stopJobs = onExtensionEvent('onMediaPickerJobEvent', job => console.log(job.kind, job.state, job.progress));
const selection = await picker('pick', { types: 'image,video', multiple: true, limit: 10, max_file_mb: 2048 });
console.log(selection.pickId);
// Re-read selection after native UI completion: await picker('result', { pick: selection.pickId });
// await picker('transcode', { id: fileId, preset: '720p', codec: 'hevc' });
// await picker('upload', { id: outputFileId, url: uploadUrl, release_after: true });
// Dispose subscriptions when the page unmounts: stopFiles(); stopJobs();
```

Every route answers at once, once. What happens later arrives as an event and stays readable through a route. A route answers in `mediaPicker<Route>` and `onMediaPicker<Route>`, for example `mediaPickerPick` and `onMediaPickerPick`.

### Routes

| Route       | Parameters | Answer `data` | Answer variable | Callback |
| ----------- | ---------- | ------------- | --- | --- |
| `pick`      | `types` (comma list of `image`, `video`, `audio`, `document`, `any` and MIME types such as `application/pdf` or `audio/*`), `source` (`auto`, `library`, `camera`, `files`), `multiple`, `limit` (0 for the platform's maximum, up to 500), `max_file_mb` and `max_total_mb` (0 to 4096), `representation` (`current`, `compatible`, `automatic`), `ordered`, `camera` (`photo`, `video`, `both`), `max_duration_s` (0 to 3600), `camera_quality` (`low`, `medium`, `high`), `accent_color` (`#RRGGBB`), `tab` (`photos`, `albums`) | `pickId`, `state` (`presenting`), `source`, `limit`, `limitLowered` | `mediaPickerPick` | `onMediaPickerPick` |
| `result`    | `pick` (required) | The pick: `pickId`, `state` (`presenting`, `loading`, `done`, `cancelled` or `failed`), `source`, `files` | `mediaPickerResult` | `onMediaPickerResult` |
| `files`     | `id` (optional) | One file, or `files` and `base`. A released file answers with `state: "released"` and no `url`. The list leaves released files out | `mediaPickerFiles` | `onMediaPickerFiles` |
| `release`   | `id`, or `all: true` | `released`: the ids. Answers `busy` while a transcode or an upload still needs the file | `mediaPickerRelease` | `onMediaPickerRelease` |
| `transcode` | `id` (required), `preset` (`480p`, `540p`, `720p`, `1080p`, `1440p`, `2160p`, `source`), `codec` (`h264`, `hevc`), `bitrate_kbps` (text, 100 to 400000; empty for the default for the size), `frame_rate` (a cap, up to 120), `audio` (`keep`, `remove`), `audio_bitrate_kbps` (32 to 320) | The job: `jobId`, `kind: "transcode"`, `state`, `outputFileId`, `width`, `height`, `bitrateKbps`, `codec`. `state` is `queued` behind a running transcode, or `transcoding` when it started at once | `mediaPickerTranscode` | `onMediaPickerTranscode` |
| `upload`    | `id` (required), `url` (required, https, an approved host), `method` (`PUT`, `POST`), `form_field` (a multipart field; without it the body is the raw file), `content_type`, `release_after` | The job, with `state: "uploading"` | `mediaPickerUpload` | `onMediaPickerUpload` |
| `jobs`      | `id` (optional) | One job, or `jobs`. Carries `indexUnread: true` while the upload index cannot be read yet | `mediaPickerJobs` | `onMediaPickerJobs` |
| `cancel`    | `id` (required) | The job, now `cancelled`. A finished job is returned unchanged | `mediaPickerCancel` | `onMediaPickerCancel` |

### The file object

`fileId`, `pickId`, `state`, `name`, `mime`, `kind`, `bytes`, `origin`, `url`, `width`, `height`, `durationMs`, `progress` and `reason`. `state` is `loading`, `ready`, `failed` or `released`. `url` exists only while `state` is `ready`. After release the old URL answers 404.

### Events

| Event    | Callback                     | Scope      | When                                                                 |
| -------- | ---------------------------- | ---------- | -------------------------------------------------------------------- |
| `pick`   | `onMediaPickerPickEvent`     | `document` | The state of a pick changed                                          |
| `file`   | `onMediaPickerFileEvent`     | `document` | A file is loading (with progress), ready (with its `url`), failed (with `reason`) or released |
| `job`    | `onMediaPickerJobEvent`      | `document` | Transcode and upload progress and outcome                            |
| `server` | `onMediaPickerServerEvent`   | `document` | `running` and `base`, when the local port changes. Rebuild URLs from `files` then |

### Reasons and error codes

- **File and transcode reasons:** `too_large`, `total_exceeded`, `limit_exceeded`, `import_failed`, `cancelled`, `not_supported_on_device`, `unreadable`, `encode_failed`, `no_video` and `background_expired` (iOS: the background time ran out).
- **Pick reasons:** `cancelled`, `capture_failed`, `activity_destroyed`, `permission_denied` (a camera chosen from the sheet was not allowed) and `camera_unavailable`.
- **Upload reasons:** `http`, `network`, `redirect_refused`, `url_not_approved`, `no_file` and `policy_unavailable`.
- **Route errors:** `bad_request`, `bad_value`, `not_found`, `busy`, `permission_denied`, `camera_unavailable`, `unavailable`, `timeout`, `release_failed`, `storage_failed` (the upload index could not be written, or on iOS exists but cannot be read yet, before the first unlock), `unreadable` and `not_supported_on_device`.

See also the standard codes.

---

## How files reach the page

A reply is a JavaScript string, so file bytes never belong in one. The extension serves each file from your own phone on `http://127.0.0.1:<port>/f/<token>/<name>`, straight from the file on disk.

- It answers `GET`, `HEAD` and `OPTIONS`, with single byte ranges, so a `<video>` can seek and `fetch(url).then(r => r.blob())` works from your https page. Files stream in 256 KiB chunks and are never held whole in memory.
- The URL is the access: it holds a random 256-bit token, and the token is withdrawn the moment the file is released. The server listens on the loopback interface only.
- Limits: a connection that sends no complete request in 10 seconds is closed, and at most 16 connections are open at once.

## Pickers

- **iOS.** `PHPickerViewController` for photos and videos (no photo library permission), the document picker for files and audio, `UIImagePickerController` for the camera, and for `source: "auto"` an action sheet. Nothing is restyled except the optional tint.
- **Android.** The system Photo Picker, `ACTION_OPEN_DOCUMENT` for documents, and the system camera app writing to a `CaptureProvider` URI that is not exported. `source: "auto"` shows a Material dialog list.

## Video tools

- **iOS.** AVAssetReader and AVAssetWriter: H.264 or HEVC, bitrate, a frame-rate cap and AAC audio. HDR is kept only for HEVC. Support is checked before a job is queued. A transcode keeps running for a while after the person leaves the app. When iOS ends that time, the job fails with `background_expired`.
- **Android.** Media3 Transformer inside a foreground service, with encoder support checked first.
- **Sizes.** The short side is the preset's or the source's, whichever is smaller. Video is never upscaled. The long side keeps the aspect ratio, and both are even.
- **Bitrates.** The default for H.264, in kbps: 2000 at 480p, 2500 at 540p, 5000 at 720p, 8000 at 1080p, 16000 at 1440p and 35000 above. HEVC uses 0.6 times that. Above 31 fps it is 1.5 times. It never goes above the source's own bitrate.

## Durable upload

- Redirects are never followed, and the URL is checked against the approved hosts again before each attempt.
- Retries are scheduled by the platform. On iOS, backoff is `30 * 2^(n-1)` seconds up to 15 minutes, 5 counted attempts, and a background processing task named `upload` finishes the job after the app was killed. On Android, WorkManager runs it.
- **Resume** means the upload continues after a kill or reboot, restarting the body from its first byte. A plain `PUT` or `POST` has no resumable protocol.
- The upload index is written before the route answers. A write that fails answers `storage_failed` and queues nothing. Finished records are kept 7 days.
- On iOS the index is protected until the first unlock. Until it can be read, nothing is pruned or written, and `upload` answers `storage_failed`. It is read again on unlock, on foreground and on each `upload` or `jobs` call.

## Clean-up

A file is deleted when the page releases it, when an upload with `release_after` finishes, or at the next launch. The exception is a file an unfinished upload still needs. It is listed again with a new URL token. Files are excluded from iCloud backup. Camera captures that were never picked up are deleted at launch.

---

## Permissions and capabilities

### iOS

- **Camera and microphone.** `NSCameraUsageDescription` and `NSMicrophoneUsageDescription`, declared under `requires.usage_descriptions`. Set the purpose text in Despia.
- **Background.** The `processing` background mode and a background task named `upload`.

### Android

- **Camera.** `android.permission.CAMERA`, declared under `requires.permissions`. Turn it on for your app in Despia.
- **Foreground service.** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `FOREGROUND_SERVICE_MEDIA_PROCESSING` for the transcode service, and `POST_NOTIFICATIONS` for its notification.
- **Capture provider.** A `FileProvider` that is not exported. It is listed in the approval policy because it grants URI access to the camera app.
- **Libraries.** `androidx.core:core:1.18.0`, `androidx.activity:activity:1.13.0`, `androidx.work:work-runtime:2.11.2` and `androidx.media3:media3-transformer:1.10.1`, the versions your app's base build already resolves.

---

## Platform limits

- **Android WorkManager.** A regular worker is stopped after 10 minutes, and the upload restarts from its first byte.
- **iOS.** A background session would follow redirects, so uploads run in the app's own session, plus the background processing task. iOS does not guarantee when it runs.
- **Android camera quality.** `EXTRA_VIDEO_QUALITY` takes only 0 or 1, so `medium` maps to 1.
- **Media3.** The extension uses the version your app's base build resolves (1.10.1).

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE).
You may inspect the source. Using or modifying this extension requires a valid
Despia commercial license and a valid Despia project for the particular app.
The standard price is **USD 249 per application per operating system**.
iOS and Android require separate licenses. See [Despia](https://despia.com).
Third-party SDKs keep their own licenses. Experimental V3 extensions do not
include a commitment to maintenance or a V4 port.

## Source and licensing

Browse, clone or fork this source to inspect it. Public GitHub forks remain public. For private development, clone into a private workspace or private repository. Modification, building, execution and deployment require a valid Despia commercial license and a valid Despia project for the application. The standard price is USD 249 per application per operating system. Licensed users may customize the extension or use it as a reference for new functionality in that licensed app. Keep the license and applicable notices. See [LICENSE](LICENSE) for the terms.
