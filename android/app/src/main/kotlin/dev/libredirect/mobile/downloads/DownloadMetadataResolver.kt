package dev.libredirect.mobile.downloads

import dev.libredirect.mobile.core.url.UrlParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

private const val MAX_URL_HINT_DEPTH = 2
private const val RFC5987_CHARSET_GROUP = 1
private const val RFC5987_ENCODED_GROUP = 3
private const val SNIFF_HEADER_BYTES = 64
private const val FTYP_OFFSET = 4
private const val FTYP_BRAND_OFFSET = 8
private const val FTYP_BRAND_LEN = 4
private const val MIN_FTYP_HEADER_BYTES = 12
private const val MIN_WEBP_HEADER_BYTES = 12
private const val WEBP_TAG_OFFSET = 8
private const val BYTE_MASK = 0xFF
private const val JPEG_BYTE_0 = 0xFF
private const val JPEG_BYTE_1 = 0xD8
private const val JPEG_BYTE_2 = 0xFF
private const val JPEG_MIN_BYTES = 3
private const val PNG_LEAD_BYTE = 0x89
private const val PNG_MIN_BYTES = 8
private const val EBML_BYTE_0 = 0x1A
private const val EBML_BYTE_1 = 0x45
private const val EBML_BYTE_2 = 0xDF
private const val EBML_BYTE_3 = 0xA3
private const val EBML_MIN_BYTES = 4

private val PLACEHOLDER_PROXY_STEMS =
    setOf(
        "download",
        "mediaproxy",
        "proxy",
        "imageproxy",
        "imgproxy",
        "vidproxy",
        "videoproxy",
        "dl",
        "get",
        "fetch",
        "stream",
        "media",
        "file",
        "attachment",
        "view",
        "raw",
    )

private val FILENAME_QUERY_KEYS =
    listOf("filename", "file_name", "name", "download", "title", "dl")

private val EMBEDDED_URL_QUERY_KEYS =
    listOf("url", "src", "media", "file", "target", "uri", "link", "href", "video", "img", "image", "u", "q")

private val MIME_QUERY_KEYS =
    listOf("mime", "mime_type", "content_type", "type")

private val EXT_QUERY_KEYS =
    listOf("ext", "format", "fmt")

private val HTML_MIME_TYPES =
    setOf("text/html", "application/xhtml+xml")

private val PROXY_ENDPOINT_EXTENSIONS =
    setOf("php", "asp", "aspx", "jsp", "cgi", "do", "action", "html", "htm")

private val RFC5987_FILENAME_REGEX =
    Regex(
        pattern = """(?:^|[;\s])filename\*\s*=\s*(?:([^']*)'[^']*')?("?)([^";\r\n]+)\2""",
        option = RegexOption.IGNORE_CASE,
    )

private val QUOTED_FILENAME_REGEX =
    Regex(
        pattern = """(?:^|[;\s])filename\s*=\s*"((?:[^"\\]|\\.)*)"""",
        option = RegexOption.IGNORE_CASE,
    )

private val UNQUOTED_FILENAME_REGEX =
    Regex(
        pattern = """(?:^|[;\s])filename\s*=\s*([^;\r\n]+)""",
        option = RegexOption.IGNORE_CASE,
    )

/**
 * Resolves the best initial filename and MIME type for an HTTP download
 * before network transfer starts, unwrapping filename/media hints hidden in
 * proxy query parameters (e.g. Kittygram `/mediaproxy?url=...mp4`).
 */
fun resolveInitialHttpDownloadMetadata(
    url: String,
    contentDisposition: String?,
    declaredMimeType: String?,
    platformGuess: String? = null,
): ResolvedDownloadMetadata {
    val dispositionName = parseContentDispositionFileName(contentDisposition)
    val urlHint = extractHintFromUrl(url)
    val cleanDeclared = normalizeMimeType(declaredMimeType)

    val effectiveMime =
        resolveInitialMime(
            cleanDeclared = cleanDeclared,
            dispositionName = dispositionName,
            urlHint = urlHint,
            platformGuess = platformGuess,
        )

    val urlLeaf = urlPathLeaf(url)
    val rawCandidate =
        when {
            dispositionName != null -> dispositionName
            urlHint?.fileName != null -> urlHint.fileName
            !urlLeaf.isNullOrBlank() -> urlLeaf
            else -> platformGuess ?: "download"
        }

    var finalName = sanitizeFileName(rawCandidate, effectiveMime)
    if (fileExtensionOf(finalName) == "bin" && !isGenericMimeType(effectiveMime)) {
        val ext = extensionFromMimeType(effectiveMime)
        if (!ext.isNullOrBlank() && ext != "bin") {
            finalName = sanitizeFileName(replaceFileExtension(finalName, ext), effectiveMime)
        }
    }
    return ResolvedDownloadMetadata(
        fileName = finalName,
        mimeType = guessMimeType(finalName, effectiveMime),
    )
}

/**
 * Upgrades placeholder `.bin` / extensionless filenames and generic MIME types
 * using authoritative HTTP response headers and optional container magic-byte
 * sniffing from the response payload.
 */
@Suppress("LongParameterList")
fun resolveServerDownloadMetadata(
    url: String,
    currentFileName: String,
    currentMimeType: String,
    contentDisposition: String?,
    serverContentType: String?,
    sniffedMimeType: String? = null,
): ResolvedDownloadMetadata {
    val currentExt = fileExtensionOf(currentFileName)
    val currentIsPlaceholderExt =
        currentExt.isEmpty() || (currentExt == "bin" && isGenericMimeType(currentMimeType))
    val dispositionName = parseContentDispositionFileName(contentDisposition)
    val usableServerMime =
        filterUsableServerMime(
            serverContentType = serverContentType,
            hasDispositionName = dispositionName != null,
        )
    val effectiveMime =
        pickServerMimeType(
            currentMimeType = currentMimeType,
            dispositionName = dispositionName,
            usableServerMime = usableServerMime,
            sniffedMimeType = sniffedMimeType,
        )

    var finalName = currentFileName
    if (dispositionName != null && isAutoPlaceholderName(url, currentFileName, currentIsPlaceholderExt)) {
        finalName = sanitizeFileName(dispositionName, effectiveMime)
    } else if (currentIsPlaceholderExt && !isGenericMimeType(effectiveMime)) {
        val resolvedExt = extensionFromMimeType(effectiveMime)
        if (!resolvedExt.isNullOrBlank() && resolvedExt != "bin") {
            finalName = sanitizeFileName(replaceFileExtension(currentFileName, resolvedExt), effectiveMime)
        }
    }
    return ResolvedDownloadMetadata(
        fileName = finalName,
        mimeType = effectiveMime,
    )
}

/** Parses RFC 6266 / RFC 5987 `filename*=` and `filename=` values from `Content-Disposition`. */
@Suppress("ReturnCount")
fun parseContentDispositionFileName(headerValue: String?): String? {
    val raw = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    RFC5987_FILENAME_REGEX.find(raw)?.let { match ->
        val charsetName = match.groupValues[RFC5987_CHARSET_GROUP].trim()
        val encoded = match.groupValues[RFC5987_ENCODED_GROUP].trim()
        val decoded = decodePercentPreservingPlus(encoded, charsetName)
        cleanDispositionLeaf(decoded)?.let { return it }
    }
    QUOTED_FILENAME_REGEX.find(raw)?.let { match ->
        val unescaped = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        cleanDispositionLeaf(unescaped)?.let { return it }
    }
    UNQUOTED_FILENAME_REGEX.find(raw)?.let { match ->
        cleanDispositionLeaf(match.groupValues[1])?.let { return it }
    }
    return null
}

/** Parses total byte size from an HTTP `Content-Range` header (e.g. `bytes 0-63/6426042`). */
fun parseContentRangeTotalBytes(contentRange: String?): Long? =
    contentRange
        ?.trim()
        ?.takeIf { it.contains('/') }
        ?.substringAfterLast('/')
        ?.trim()
        ?.takeIf { it != "*" }
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

/** Reads up to [maxBytes] leading bytes from [stream] without closing it. */
internal fun readLeadingStreamBytes(
    stream: InputStream,
    maxBytes: Int,
): ByteArray {
    val buffer = ByteArray(maxBytes)
    var total = 0
    while (total < maxBytes) {
        val count = stream.read(buffer, total, maxBytes - total)
        if (count <= 0) break
        total += count
    }
    return buffer.copyOf(total)
}

/** Sniffs common media and document MIME types from the beginning of a downloaded staging file. */
fun sniffMimeTypeFromFile(file: File): String? {
    if (!file.exists() || !file.isFile) return null
    return try {
        val header = file.inputStream().use { input -> readLeadingStreamBytes(input, SNIFF_HEADER_BYTES) }
        sniffMimeTypeFromBytes(header)
    } catch (_: IOException) {
        null
    }
}

/** Sniffs common container MIME types from leading bytes of a payload. */
@Suppress("ReturnCount")
fun sniffMimeTypeFromBytes(
    bytes: ByteArray,
    length: Int = bytes.size,
): String? {
    if (length <= 0) return null
    val safeLen = length.coerceAtMost(bytes.size)
    sniffIsoBaseMedia(bytes, safeLen)?.let { return it }
    sniffWebmOrMatroska(bytes, safeLen)?.let { return it }
    if (safeLen >= JPEG_MIN_BYTES && matchesBytes(bytes, JPEG_BYTE_0, JPEG_BYTE_1, JPEG_BYTE_2)) {
        return "image/jpeg"
    }
    if (
        safeLen >= PNG_MIN_BYTES &&
        (bytes[0].toInt() and BYTE_MASK) == PNG_LEAD_BYTE &&
        matchesAscii(bytes, safeLen, 1, "PNG\r\n\u001a\n")
    ) {
        return "image/png"
    }
    if (matchesAscii(bytes, safeLen, 0, "GIF87a") || matchesAscii(bytes, safeLen, 0, "GIF89a")) {
        return "image/gif"
    }
    if (
        safeLen >= MIN_WEBP_HEADER_BYTES &&
        matchesAscii(bytes, safeLen, 0, "RIFF") &&
        matchesAscii(bytes, safeLen, WEBP_TAG_OFFSET, "WEBP")
    ) {
        return "image/webp"
    }
    return sniffDocumentOrArchive(bytes, safeLen)
}

@Suppress("ReturnCount")
private fun resolveInitialMime(
    cleanDeclared: String?,
    dispositionName: String?,
    urlHint: UrlMediaHint?,
    platformGuess: String?,
): String {
    if (cleanDeclared != null && !isGenericMimeType(cleanDeclared)) {
        return cleanDeclared
    }
    if (dispositionName != null) {
        val guessed = guessMimeType(dispositionName, cleanDeclared)
        if (!isGenericMimeType(guessed)) return guessed
    }
    val hintMime = urlHint?.mimeType
    if (hintMime != null && !isGenericMimeType(hintMime)) {
        return hintMime
    }
    if (urlHint?.fileName != null) {
        val guessed = guessMimeType(urlHint.fileName, cleanDeclared)
        if (!isGenericMimeType(guessed)) return guessed
    }
    val platformExt = platformGuess?.let(::fileExtensionOf).orEmpty()
    if (platformExt.isNotEmpty() && platformExt != "bin") {
        val guessed = guessMimeType(platformGuess.orEmpty(), cleanDeclared)
        if (!isGenericMimeType(guessed)) return guessed
    }
    return "application/octet-stream"
}

@Suppress("ReturnCount")
private fun pickServerMimeType(
    currentMimeType: String,
    dispositionName: String?,
    usableServerMime: String?,
    sniffedMimeType: String?,
): String {
    if (!isGenericMimeType(currentMimeType)) {
        return normalizeMimeType(currentMimeType) ?: currentMimeType
    }
    if (dispositionName != null) {
        val fromDisposition = guessMimeType(dispositionName, usableServerMime)
        if (!isGenericMimeType(fromDisposition)) return fromDisposition
    }
    if (usableServerMime != null) {
        return usableServerMime
    }
    val cleanSniffed = normalizeMimeType(sniffedMimeType)
    if (cleanSniffed != null && !isGenericMimeType(cleanSniffed)) {
        return cleanSniffed
    }
    return "application/octet-stream"
}

private fun filterUsableServerMime(
    serverContentType: String?,
    hasDispositionName: Boolean,
): String? =
    normalizeMimeType(serverContentType)
        ?.takeIf { clean ->
            !isGenericMimeType(clean) && (clean !in HTML_MIME_TYPES || hasDispositionName)
        }

private fun isAutoPlaceholderName(
    url: String,
    currentFileName: String,
    currentIsPlaceholderExt: Boolean,
): Boolean {
    if (!currentIsPlaceholderExt) return false
    val stem = fileStemOf(currentFileName).lowercase(Locale.US)
    val urlLeafStem = urlPathLeaf(url)?.let(::fileStemOf)?.lowercase(Locale.US).orEmpty()
    return stem in PLACEHOLDER_PROXY_STEMS || (urlLeafStem.isNotEmpty() && stem == urlLeafStem)
}

private fun extractHintFromUrl(
    url: String,
    depth: Int = 0,
): UrlMediaHint? {
    val direct = directLeafHint(url, depth)
    return when {
        direct != null -> direct
        depth >= MAX_URL_HINT_DEPTH -> null
        else -> {
            val queryPairs = parseUrlQueryPairs(url)
            val leaf = urlPathLeaf(url)
            extractFilenameParamHint(queryPairs, leaf)
                ?: extractEmbeddedUrlHint(queryPairs, depth)
                ?: extractFormatParamHint(queryPairs, leaf)
        }
    }
}

@Suppress("ReturnCount")
private fun directLeafHint(
    url: String,
    depth: Int,
): UrlMediaHint? {
    val leaf = urlPathLeaf(url) ?: return null
    val leafExt = fileExtensionOf(leaf)
    if (leafExt.isEmpty()) return null
    if (leafExt == "bin" && depth > 0) return null
    if (depth == 0 && leafExt in PROXY_ENDPOINT_EXTENSIONS && hasProxyParams(url)) return null
    val leafMime = guessMimeType(leaf, null)
    val isDirectBin = leafExt == "bin" && !hasProxyParams(url)
    if (isGenericMimeType(leafMime) && !isDirectBin) return null
    return UrlMediaHint(fileName = leaf, mimeType = leafMime)
}

private fun extractFilenameParamHint(
    queryPairs: Map<String, String>,
    outerLeaf: String?,
): UrlMediaHint? {
    val cleanedLeaf =
        FILENAME_QUERY_KEYS
            .firstNotNullOfOrNull { key -> queryPairs[key]?.takeIf { it.isNotBlank() } }
            ?.let(::cleanDispositionLeaf)
    val ext =
        cleanedLeaf
            ?.let(::fileExtensionOf)
            ?.takeIf { it.isNotEmpty() && it != "bin" }
    return if (cleanedLeaf != null && ext != null) {
        val name = if (cleanedLeaf.contains('.') || outerLeaf.isNullOrBlank()) cleanedLeaf else "$outerLeaf.$ext"
        UrlMediaHint(fileName = name, mimeType = guessMimeType(name, null))
    } else {
        null
    }
}

private fun extractEmbeddedUrlHint(
    queryPairs: Map<String, String>,
    depth: Int,
): UrlMediaHint? =
    EMBEDDED_URL_QUERY_KEYS
        .asSequence()
        .mapNotNull { key -> queryPairs[key]?.trim()?.takeIf { it.isNotEmpty() } }
        .mapNotNull(::unwrapNestedUrlValue)
        .mapNotNull { nestedUrl -> extractHintFromUrl(nestedUrl, depth + 1) }
        .firstOrNull()

private fun extractFormatParamHint(
    queryPairs: Map<String, String>,
    outerLeaf: String?,
): UrlMediaHint? {
    val stem = outerLeaf?.let(::fileStemOf)?.ifEmpty { null } ?: "download"
    val mimeHint =
        MIME_QUERY_KEYS
            .asSequence()
            .mapNotNull { key -> normalizeMimeType(queryPairs[key]) }
            .mapNotNull { mimeVal ->
                extensionFromMimeType(mimeVal)?.let { ext -> UrlMediaHint("$stem.$ext", mimeVal) }
            }.firstOrNull()
    val extHint =
        EXT_QUERY_KEYS
            .asSequence()
            .map { key -> queryPairs[key]?.trim()?.trimStart('.')?.lowercase(Locale.US).orEmpty() }
            .filter { it.isNotEmpty() && it != "bin" }
            .mapNotNull { extVal ->
                val candidateName = "$stem.$extVal"
                val guessedMime = guessMimeType(candidateName, null)
                guessedMime.takeIf { !isGenericMimeType(it) }?.let { UrlMediaHint(candidateName, it) }
            }.firstOrNull()
    return mimeHint ?: extHint
}

private fun unwrapNestedUrlValue(rawValue: String): String? {
    var candidate = rawValue
    var step = 0
    var resolved: String? = null
    while (step < MAX_URL_HINT_DEPTH && resolved == null) {
        if (isHttpUrl(candidate)) {
            resolved = candidate
        } else if (candidate.startsWith("/") && candidate.contains('.')) {
            resolved = "https://nested.local$candidate"
        } else {
            val decoded = decodeQueryLenient(candidate)
            if (decoded == null || decoded == candidate) {
                step = MAX_URL_HINT_DEPTH
            } else {
                candidate = decoded
                step++
            }
        }
    }
    return resolved ?: candidate.takeIf(::isHttpUrl)
}

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

private fun hasProxyParams(url: String): Boolean {
    val pairs = parseUrlQueryPairs(url)
    return EMBEDDED_URL_QUERY_KEYS.any { pairs.containsKey(it) } ||
        FILENAME_QUERY_KEYS.any { pairs.containsKey(it) }
}

private fun parseUrlQueryPairs(url: String): Map<String, String> {
    val rawQuery =
        UrlParser.parse(url)?.rawQuery
            ?: url.substringAfter('?', "").substringBefore('#')
    if (rawQuery.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    rawQuery
        .split('&')
        .asSequence()
        .filter { it.isNotEmpty() }
        .forEach { part ->
            val eq = part.indexOf('=')
            val rawKey = if (eq >= 0) part.substring(0, eq) else part
            val rawVal = if (eq >= 0) part.substring(eq + 1) else ""
            val key = decodeQueryLenient(rawKey)?.lowercase(Locale.US).orEmpty()
            val decodedVal = decodeQueryLenient(rawVal) ?: rawVal
            if (key.isNotEmpty() && !result.containsKey(key)) {
                result[key] = decodedVal
            }
        }
    return result
}

private fun urlPathLeaf(url: String): String? {
    val rawPath =
        UrlParser.parse(url)?.rawPath
            ?: url.substringBefore('?').substringBefore('#')
    val rawLeaf = rawPath.trimEnd('/').substringAfterLast('/', "").trim()
    if (rawLeaf.isEmpty()) return null
    val decoded = decodePercentPreservingPlus(rawLeaf, "").trim()
    return decoded.takeIf { it.isNotEmpty() && it != "." && it != ".." }
}

private fun fileStemOf(fileName: String): String {
    val trimmed = fileName.trim()
    val dot = trimmed.lastIndexOf('.')
    return if (dot > 0) trimmed.substring(0, dot) else trimmed
}

private fun cleanDispositionLeaf(raw: String): String? {
    val cleaned =
        raw
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }
}

private fun decodePercentPreservingPlus(
    encoded: String,
    charsetName: String,
): String =
    try {
        val encodingName =
            if (charsetName.isNotBlank() && Charset.isSupported(charsetName)) {
                Charset.forName(charsetName).name()
            } else {
                Charsets.UTF_8.name()
            }
        URLDecoder.decode(encoded.replace("+", "%2B"), encodingName)
    } catch (_: Exception) {
        encoded
    }

private fun decodeQueryLenient(value: String): String? =
    try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

@Suppress("ReturnCount")
private fun sniffIsoBaseMedia(
    bytes: ByteArray,
    length: Int,
): String? {
    if (length < MIN_FTYP_HEADER_BYTES || !matchesAscii(bytes, length, FTYP_OFFSET, "ftyp")) {
        return null
    }
    val brand = String(bytes, FTYP_BRAND_OFFSET, FTYP_BRAND_LEN, Charsets.US_ASCII)
    return when (brand) {
        "M4A " -> "audio/mp4"
        "qt  " -> "video/quicktime"
        "avif", "avis" -> "image/avif"
        else -> "video/mp4"
    }
}

@Suppress("ReturnCount")
private fun sniffWebmOrMatroska(
    bytes: ByteArray,
    length: Int,
): String? {
    if (length < EBML_MIN_BYTES || !matchesBytes(bytes, EBML_BYTE_0, EBML_BYTE_1, EBML_BYTE_2, EBML_BYTE_3)) {
        return null
    }
    val headerText = String(bytes, 0, length, Charsets.ISO_8859_1).lowercase(Locale.US)
    return when {
        headerText.contains("matroska") -> "video/x-matroska"
        headerText.contains("webm") -> "video/webm"
        else -> "video/webm"
    }
}

private fun sniffDocumentOrArchive(
    bytes: ByteArray,
    length: Int,
): String? =
    when {
        matchesAscii(bytes, length, 0, "%PDF-") -> "application/pdf"
        matchesAscii(bytes, length, 0, "OggS") -> "audio/ogg"
        matchesAscii(bytes, length, 0, "ID3") -> "audio/mpeg"
        matchesAscii(bytes, length, 0, "PK\u0003\u0004") -> "application/zip"
        else -> null
    }

private fun matchesBytes(
    bytes: ByteArray,
    vararg expected: Int,
): Boolean =
    bytes.size >= expected.size &&
        expected.indices.all { i -> (bytes[i].toInt() and BYTE_MASK) == expected[i] }

private fun matchesAscii(
    bytes: ByteArray,
    length: Int,
    offset: Int,
    ascii: String,
): Boolean =
    offset >= 0 &&
        offset + ascii.length <= length &&
        offset + ascii.length <= bytes.size &&
        ascii.indices.all { i -> (bytes[offset + i].toInt() and BYTE_MASK) == ascii[i].code }
