import support.buildinfo.CommitHistoryValueSource

// Bakes `dreamdisplays-commits.txt` (the commit graph behind this build, see CommitHistoryValueSource) into this
// module's resources; the server plugin reads it to tell which client builds are older than the current release.
// CI checkouts must fetch the full history (actions/checkout fetch-depth: 0).

val commitHistory = providers.of(CommitHistoryValueSource::class.java) {
    parameters.repoDir.set(rootProject.layout.projectDirectory)
}

val generateCommitHistory = tasks.register("generateCommitHistory") {
    description = "Writes dreamdisplays-commits.txt with the commit graph reachable from HEAD."
    val outDir = layout.buildDirectory.dir("generated/commithistory")
    val history = commitHistory // local copy: task actions must not capture the script object
    inputs.property("history", history)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val text = history.get()
        if (text.isBlank()) logger.warn("No git history found: the plugin can't tell older client builds by commit.")
        else if (text.startsWith("# shallow")) logger.warn("Shallow git checkout: client builds before its first commit can't be placed.")
        dir.resolve("dreamdisplays-commits.txt").writeText(text, Charsets.UTF_8)
    }
}

pluginManager.withPlugin("java") {
    the<SourceSetContainer>().named("main") { resources.srcDir(generateCommitHistory) }
}
