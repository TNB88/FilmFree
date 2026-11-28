import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

version = 218

cloudstream {
    description = "MotChill: remote GitHub, theo tên miền API mới, dự phòng khi máy chủ cấu hình lỗi; làm mới link phát."
    authors = listOf("TNB88")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    requiresResources = false
    language = "vi"
    iconUrl = "https://raw.githubusercontent.com/TNB88/FilmFree/refs/heads/main/icon/motchill.ico"
}

// Keep the runtime manifest explicit: the cached Cloudstream build plugin can
// report generateManifest NO-SOURCE for dex-only providers.
tasks.named("make") {
    doLast {
        val dex = layout.buildDirectory.file("intermediates/classes.dex").get().asFile
        require(dex.isFile && dex.length() > 0) { "Missing compiled classes.dex" }
        val output = layout.buildDirectory.file("MotChillProvider.cs3").get().asFile
        val manifest = """{"requiresResources":false,"version":218,"pluginClassName":"com.anhdaden.MotChillPlugin","name":"MotChillProvider"}"""
        ZipOutputStream(output.outputStream()).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest.toByteArray(Charsets.UTF_8), "classes.dex" to dex.readBytes())) {
                val entry = ZipEntry(name)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        ZipFile(output).use { zip ->
            require(zip.getEntry("manifest.json") != null && zip.getEntry("classes.dex") != null)
        }
    }
}
