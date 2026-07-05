package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.vulkan.VKOnly_TextureViewAndSamplerAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanRenderPassAccessor;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisVulkanRenderPassBindings {
	private static final Map<String, String> UNIFORM_ALIASES = Map.of(
		"iris_DynamicTransforms", "DynamicTransforms",
		"iris_Projection", "Projection",
		"iris_Globals", "Globals",
		"iris_Fog", "Fog",
		"iris_CloudInfo", "CloudInfo"
	);
	private static final Set<String> WARNED_MISSING_TEXTURES = ConcurrentHashMap.newKeySet();
	private static final Set<String> WARNED_MISSING_UNIFORMS = ConcurrentHashMap.newKeySet();
	private static GpuBuffer dummyUniformBuffer;
	private static GpuBuffer dummyTexelBuffer;
	private static GpuTexture dummyTexture;
	private static GpuTextureView dummyTextureView;

	private IrisVulkanRenderPassBindings() {
	}

	public static void apply(RenderPass pass, VulkanRenderPass backend,
							 List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		VulkanRenderPipeline compiled = ((VKOnly_VulkanRenderPassAccessor) backend).iris$getPipeline();

		if (compiled == null || !compiled.isValid()) {
			return;
		}

		RenderPipeline pipeline = compiled.info();
		RenderSystem.bindDefaultUniforms(pass);
		bindUniformAliases(pass, backend, pipeline);
		bindTextureAliases(pass, backend, pipeline, colorAttachments);
	}

	private static void bindUniformAliases(RenderPass pass, VulkanRenderPass backend, RenderPipeline pipeline) {
		Map<String, GpuBufferSlice> uniforms = ((VKOnly_VulkanRenderPassAccessor) backend).iris$getUniforms();
		List<BindGroupLayout.UniformDescription> requiredUniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());

		for (BindGroupLayout.UniformDescription uniform : requiredUniforms) {
			String name = uniform.name();

			if (uniforms.containsKey(name)) {
				continue;
			}

			String source = UNIFORM_ALIASES.get(name);
			if (source != null && uniforms.containsKey(source)) {
				pass.setUniform(name, uniforms.get(source));
				continue;
			}

			if (bindDefaultUniform(pass, name)) {
				continue;
			}

			if (uniform.type() == com.mojang.blaze3d.shaders.UniformType.TEXEL_BUFFER) {
				pass.setUniform(name, dummyTexelBuffer().slice());
			} else {
				pass.setUniform(name, dummyUniformBuffer().slice());
			}

			if (WARNED_MISSING_UNIFORMS.add(name)) {
				Iris.logger.warn("Missing Vulkan uniform binding for {}; using a dummy buffer.", name);
			}
		}
	}

	private static boolean bindDefaultUniform(RenderPass pass, String name) {
		if (name.equals("Projection")) {
			GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();

			if (projection != null) {
				pass.setUniform(name, projection);
				return true;
			}
		}

		if (name.equals("Globals")) {
			GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();

			if (globals != null) {
				pass.setUniform(name, globals);
				return true;
			}
		}

		return false;
	}

	private static void bindTextureAliases(RenderPass pass, VulkanRenderPass backend, RenderPipeline pipeline,
										   List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		Map<String, Object> textures = ((VKOnly_VulkanRenderPassAccessor) backend).iris$getTextures();
		List<String> requiredSamplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());

		for (String sampler : requiredSamplers) {
			if (textures.containsKey(sampler)) {
				continue;
			}

			TextureBinding binding = findTextureBinding(sampler, textures);

			if (binding != null) {
				pass.bindTexture(sampler, binding.view(), binding.sampler());
				continue;
			}

			GpuTextureView view = findRenderTargetView(sampler, colorAttachments);
			GpuSampler gpuSampler = findFallbackSampler(textures);

			if (view != null && gpuSampler != null) {
				pass.bindTexture(sampler, view, gpuSampler);
				continue;
			}

			TextureBinding fallback = findAnyTexture(textures);
			if (fallback != null) {
				pass.bindTexture(sampler, fallback.view(), fallback.sampler());

				if (WARNED_MISSING_TEXTURES.add(sampler)) {
					Iris.logger.warn("Missing Vulkan texture binding for {}; reusing an existing texture as a fallback.", sampler);
				}
				continue;
			}

			TextureBinding dummy = dummyTextureBinding();
			pass.bindTexture(sampler, dummy.view(), dummy.sampler());

			if (WARNED_MISSING_TEXTURES.add(sampler)) {
				Iris.logger.warn("Missing Vulkan texture binding for {}; using a dummy texture.", sampler);
			}
		}
	}

	private static TextureBinding findTextureBinding(String sampler, Map<String, Object> textures) {
		if (isAlbedoSampler(sampler)) {
			return firstTexture(textures, "Sampler0", "u_BlockTex", "u_MainSampler", "gtexture", "tex", "texture", "colortex0", "gcolor");
		}

		if (sampler.equals("lightmap") || sampler.equals("u_LightTex")) {
			return firstTexture(textures, "Sampler2", "u_LightTex", "lightmap", "Sampler0", "u_BlockTex");
		}

		if (sampler.equals("iris_overlay")) {
			return firstTexture(textures, "Sampler1", "iris_overlay", "Sampler0", "u_BlockTex");
		}

		return null;
	}

	private static boolean isAlbedoSampler(String sampler) {
		return sampler.equals("tex") || sampler.equals("texture") || sampler.equals("gtexture") ||
			sampler.equals("u_MainSampler") || sampler.equals("gcolor") || sampler.equals("colortex0");
	}

	private static GpuTextureView findRenderTargetView(String sampler,
													   List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		GpuTextureView gbufferView = IrisVulkanGbufferTargets.colorSamplerView(sampler);

		if (gbufferView != null) {
			return gbufferView;
		}

		if ((sampler.equals("colortex0") || sampler.equals("gcolor")) && !colorAttachments.isEmpty()) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colorAttachments.getFirst();

			if (attachment != null) {
				return attachment.textureView();
			}
		}

		if (sampler.equals("depthtex0") || sampler.equals("gdepthtex") || sampler.equals("depthtex1") || sampler.equals("depthtex2")) {
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();

			if (target != null && target.getDepthTextureView() != null) {
				return target.getDepthTextureView();
			}
		}

		return null;
	}

	private static GpuSampler findFallbackSampler(Map<String, Object> textures) {
		TextureBinding existing = findAnyTexture(textures);

		if (existing != null) {
			return existing.sampler();
		}

		return IrisSamplers.getTerrainCache(1);
	}

	private static TextureBinding firstTexture(Map<String, Object> textures, String... names) {
		for (String name : names) {
			TextureBinding texture = textureBinding(textures.get(name));

			if (texture != null) {
				return texture;
			}
		}

		return null;
	}

	private static TextureBinding findAnyTexture(Map<String, Object> textures) {
		return textures.values().stream().map(IrisVulkanRenderPassBindings::textureBinding).filter(binding -> binding != null).findFirst().orElse(null);
	}

	private static TextureBinding textureBinding(Object value) {
		if (value == null) {
			return null;
		}

		VKOnly_TextureViewAndSamplerAccessor accessor = (VKOnly_TextureViewAndSamplerAccessor) value;
		return new TextureBinding(accessor.iris$getView(), accessor.iris$getSampler());
	}

	private static GpuBuffer dummyUniformBuffer() {
		if (dummyUniformBuffer == null || dummyUniformBuffer.isClosed()) {
			dummyUniformBuffer = RenderSystem.getDevice().createBuffer(() -> "Iris dummy Vulkan uniform buffer",
				GpuBuffer.USAGE_UNIFORM, 4096L);
		}

		return dummyUniformBuffer;
	}

	private static GpuBuffer dummyTexelBuffer() {
		if (dummyTexelBuffer == null || dummyTexelBuffer.isClosed()) {
			dummyTexelBuffer = RenderSystem.getDevice().createBuffer(() -> "Iris dummy Vulkan texel buffer",
				GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 16L);
		}

		return dummyTexelBuffer;
	}

	private static TextureBinding dummyTextureBinding() {
		if (dummyTexture == null || dummyTexture.isClosed() || dummyTextureView == null || dummyTextureView.isClosed()) {
			dummyTexture = RenderSystem.getDevice().createTexture(() -> "Iris dummy Vulkan texture",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			dummyTextureView = RenderSystem.getDevice().createTextureView(dummyTexture);

			try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
				image.setPixel(0, 0, 0xFFFFFFFF);
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(dummyTexture, image);
			}
		}

		return new TextureBinding(dummyTextureView, IrisSamplers.getTerrainCache(1));
	}

	private record TextureBinding(GpuTextureView view, GpuSampler sampler) {
	}
}
