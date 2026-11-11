//? if >=1.21.6 <1.21.11 {
package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import org.joml.Vector4f
import java.util.OptionalDouble
import java.util.OptionalInt

/**
 * Display [RenderType] for 1.21.6 - 1.21.10, standing in for 1.21.11's `RenderType.create(name, RenderSetup)`:
 * draws [pipeline] into the main target with every texture in [textures] bound to its sampler name.
 *
 * Vanilla's own route (`RenderType.create` + `CompositeState`) is package-private / protected on these
 * versions, so the draw is done here, mirroring `RenderType.CompositeRenderType.draw`.
 */
internal class DisplayRenderType(
    typeName: String,
    private val pipeline: RenderPipeline,
    private val textures: List<Pair<String, ResourceLocation>>,
) : RenderType(typeName, BUFFER_SIZE, false, false, {}, {}) {
    private val label = typeName

    override fun format(): VertexFormat = pipeline.vertexFormat

    override fun mode(): VertexFormat.Mode = pipeline.vertexFormatMode

    override fun draw(mesh: MeshData) {
        try {
            val transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(),
                Vector4f(1f, 1f, 1f, 1f),
                RenderSystem.getModelOffset(),
                RenderSystem.getTextureMatrix(),
                RenderSystem.getShaderLineWidth(),
            )
            val drawState = mesh.drawState()
            val vertexBuffer = pipeline.vertexFormat.uploadImmediateVertexBuffer(mesh.vertexBuffer())
            val indexBuffer: GpuBuffer
            val indexType: VertexFormat.IndexType
            val meshIndices = mesh.indexBuffer()
            if (meshIndices == null) {
                val sequential = RenderSystem.getSequentialBuffer(drawState.mode())
                indexBuffer = sequential.getBuffer(drawState.indexCount())
                indexType = sequential.type()
            } else {
                indexBuffer = pipeline.vertexFormat.uploadImmediateIndexBuffer(meshIndices)
                indexType = drawState.indexType()
            }

            val mc = Minecraft.getInstance()
            val target = mc.mainRenderTarget
            val colorView = RenderSystem.outputColorTextureOverride ?: target.colorTextureView
            val depthView = if (target.useDepth) RenderSystem.outputDepthTextureOverride ?: target.depthTextureView else null
            val textureManager = mc.textureManager

            RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                { "Immediate draw for $label" }, colorView, OptionalInt.empty(), depthView, OptionalDouble.empty(),
            ).use { pass ->
                pass.setPipeline(pipeline)
                val scissor = RenderSystem.getScissorStateForRenderTypeDraws()
                if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height())
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("DynamicTransforms", transforms)
                pass.setVertexBuffer(0, vertexBuffer)
                for ((sampler, id) in textures) {
                    pass.bindSampler(sampler, textureManager.getTexture(id).textureView)
                }
                pass.setIndexBuffer(indexBuffer, indexType)
                pass.drawIndexed(0, 0, drawState.indexCount(), 1)
            }
        } finally {
            mesh.close()
        }
    }

    override fun toString(): String = "RenderType[$label:${pipeline.location}]"

    private companion object {
        const val BUFFER_SIZE = 1536
    }
}
//?}
