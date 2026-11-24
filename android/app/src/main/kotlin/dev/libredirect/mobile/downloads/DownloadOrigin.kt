package dev.libredirect.mobile.downloads

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

internal data class DownloadOrigin(val url: String)

internal fun sameDownloadOrigin(
    first: String,
    second: String,
): Boolean {
    val source = first.toHttpUrlOrNull()
    val target = second.toHttpUrlOrNull()
    if (source == null || target == null) return false
    return source.scheme == target.scheme && source.host == target.host && source.port == target.port
}

private val contentRangePattern = Regex("""bytes (\d+)-(\d+)/(\d+)""")
private const val PARTIAL_CONTENT = 206
private const val UNSATISFIABLE_RANGE = 416

internal fun downloadValidator(response: Response): String? =
    response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
        ?: response.header("Last-Modified")

internal fun shouldRestartTransfer(
    response: Response,
    initial: DownloadItem,
    offset: Long,
): Boolean =
    when (response.code) {
        UNSATISFIABLE_RANGE -> true
        PARTIAL_CONTENT ->
            !validContentRange(response, offset, initial.totalBytes) ||
                initial.resumeUrl != response.request.url.toString() ||
                initial.resumeValidator != downloadValidator(response)
        else -> false
    }

@Suppress("ReturnCount")
private fun validContentRange(
    response: Response,
    offset: Long,
    knownTotal: Long,
): Boolean {
    val range = response.header("Content-Range")?.let(contentRangePattern::matchEntire) ?: return false
    val (first, last, size) = range.destructured
    val total = size.toLongOrNull() ?: return false
    val length = response.body?.contentLength() ?: -1L
    return first.toLongOrNull() == offset && offset < total && last.toLongOrNull() == total - 1 &&
        (knownTotal < 0L || total == knownTotal) && (length < 0L || length == total - offset)
}
