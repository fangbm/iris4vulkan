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
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisVulkanRenderPassBindings {
	private static final Map<String, String> UNIFORM_ALIASES = Map.of(
		"iris_DynamicTransforms", "DynamicTransforms",
		"iris_Projection", "Projection",
		"iris_Globals", "Globals",
		"iris_Fog", "Fog"
	);
	private static final Set<String> WARNED_DUMMY_TEXTURES = ConcurrentHashMap.newKeySet();
	private static final Set<String> LOGGED_SCREEN_PASS_BINDINGS = ConcurrentHashMap.newKeySet();
	private static final Map<RenderPass, ScreenPassContext> SCREEN_PASS_CONTEXTS =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<RenderPass, GpuBuffer> SNAPSHOT_BUFFERS =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static GpuTexture dummyTexture;
	private static GpuTextureView dummyTextureView;

	private IrisVulkanRenderPassBindings() {
	}

	public static void closePackResources() {
		IrisVulkanCustomTextures.close();
	}

	public static void apply(RenderPass pass, VulkanRenderPass backend,
							 List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		VulkanRenderPipeline compiled = ((VKOnly_VulkanRenderPassAccessor) backend).iris$getPipeline();

		if (compiled == null || !compiled.isValid()) {
			return;
		}

		RenderPipeline pipeline = compiled.info();
		ScreenPassContext screenPassContext = SCREEN_PASS_CONTEXTS.remove(pass);

		if (screenPassContext != null) {
			bindScreenPassResources(pass, pipeline, screenPassContext.depthView(), screenPassContext.finalSourceView(),
				screenPassContext.passLabel(), screenPassContext.stage());

			if (LOGGED_SCREEN_PASS_BINDINGS.add(screenPassContext.passLabel())) {
				Iris.logger.info("Bound native Vulkan screen pass {} with compiled pipeline {}, samplers={}, dummySamplers={}.",
					screenPassContext.passLabel(), pipeline.getLocation(),
					BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()),
					IrisNativeVulkan.screenPassDummySamplers());
			}

			return;
		}

		RenderSystem.bindDefaultUniforms(pass);
		bindUniformSnapshot(pass, pipeline, "render pass");
		bindUniformAliases(pass, backend, pipeline);
		bindTextureAliases(pass, backend, pipeline, colorAttachments);
	}

	public static void prepareScreenPassResources(RenderPass pass, String passLabel, TextureStage stage,
												  GpuTextureView depthView) {
		prepareScreenPassResources(pass, passLabel, stage, depthView, null);
	}

	public static void prepareScreenPassResources(RenderPass pass, String passLabel, TextureStage stage,
												  GpuTextureView depthView, GpuTextureView finalSourceView) {
		SCREEN_PASS_CONTEXTS.put(pass, new ScreenPassContext(passLabel, stage, depthView, finalSourceView));
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView) {
		bindScreenPassResources(pass, pipeline, depthView, "<unknown>");
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   String passLabel) {
		bindScreenPassResources(pass, pipeline, depthView, passLabel, TextureStage.COMPOSITE_AND_FINAL);
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   String passLabel, TextureStage stage) {
		bindScreenPassResources(pass, pipeline, depthView, null, passLabel, stage);
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   GpuTextureView finalSourceView, String passLabel, TextureStage stage) {
		RenderSystem.bindDefaultUniforms(pass);
		bindScreenPassUniforms(pass, pipeline, passLabel);
		bindScreenPassTextures(pass, pipeline, depthView, finalSourceView, passLabel, stage);
	}

	private static void bindScreenPassUniforms(RenderPass pass, RenderPipeline pipeline, String passLabel) {
		List<BindGroupLayout.UniformDescription> requiredUniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		GpuBuffer snapshotBuffer = bindUniformSnapshot(pass, pipeline, passLabel);

		for (BindGroupLayout.UniformDescription uniform : requiredUniforms) {
			String name = uniform.name();

			if (name.equals(IrisVulkanUniformSnapshot.BLOCK_NAME)) {
				if (snapshotBuffer == null) {
					throw missingUniform(passLabel, name);
				}

				continue;
			}

			if (bindDefaultUniform(pass, name)) {
				continue;
			}

			throw missingUniform(passLabel, name);
		}
	}

	private static GpuBuffer bindUniformSnapshot(RenderPass pass, RenderPipeline pipeline, String passLabel) {
		IrisVulkanShaderResources.ResourceSet resources = IrisVulkanShaderResources.resourcesFor(pipeline);
		if (resources == null || resources.uniformFields().isEmpty()) {
			return null;
		}

		List<BindGroupLayout.UniformDescription> requiredUniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		if (requiredUniforms.stream().noneMatch(uniform ->
			uniform.name().equals(IrisVulkanUniformSnapshot.BLOCK_NAME))) {
			return null;
		}

		GpuBuffer buffer = SNAPSHOT_BUFFERS.get(pass);
		if (buffer == null || buffer.isClosed()) {
			IrisVulkanUniformSnapshot.Snapshot snapshot = IrisVulkanUniformSnapshot.capture(resources.uniformFields());
			buffer = snapshot.upload();
			SNAPSHOT_BUFFERS.put(pass, buffer);
			GpuBuffer retainedBuffer = buffer;
			RenderSystem.queueFencedTask(() -> {
				if (SNAPSHOT_BUFFERS.remove(pass, retainedBuffer)) {
					retainedBuffer.close();
				}
			});
		}

		pass.setUniform(IrisVulkanUniformSnapshot.BLOCK_NAME, buffer.slice());
		return buffer;
	}

	private static IllegalStateException missingUniform(String passLabel, String name) {
		return new IllegalStateException("Missing Vulkan " + passLabel + " uniform binding for " + name
			+ "; the resource must be supported explicitly or the pass must be disabled");
	}

	private static void bindScreenPassTextures(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   GpuTextureView finalSourceView, String passLabel, TextureStage stage) {
		List<String> requiredSamplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
		java.util.HashSet<String> boundSamplers = new java.util.HashSet<>();

		for (String sampler : requiredSamplers) {
			if (!boundSamplers.add(sampler)) {
				continue;
			}

			TextureBinding binding = screenPassTextureBinding(sampler, depthView, finalSourceView, passLabel, stage);
			if (!isUsable(binding)) {
				throw new IllegalStateException("Invalid Vulkan screen pass texture binding for " + sampler);
			}

			pass.bindTexture(sampler, binding.view(), binding.sampler());
		}
	}

	private static TextureBinding screenPassTextureBinding(String sampler, GpuTextureView depthView, String passLabel,
														  TextureStage stage) {
		return screenPassTextureBinding(sampler, depthView, null, passLabel, stage);
	}

	private static TextureBinding screenPassTextureBinding(String sampler, GpuTextureView depthView,
														  GpuTextureView finalSourceView, String passLabel,
														  TextureStage stage) {
		if (IrisNativeVulkan.shouldDummyScreenPassSampler(sampler)) {
			if (WARNED_DUMMY_TEXTURES.add("screen:" + passLabel + ":" + sampler)) {
				Iris.logger.warn("Using dummy Vulkan screen pass texture for {} sampler {} because iris.vulkan.screenPassDummySamplers={} is set.",
					passLabel, sampler, IrisNativeVulkan.screenPassDummySamplers());
			}

			return dummyTextureBinding();
		}

		if (sampler.equals("noisetex")) {
			return fromCustom(IrisVulkanCustomTextures.noise(currentNoiseTextureResolution()));
		}

		IrisVulkanCustomTextures.Binding customBinding = IrisVulkanCustomTextures.find(stage, sampler);

		if (customBinding != null) {
			return fromCustom(customBinding);
		}

		GpuSampler gpuSampler = IrisSamplers.getTerrainCache(1);

		if (isFinalSourceSampler(sampler) && finalSourceView != null && !finalSourceView.isClosed()) {
			return new TextureBinding(finalSourceView, gpuSampler);
		}

		GpuTextureView targetView = IrisVulkanGbufferTargets.colorSamplerView(sampler);

		if (targetView != null) {
			return new TextureBinding(targetView, gpuSampler);
		}

		if ((sampler.equals("depthtex0") || sampler.equals("gdepthtex") || sampler.equals("depthtex1") || sampler.equals("depthtex2"))
			&& depthView != null && !depthView.isClosed()) {
			return new TextureBinding(depthView, gpuSampler);
		}

		if (isAlbedoSampler(sampler)) {
			targetView = IrisVulkanGbufferTargets.colorSamplerView("colortex" + IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET);

			if (targetView != null) {
				return new TextureBinding(targetView, gpuSampler);
			}
		}

		throw new IllegalStateException("Missing Vulkan screen pass " + passLabel
			+ " texture binding for " + sampler + "; the resource must be supported explicitly");
	}

	private static boolean isFinalSourceSampler(String sampler) {
		return sampler.equals("colortex" + IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET)
			|| sampler.equals("gnormal")
			|| sampler.equals("gaux3");
	}

	private static TextureBinding fromCustom(IrisVulkanCustomTextures.Binding binding) {
		return new TextureBinding(binding.view(), binding.sampler());
	}

	private static void bindUniformAliases(RenderPass pass, VulkanRenderPass backend, RenderPipeline pipeline) {
		Map<String, GpuBufferSlice> uniforms = ((VKOnly_VulkanRenderPassAccessor) backend).iris$getUniforms();
		List<BindGroupLayout.UniformDescription> requiredUniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		GpuBuffer snapshotBuffer = bindUniformSnapshot(pass, pipeline, "render pass");

		for (BindGroupLayout.UniformDescription uniform : requiredUniforms) {
			String name = uniform.name();

			if (name.equals(IrisVulkanUniformSnapshot.BLOCK_NAME)) {
				if (snapshotBuffer == null) {
					throw missingUniform("render pass", name);
				}

				continue;
			}

			GpuBufferSlice directUniform = uniforms.get(name);
			if (directUniform != null) {
				continue;
			}

			String source = UNIFORM_ALIASES.get(name);
			GpuBufferSlice aliasedUniform = source == null ? null : uniforms.get(source);
			if (aliasedUniform != null) {
				pass.setUniform(name, aliasedUniform);
				continue;
			}

			if (bindDefaultUniform(pass, name)) {
				continue;
			}

			throw missingUniform("render pass", name);
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
			TextureBinding directBinding = textureBinding(textures.get(sampler));
			if (isUsable(directBinding)) {
				continue;
			}

			TextureBinding binding = findTextureBinding(sampler, textures);

			if (isUsable(binding)) {
				pass.bindTexture(sampler, binding.view(), binding.sampler());
				continue;
			}

			GpuTextureView view = findRenderTargetView(sampler, colorAttachments);
			GpuSampler gpuSampler = findFallbackSampler(textures);

			if (view != null && gpuSampler != null) {
				pass.bindTexture(sampler, view, gpuSampler);
				continue;
			}

			throw new IllegalStateException("Missing Vulkan texture binding for " + sampler
				+ "; the resource must be supported explicitly");
		}
	}

	private static TextureBinding findTextureBinding(String sampler, Map<String, Object> textures) {
		if (sampler.equals("noisetex")) {
			return fromCustom(IrisVulkanCustomTextures.noise(currentNoiseTextureResolution()));
		}

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

	private static int currentNoiseTextureResolution() {
		try {
			Optional<ShaderPack> currentPack = Iris.getCurrentPack();

			if (currentPack.isEmpty()) {
				return 256;
			}

			NamespacedId dimension = Iris.getCurrentDimension();
			if (dimension == null) {
				dimension = DimensionId.OVERWORLD;
			}

			return Math.max(1, currentPack.get().getProgramSet(dimension).getPackDirectives().getNoiseTextureResolution());
		} catch (RuntimeException e) {
			Iris.logger.warn("Could not read shaderpack noisetex resolution for native Vulkan; using 256: {}", e.getMessage());
			return 256;
		}
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
		return textures.values().stream().map(IrisVulkanRenderPassBindings::textureBinding)
			.filter(IrisVulkanRenderPassBindings::isUsable).findFirst().orElse(null);
	}

	private static TextureBinding textureBinding(Object value) {
		if (value == null) {
			return null;
		}

		VKOnly_TextureViewAndSamplerAccessor accessor = (VKOnly_TextureViewAndSamplerAccessor) value;
		return new TextureBinding(accessor.iris$getView(), accessor.iris$getSampler());
	}

	private static boolean isUsable(TextureBinding binding) {
		return binding != null && binding.view() != null && !binding.view().isClosed() && binding.sampler() != null;
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

	private record ScreenPassContext(String passLabel, TextureStage stage, GpuTextureView depthView,
									 GpuTextureView finalSourceView) {
	}
}
