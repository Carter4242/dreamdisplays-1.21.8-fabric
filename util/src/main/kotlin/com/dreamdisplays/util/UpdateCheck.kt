package com.dreamdisplays.util

import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.coroutines.launch
import org.semver4j.Semver
import org.slf4j.LoggerFactory

/** Checks mod updates against the latest stable release of this build's own repository (not upstream's). **/
object UpdateCheck {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val REPO = "Carter4242/dreamdisplays-1.21.8-fabric"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** Where the update arrow sends the player: this repository's newest release (its jars). */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    @Volatile
    private var checked = false

    @Volatile
    private var latestVersion: String? = null

    fun shouldShowArrow(): Boolean {
        if (isPreRelease(GeneralUtil.getModVersion())) return false
        if (!checked) startCheck()
        val latest = latestVersion ?: return false
        return compareVersions(latest, GeneralUtil.getModVersion()) > 0
    }

    fun isPreRelease(version: String): Boolean =
        version.contains("-dev", ignoreCase = true) || version.contains("-preview", ignoreCase = true)

    @Synchronized
    private fun startCheck() {
        if (checked) return
        checked = true
        DreamCoroutines.clientIo.launch { doCheck() }
    }

    private fun doCheck() {
        runCatching {
            val body = DreamHttpClient.readText(
                API,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "User-Agent" to
                                "DreamDisplays/${GeneralUtil.getModVersion()} (+github.com/$REPO)",
                        "Accept" to "application/vnd.github+json",
                    ),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 8_000,
                ),
            )
            val root = DreamJson.compact.parseToJsonElement(body)
            val rawTag: String = when {
                root.asJsonObjectOrNull() != null -> {
                    val obj = root.asJsonObjectOrNull()!!
                    obj.optString("tag_name") ?: obj.optString("name")
                }

                root.asJsonArrayOrNull() != null -> {
                    val arr = root.asJsonArrayOrNull()!!
                    arr.firstOrNull().asJsonObjectOrNull()?.optString("tag_name")
                }

                else -> null
            } ?: return
            latestVersion = releaseVersion(rawTag) ?: return
        }.onFailure { e ->
            logger.warn("Update check failed: ${e.message}")
        }
    }

    /** The mod version a release tag names: `v1.10.9-mc1.21.8` is `1.10.9`; null when it names none. */
    internal fun releaseVersion(tag: String): String? =
        Regex("""^[vV]?(\d+(?:\.\d+){0,2})(?:$|[-+])""").find(tag.trim())?.groupValues?.get(1)

    internal fun compareVersions(a: String, b: String): Int {
        val av = Semver.coerce(a) ?: return a.compareTo(b)
        val bv = Semver.coerce(b) ?: return a.compareTo(b)
        return av.compareTo(bv)
    }
}
