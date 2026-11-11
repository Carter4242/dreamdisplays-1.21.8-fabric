package support.buildinfo

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * The build id baked into client jars: the first 8 hex digits of the commit.
 *
 * - `GITHUB_SHA` set (GitHub Actions): that commit, as is (`1a2b3c4d`).
 * - otherwise local git HEAD with `-dev`, plus `-dirty` when tracked files differ (`1a2b3c4d-dev-dirty`).
 * - no git: `unknown`.
 *
 * A [ValueSource] so the configuration cache re-obtains it on every build instead of storing a stale hash.
 */
abstract class BuildIdValueSource : ValueSource<String, BuildIdValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repoDir: DirectoryProperty
        val githubSha: Property<String>
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String {
        parameters.githubSha.orNull?.trim()?.takeIf { HEX.matches(it) }?.let { return it.take(8).lowercase() }
        val head = git("rev-parse", "HEAD")?.trim()?.takeIf { HEX.matches(it) } ?: return "unknown"
        val dirty = git("status", "--porcelain", "--untracked-files=no")?.isNotBlank() ?: false
        return head.take(8).lowercase() + "-dev" + if (dirty) "-dirty" else ""
    }

    private fun git(vararg args: String): String? = runCatching {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            workingDir = parameters.repoDir.get().asFile
            commandLine(listOf("git") + args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) out.toString(Charsets.UTF_8) else null
    }.getOrNull()

    private companion object {
        val HEX = Regex("[0-9a-fA-F]{8,64}")
    }
}
