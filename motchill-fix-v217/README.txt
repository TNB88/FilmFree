MotChill v217 source snapshot - 2026-11-28

Build: JDK 21, Android SDK 35, build-tools 37.0.0, Gradle 8.12.
Set ANDROID_HOME to your Android SDK directory.
From source/: gradlew.bat :MotChillProvider:make :MotChillProvider:testDebugUnitTest
Output: MotChillProvider/build/MotChillProvider.cs3

The four included tests cover remote priority, fallback deduplication,
canonical API redirects, unsafe/non-API redirects and current hub JSON.
The larger local regression suite ran 44 cases: 40 passed, 4 live tests
skipped (network unavailable to CLI). See the root technical note for
browser playback checks and limitations. Do not equate browser playback
with a Cloudstream device test.

Only MotChill binary was replaced; shared helper copies here are for this
standalone build, not instructions to replace other released providers.
