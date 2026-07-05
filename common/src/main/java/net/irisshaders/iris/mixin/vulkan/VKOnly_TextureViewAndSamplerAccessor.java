package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanRenderPass$TextureViewAndSampler")
public interface VKOnly_TextureViewAndSamplerAccessor {
	@Accessor("view")
	VulkanGpuTextureView iris$getView();

	@Accessor("sampler")
	VulkanGpuSampler iris$getSampler();
}
