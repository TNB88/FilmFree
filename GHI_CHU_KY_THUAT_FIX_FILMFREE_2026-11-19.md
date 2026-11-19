# Ghi chú sửa plugin FilmFree ngày 19 tháng 11 năm 2026

Bản sửa gồm MotChill v216, BluPhim v251, KKPhim v217 và Nguồn C v231 trong `unlocked_gitlab`. Giữ nguyên tên đăng ký và lớp plugin để Cloudstream nhận cập nhật. Không sửa APK Phim4K, tài khoản, TorBox hay các provider khác trong kho.

## Máy chủ và nguyên nhân

| Plugin | Cấu hình GitHub | Máy chủ mặc định | Thay đổi chính |
| --- | --- | --- | --- |
| MotChill v216 | `domainmotchill.txt` | `https://motchill.show` | Dùng đúng tên miền người dùng xác nhận. Bỏ parser WordPress cũ, dùng API của trang mới. |
| BluPhim v251 | `domainbluphim.txt` | `https://bluphim.cam` | Tên miền `.media` chuyển cả đường dẫn phim về trang chủ. Đọc cấu trúc thẻ phim, tập và nguồn phát hiện tại. |
| KKPhim v217 | `domainkkphim.txt` | `https://phimapi.com` | Chuẩn hóa địa chỉ API, tách endpoint danh sách và chi tiết, đọc JSON linh hoạt và lấy HLS trực tiếp. |
| Nguồn C v231 | `domainnguonc.txt` | `https://phim.nguonc.com/api` | Cấu hình cũ trỏ vào HTML của `nguonphim1.site`, không phải API. Khôi phục API riêng của nguồn C và hỗ trợ bootstrap HLS mới. |

Các địa chỉ từ GitHub được đọc có thời hạn lưu 5 phút. KKPhim và nguồn C có máy chủ mặc định dự phòng khi cấu hình lỗi, trả HTML hoặc không đúng cấu trúc JSON. MotChill và BluPhim cũng có địa chỉ dự phòng khi tên miền từ GitHub không tương thích. Không tự đổi phim của nguồn C sang KKPhim.

GET có tối đa một lần thử lại khi lỗi mạng, 429 hoặc 500/502/503/504. Tôn trọng `Retry-After`; không lặp khi máy chủ yêu cầu chờ quá 3 giây và không coi 403 là lỗi tạm thời để gửi lặp. Một lần kiểm tra lặp lại gặp 503 ở embed MotChill; lần kiểm tra cuối đã qua bước phát sau xử lý lỗi tạm thời.

## MotChill

API cùng tên miền, không cần tài khoản:

- `/api/hub/movies?page=1&page_size=36&sort=newest` cho phim mới.
- `/api/hub/movies?movie_type=phim-le&page=1&page_size=36` cho phim lẻ; đổi thành `phim-bo` hoặc `phim-chieu-rap` cho các danh sách tương ứng.
- `/api/hub/movies?genre=hoat-hinh&page=1&page_size=36` cho hoạt hình.
- `/api/hub/search?q=...&page=1&page_size=40` cho tìm kiếm, mã hóa từ khóa UTF-8.
- `/api/hub/movies/{slug}` trả thông tin phim và `raw_movie_id`.
- `/api/hub/movies/{raw_movie_id}/episodes` trả tập và các nguồn ngôn ngữ.

Không dùng `/danh-sach/phim-le`: trang này chỉ chứa một danh sách tuyển chọn ngắn. API kho phim lẻ trả 36 mục mỗi trang, có `total`, `page`, `page_size`; trang 1 và 2 đã được kiểm tra khác nhau. Phim bộ có danh sách riêng.

Dữ liệu phát lưu `movieSlug` và `episodeSlug`, không lưu lâu URL ký có `ts` và `sig`. Khi bấm phát, plugin đọc lại danh sách tập để lấy embed mới, đọc `window.__EMBED_DATA__`, rồi sử dụng endpoint native HLS do player cung cấp. Giữ User-Agent và Referer nhất quán. Không tạo token hoặc kéo dài hạn token của máy chủ.

## KKPhim

`domainkkphim.txt` vẫn có thể chứa `https://phimapi.com/v1/api`; plugin chuẩn hóa về origin, tránh ghép lặp `/v1/api`.

- Danh sách và tìm kiếm: `/v1/api/danh-sach/...`, `/v1/api/quoc-gia/...`, `/v1/api/tim-kiem?keyword=...`.
- Mới cập nhật: `/danh-sach/phim-moi-cap-nhat?page=...`.
- Chi tiết: `/phim/{slug}`, không nằm dưới `/v1/api`.
- Đọc được `items` ở gốc hoặc `data.items`, trạng thái boolean hoặc chuỗi.
- Ưu tiên `link_m3u8`; giữ tên các máy chủ Vietsub, Thuyết Minh, Lồng Tiếng do API thật trả về.
- Gom cùng số tập giữa các máy chủ, sắp số tập và giữ từng nguồn ngôn ngữ. Không biến phim lẻ `Full (1/1)` thành phim bộ.
- Ảnh tương đối dùng CDN API cung cấp, không ghép trùng `uploads/movies`.

## Nguồn C

API riêng:

- `/api/films/phim-moi-cap-nhat?page=...`.
- `/api/films/danh-sach/phim-le` và `/api/films/danh-sach/phim-bo`.
- `/api/films/search?keyword=...&page=...`.
- `/api/film/{slug}`; tập nằm trong `movie.episodes[].items[]`.

Nguồn C không có route quốc gia `au-my`, route này trả 404. Thay mục đó bằng Phim Mỹ (`my`) và Phim Anh (`anh`), không gắn nhãn Âu Mỹ cho một quốc gia duy nhất. Ưu tiên poster WebP gốc, bỏ phụ thuộc proxy ảnh bên ngoài.

Player `streamc.xyz` hiện trả cấu hình `stream-bootstrap`. Plugin thực hiện POST bootstrap JSON theo giao thức native của trang, yêu cầu `playlist_format=hls`, rồi dùng `preissued.playlist`. Không dùng cách GET API bootstrap cũ và không chỉ nối `.m3u8` vào URL. Các bước xác minh không được giả lập: nếu máy chủ yêu cầu Turnstile mà chưa cấp quyền phát, plugin báo đúng tình trạng đó.

Giữ cơ chế xử lý Cloudflare có sẵn của Cloudstream cho player và đường phát nguồn C. Trong kiểm tra trên PC, API, bootstrap và playlist trả 200, nhưng các CDN đoạn video như `phili18.amass15.top` và `hihihoho` trả **403 Attention Required Cloudflare**. Đây là giới hạn còn lại cần kiểm tra bằng Cloudstream trên thiết bị thật; chưa xác nhận nguồn C phát hoàn chỉnh. Chuyển API không tự xóa được giới hạn của CDN.

## BluPhim

Đọc thẻ `.movie-card`, `.movie-card-2`, `.movie-card-3`; lấy ảnh gốc hoặc ảnh lazy-load. Đọc toàn bộ `.block_episode` và `.episodes-grid`, giữ nhóm máy chủ và số tập. Đọc đầy đủ mảng `all_sources` thay vì lấy URL đầu tiên bằng cắt chuỗi; xử lý JSON escape và phụ đề `all_subtitles` hoặc thẻ `track`. Không tự sửa thiết lập DNS toàn ứng dụng như plugin cũ.

## Build và kiểm tra

Source được giữ tại workspace Codex, thư mục `build/filmfree-repair-20261119/source`. Source dùng chung có `Protocol`, `ApiProvider`, `MotProtocol`, `WebProtocol`, `RemoteDomain`, `StreamResolver`; mỗi plugin giữ entry class cũ.

Build dùng Gradle 8.12, Kotlin 2.3.0, Android API 35, min API 21, Jackson 2.13.1, NiceHttp 0.4.13. Dùng D8 của Android build-tools 37.0.0 để tránh cảnh báo ghi lại metadata Kotlin 2.3 của D8 cũ. File `.cs3` chứa dex và manifest, không đóng gói lại thư viện của Cloudstream.

Lệnh kiểm tra: `gradlew.bat :KKPhimProvider:testDebugUnitTest -Plive=true`. Bộ kiểm tra chung bao gồm cả KRX được sửa cùng đợt: **38 ca, 37 đạt, 1 bỏ qua ở bước CDN nguồn C do Cloudflare**. Bốn phép kiểm tra phát còn lại đã đọc được playlist và đoạn video đầu: MotChill, KKPhim, BluPhim, KRX. Kiểm tra thêm **22 endpoint danh sách** của MotChill, KKPhim và nguồn C đều trả JSON hợp lệ với mục phim.

Các kiểm tra HTTP không thay cho thử remote, giao diện hoặc xem dài trên thiết bị. Không có thiết bị ADB kết nối ở thời điểm kiểm tra này.

## Cập nhật và bảo trì

Đồng bộ version nhúng, `fileSize` và SHA-256 trong `plugins.json`. URL tải gói trỏ vào commit chứa đúng binary để tránh nhận nhầm bản cũ từ bộ nhớ đệm. `repo.json` và `repo-v2.json` vẫn đọc manifest nhánh `main`; không sửa `repo-v226.json` vì đó là bản kiểm thử lịch sử cố định.

Trong Cloudstream, làm mới kho rồi cập nhật bốn plugin. Nếu gói vẫn hiện version cũ, tải lại metadata kho trước. Tên miền GitHub chỉ thay được máy chủ có **cùng giao thức/API tương thích**; không bảo đảm một trang khác cùng tên sẽ đọc được bằng parser này.

Giữ lại bản gốc và source tại `build/filmfree-repair-20261119` để tra cứu. Các ghi chú lịch sử và provider ngoài phạm vi được giữ nguyên.
