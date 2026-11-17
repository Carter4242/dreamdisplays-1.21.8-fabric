//? if >=1.21.6 {
package com.dreamdisplays.platform.client.render

//? if <26.3 {
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.VertexFormat
//?}
import com.mojang.blaze3d.vertex.DefaultVertexFormat
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

internal object RenderPipelineCompat {
    /** Creates a render pipeline for a display quad. */
    fun createDisplayPipeline(
        location: Identifier,
        vertexShader: Identifier,
        fragmentShader: Identifier,
        samplers: List<String>,
        lateDraw: Boolean = false,
    ): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(vertexShader)
            .withFragmentShader(fragmentShader)
            .withCull(false)

        configureDepth(builder, lateDraw)
        configureBlend(builder)
        if (supportsBindGroupLayouts()) {
            configure262(builder, samplers)
        } else {
            configureLegacy(builder, samplers)
        }

        return builder.build()
    }

    fun configureBlend(builder: RenderPipelineBuilder) {
        val builderClass = builder.javaClass
        val blendFunctionClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.BlendFunction",
            "com.mojang.blaze3d.pipeline.BlendFunction",
        ) ?: return
        val translucent = blendFunctionClass.getField("TRANSLUCENT").get(null)

        val colorTargetStateClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.ColorTargetState",
            "com.mojang.blaze3d.pipeline.ColorTargetState",
        )
        if (colorTargetStateClass != null) {
            val state = colorTargetStateClass.getConstructor(blendFunctionClass).newInstance(translucent)
            builderClass.getMethod("withColorTargetState", colorTargetStateClass).invoke(builder, state)
            return
        }

        runCatching {
            builderClass.getMethod("withBlend", blendFunctionClass).invoke(builder, translucent)
        }
    }

    private const val DEPTH_BIAS = 3.0f

    /**
     * Polygon offset of a display drawn in the level pass (versions before `DepthStencilState`): one depth slope plus three depth-buffer
     * steps toward the viewer. The world-space surface offset alone falls below the depth buffer's resolution far away,
     * and big quads seen nearly edge-on lose it to rasterization error.
     */
    private const val LEVEL_BIAS_SLOPE = 1.0f
    private const val LEVEL_BIAS_UNITS = 3.0f

    /**
     * Polygon offset of [UnshadedDisplayPass]'s copy, which is tested against the depth the level-pass copy wrote. That
     * one went through the shader pack, whose temporal anti-aliasing jitters every vertex by up to a pixel; edge-on, a
     * pixel is a large depth step, so the copy needs two more depth slopes of headroom than the level-pass draw.
     */
    private const val LATE_BIAS_SLOPE = 3.0f
    private const val LATE_BIAS_UNITS = 6.0f

    /**
     * Configures the depth state of the pipeline. [lateDraw] = the pipeline of [UnshadedDisplayPass]'s copy (only
     * differs before `DepthStencilState`).
     */
    fun configureDepth(builder: RenderPipelineBuilder, lateDraw: Boolean = false) {
        val builderClass = builder.javaClass
        val depthStencilStateClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.DepthStencilState",
            "com.mojang.blaze3d.pipeline.DepthStencilState",
        )
        if (depthStencilStateClass != null) {
            val defaultDepth = depthStencilStateClass.getField("DEFAULT").get(null)
            val biasedDepth = withPolygonOffset(depthStencilStateClass, defaultDepth)
            builderClass.getMethod("withDepthStencilState", depthStencilStateClass).invoke(builder, biasedDepth)
            return
        }

        val depthTestFunctionClass = runCatching {
            Class.forName("com.mojang.blaze3d.platform.DepthTestFunction")
        }.getOrNull() ?: return

        val lequalDepth = reflectiveEnumValue(depthTestFunctionClass, "LEQUAL_DEPTH_TEST")
        builderClass.getMethod("withDepthTestFunction", depthTestFunctionClass).invoke(builder, lequalDepth)
        runCatching {
            builderClass.getMethod("withDepthWrite", Boolean::class.javaPrimitiveType).invoke(builder, true)
        }
        // Standard depth here (the depth test is LEQUAL): a negative offset pulls the quad toward the viewer
        val slope = if (lateDraw) LATE_BIAS_SLOPE else LEVEL_BIAS_SLOPE
        val units = if (lateDraw) LATE_BIAS_UNITS else LEVEL_BIAS_UNITS
        runCatching {
            builderClass.getMethod("withDepthBias", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(builder, -slope, -units)
        }
    }

    private fun withPolygonOffset(depthStencilStateClass: Class<*>, defaultDepth: Any): Any {
        val depthTest = depthStencilStateClass.getMethod("depthTest").invoke(defaultDepth)
        val writeDepth = depthStencilStateClass.getMethod("writeDepth").invoke(defaultDepth) as Boolean
        val reversedZ = (depthTest as Enum<*>).name == "GREATER_THAN_OR_EQUAL"
        val bias = if (reversedZ) DEPTH_BIAS else -DEPTH_BIAS

        val compareOpClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.CompareOp",
            "com.mojang.blaze3d.platform.CompareOp",
        ) ?: return defaultDepth
        val ctor = depthStencilStateClass.getConstructor(
            compareOpClass,
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
        return ctor.newInstance(depthTest, writeDepth, bias, bias)
    }

    private fun supportsBindGroupLayouts(): Boolean = bindGroupLayoutClass() != null

    private fun bindGroupLayoutClass(): Class<*>? = loadClass(
        "com.mojang.renderpearl.api.pipeline.BindGroupLayout",
        "com.mojang.blaze3d.pipeline.BindGroupLayout",
    )

    private fun loadClass(vararg names: String): Class<*>? =
        names.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }

    private fun configureLegacy(builder: RenderPipelineBuilder, samplers: List<String>) {
        val builderClass = builder.javaClass
        val withUniform = builderClass.getMethod("withUniform", String::class.java, UniformType::class.java)
        withUniform.invoke(builder, "DynamicTransforms", UniformType.UNIFORM_BUFFER)
        withUniform.invoke(builder, "Projection", UniformType.UNIFORM_BUFFER)
        withUniform.invoke(builder, "Fog", UniformType.UNIFORM_BUFFER)

        val withSampler = builderClass.getMethod("withSampler", String::class.java)
        for (sampler in samplers) {
            withSampler.invoke(builder, sampler)
        }

        //? if >=26 {
        val modeClass = Class.forName($$"com.mojang.blaze3d.vertex.VertexFormat$Mode")

        val quads = reflectiveEnumValue(modeClass, "QUADS")
        builderClass.getMethod("withVertexFormat", VertexFormat::class.java, modeClass)
            .invoke(builder, DefaultVertexFormat.POSITION_TEX_COLOR, quads)
        //?} else
        /*val modeClass = VertexFormat.Mode::class.java
        val quads = VertexFormat.Mode.QUADS
        builderClass.getMethod("withVertexFormat", VertexFormat::class.java, modeClass)
            .invoke(builder, DefaultVertexFormat.POSITION_TEX_COLOR, quads)*/
    }

    private fun configure262(builder: RenderPipelineBuilder, samplers: List<String>) {
        val builderClass = builder.javaClass
        val bglClass = bindGroupLayoutClass() ?: return
        val withBindGroupLayout = builderClass.getMethod("withBindGroupLayout", bglClass)

        bindWorldDisplayUniforms(withBindGroupLayout, builder)
        withBindGroupLayout.invoke(
            builder,
            if (samplers == ["Sampler0"]) vanillaLayout("SAMPLER0") else samplerLayout(samplers),
        )

        builderClass.getMethod("withVertexBinding", Int::class.javaPrimitiveType, VertexFormat::class.java)
            .invoke(builder, 0, DefaultVertexFormat.POSITION_TEX_COLOR)

        val topologyClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.PrimitiveTopology",
            "com.mojang.blaze3d.PrimitiveTopology",
        ) ?: return

        val quads = reflectiveEnumValue(topologyClass, "QUADS")
        builderClass.getMethod("withPrimitiveTopology", topologyClass).invoke(builder, quads)
    }

    internal fun bindWorldDisplayUniforms(withBindGroupLayout: java.lang.reflect.Method, builder: Any) {
        withBindGroupLayout.invoke(builder, vanillaLayout("GLOBALS"))
        if (hasLayout("DYNAMIC_TRANSFORMS")) {
            withBindGroupLayout.invoke(builder, vanillaLayout("PROJECTION"))
            withBindGroupLayout.invoke(builder, vanillaLayout("DYNAMIC_TRANSFORMS"))
        } else {
            withBindGroupLayout.invoke(builder, vanillaLayout("MATRICES_PROJECTION"))
        }
        withBindGroupLayout.invoke(builder, vanillaLayout("FOG"))
    }

    private fun hasLayout(name: String): Boolean =
        runCatching {
            Class.forName("net.minecraft.client.renderer.BindGroupLayouts").getField(name)
        }.isSuccess

    private fun samplerLayout(samplers: List<String>): Any {
        val bglClass = bindGroupLayoutClass() ?: error("BindGroupLayout is missing")
        val layoutBuilder = bglClass.getMethod("builder").invoke(null)
        val withSampler = runCatching {
            layoutBuilder.javaClass.getMethod("withSampler", String::class.java)
        }.getOrNull()
        if (withSampler != null) {
            for (sampler in samplers) {
                withSampler.invoke(layoutBuilder, sampler)
            }
        } else {
            val uniformTypeClass = loadClass(
                "com.mojang.renderpearl.api.pipeline.UniformType",
                "com.mojang.blaze3d.shaders.UniformType",
            ) ?: error("UniformType is missing")
            val combined = reflectiveEnumValue(uniformTypeClass, "COMBINED_IMAGE_SAMPLER")
            val withUniform = layoutBuilder.javaClass.getMethod(
                "withUniform",
                String::class.java,
                uniformTypeClass,
            )
            for (sampler in samplers) {
                withUniform.invoke(layoutBuilder, sampler, combined)
            }
        }
        return layoutBuilder.javaClass.getMethod("build").invoke(layoutBuilder)
    }

    private fun vanillaLayout(vararg names: String): Any {
        val clazz = Class.forName("net.minecraft.client.renderer.BindGroupLayouts")
        val last = names.last()
        names.forEach { name ->
            runCatching { return clazz.getField(name).get(null) }
        }
        return clazz.getField(last).get(null)
    }

    /** Resolves enum constant [name] on [enumClass], whose static type is not known at compile time. */
    fun reflectiveEnumValue(enumClass: Class<*>, name: String): Any =
        enumClass.enumConstants.first { (it as Enum<*>).name == name }
}
//?}
