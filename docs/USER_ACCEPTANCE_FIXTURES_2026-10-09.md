# Required user acceptance fixtures

The user supplied these fixtures during implementation on 9 October 2026. They are required when the associated features reach acceptance; they do not replace the ongoing engineering work. A controlled fixture passing cannot substitute for these real sources. The partial executions and remaining acceptance are recorded below.

| Fixture | Supplied source | Required actual operations |
|---|---|---|
| Manhwa chapter | https://demonicscans.org/title/Kidnapped-Dragons/chapter/63/1 | Acquire and download the chapter, retain all original pages in Library, translate via OCR to Hindi and Hinglish, inspect rendered lettering and meaning, reopen saved results. |
| YouTube video | https://youtu.be/RzasqVwpLOA?si=vn3VhAdIZKeUXgZE | Resolve and play in the native player with audio; download 1080p (user reports it exists) and the highest accessible representation; inspect actual dimensions, sample/audio coverage and end of video; evaluate live subtitles, then import/play the saved video and evaluate local live subtitles and retained player features. |
| Instagram reel | https://www.instagram.com/reel/DeODl9XI0ex/?dlrf=MTljZnpjY21lbGh2Mw== | Resolve and play in the native player with audio; download the highest accessible representation, including 1080p if actually offered; inspect dimensions/audio/tail; evaluate the same subtitle and local-player paths where content supports speech or captions. |

## Oracles and evidence

- Record exact source/build SHA, dirty-source manifest if applicable, APK hash, Android API/ABI/profile, actual representation and original source identity.
- A source-page navigation is not playback, and an enqueued transfer is not a verified download. Verify produced media with decoding and actual track/duration/dimension measurements. Do not silently label a lower representation 1080p or claim a quality the source did not offer.
- Compare Hindi/Hinglish against the source dialogue, including omitted/copied clauses, names, register, negation, anger/emphasis and idioms. Preserve rejected original bubbles. Inspect balloon fit, erasure artifacts, reading order and saved/reopened output. State which samples were inspected and the quality criteria; do not equate nonempty text with excellent quality.
- For subtitles, compare against actual spoken words or genuine source captions, with timestamps, omissions, hallucinations, language/register, lag, repeats and seek recovery. Silence must not invent speech. Distinguish embedded/remote captions, burned-in OCR and actual ASR evidence.
- Test real player controls, source refresh, cancel/back, local selection/import, seek, subtitle enable/disable, target changes and restart behavior. Preserve original documents and completed downloads.
- Diagnose weak/failed cases, repair the implementation, rerun the affected case and required core regression. Retain before/after outputs and honest limitations.
- Use legitimately accessible source content and existing authorized sessions. No account impersonation, DRM bypass, paid services or fabricated success. Provider restrictions and unavailable physical hardware remain explicit evidence gaps until resolved.

## Current outcomes

| Source | Executed evidence | Current acceptance |
|---|---|---|
| Manhwa | Host fetched the real source and all 15 original pages: 14,264,371 bytes, all decoded and individually hashed. The installed checkpoint 2 app acquired and saved a real page, then crashed in the hardware bitmap/GPU path while opening the tall strip. The native tombstone reports `glTexSubImage2D` / `GL_INVALID_ENUM`; it does not prove a particular GPU texture limit. | **FAIL / incomplete:** full app download, safe reader rendering, actual Hindi/Hinglish OCR/translation, human meaning/lettering inspection and cold reopening remain to run after fixes. Host download is not app acceptance. |
| YouTube | Workspace host attempted metadata and complete best-quality download with pinned yt-dlp 2026.08.19; proxy CONNECT was denied (403). [Ordinary GitHub runner run 37886227099](https://github.com/RezoxNemesis/MangaLens/actions/runs/37886227099) attempted both operations and received “Sign in to confirm you're not a bot.” | **BLOCKED / failed access:** no media file, 1080p result, app playback, audio or subtitle quality is verified. No credentials or challenge bypass was used. |
| Instagram | Workspace and the same GitHub runner both attempted metadata and complete best-quality download. The provider reported that the reel cannot be seen by certain audiences. | **BLOCKED / failed access:** no download, native playback, audio or subtitle quality is verified. No audience or login bypass was used. |

The GitHub diagnostic checks only host extraction and actual decoded files. Its 12 process/quality/privacy regressions passed; the two real provider attempts failed and were reported as failures. It retains redacted text evidence for three days and deletes temporary media. Source commit: `80fb43fd0797d79d9c9b2cd3b968cd7b9154ff78`.

Local fixture evidence is under `/workspace/android-setup/user-fixtures`; installed candidate reports and unchanged screenshots are under `/workspace/android-setup/candidate-checkpoint3`. The chapter's original pages remain outside Git and are preserved. No “excellent” or “perfect” OCR/subtitle quality claim has been made.
