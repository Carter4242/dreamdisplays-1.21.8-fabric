package com.dreamdisplays.core.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sender: its permission nodes (null = every node) and whether it is a player. */
internal data class FakeSource(val perms: Set<String>? = null, val player: Boolean = true)

/** Records what the tree ran and said; texts are the raw `en.json` templates with `{n}` filled in. */
internal class FakePlatform : CommandPlatform<FakeSource> {
    val sent = mutableListOf<ChatLine>()
    val suggestedFor = mutableListOf<String>()

    override fun has(source: FakeSource, permission: String) = source.perms?.contains(permission) ?: true
    override fun isPlayer(source: FakeSource) = source.player
    override fun text(source: FakeSource, key: String, vararg args: String): String {
        var t = Lang.en[key] ?: key
        args.forEachIndexed { i, a -> t = t.replace("{$i}", a) }
        return t
    }

    override fun send(source: FakeSource, lines: List<ChatLine>) {
        sent += lines
    }

    override fun suggest(source: FakeSource, request: SuggestRequest<FakeSource>): List<Suggestion> {
        suggestedFor += request.name
        return when (request.kind) {
            Kind.DISPLAY -> listOf(Suggestion("this"), Suggestion("lobby"), Suggestion("stage"))
            else -> when (request.name) {
                "players" -> listOf(Suggestion("Alex"), Suggestion("Bubs"))
                "x" -> listOf(Suggestion("12.5"))
                else -> emptyList()
            }
        }.startingWith(request.typed.substringAfterLast(','))
    }

    fun texts(): List<String> = sent.map { it.text.replace(Regex("&[0-9a-fk-or]"), "") }
}

class DisplayCommandTreeTest {
    private val platform = FakePlatform()
    private val ran = mutableListOf<Call<FakeSource>>()
    private val tree = DisplayCommandTree(
        platform,
        DisplayCommands.ALL.filter { !it.helpOnly && it.id != "help" }.associate { it.id to Handler<FakeSource> { c -> ran += c } },
    )
    private val op = FakeSource()
    private val console = FakeSource(player = false)

    private fun run(input: String, source: FakeSource = op): Call<FakeSource>? {
        ran.clear()
        platform.sent.clear()
        tree.execute(source, input)
        return ran.singleOrNull()
    }

    private fun ranAs(input: String, id: String, vararg values: Pair<String, String>, source: FakeSource = op) {
        val call = run(input, source)
        assertEquals(id, call?.def?.id, "'$input' -> ${platform.texts()}")
        assertEquals(values.toMap(), call!!.values, "'$input' values")
    }

    @Test
    fun everyCommandParsesWithArguments() {
        ranAs("create", "create")
        ranAs("delete", "delete")
        ranAs("delete lobby", "delete", "display" to "lobby")
        ranAs("info 619a9ce2", "info", "display" to "619a9ce2")
        ranAs("name", "name")
        ranAs("name lobby stage-2", "name", "display" to "lobby", "name" to "stage-2")
        ranAs("list", "list")
        ranAs("list 2", "list", "page" to "2")
        ranAs("list mine 3", "list.mine", "page" to "3")
        ranAs("list world world_nether", "list.world", "world" to "world_nether")
        ranAs("list owner Bubs 2", "list.owner", "owner" to "Bubs", "page" to "2")
        ranAs("list mode synced", "list.mode", "mode" to "synced")
        ranAs("video lobby https://youtu.be/x en", "video", "display" to "lobby", "url" to "https://youtu.be/x", "lang" to "en")
        ranAs("video this clear", "video", "display" to "this", "url" to "clear")
        ranAs("speed", "speed")
        ranAs("speed lobby 1.5", "speed", "display" to "lobby", "rate" to "1.5")
        ranAs("mode lobby broadcast", "mode", "display" to "lobby", "mode" to "broadcast")
        ranAs("stats", "stats")
        ranAs("stats all", "stats", "display" to "all")
        ranAs("schedule show", "schedule.show")
        ranAs("schedule play lobby 18:30", "schedule.play", "display" to "lobby", "time" to "18:30")
        ranAs("schedule cancel lobby", "schedule.cancel", "display" to "lobby")
        ranAs("queue list", "queue.list")
        ranAs("queue add lobby https://a.b/c", "queue.add", "display" to "lobby", "url" to "https://a.b/c")
        ranAs("queue move lobby 3 1", "queue.move", "display" to "lobby", "n" to "3", "to" to "1")
        ranAs("queue loop lobby on", "queue.loop", "display" to "lobby", "state" to "on")
        ranAs("karaoke status", "karaoke.status")
        ranAs("karaoke trim lobby -40", "karaoke.trim", "display" to "lobby", "ms" to "-40")
        ranAs(
            "karaoke singer add lobby Bubs duet 300", "karaoke.singer.add",
            "display" to "lobby", "player" to "Bubs", "duet" to "duet", "lead" to "300",
        )
        ranAs("karaoke singer trim lobby Bubs +20", "karaoke.singer.trim", "display" to "lobby", "singer" to "Bubs", "ms" to "+20")
        ranAs("karaoke line list", "karaoke.line.list")
        ranAs("karaoke line add lobby Bubs https://x.y/z", "karaoke.line.add", "display" to "lobby", "player" to "Bubs", "url" to "https://x.y/z")
        ranAs("prepare list", "prepare.list")
        ranAs("prepare cancel all", "prepare.cancel", "id" to "all")
        ranAs("fullscreen list", "fullscreen.list")
        ranAs("fullscreen stop lobby", "fullscreen.stop", "id" to "lobby")
        ranAs("share stop", "share.stop")
        ranAs("share list", "share.list")
        ranAs("config", "config")
        ranAs("config live.relay_play_url rtmp://h:1/live/{id}", "config", "key" to "live.relay_play_url", "value" to "rtmp://h:1/live/{id}")
        ranAs("policy lobby reset", "policy", "display" to "lobby", "key" to "reset")
        ranAs("reload", "reload")
        ranAs("diag clients", "diag.clients")
        ranAs("diag env Bubs", "diag.env", "player" to "Bubs")
        ranAs("diag loglevel Bubs ultra", "diag.loglevel", "player" to "Bubs", "level" to "ultra")
        ranAs("diag errors #12", "diag.errors", "who" to "#12")
        ranAs("diag errors Bubs hitches 25", "diag.errors", "who" to "Bubs", "type" to "hitches", "count" to "25")
        ranAs("on", "on")
        ranAs("off Bubs", "off", "player" to "Bubs")
    }

    @Test
    fun fullscreenFlagsParseInAnyOrder() {
        val call = run("fullscreen start lobby loop volume 150 radius 16 1 2 3 view immersive target @a,Bubs")
        assertEquals("fullscreen.start", call?.def?.id, platform.texts().toString())
        val flags = call!!.flags!!
        assertTrue(flags.has("loop"))
        assertEquals(150.0, flags.number("volume"))
        assertEquals(listOf(16.0, 1.0, 2.0, 3.0), flags.numbers("radius"))
        assertEquals("immersive", flags.word("view"))
        assertEquals("@a,Bubs", flags.word("target"))
        assertEquals("lobby", call["source"])

        val prep = run("prepare start https://a.b/c.mp4 rewind quality 720")
        assertEquals("prepare.start", prep?.def?.id)
        assertEquals("720", prep!!.flags!!.word("quality"))
        assertTrue(prep.flags!!.has("rewind"))
    }

    @Test
    fun badFlagsReplyWithoutRunning() {
        assertNull(run("fullscreen start lobby volume 900"))
        assertEquals(listOf("D | volume must be a number from 0 to 200 (got '900')."), platform.texts())
        assertNull(run("fullscreen start lobby loop loop"))
        assertEquals(listOf("D | Flag 'loop' is given twice."), platform.texts())
        assertNull(run("prepare start lobby forced"))
        assertTrue(platform.texts().single().startsWith("D | Unknown flag 'forced'. Flags: target, radius, quality, rewind"))
        // `server` needs the network permission; without it, it is an unknown flag
        val noNetwork = FakeSource(perms = setOf(Perm.FULLSCREEN))
        assertNull(run("fullscreen start lobby server lobby2", noNetwork))
        assertTrue(platform.texts().single().startsWith("D | Unknown flag 'server'."))
    }

    @Test
    fun incompleteCommandsShowTheirUsage() {
        assertNull(run("queue add lobby"))
        assertEquals(listOf("D | Usage:", " /display queue add <display> <url> — Add a video at the end"), platform.texts())
        assertNull(run("video"))
        assertEquals(
            listOf(
                "D | Usage:",
                " /display video <display> <url> [lang] — Set what a display plays",
                " /display video <display> clear — Take the video off a display for everyone",
            ),
            platform.texts(),
        )
        assertNull(run("list sometext"))
        assertEquals(" /display list [mine|world <world>|owner <player>|mode <mode>] [page] — List displays, optionally filtered", platform.texts()[1])
        assertNull(run("queue move lobby 0 1"))
        assertEquals("D | Usage:", platform.texts()[0])
    }

    @Test
    fun helpOverviewListsOnlyWhatTheSenderCanRun() {
        run("help")
        assertEquals(
            listOf(
                "D | Dream Displays — /display help <area> for details",
                " displays: create  delete  info  name  list",
                " playback: video  speed  mode  stats",
                " schedule: show  play  pause  cancel",
                " queue: list  add  remove  move  skip  clear  loop",
                " karaoke: status  stop  trim  singer  join  leave  line",
                " prepare: start  list  cancel",
                " fullscreen: start  list  stop",
                " share: stop  list",
                " admin: config  policy  reload",
                " diag: clients  env  loglevel  errors",
                " viewing: on  off",
                Lang.en.getValue("cmd.help.target").replace("&7", ""),
            ),
            platform.texts(),
        )
        assertEquals(ChatLine.Click.Run("/display help queue"), platform.sent[4].click)
        run("help", FakeSource(perms = setOf(Perm.KARAOKE_JOIN, Perm.VIEWING_TOGGLE)))
        assertEquals(listOf(" karaoke: join  leave  line", " viewing: on  off"), platform.texts().drop(1).dropLast(1))
        run("help karaoke", FakeSource(perms = setOf(Perm.KARAOKE_JOIN)))
        assertEquals(
            listOf(
                "D | Karaoke — singers, their timing and the singer line",
                " /display karaoke join <display> <url> — Join the singer line with a song link",
                " /display karaoke leave [display] — Leave the singer line",
                " /display karaoke line list [display] — Who is waiting to sing",
            ),
            platform.texts().dropLast(1),
        )
    }

    @Test
    fun aBareAreaPrintsItsHelp() {
        run("queue")
        val texts = platform.texts()
        assertEquals("D | Queue — videos that play after the current one", texts[0])
        assertEquals(" /display queue list [display] — Show the queue", texts[1])
        assertEquals(9, texts.size)
        assertTrue(platform.sent[1].click == ChatLine.Click.Suggest("/display queue list "))
    }

    @Test
    fun unknownCommandsAndMissingPermissions() {
        assertNull(run("frobnicate now"))
        assertEquals(listOf("D | Unknown command 'frobnicate'. Try /display help"), platform.texts())
        val queueOnly = FakeSource(perms = setOf(Perm.QUEUE))
        assertNull(run("karaoke stop lobby", queueOnly))
        assertEquals(listOf("D | You can't use /display karaoke stop."), platform.texts())
        ranAs("queue skip lobby", "queue.skip", "display" to "lobby", source = queueOnly)
        // A karaoke joiner sees and runs only the join-level actions
        val joiner = FakeSource(perms = setOf(Perm.KARAOKE_JOIN))
        assertNull(run("karaoke stop lobby", joiner))
        assertEquals(listOf("D | You can't use /display karaoke stop."), platform.texts())
        ranAs("karaoke leave lobby", "karaoke.leave", "display" to "lobby", source = joiner)
    }

    @Test
    fun playerOnlyCommandsRefuseTheConsole() {
        assertNull(run("create", console))
        assertEquals(listOf("D | Only players can use /display create."), platform.texts())
        ranAs("queue list lobby", "queue.list", "display" to "lobby", source = console)
    }

    @Test
    fun oldFormsPrintWhereTheyMoved() {
        assertNull(run("karaoke test singer add Bubs"))
        assertEquals(listOf("D | Moved: /display karaoke singer add test Bubs"), platform.texts())
        assertEquals(ChatLine.Click.Suggest("/display karaoke singer add test Bubs"), platform.sent.single().click)
        assertNull(run("clients"))
        assertEquals(listOf("D | Moved: /display diag clients"), platform.texts())
    }

    @Test
    fun rootVisibilityFollowsPermissions() {
        assertTrue(tree.visible(op))
        assertTrue(tree.visible(FakeSource(perms = setOf(Perm.VIEWING_TOGGLE))))
        assertTrue(!tree.visible(FakeSource(perms = emptySet())))
        // Menu-only nodes don't make the command visible
        assertTrue(!tree.visible(FakeSource(perms = setOf(Perm.MENU_SHARE, Perm.MENU_CUSTOM))))
    }

    @Test
    fun completesLiteralsDisplaysAndFlags() {
        assertEquals(listOf("queue"), tree.completions(op, "qu"))
        assertEquals(listOf("add"), tree.completions(op, "queue a"))
        assertEquals(listOf("lobby"), tree.completions(op, "queue add lo"))
        // Only what the sender may run
        val queueOnly = FakeSource(perms = setOf(Perm.QUEUE))
        assertEquals(listOf("help", "queue"), tree.completions(queueOnly, ""))
        // Flags: unused names, then values; players after a comma
        val start = "fullscreen start lobby loop "
        val names = tree.completions(op, start)
        assertTrue("loop" !in names && "volume" in names && "server" in names, names.toString())
        assertTrue("server" !in tree.completions(FakeSource(perms = setOf(Perm.FULLSCREEN)), start))
        assertEquals(listOf("Bubs"), tree.completions(op, "fullscreen start lobby target @a,B"))
        assertEquals(listOf("standard", "immersive"), tree.completions(op, "fullscreen start lobby view "))
        assertTrue("12.5" in tree.completions(op, "fullscreen start lobby radius 16 "))
    }
}
