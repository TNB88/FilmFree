MotChill v218 source (continued from v217) - 2026-11-28

Build: JDK 21, Android SDK 35, build-tools 37.0.0, Gradle 8.12.
Set ANDROID_HOME to your Android SDK directory.
From source/: gradlew.bat :MotChillProvider:make :MotChillProvider:testDebugUnitTest
Output: MotChillProvider/build/MotChillProvider.cs3

The eight included tests cover remote priority, fallback deduplication,
canonical API redirects, unsafe/non-API redirects, current hub JSON,
saved movie IDs, master/relative HLS paths and invalid CDN segments.
The larger local regression suite ran 44 cases: 40 passed, 4 live tests
skipped (network unavailable to CLI). See the root technical note for
browser playback checks and limitations. Do not equate browser playback
with a Cloudstream device test.

v218: the Fold5 log identified JoJo episode source 149585 buffering at 0.
Live curl checks with the player-issued token: CDN returned a 70-byte PNG,
the m3u8 variant returned 404, B2 returned video/mp2t (206, 1024-byte probe).
The resolver now verifies a segment and falls through to the next tier.
Catalog metadata/IDs are reused to avoid redundant detail API requests.
Expiring embed URLs are still refreshed for every play request.

Only MotChill binary was replaced; shared helper copies here are for this
standalone build, not instructions to replace other released providers.
