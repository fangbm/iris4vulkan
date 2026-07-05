package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;

@Mixin(VulkanRenderPass.class)
public interface VKOnly_VulkanRenderPassAccessor {
	@Accessor("pipeline")
	VulkanRenderPipeline iris$getPipeline();

	@Accessor("uniforms")
	HashMap<String, GpuBufferSlice> iris$getUniforms();

	@Accessor("textures")
	HashMap<String, Object> iris$getTextures();
}
