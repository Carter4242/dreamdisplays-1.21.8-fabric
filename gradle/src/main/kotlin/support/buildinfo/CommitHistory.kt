package support.buildinfo

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * The commit graph behind the build, for the server plugin to tell whether a client's build is older than the current
 * release: one line per commit reachable from HEAD, newest first, `<commit> <parent>...` as 8-digit ids (the form
 * clients report). `# shallow` heads the file when the checkout is shallow (its oldest commits then look parentless);
 * empty without git.
 *
 * A [ValueSource] so the configuration cache re-obtains it on every build.
 */
abstract class CommitHistoryValueSource : ValueSource<String, CommitHistoryValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repoDir: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String {
        val log = git("rev-list", "--parents", "HEAD") ?: return ""
        val shallow = git("rev-parse", "--is-shallow-repository")?.trim() == "true"
        return buildString {
            if (shallow) append("# shallow\n")
            log.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                append(line.split(' ').filter { HEX.matches(it) }.joinToString(" ") { it.take(8).lowercase() })
                append('\n')
            }
        }
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
