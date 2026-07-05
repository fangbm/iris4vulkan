package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanDevice.class)
public class VKOnly_MixinVulkanDevice_Overrides {
	@Inject(method = "getOrCompilePipeline", at = @At("HEAD"), cancellable = true)
	private void iris$overridePipeline(RenderPipeline renderPipeline, CallbackInfoReturnable<VulkanRenderPipeline> cir) {
		IrisNativeVulkan.tryOverridePipeline((VulkanDevice) (Object) this, renderPipeline).ifPresent(cir::setReturnValue);
	}

	@Inject(method = "clearPipelineCache", at = @At("RETURN"))
	private void iris$clearPipelineCache(CallbackInfo ci) {
		IrisNativeVulkan.clearDevicePipelineCache((VulkanDevice) (Object) this);
	}
}
