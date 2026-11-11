package com.dreamdisplays.media.source.youtube.binary

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpJsRuntimeTest {
    private val rt = YtDlpJsRuntime

    @Test
    fun `versions parse from each runtime's --version output`() {
        assertEquals([2, 9, 8], rt.parseVersion("deno", "deno 2.9.8 (stable, release, x86_64-pc-windows-msvc)\nv8 14.6\n"))
        assertEquals([24, 19, 0], rt.parseVersion("node", "v24.19.0\r\n"))
        assertEquals([1, 2, 19], rt.parseVersion("bun", "1.2.19\n"))
        assertNull(rt.parseVersion("deno", "'deno' is not recognized as an internal or external command"))
        assertNull(rt.parseVersion("node", ""))
    }

    @Test
    fun `only versions yt-dlp accepts are supported`() {
        assertTrue(rt.supported("deno", [2, 3, 0]))
        assertTrue(rt.supported("deno", [2, 9, 8]))
        assertFalse(rt.supported("deno", [2, 2, 12]))
        assertFalse(rt.supported("deno", [1, 46, 3]))
        assertTrue(rt.supported("node", [22, 0, 0]))
        assertFalse(rt.supported("node", [20, 18, 1]))
        assertTrue(rt.supported("bun", [1, 2, 11]))
        assertFalse(rt.supported("bun", [1, 2, 10]))
        assertFalse(rt.supported("qjs", [2025, 1, 1]))
    }

    @Test
    fun `deno beats node beats bun, and a too-old one is skipped and noted`() {
        val candidates = [
            "deno" to "/usr/bin/deno",
            "node" to "/usr/bin/node",
            "node" to "/opt/homebrew/bin/node",
            "bun" to "/home/a/.bun/bin/bun",
        ]
        val outputs = mapOf(
            "/usr/bin/deno" to "deno 2.1.4 (stable)",
            "/usr/bin/node" to "v20.11.0",
            "/opt/homebrew/bin/node" to "v22.12.0",
            "/home/a/.bun/bin/bun" to "1.2.19",
        )
        val tooOld = ArrayList<String>()
        val pick = rt.select(candidates, probe = { outputs[it] }, exists = { true }, tooOld = tooOld)
        assertEquals(YtDlpJsRuntime.Runtime("node", "/opt/homebrew/bin/node", "22.12.0", "system"), pick)
        assertEquals(["deno 2.1.4", "node 20.11.0"], tooOld)
    }

    @Test
    fun `missing files and runtimes that do not run are passed over`() {
        val candidates = ["deno" to "/a/deno", "deno" to "/b/deno", "bun" to "/c/bun"]
        val pick = rt.select(
            candidates,
            probe = { if (it == "/b/deno") null else "1.2.11" }, // /b/deno exists but fails to run
            exists = { it != "/a/deno" },
        )
        assertEquals("bun", pick?.name)
        assertNull(rt.select(candidates, probe = { null }, exists = { true }))
    }

    @Test
    fun `candidates cover PATH, then the usual install folders, in priority order`() {
        val home = Files.createTempDirectory("home")
        listOf("v20.1.0", "v22.3.0", "v9.0.0").forEach { Files.createDirectories(home.resolve(".nvm/versions/node/$it/bin")) }
        val list = rt.candidates(mapOf("PATH" to "/custom/bin:/usr/bin"), home.toString(), windows = false)
        val deno = list.filter { it.first == "deno" }.map { it.second }
        assertEquals("/custom/bin/deno", deno.first())
        assertTrue("$home/.deno/bin/deno" in deno)
        assertTrue("/opt/homebrew/bin/deno" in deno)
        val node = list.filter { it.first == "node" }.map { it.second }
        val nvm = node.filter { ".nvm" in it }
        assertEquals(["v22.3.0", "v20.1.0", "v9.0.0"].map { "$home/.nvm/versions/node/$it/bin/node" }, nvm)
        assertEquals(["deno", "node", "bun"], list.map { it.first }.distinct())
        assertEquals(list.size, list.map { it.second }.distinct().size)
    }

    @Test
    fun `windows candidates use exe names and the usual Windows folders`() {
        val env = mapOf(
            "Path" to "C:\\Windows\\system32;\"C:\\Tools\"",
            "LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local",
            "ProgramFiles" to "C:\\Program Files",
        )
        val list = rt.candidates(env, "C:\\Users\\a", windows = true)
        val paths = list.map { it.second }
        assertEquals("C:\\Windows\\system32\\deno.exe", paths.first())
        assertTrue("C:\\Tools\\node.exe" in paths)
        assertTrue("C:\\Users\\a\\.deno\\bin\\deno.exe" in paths)
        assertTrue("C:\\Users\\a\\AppData\\Local\\Microsoft\\WinGet\\Links\\deno.exe" in paths)
        assertTrue("C:\\Program Files\\nodejs\\node.exe" in paths)
        assertFalse("C:\\Program Files\\nodejs\\deno.exe" in paths)
    }

    @Test
    fun `the runtime reaches yt-dlp as name colon path`() {
        assertEquals(
            ["--js-runtimes", "node:C:\\Program Files\\nodejs\\node.exe"],
            rt.argsFor(YtDlpJsRuntime.Runtime("node", "C:\\Program Files\\nodejs\\node.exe", "24.19.0", "system")),
        )
        assertEquals(emptyList(), rt.argsFor(null))
    }

    @Test
    fun `deno assets exist for every platform deno publishes`() {
        assertEquals("deno-x86_64-pc-windows-msvc.zip", rt.denoAsset(windows = true, mac = false, arm64 = false, arm = false))
        assertEquals("deno-aarch64-pc-windows-msvc.zip", rt.denoAsset(windows = true, mac = false, arm64 = true, arm = true))
        assertEquals("deno-aarch64-apple-darwin.zip", rt.denoAsset(windows = false, mac = true, arm64 = true, arm = true))
        assertEquals("deno-x86_64-apple-darwin.zip", rt.denoAsset(windows = false, mac = true, arm64 = false, arm = false))
        assertEquals("deno-x86_64-unknown-linux-gnu.zip", rt.denoAsset(windows = false, mac = false, arm64 = false, arm = false))
        assertEquals("deno-aarch64-unknown-linux-gnu.zip", rt.denoAsset(windows = false, mac = false, arm64 = true, arm = true))
        assertNull(rt.denoAsset(windows = false, mac = false, arm64 = false, arm = true))
    }

    @Test
    fun `only a yt-dlp with --js-runtimes gets the flag`() {
        assertTrue(YtDlpBinary.takesJsRuntimes("2026.10.26"))
        assertTrue(YtDlpBinary.takesJsRuntimes("2025.11.12"))
        assertFalse(YtDlpBinary.takesJsRuntimes("2025.10.22"))
        assertFalse(YtDlpBinary.takesJsRuntimes("2024.12.23"))
        assertFalse(YtDlpBinary.takesJsRuntimes("failed (exit 1): boom"))
        assertFalse(YtDlpBinary.takesJsRuntimes(null))
    }
}
