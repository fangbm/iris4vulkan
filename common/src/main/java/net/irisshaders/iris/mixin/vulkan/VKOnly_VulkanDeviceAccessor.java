package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanDevice.class)
public interface VKOnly_VulkanDeviceAccessor {
	@Accessor("glslCompiler")
	GlslCompiler iris$getGlslCompiler();
}
