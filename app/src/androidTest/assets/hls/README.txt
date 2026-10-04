Original generated test fixture: a 440 Hz sine tone lasting two seconds.
Generated with FFmpeg's lavfi sine source and AAC encoding; no third-party media.
Segments are base64 encoded to keep repository updates text-safe and decoded by the test.
Test-only assets are packaged in the instrumentation APK, not the application APK.

Generation command:
ffmpeg -f lavfi -i sine=frequency=440:duration=2 -c:a aac -b:a 64k -f hls -hls_time 1 -hls_list_size 0 -hls_segment_filename mangalens-qa-%02d.ts playlist.m3u8
