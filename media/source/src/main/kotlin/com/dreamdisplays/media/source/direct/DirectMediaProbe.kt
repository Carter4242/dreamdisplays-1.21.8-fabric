package com.dreamdisplays.media.source.direct

import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A single cheap HTTP round trip that answers everything the direct resolver needs to know about a user-pasted URL.
 */
internal object DirectMediaProbe {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val CONNECT_TIMEOUT_MS = 10_000L

    // Some file hosts queue parallel requests for well over ten seconds before the first byte; the
    // load is retried on a timeout anyway, so waiting a little longer here saves a whole round.
    private const val READ_TIMEOUT_MS = 20_000L
    private const val SNIFF_BYTES = 512
    private const val MIN_SNIFF_BYTES = 12
    private const val TS_PACKET_BYTES = 188

    /** What the server said about the URL.  makes a progressive file seekable. */
    data class Result(
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val acceptsRanges: Boolean,
        val fileName: String? = null,
        val verifiedByBytes: Boolean = false,
    ) {
        /** True when the MIME type names audio or video (or an HLS / DASH manifest type). */
        val isMediaType: Boolean
            get() {
                val type = contentType ?: return false
                return type.startsWith("video/") || type.startsWith("audio/") ||
                        type in MANIFEST_TYPES || type in TOLERATED_TYPES
            }

        /** True when the server answered with a web page, the classic "that link is not the file" case. */
        val isHtml: Boolean get() = contentType == "text/html" || contentType == "application/xhtml+xml"

        /** True when the MIME type names an audio container, so there is no picture to show. */
        val isAudioType: Boolean get() = contentType?.startsWith("audio/") == true
    }

    private val MANIFEST_TYPES = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
        "application/dash+xml",
    )

    private val TOLERATED_TYPES = setOf("application/octet-stream", "binary/octet-stream")

    /**
     * Why a probe produced no [Result]. [Unreachable] is a passing condition worth retrying (timeout,
     * dropped connection, 429 / 5xx, a redirect that has to be walked again); [Refused] is the server
     * permanently refusing the link (any other 4xx), so retrying cannot help.
     */
    sealed interface Failure {
        data class Unreachable(val reason: String) : Failure
        data class Refused(val status: Int) : Failure
    }

    /** A [Result], or the [Failure] that prevented one. */
    sealed interface Outcome {
        data class Ok(val result: Result) : Outcome
        data class Failed(val failure: Failure) : Outcome
    }

    /** Probes [url], or returns null when it could not be probed. See [probeOutcome] for the reason. */
    fun probe(url: String, requireBytes: Boolean = false): Result? =
        (probeOutcome(url, requireBytes) as? Outcome.Ok)?.result

    /**
     * Probes [url]. Never throws: a failed probe means "the direct path cannot answer for this URL
     * right now", and [Outcome.Failed] says whether that is likely to change.
     */
    fun probeOutcome(url: String, requireBytes: Boolean = false): Outcome {
        val head = head(url)
        (head as? Response.Ok)?.result?.takeIf { it.contentType != null || it.contentLength != null }?.let { base ->
            // A HEAD carries no body. Sniff when the declared type is not media or too generic to
            // tell a playlist from a file, and whenever the caller wants the container proven
            // rather than asserted.
            if (requireBytes || !base.isMediaType || base.contentType in TOLERATED_TYPES) {
                when (val sniff = sniff(url, base.contentLength)) {
                    is Sniff.Recognized -> return Outcome.Ok(base.copy(contentType = sniff.type, verifiedByBytes = true))
                    // The bytes could not be read this time: that says nothing about what the link
                    // serves, so it must not be reported as "not a video".
                    is Sniff.Unread -> return Outcome.Failed(Failure.Unreachable(sniff.reason))
                    Sniff.Unrecognized -> Unit
                }
            }
            return Outcome.Ok(base)
        }

        // The ranged GET both confirms range support and, crucially, already returns the leading
        // bytes — so the magic-byte sniff reads them from here instead of re-fetching the URL
        return when (val ranged = rangedGet(url)) {
            is Response.Ok -> {
                val body = ranged.body
                magicContentType(body)?.let { return Outcome.Ok(ranged.result.copy(contentType = it, verifiedByBytes = true)) }
                if (isShortRead(body, ranged.result.contentLength)) {
                    Outcome.Failed(Failure.Unreachable("only ${body.size} leading byte(s) arrived"))
                } else {
                    Outcome.Ok(ranged.result)
                }
            }
            // A HEAD that a server refuses outright while the GET merely failed still says
            // nothing final; only a refusal of the GET itself is a verdict.
            is Response.Status -> Outcome.Failed(failureFor(ranged.code))
            is Response.Error -> Outcome.Failed(Failure.Unreachable(ranged.reason))
        }
    }

    /** What the server did with one request. */
    private sealed interface Response {
        class Ok(val result: Result, val body: ByteArray = ByteArray(0)) : Response
        class Status(val code: Int) : Response
        class Error(val reason: String) : Response
    }

    private sealed interface Sniff {
        class Recognized(val type: String) : Sniff
        data object Unrecognized : Sniff
        class Unread(val reason: String) : Sniff
    }

    /** Classifies an HTTP status that ended a probe. Redirects count as passing: the walk is redone. */
    internal fun failureFor(status: Int): Failure =
        if (status in 400..499 && status != 408 && status != 425 && status != 429) Failure.Refused(status)
        else Failure.Unreachable("HTTP $status")

    /**
     * True when fewer leading bytes arrived than both the sniff window and the file hold: the read was
     * cut short, so an unrecognized result is no evidence about the container.
     */
    internal fun isShortRead(body: ByteArray, contentLength: Long?): Boolean {
        val expected = minOf(SNIFF_BYTES.toLong(), contentLength ?: SNIFF_BYTES.toLong())
        return body.size < MIN_SNIFF_BYTES && body.size < expected
    }

    private fun head(url: String): Response = runCatching<Response> {
        val response = DreamHttpClient.execute(url, requestOptions(url, "HEAD"))
        if (!response.isSuccessful) Response.Status(response.code) else Response.Ok(response.toResult())
    }.getOrElse {
        logger.debug("HEAD probe failed for {}: {}.", url.take(120), it.message)
        Response.Error(it.message ?: it.javaClass.simpleName)
    }

    private fun rangedGet(url: String): Response = runCatching<Response> {
        val response = DreamHttpClient.executeLimited(
            url,
            maxBytes = SNIFF_BYTES,
            options = requestOptions(url, "GET", range = "bytes=0-${SNIFF_BYTES - 1}"),
        )
        if (!response.isSuccessful) return@runCatching Response.Status(response.code)
        val contentRange = response.headerValue("content-range")
        val result = response.toResult(
            contentLengthOverride = contentRange?.substringAfter('/', "")?.toLongOrNull()
                ?: response.headerValue("content-length")?.toLongOrNull()?.takeIf { response.code != 206 },
            acceptsRangesOverride = response.code == 206 ||
                    response.headerValue("accept-ranges")?.contains("bytes", ignoreCase = true) == true,
        )
        Response.Ok(result, response.body)
    }.getOrElse {
        logger.debug("Ranged GET probe failed for {}: {}.", url.take(120), it.message)
        Response.Error(it.message ?: it.javaClass.simpleName)
    }

    private fun sniff(url: String, contentLength: Long?): Sniff = runCatching<Sniff> {
        val response = DreamHttpClient.executeLimited(
            url, maxBytes = SNIFF_BYTES, options = requestOptions(url, "GET", range = "bytes=0-${SNIFF_BYTES - 1}"),
        )
        when {
            response.isSuccessful -> magicContentType(response.body)?.let { Sniff.Recognized(it) }
                ?: if (isShortRead(response.body, contentLength)) Sniff.Unread("short read") else Sniff.Unrecognized
            // The HEAD already answered, so a GET the server turns down is about the range request,
            // not the link; keep the header verdict.
            failureFor(response.code) is Failure.Refused -> Sniff.Unrecognized
            else -> Sniff.Unread("HTTP ${response.code}")
        }
    }.getOrElse {
        logger.debug("Sniff failed for {}: {}.", url.take(120), it.message)
        Sniff.Unread(it.message ?: it.javaClass.simpleName)
    }

    /**
     * Maps a leading byte pattern to a content type for the container families players paste, or
     * null when the bytes are not a container this player can name. Covers every mainstream one,
     * because for a host nobody vouched for this is the only evidence that the link really is
     * media — a `Content-Type` header is just what that host chose to write.
     */
    internal fun magicContentType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun ascii(offset: Int, len: Int) = String(bytes, offset, len, StandardCharsets.US_ASCII)
        fun at(offset: Int, vararg pattern: Int) =
            pattern.withIndex().all { (i, b) -> bytes[offset + i] == b.toByte() }
        return when {
            ascii(4, 4) == "ftyp" -> "video/mp4"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            ascii(0, 3) == "FLV" -> "video/x-flv"
            ascii(0, 4) == "OggS" -> "video/ogg"
            ascii(0, 7) == "#EXTM3U" -> "application/vnd.apple.mpegurl"
            at(0, 0x52, 0x49, 0x46, 0x46) && ascii(8, 4) == "AVI " -> "video/x-msvideo"
            at(0, 0x30, 0x26, 0xB2, 0x75) -> "video/x-ms-asf"
            at(0, 0x00, 0x00, 0x01, 0xBA) -> "video/mpeg"
            isTransportStream(bytes) -> "video/mp2t"
            dashManifest(ascii(0, minOf(bytes.size, 64))) -> "application/dash+xml"
            else -> null
        }
    }

    private fun isTransportStream(bytes: ByteArray): Boolean =
        bytes.size > TS_PACKET_BYTES &&
                bytes[0] == 0x47.toByte() &&
                bytes[TS_PACKET_BYTES] == 0x47.toByte()

    private fun dashManifest(head: String): Boolean {
        val text = head.trimStart('﻿', ' ', '\n', '\r', '\t')
        return text.startsWith("<MPD", ignoreCase = true) ||
                (text.startsWith("<?xml") && head.contains("<MPD", ignoreCase = true))
    }

    private fun requestOptions(url: String, method: String, range: String? = null) = MediaHostGuard.guarded(
        url,
        DreamHttpClient.RequestOptions(
            method = method,
            headers = range?.let { DreamHttpClient.headersOf("Range" to it) } ?: emptyMap(),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            // The caller already redirect-resolved the URL through the SSRF guard; do not let OkHttp
            // silently follow a fresh redirect to an unvalidated (possibly internal) host.
            followRedirects = false,
        ),
    )

    private fun DreamHttpClient.HttpResponse.toResult(
        contentLengthOverride: Long? = headerValue("content-length")?.toLongOrNull(),
        acceptsRangesOverride: Boolean = headerValue("accept-ranges")?.contains("bytes", ignoreCase = true) == true,
    ) = Result(
        finalUrl = finalUrl,
        contentType = headerValue("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT),
        contentLength = contentLengthOverride,
        acceptsRanges = acceptsRangesOverride,
        fileName = fileNameFrom(headerValue("content-disposition")),
    )

    private fun fileNameFrom(disposition: String?): String? {
        if (disposition == null) return null
        // RFC 5987 extended form: filename*=UTF-8''percent%20encoded.mp4
        Regex("filename\\*=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1], StandardCharsets.UTF_8) }
                .getOrNull()?.takeIf { name -> name.isNotBlank() }
        }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return it.groupValues[1].trim().takeIf { name -> name.isNotBlank() }
        }
        return null
    }

    private fun DreamHttpClient.HttpResponse.headerValue(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}
