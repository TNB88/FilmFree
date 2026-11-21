YanHH3D v231 - 2026-11-21

Cause:
The current site uses streamrpt1.site player pages, including URLs ending in
.m3u8 which actually return HTML rather than a video playlist. The old resolver
recognizes only a fixed list of legacy hosts and misses these new players.

Fix:
- Resolve each episode URL and follow redirects to the current site.
- Read source URLs from #list_sv a[data-src], keeping the server labels.
- If a response is already an HLS playlist, use it directly.
- For the current player, read its public #player[data-obf] configuration and
  use pU, the server-issued plain/native HLS URL. Do not emit sU as plain HLS.
- Confirm the response is an HLS playlist before passing it to Cloudstream.
- Keep the player URL as Referer and preserve the relevant resolution labels.
- Skip an unavailable mirror without losing other working mirrors.
- Fall back to the existing ExtractorKt.invokeSource for legacy host types.
- Update domainyanhh3d.txt to the current canonical domain, yanhh3d.baby.

The original provider, catalog, episode handling and video interceptor remain
unchanged except for redirecting loadLinks to the new helper. No account,
activation, VIP or DRM mechanisms are changed. No signed playback URLs or
account credentials are included in these sources.

Build:
Kotlin 2.3.0, Android build-tools 37.0.0 D8, minimum API 21. Compile YanLinkFix.kt
against the Cloudstream API. An ExtractorKt.invokeSource declaration is needed
at compile time; it is provided by the original plugin at runtime. Do NOT ship
the compile-time ExtractorStub.kt implementation. Only the new YanLinkFix*
and YanPlayerData* classes are merged into the original plugin dex.
The original loadLinks invocation of ExtractorKt.invokeSource is redirected to
YanLinkFix.load, with the same five suspend/callback argument types. The old
ExtractorKt and all other original classes are retained. Plugin entry class
stays com.anhdaden.YanHH3DPlugin. Set manifest version to 231.

Checks:
- Four tests passed, none skipped.
- Relative/absolute server URLs, duplicate filtering and unsafe URL rejection.
- Native pU selection, malformed config handling, no accidental sU emission.
- Live tests freshly resolve an episode from Tien Nghich and Gia Thien, then
  obtain native HLS and successfully retrieve bytes from the first video segment.
- This verifies resolution and initial media availability, not long playback
  on every movie or device. Device UI playback still requires a separate test.

Publication:
Update only YanHH3DProvider's version, size, SHA-256 and immutable binary URL.
Keep all other FilmFree plugin entries unchanged. Preserve historical repo
manifests; update the active repo.json/repo-v2.json manifest cache marker only.
