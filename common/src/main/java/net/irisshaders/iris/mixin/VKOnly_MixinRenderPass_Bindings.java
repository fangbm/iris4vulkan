package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import net.irisshaders.iris.vulkan.IrisVulkanGbufferTargets;
import net.irisshaders.iris.vulkan.IrisVulkanRenderPassBindings;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Mixin(RenderPass.class)
public class VKOnly_MixinRenderPass_Bindings {
	@Shadow
	@Final
	private RenderPassBackend backend;

	@Shadow
	@Final
	private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

	private void iris$bindVulkanResources() {
		if (backend instanceof VulkanRenderPass vulkanRenderPass) {
			IrisVulkanRenderPassBindings.apply((RenderPass) (Object) this, vulkanRenderPass, colorAttachments);
		}
	}

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
	private RenderPipeline iris$adaptPipelineForGbufferPass(RenderPipeline pipeline) {
		return IrisVulkanGbufferTargets.adaptPipelineForGbufferPass(pipeline, colorAttachments);
	}

	@Inject(method = "drawIndexed(IIIII)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDrawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDrawIndexed(IntBuffer drawParameters, int firstIndex, int vertexOffset, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDrawIndexed(PointerBuffer firstIndices, IntBuffer indexCounts, IntBuffer baseVertices, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "drawIndexedIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDrawIndexedIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V", at = @At("HEAD"))
	private <T> void iris$bindVulkanResourcesBeforeDrawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer, IndexType indexType, Collection<String> dynamicUniforms, T uniformData, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "draw(IIII)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDraw(int vertexOffset, int vertexCount, int instanceCount, int firstInstance, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDraw(IntBuffer drawParameters, int vertexOffset, int firstInstance, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "drawIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDrawIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}
}
