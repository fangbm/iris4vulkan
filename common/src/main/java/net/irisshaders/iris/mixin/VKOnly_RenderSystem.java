package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class VKOnly_RenderSystem {
	@Inject(method = "initRenderer", at = @At("RETURN"), remap = false)
	private static void iris$onVulkanRendererInit(GpuDevice device, CallbackInfo ci) {
		IrisNativeVulkan.onRendererInitialized(device);
	}
}
