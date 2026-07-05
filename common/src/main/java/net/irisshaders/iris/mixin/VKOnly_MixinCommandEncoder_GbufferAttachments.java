package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import net.irisshaders.iris.vulkan.IrisVulkanGbufferTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandEncoder.class)
public class VKOnly_MixinCommandEncoder_GbufferAttachments {
	@Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;", at = @At("HEAD"))
	private void iris$rewriteGbufferAttachments(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		IrisVulkanGbufferTargets.rewriteMainRenderPass((CommandEncoder) (Object) this, descriptor);
	}
}
