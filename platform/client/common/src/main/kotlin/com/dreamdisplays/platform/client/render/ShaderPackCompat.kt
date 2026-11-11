package com.dreamdisplays.platform.client.render

import com.dreamdisplays.core.render.ShaderBackend

/** Shader-pack detector: checks for `Iris`, `OptiFine`, or `Canvas` shader packs without adding hard dependencies. */
internal object ShaderPackCompat {
    /** True when any supported shader pack is currently in use. */
    val isShaderPackActive: Boolean get() = shaderBackend() != ShaderBackend.NONE

    /** Active shader backend, or [ShaderBackend.NONE]. */
    fun shaderBackend(): ShaderBackend = when {
        iris.active() -> ShaderBackend.IRIS
        optifine.active() -> ShaderBackend.OPTIFINE
        canvasPresent -> ShaderBackend.CANVAS
        else -> ShaderBackend.NONE
    }

    // Asked several times per display per frame: the reflective lookups are resolved once, and a mod that is not
    // installed is remembered as absent instead of throwing a ClassNotFoundException (a search of every mod jar) each time
    private val iris = ReflectiveProbe {
        val api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null)
        val inUse = api.javaClass.getMethod("isShaderPackInUse");
        { inUse.invoke(api) as? Boolean == true }
    }

    private val optifine = ReflectiveProbe {
        val isShaders = Class.forName("net.optifine.Config").getMethod("isShaders");
        { isShaders.invoke(null) as? Boolean == true }
    }

    private val canvasPresent: Boolean by lazy {
        classPresent("grondag.canvas.CanvasMod") || classPresent("io.vram.canvas.CanvasFabricMod")
    }

    private fun classPresent(name: String): Boolean = runCatching {
        Class.forName(name, false, ShaderPackCompat::class.java.classLoader)
        true
    }.getOrDefault(false)
}

/**
 * A yes/no question answered through reflection. [resolve] looks the API up and returns the query; a missing class
 * makes the answer false for good, any other failure (the mod not initialized yet) is retried on the next call.
 */
internal class ReflectiveProbe(private val resolve: () -> (() -> Boolean)) {
    @Volatile
    private var query: (() -> Boolean)? = null

    @Volatile
    private var absent = false

    fun active(): Boolean {
        if (absent) return false
        val q = query ?: try {
            resolve().also { query = it }
        } catch (_: ClassNotFoundException) {
            absent = true
            return false
        } catch (_: LinkageError) {
            absent = true
            return false
        } catch (_: Exception) {
            return false
        }
        return runCatching { q() }.getOrDefault(false)
    }
}
