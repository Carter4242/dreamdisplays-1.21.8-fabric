import support.buildinfo.BuildIdValueSource
import java.time.Instant
import java.time.temporal.ChronoUnit

// Bakes `dreamdisplays-build.properties` (commit + build time, see BuildIdValueSource) into this module's resources,
// read at runtime by com.dreamdisplays.util.BuildInfo. Applied to the client module every loader jar shades in.

val buildId = providers.of(BuildIdValueSource::class.java) {
    parameters.repoDir.set(rootProject.layout.projectDirectory)
    parameters.githubSha.set(providers.environmentVariable("GITHUB_SHA"))
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "Writes dreamdisplays-build.properties with the build's commit id and time."
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    val commit = buildId // local copy: task actions must not capture the script object
    inputs.property("commit", commit)
    outputs.dir(outDir)
    // The build time is part of the file, so it is rewritten on every build
    outputs.upToDateWhen { false }
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val builtAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        dir.resolve("dreamdisplays-build.properties").writeText(
            "commit=${commit.get()}\nbuiltAt=$builtAt\n",
            Charsets.UTF_8,
        )
    }
}

pluginManager.withPlugin("java") {
    the<SourceSetContainer>().named("main") { resources.srcDir(generateBuildInfo) }
}
