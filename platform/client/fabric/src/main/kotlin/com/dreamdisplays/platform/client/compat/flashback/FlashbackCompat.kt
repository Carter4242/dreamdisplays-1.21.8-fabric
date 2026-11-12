package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.RequestSync
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.client.replay.ReplayBridge
import com.dreamdisplays.platform.client.replay.ReplayCard
import com.dreamdisplays.platform.client.replay.ReplayHooks
import com.dreamdisplays.platform.client.utils.identifier
import com.dreamdisplays.shared.protocol.PacketDirection
import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload*/
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import java.util.*
import java.util.function.Consumer
import kotlin.math.abs

/**
 * Dream Displays in Flashback replays. Loaded only when Flashback is installed; it reaches Flashback through the same
 * public statics and custom replay actions Flashback's own Simple Voice Chat support uses, looked up once by reflection.
 * If any of them is missing (another Flashback version), replay support stays off and that is logged once.
 *
 * Recording: what the server says about displays nearby is written into the replay as an optional action (a full
 * picture every few seconds, each change as it arrives). Screen shares are written without their address and replay as
 * a card. Playback: the recorded Dream Displays traffic is ignored; [ReplayDriver] feeds the client from the actions
 * instead, on the replay's clock.
 */
object FlashbackCompat : ReplayBridge {
    private val logger = LoggerFactory.getLogger("DreamDisplays/Flashback")

    private const val FULL_FRAME_EVERY_MS = 5_000L
    private const val RECORD_RANGE_BLOCKS = 256

    private lateinit var api: Api
    private val cache = ReplayRecordCache()
    private val model = ReplayModel()
    private val outbox = ArrayList<DreamPacket>()
    private val driver = ReplayDriver { outbox += it }

    // Client thread
    private val pending = ArrayList<Pair<ReplayFrame, Long>>() // an update and when it was made
    private var recording: Any? = null
    private var lastFullFrameMs = Long.MIN_VALUE
    private var wasInReplay = false

    // Replay-server thread
    private var actionServer: Any? = null
    private var actionFailureLogged = false

    /** Called once at client start; does nothing without Flashback. Never throws. */
    fun initIfInstalled() {
        val mod = FabricLoader.getInstance().getModContainer("flashback").orElse(null) ?: return
        val version = mod.metadata.version.friendlyString
        try {
            api = Api.bind { name, buf -> onAction(name, buf) }
        } catch (t: Throwable) {
            logger.warn("Flashback {} is installed but its API is not the one this version knows: no displays in replays ({}).", version, t.toString())
            return
        }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute(cache::clear) }
        ReplayHooks.bridge = this
        logger.info("Flashback {} found: displays are recorded into replays and play back in them.", version)
    }

    // ---------------------------------------------------------------------------------------------- hooks

    override fun inReplay(): Boolean = api.isInReplay()

    override fun onServerPacket(bytes: ByteArray): Boolean {
        if (api.isInReplay()) return true // recorded traffic: the driver speaks for the server in replays
        guarded("recording") {
            val packet = PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT) ?: return@guarded
            val now = nowMs()
            val frame = cache.onPacket(packet, now, System.currentTimeMillis(), oneWayLatencyMs()) ?: return@guarded
            // Written from the tick, never from here: Flashback runs queued client tasks (this handler among them)
            // while it starts a replay chunk, where an action would land before the chunk's snapshot
            if (api.recorder() != null) pending += frame to now
        }
        return false
    }

    override fun onClientPayload(payload: CustomPacketPayload): Boolean {
        val bytes = (payload as? V2Payload)?.bytes ?: return true
        guarded("replay") {
            when (val packet = PacketRegistry.decode(bytes, PacketDirection.CLIENT_TO_SERVER)) {
                is ClientHello -> Minecraft.getInstance().execute(::greet)
                is RequestSync -> driver.requestSync(packet.id, packet.knownMediaGen)
                else -> {}
            }
        }
        return true
    }

    override fun card(displayId: UUID): ReplayCard? = driver.card(displayId)

    // ---------------------------------------------------------------------------------------------- client tick

    private fun tick() = guarded("tick") {
        if (api.isInReplay()) {
            wasInReplay = true
            drive()
            return@guarded
        }
        if (wasInReplay) {
            wasInReplay = false
            driver.reset()
            model.clear()
        }
        record()
    }

    /** Runs [body]; a failure turns replay support off for the session (logged once) and never reaches normal play. */
    private inline fun guarded(what: String, body: () -> Unit) {
        if (ReplayHooks.bridge == null) return
        try {
            body()
        } catch (t: Throwable) {
            ReplayHooks.bridge = null
            logger.error("Replay support failed ({}); it is off until the game restarts.", what, t)
        }
    }

    private fun record() {
        val recorder = api.recorder()
        if (recorder !== recording) {
            recording = recorder
            lastFullFrameMs = Long.MIN_VALUE
        }
        if (recorder == null || !api.readyToWrite(recorder)) {
            pending.clear()
            lastFullFrameMs = Long.MIN_VALUE // paused: the picture is written again when it resumes
            return
        }
        // An update Flashback could not take (see Api.submit) leaves a gap only the next full picture closes
        if (api.dropped.getAndSet(false)) lastFullFrameMs = Long.MIN_VALUE
        val now = nowMs()
        for ((frame, madeMs) in pending) api.submit(recorder, ReplayFormat.encode(frame.writtenLater(now - madeMs)))
        pending.clear()
        if (lastFullFrameMs != Long.MIN_VALUE && now - lastFullFrameMs < FULL_FRAME_EVERY_MS) return
        val player = Minecraft.getInstance().player ?: return
        val frame = cache.fullFrame(now) { info ->
            info.forced || (abs(info.x - player.blockX) <= RECORD_RANGE_BLOCKS && abs(info.z - player.blockZ) <= RECORD_RANGE_BLOCKS)
        }
        api.submit(recorder, ReplayFormat.encode(frame))
        lastFullFrameMs = now
    }

    private fun drive() {
        val mc = Minecraft.getInstance()
        val server = mc.singleplayerServer ?: return
        val replay = api.replayServer() ?: return
        if (!ProtocolRouter.v2Negotiated) greet()
        val ticks = server.tickRateManager()
        // Mid-seek the replay time runs ahead of the actions handled so far: wait for it to settle
        if (api.replaySeeking(replay)) return
        val held = api.replayPaused(replay) || ticks.isFrozen || server.isPaused
        val clock = ReplayDriver.Clock(api.visualMillis(), held, ticks.tickrate() / 20.0)
        driver.tick(nowMs(), clock, model.at(clock.replayMs), ::clientHas, oneWayLatencyMs())
        if (outbox.isEmpty()) return
        val packets = outbox.toList()
        outbox.clear()
        if (trace()) packets.forEach { logger.info("[replay] T={} held={} speed={} -> {}", clock.replayMs, clock.held, clock.speed, describe(it)) }
        packets.forEach(::deliver)
    }

    private fun clientHas(id: UUID): Boolean = DisplayRegistry.screens.containsKey(id) || DisplayRegistry.unloadedScreens.containsKey(id)

    /** Answers the client's hello, as a server would. */
    private fun greet() {
        deliver(driver.hello())
        // Tests: the client's own sync logging, as a server would switch it on
        if (trace()) deliver(com.dreamdisplays.core.protocol.common.packets.ServerPolicy("""{"v":1,"debug":{"logLevel":"ultra"}}""".toByteArray()))
    }

    /** Hands [packet] to the client as if the server had sent it. Client thread. */
    private fun deliver(packet: DreamPacket) = ProtocolRouter.onV2Received(PacketRegistry.encode(packet))

    // ---------------------------------------------------------------------------------------------- replay server

    private fun onAction(server: Any, buf: ByteBuf) {
        try {
            val bytes = ByteArray(buf.readableBytes()).also(buf::readBytes)
            val frame = ReplayFormat.decode(bytes) ?: return
            if (server !== actionServer) { // another replay opened
                actionServer = server
                model.clear()
            }
            val tick = api.actionTick(server)
            model.apply(tick, frame)
            if (trace()) logger.info("[replay] action at tick {}: {} {}", tick, if (frame.full) "full" else "update",
                frame.entries.joinToString { e -> "${e.id.toString().take(8)}${if (e.info != null) " info" else ""}${if (e.removed) " removed" else ""}${e.sample?.let { " ${it.positionMs}@${it.anchorMs}${if (it.paused) " paused" else ""} x${it.rate}" } ?: ""}" })
        } catch (t: Throwable) {
            if (!actionFailureLogged) {
                actionFailureLogged = true
                logger.warn("Could not read Dream Displays data in this replay.", t)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Step-by-step replay logging, for tests (`-Ddreamdisplays.replayTrace=true`). */
    private fun trace(): Boolean = java.lang.Boolean.getBoolean("dreamdisplays.replayTrace")

    private fun describe(p: DreamPacket): String = when (p) {
        is com.dreamdisplays.core.protocol.common.packets.DisplaySync ->
            "sync ${p.id.toString().take(8)} ${p.currentTimeMs}${if (p.isPaused) " paused" else ""} x${p.rate}"
        is com.dreamdisplays.core.protocol.common.packets.DisplayInfo -> "info ${p.id.toString().take(8)} ${p.url.takeLast(24)}"
        else -> p::class.simpleName ?: "?"
    }

    /** Half the player's round trip, as the client's timeline follower adds it to a playing timeline. */
    private fun oneWayLatencyMs(): Double {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0.0
        val rtt = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
        return rtt.coerceIn(0, 2_000) / 2.0
    }

    /**
     * Flashback, by reflection: everything is looked up here, so a version without any of it fails [bind] instead of
     * failing later in a replay.
     */
    private class Api(
        private val isInReplay: MethodHandle,
        private val visualMillis: MethodHandle,
        private val replayServer: MethodHandle,
        private val recorder: MethodHandle,
        private val readyToWrite: MethodHandle,
        private val submitCustomTask: MethodHandle,
        private val startAction: MethodHandle,
        private val friendlyByteBuf: MethodHandle,
        private val finishAction: MethodHandle,
        private val replayPaused: MethodHandle,
        private val fastForwarding: MethodHandle,
        private val processingSnapshot: MethodHandle,
        private val currentTick: MethodHandle,
        private val writerState: MethodHandle,
        private val writingData: Int,
        private val action: Any,
    ) {
        /** Set when a write was refused; read and cleared on the client thread. */
        val dropped = java.util.concurrent.atomic.AtomicBoolean(false)

        fun isInReplay(): Boolean = isInReplay.invoke() as Boolean
        fun visualMillis(): Long = visualMillis.invoke() as Long
        fun replayServer(): Any? = replayServer.invoke()
        fun recorder(): Any? = recorder.invoke()
        fun readyToWrite(recorder: Any): Boolean = readyToWrite.invoke(recorder) as Boolean

        fun replayPaused(server: Any): Boolean = replayPaused.invoke(server) as Boolean

        /** Seeking: running through recorded ticks, or rebuilding the world from a snapshot. */
        fun replaySeeking(server: Any): Boolean =
            fastForwarding.invoke(server) as Boolean || processingSnapshot.invoke(server) as Boolean

        /** The tick the replay server is handling now (its replay tick is where it is headed). Replay-server thread. */
        fun actionTick(server: Any): Int = currentTick.invoke(server) as Int

        /**
         * Writes [payload] as one action into the recording, after what the recorder has already queued. Runs on
         * Flashback's saver thread, and only writes while the chunk is past its snapshot: an action anywhere else
         * would make the chunk unreadable.
         */
        fun submit(recorder: Any, payload: ByteArray) {
            submitCustomTask.invoke(recorder, Consumer<Any> { writer ->
                if (writerState.invoke(writer) as Int != writingData) {
                    dropped.set(true)
                    return@Consumer
                }
                startAction.invoke(writer, action)
                try {
                    (friendlyByteBuf.invoke(writer) as ByteBuf).writeBytes(payload)
                } finally {
                    finishAction.invoke(writer, action)
                }
            })
        }

        companion object {
            /** Name of the replay action; the `optional` ending lets replays open where Dream Displays is missing. */
            const val ACTION_PATH = "replay/displays_optional"

            fun bind(onAction: (Any, ByteBuf) -> Unit): Api {
                val lookup = MethodHandles.publicLookup()
                val flashback = Class.forName("com.moulberry.flashback.Flashback")
                val replayServer = Class.forName("com.moulberry.flashback.playback.ReplayServer")
                val recorder = Class.forName("com.moulberry.flashback.record.Recorder")
                val writer = Class.forName("com.moulberry.flashback.io.ReplayWriter")
                val actionType = Class.forName("com.moulberry.flashback.action.Action")
                val registry = Class.forName("com.moulberry.flashback.action.ActionRegistry")

                fun generic(h: MethodHandle): MethodHandle = h.asType(h.type().generic())
                fun static(name: String, ret: Class<*>) = generic(lookup.findStatic(flashback, name, MethodType.methodType(ret)))
                fun virtual(owner: Class<*>, name: String, ret: Class<*>, vararg args: Class<*>) =
                    generic(lookup.findVirtual(owner, name, MethodType.methodType(ret, args)))
                fun field(name: String) = generic(lookup.findGetter(replayServer, name, Boolean::class.javaPrimitiveType))

                val writingData = writer.getDeclaredField("STATE_WRITING_DATA").also { it.isAccessible = true }.getInt(null)
                val currentTick = replayServer.getDeclaredField("currentTick")
                require(currentTick.type == Int::class.javaPrimitiveType) { "ReplayServer.currentTick is not an int" }
                currentTick.isAccessible = true

                val name = identifier(Initializer.MOD_ID, ACTION_PATH)
                val action = Proxy.newProxyInstance(actionType.classLoader, arrayOf(actionType)) { proxy, method, args ->
                    when (method.name) {
                        "name" -> name
                        "handle" -> onAction(args!![0], args[1] as ByteBuf)
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> name.toString()
                        else -> null
                    }
                }
                val api = Api(
                    isInReplay = static("isInReplay", Boolean::class.javaPrimitiveType!!),
                    visualMillis = static("getVisualMillis", Long::class.javaPrimitiveType!!),
                    replayServer = static("getReplayServer", replayServer),
                    recorder = generic(lookup.findStaticGetter(flashback, "RECORDER", recorder)),
                    readyToWrite = virtual(recorder, "readyToWrite", Boolean::class.javaPrimitiveType!!),
                    submitCustomTask = virtual(recorder, "submitCustomTask", Void.TYPE, Consumer::class.java),
                    startAction = virtual(writer, "startAction", Void.TYPE, actionType),
                    friendlyByteBuf = generic(lookup.findVirtual(writer, "friendlyByteBuf", MethodType.methodType(
                        writer.getMethod("friendlyByteBuf").returnType))),
                    finishAction = virtual(writer, "finishAction", Void.TYPE, actionType),
                    replayPaused = field("replayPaused"),
                    fastForwarding = field("fastForwarding"),
                    processingSnapshot = field("isProcessingSnapshot"),
                    currentTick = generic(MethodHandles.lookup().unreflectGetter(currentTick)),
                    writerState = generic(lookup.findGetter(writer, "state", Int::class.javaPrimitiveType)),
                    writingData = writingData,
                    action = action,
                )
                // Last: once registered the action can't be taken back, so only a complete binding registers it
                registry.getMethod("register", actionType).invoke(null, action)
                return api
            }
        }
    }
}
