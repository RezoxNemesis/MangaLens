package com.mangalens.ui.web

/**
 * Evaluate in the current main WebView document; each script returns a JSON string.
 * No bridge or network operation is installed. Publisher metadata is still admitted
 * by the native parser and ProviderCaptionUrlPolicy before any caption is fetched.
 */
internal object BrowserCaptionScript {
    private val opaqueIdentity = Regex("[a-f0-9]{32}")
    private val captionLanguage = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}")

    fun capture(): String = captureScript

    fun clock(
        expectedDocumentNonce: String,
        expectedElementId: String,
        expectedSourceVersion: String,
        expectedAudioTrackKey: String,
        expectedAudioLanguage: String,
        expectedPageUrl: String
    ): String {
        require(listOf(expectedDocumentNonce, expectedElementId, expectedSourceVersion).all(opaqueIdentity::matches))
        require(expectedAudioTrackKey.length in 1..256 && expectedAudioTrackKey.none { it.code < 32 || it.code == 127 })
        require(expectedAudioLanguage.length <= 64 && captionLanguage.matches(expectedAudioLanguage))
        require(expectedPageUrl.length in 1..16_384 && expectedPageUrl.none { it.code < 32 || it.code == 127 })
        val expected = "{" + listOf(
            "documentNonce" to expectedDocumentNonce,
            "elementId" to expectedElementId,
            "sourceVersion" to expectedSourceVersion,
            "audioTrackKey" to expectedAudioTrackKey,
            "audioLanguage" to expectedAudioLanguage,
            "pageUrl" to expectedPageUrl
        ).joinToString(",") { (key, value) -> quote(key) + ":" + quote(value) } + "}"
        return """
            (() => {
              'use strict';
              const state = window.__mangalensCaptionDomStateV1;
              if (!state || state.schemaVersion !== 1 || state.document !== document || typeof state.clock !== 'function') return JSON.stringify({schemaVersion:1,status:'retired',reason:'DOCUMENT_UNAVAILABLE'});
              try { return JSON.stringify(state.clock($expected)); }
              catch (_) { return JSON.stringify({schemaVersion:1,status:'retired',reason:'CURRENT_VIDEO_UNAVAILABLE'}); }
            })()
        """.trimIndent()
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (char.code < 32 || char.code == 0x2028 || char.code == 0x2029 || char.code in 0xD800..0xDFFF) {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
            } else append(char)
        }
        append('"')
    }

    private val captureScript = """
        (() => {
          'use strict';
          const STATE_KEY = '__mangalensCaptionDomStateV1';
          const VERSION = 1;
          const MAX_URL = 16384;
          const MAX_TRACKS = 128;
          const MAX_TOTAL_URL = 131072;
          const MAX_SECONDS = 21600;
          const knownReasons = new Set(['NO_VIDEO','AMBIGUOUS_VIDEO','VIDEO_NOT_READY','UNSUPPORTED_AUDIO_BINDING','UNBOUND_PROVIDER_VIDEO','NO_SOURCE_CAPTIONS','METADATA_LIMIT','UNSUPPORTED_PAGE','STATE_UNAVAILABLE']);
          function resultFailure(status, reason) {
            return {schemaVersion:VERSION,status:status,reason:reason};
          }
          function fail(reason) { throw new Error(reason); }
          function text(value, maximum) {
            if (typeof value !== 'string' || value.length > maximum || /[\u0000-\u001f\u007f]/.test(value)) return null;
            return value;
          }
          function language(value) {
            const raw = text(value, 64);
            if (raw === null) return null;
            const normalized = raw.trim().replace(/_/g, '-').toLowerCase();
            return /^[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}$/.test(normalized) ? normalized : null;
          }
          function opaqueId() {
            const bytes = new Uint8Array(16);
            if (!window.crypto || typeof window.crypto.getRandomValues !== 'function') fail('STATE_UNAVAILABLE');
            window.crypto.getRandomValues(bytes);
            return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
          }
          function createState() {
            const capturedDocument = document;
            const documentNonce = opaqueId();
            const elements = new WeakMap();
            const sourceObjects = new WeakMap();
            const audioObjects = new WeakMap();
            function objectId(map, object) {
              if (!map.has(object)) map.set(object, opaqueId());
              return map.get(object);
            }
            function pageUrl() {
              if (window.top !== window.self || document !== capturedDocument) fail('UNSUPPORTED_PAGE');
              const value = text(window.location.href, MAX_URL);
              if (!value) fail('UNSUPPORTED_PAGE');
              const parsed = new URL(value);
              if (!['https:', 'http:'].includes(parsed.protocol) || parsed.username || parsed.password) fail('UNSUPPORTED_PAGE');
              return value;
            }
            function visible(video) {
              if (!(video instanceof HTMLVideoElement) || !video.isConnected || video.ownerDocument !== capturedDocument) return false;
              const rect = video.getBoundingClientRect();
              if (!Number.isFinite(rect.width) || !Number.isFinite(rect.height) || rect.width < 2 || rect.height < 2 || rect.right <= 0 || rect.bottom <= 0 || rect.left >= window.innerWidth || rect.top >= window.innerHeight) return false;
              let parent = video;
              for (let depth = 0; parent && depth < 64; depth++, parent = parent.parentElement) {
                const style = getComputedStyle(parent);
                if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) <= 0.01) return false;
              }
              return true;
            }
            function selectedVideo() {
              const all = capturedDocument.querySelectorAll('video');
              if (all.length > 32) fail('METADATA_LIMIT');
              const candidates = Array.from(all).filter(video => visible(video) || (video.isConnected && !video.paused && !video.ended && video.readyState >= 2));
              if (candidates.length > 1) fail('AMBIGUOUS_VIDEO');
              if (candidates.length !== 1 || !visible(candidates[0])) fail('NO_VIDEO');
              return candidates[0];
            }
            // BEGIN CAPTION_METADATA_FENCE
            function copyBoundedCaptionMetadata(value, depth, budget) {
              if (depth > 6 || ++budget.nodes > 8192) fail('METADATA_LIMIT');
              if (value === undefined || value === null) return null;
              if (typeof value === 'string') {
                const bounded = text(value, MAX_URL);
                if (bounded === null || (budget.characters += bounded.length) > MAX_TOTAL_URL) fail('METADATA_LIMIT');
                return bounded;
              }
              if (typeof value === 'boolean') return value;
              if (typeof value === 'number' && Number.isFinite(value)) return value;
              if (Array.isArray(value)) {
                if (value.length > MAX_TRACKS) fail('METADATA_LIMIT');
                return value.map(item => copyBoundedCaptionMetadata(item, depth + 1, budget));
              }
              if (typeof value !== 'object' || (Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null)) fail('METADATA_LIMIT');
              const keys = Object.keys(value).sort();
              if (keys.length > 32) fail('METADATA_LIMIT');
              const copied = Object.create(null);
              for (const key of keys) {
                if (text(key, 128) === null || (budget.characters += key.length) > MAX_TOTAL_URL) fail('METADATA_LIMIT');
                copied[key] = copyBoundedCaptionMetadata(value[key], depth + 1, budget);
              }
              return copied;
            }
            function exactProviderCaptionMetadata(boundProvider) {
              if (!boundProvider) return null;
              const response = boundProvider.response;
              const renderer = response.captions && response.captions.playerCaptionsTracklistRenderer;
              const microformat = response.microformat && response.microformat.playerMicroformatRenderer;
              const raw = [response.videoDetails && response.videoDetails.defaultAudioLanguage,
                microformat && microformat.defaultAudioLanguage,
                renderer && renderer.captionTracks,renderer && renderer.audioTracks,
                renderer && renderer.defaultAudioTrackIndex,renderer && renderer.defaultCaptionTrackIndex];
              // Preserve exact normal metadata, including every caption URL token. Any change
              // conservatively retires this source; no auth-token equivalence is assumed.
              return copyBoundedCaptionMetadata(raw, 0, {nodes:0,characters:0});
            }
            function captionMetadataSignature(video, boundProvider) {
              const tracks = Array.from(video.children).filter(child => child instanceof HTMLTrackElement);
              if (tracks.length > MAX_TRACKS) fail('METADATA_LIMIT');
              let urlCharacters = 0;
              const originalLanguage = text(video.getAttribute('lang') || '', 64);
              if (originalLanguage === null) fail('METADATA_LIMIT');
              const rows = tracks.map(track => {
                const rawSrc = text(track.getAttribute('src') || '', MAX_URL);
                const resolvedSrc = text(track.src || '', MAX_URL);
                const srclang = text(track.srclang || '', 64);
                const kind = text(track.kind || '', 32);
                const rawKind = text(track.getAttribute('kind') || '', 32);
                const label = text(track.label || '', 1024);
                const rawDefault = text(track.getAttribute('default') || '', 128);
                if ([rawSrc,resolvedSrc,srclang,kind,rawKind,label,rawDefault].some(value => value === null)) fail('METADATA_LIMIT');
                urlCharacters += resolvedSrc.length;
                if (urlCharacters > MAX_TOTAL_URL) fail('METADATA_LIMIT');
                return [rawSrc,resolvedSrc,srclang,kind,rawKind,track.default,rawDefault,label];
              });
              const exact = JSON.stringify([originalLanguage,rows,exactProviderCaptionMetadata(boundProvider)]);
              if (exact.length > MAX_TOTAL_URL * 3) fail('METADATA_LIMIT');
              return exact;
            }
            // END CAPTION_METADATA_FENCE
            function sourceIdentity(video, boundProvider) {
              const currentSrc = text(video.currentSrc || '', MAX_URL);
              const declaredSrc = text(video.getAttribute('src') || '', MAX_URL);
              if (currentSrc === null || declaredSrc === null) fail('METADATA_LIMIT');
              const sources = Array.from(video.children).filter(child => child instanceof HTMLSourceElement);
              if (sources.length > 16) fail('METADATA_LIMIT');
              const sourceUrls = Array.from(sources).map(source => {
                const url = text(source.src || source.getAttribute('src') || '', MAX_URL);
                const type = text(source.getAttribute('type') || '', 256);
                const media = text(source.getAttribute('media') || '', 256);
                if (url === null || type === null || media === null) fail('METADATA_LIMIT');
                return [url, type, media];
              });
              const sourceObject = video.srcObject;
              const sourceObjectId = sourceObject && (typeof sourceObject === 'object' || typeof sourceObject === 'function') ? objectId(sourceObjects, sourceObject) : null;
              const fingerprint = JSON.stringify([currentSrc,declaredSrc,sourceObjectId,sourceUrls,captionMetadataSignature(video, boundProvider)]);
              let identity = elements.get(video);
              if (!identity) {
                identity = {elementId:opaqueId(),sourceVersion:opaqueId(),fingerprint:fingerprint};
                elements.set(video, identity);
                const rotate = () => { identity.sourceVersion = opaqueId(); identity.fingerprint = null; };
                video.addEventListener('emptied', rotate, {passive:true});
                video.addEventListener('loadstart', rotate, {passive:true});
              } else if (identity.fingerprint !== fingerprint) {
                identity.sourceVersion = opaqueId();
                identity.fingerprint = fingerprint;
              }
              return {elementId:identity.elementId,sourceVersion:identity.sourceVersion,currentSrc:currentSrc};
            }
            function htmlAudio(video) {
              const tracks = video.audioTracks;
              if (tracks && typeof tracks.length === 'number' && tracks.length > 0) {
                if (tracks.length > 16) fail('METADATA_LIMIT');
                const selected = Array.from(tracks).filter(track => track.enabled === true);
                if (selected.length !== 1) fail('UNSUPPORTED_AUDIO_BINDING');
                const selectedLanguage = language(selected[0].language);
                if (!selectedLanguage) fail('UNSUPPORTED_AUDIO_BINDING');
                const rawId = text(selected[0].id || '', 128);
                if (rawId === null) fail('UNSUPPORTED_AUDIO_BINDING');
                return {audioTrackKey:'html:' + objectId(audioObjects, selected[0]) + ':' + rawId,audioLanguage:selectedLanguage,audioBinding:'observed-track'};
              }
              const declaredLanguage = language(video.getAttribute('lang'));
              if (!declaredLanguage) fail('UNSUPPORTED_AUDIO_BINDING');
              return {audioTrackKey:'element-lang:' + declaredLanguage,audioLanguage:declaredLanguage,audioBinding:'element-language'};
            }
            function youtubeVideoId(url) {
              const path = url.pathname.replace(/\/$/, '');
              let id = null;
              if (url.hostname === 'youtu.be') id = path.slice(1).includes('/') ? null : path.slice(1);
              else if (path === '/watch') id = url.searchParams.getAll('v').length === 1 ? url.searchParams.get('v') : null;
              else {
                const parts = path.split('/');
                if (parts.length === 3 && ['embed','shorts','live'].includes(parts[1])) id = parts[2];
              }
              return typeof id === 'string' && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
            }
            function provider(video, exactPageUrl) {
              const page = new URL(exactPageUrl);
              const isYouTube = page.hostname === 'youtu.be' || page.hostname === 'youtube.com' || page.hostname.endsWith('.youtube.com');
              if (!isYouTube) return null;
              const id = youtubeVideoId(page);
              const response = window.ytInitialPlayerResponse;
              const player = capturedDocument.getElementById('movie_player');
              if (!id || !response || typeof response !== 'object' || !response.videoDetails || response.videoDetails.videoId !== id || !player || !player.contains(video) || typeof player.getVideoData !== 'function') fail('UNBOUND_PROVIDER_VIDEO');
              const data = player.getVideoData();
              if (!data || (data.video_id || data.videoId) !== id || (data.video_id && data.videoId && data.video_id !== data.videoId)) fail('UNBOUND_PROVIDER_VIDEO');
              let audio;
              if (typeof player.getAudioTrack === 'function') {
                const selected = player.getAudioTrack();
                if (!selected || typeof selected !== 'object') fail('UNSUPPORTED_AUDIO_BINDING');
                const rawKey = text(selected.id, 256);
                if (!rawKey) fail('UNSUPPORTED_AUDIO_BINDING');
                const explicitRaw = [selected.languageCode,selected.language,selected.lang].filter(value => value !== undefined && value !== null && value !== '');
                const explicit = explicitRaw.map(language);
                if (explicit.some(value => value === null) || new Set(explicit).size > 1) fail('UNSUPPORTED_AUDIO_BINDING');
                const encoded = /^([a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3})\.\d+$/i.exec(rawKey);
                const encodedLanguage = encoded ? language(encoded[1]) : null;
                const observedLanguage = explicit[0] || encodedLanguage;
                if (!observedLanguage || (explicit[0] && encodedLanguage && explicit[0] !== encodedLanguage)) fail('UNSUPPORTED_AUDIO_BINDING');
                audio = {audioTrackKey:rawKey,audioLanguage:observedLanguage,audioBinding:'observed-track'};
              } else audio = htmlAudio(video);
              const originalLanguage = language(response.videoDetails.defaultAudioLanguage) || language(response.microformat && response.microformat.playerMicroformatRenderer && response.microformat.playerMicroformatRenderer.defaultAudioLanguage) || audio.audioLanguage;
              return {videoId:id,response:response,audio:audio,originalLanguage:originalLanguage};
            }
            function clockFields(video) {
              if (video.readyState < 1) fail('VIDEO_NOT_READY');
              const seconds = video.currentTime;
              const duration = video.duration;
              const rate = video.playbackRate;
              if (!Number.isFinite(seconds) || seconds < 0 || seconds > MAX_SECONDS || !Number.isFinite(rate) || rate < 0.1 || rate > 16 || (Number.isFinite(duration) && (duration <= 0 || duration > MAX_SECONDS))) fail('VIDEO_NOT_READY');
              return {readyState:video.readyState,currentTimeMs:Math.round(seconds * 1000),durationMs:Number.isFinite(duration) ? Math.round(duration * 1000) : null,playbackRate:rate,paused:video.paused,seeking:video.seeking};
            }
            function readContext() {
              const exactPageUrl = pageUrl();
              const video = selectedVideo();
              const boundProvider = provider(video, exactPageUrl);
              const source = sourceIdentity(video, boundProvider);
              const audio = boundProvider ? boundProvider.audio : htmlAudio(video);
              const position = clockFields(video);
              return {video:video,provider:boundProvider,data:Object.assign({schemaVersion:VERSION,status:'ready',reason:null,pageUrl:exactPageUrl,documentNonce:documentNonce}, source, audio, position)};
            }
            function captionTracks(context) {
              const found = [];
              const seen = new Set();
              let totalUrlCharacters = 0;
              function add(track) {
                const key = JSON.stringify(track);
                if (seen.has(key)) return;
                totalUrlCharacters += track.url.length;
                if (found.length >= MAX_TRACKS || totalUrlCharacters > MAX_TOTAL_URL) fail('METADATA_LIMIT');
                seen.add(key);
                found.push(track);
              }
              const html = Array.from(context.video.children).filter(child => child instanceof HTMLTrackElement);
              if (html.length > MAX_TRACKS) fail('METADATA_LIMIT');
              for (const track of html) {
                if (!['subtitles','captions'].includes(track.kind)) continue;
                const rawUrl = text(track.getAttribute('src') || '', MAX_URL);
                const lang = language(track.srclang);
                if (rawUrl === null) fail('METADATA_LIMIT');
                if (!rawUrl || !lang) continue;
                // Read the selected element's actual resolved URL; <base> is publisher metadata.
                // Native authority admission still requires HTTPS and the captured page origin.
                const url = text(track.src, MAX_URL);
                if (!url) fail('METADATA_LIMIT');
                add({url:url,language:lang,kind:'MANUAL',format:'VTT',originalAutomatic:false});
              }
              if (context.provider) {
                const captions = context.provider.response.captions;
                const renderer = captions && captions.playerCaptionsTracklistRenderer;
                const tracks = renderer && renderer.captionTracks;
                if (tracks !== undefined && !Array.isArray(tracks)) fail('NO_SOURCE_CAPTIONS');
                if (tracks && tracks.length > MAX_TRACKS) fail('METADATA_LIMIT');
                for (const track of tracks || []) {
                  if (!track || typeof track !== 'object') continue;
                  const rawUrl = text(track.baseUrl, MAX_URL);
                  const lang = language(track.languageCode);
                  if (rawUrl === null && typeof track.baseUrl === 'string') fail('METADATA_LIMIT');
                  if (!rawUrl || !lang) continue;
                  let url;
                  try { url = new URL(rawUrl); } catch (_) { continue; }
                  if (url.protocol !== 'https:' || url.username || url.password || url.hash || (url.hostname !== 'youtube.com' && !url.hostname.endsWith('.youtube.com')) || url.pathname !== '/api/timedtext' || url.searchParams.getAll('v').length !== 1 || url.searchParams.get('v') !== context.provider.videoId || url.searchParams.has('tlang')) continue;
                  const keys = Array.from(url.searchParams.keys());
                  if (new Set(keys).size !== keys.length) continue;
                  const urlLanguage = language(url.searchParams.get('lang'));
                  if (!urlLanguage || urlLanguage.split('-')[0] !== lang.split('-')[0]) continue;
                  if (track.kind && track.kind !== 'asr') continue;
                  const kind = track.kind === 'asr' ? 'AUTOMATIC' : 'MANUAL';
                  for (const format of ['VTT','JSON3']) {
                    const formatted = new URL(url.href);
                    formatted.searchParams.set('fmt', format.toLowerCase());
                    const formattedUrl = text(formatted.href, MAX_URL);
                    if (!formattedUrl) fail('METADATA_LIMIT');
                    add({url:formattedUrl,language:lang,kind:kind,format:format,originalAutomatic:kind === 'AUTOMATIC'});
                  }
                }
              }
              if (found.length === 0) fail('NO_SOURCE_CAPTIONS');
              return found;
            }
            function capture() {
              try {
                const context = readContext();
                const tracks = captionTracks(context);
                return Object.assign(context.data,{videoId:context.provider ? context.provider.videoId : null,originalLanguage:context.provider ? context.provider.originalLanguage : language(context.video.getAttribute('lang')),tracks:tracks});
              } catch (error) {
                const reason = knownReasons.has(error && error.message) ? error.message : 'STATE_UNAVAILABLE';
                return resultFailure('unavailable', reason);
              }
            }
            function clock(expected) {
              try {
                const context = readContext();
                const actual = context.data;
                const keys = ['pageUrl','documentNonce','elementId','sourceVersion','audioTrackKey','audioLanguage'];
                if (!expected || keys.some(key => typeof expected[key] !== 'string' || expected[key] !== actual[key])) return resultFailure('retired','IDENTITY_CHANGED');
                return actual;
              } catch (_) { return resultFailure('retired','CURRENT_VIDEO_UNAVAILABLE'); }
            }
            return Object.freeze({schemaVersion:VERSION,document:capturedDocument,capture:capture,clock:clock});
          }
          try {
            let state = window[STATE_KEY];
            if (!state) {
              state = createState();
              Object.defineProperty(window, STATE_KEY, {value:state,enumerable:false,writable:false,configurable:false});
            }
            if (state.schemaVersion !== VERSION || state.document !== document || typeof state.capture !== 'function' || typeof state.clock !== 'function') return JSON.stringify(resultFailure('unavailable','STATE_UNAVAILABLE'));
            return JSON.stringify(state.capture());
          } catch (_) { return JSON.stringify(resultFailure('unavailable','STATE_UNAVAILABLE')); }
        })()
    """.trimIndent()
}
