package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisBackend;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanDeviceAccessor;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.IrisShaderSource;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.RenderPipelines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisNativeVulkan {
	private static final ScreenPassMode SCREEN_PASS_MODE = ScreenPassMode.fromProperties();
	private static final String SELECTED_SCREEN_PASS = selectedScreenPassFromProperties();
	private static boolean loggedRendererInit;
	private static boolean loggedScreenPassesDisabled;
	private static boolean attemptedInitialShaderpackLoad;
	private static ShaderPack sourcePack;
	private static NamespacedId sourceDimension;
	private static IrisVulkanShaderSourceMap sourceMap;
	private static ShaderPack finalPassPack;
	private static NamespacedId finalPassDimension;
	private static IrisVulkanFinalPassRenderer finalPassRenderer;
	private static boolean finalPassFailed;
	private static boolean finalPassRenderedThisFrame;
	private static final Set<RenderPipeline> missingShaders = ConcurrentHashMap.newKeySet();
	private static final Set<ShaderKey> mappedShaders = ConcurrentHashMap.newKeySet();
	private static final Set<ShaderKey> unsupportedShaders = ConcurrentHashMap.newKeySet();
	private static final Set<CacheKey> failedPipelines = ConcurrentHashMap.newKeySet();
	private static final Map<CacheKey, VulkanRenderPipeline> compiledPipelines = new ConcurrentHashMap<>();
	private static final Map<RenderPipeline, CustomPipelineSource> customPipelineSources = new ConcurrentHashMap<>();
	private static final Map<CompatiblePipelineKey, RenderPipeline> compatiblePipelines = new ConcurrentHashMap<>();

	private IrisNativeVulkan() {
	}

	public static void onRendererInitialized(GpuDevice device) {
		if (!IrisBackend.isVulkan(device) || loggedRendererInit) {
			return;
		}

		loggedRendererInit = true;
		Iris.logger.info("Detected Minecraft's native Vulkan backend; Iris Vulkan migration hooks are active.");
		loadShaderpackIfReady();
	}

	public static Optional<VulkanRenderPipeline> tryOverridePipeline(VulkanDevice device, RenderPipeline renderPipeline) {
		CustomPipelineSource customSource = customPipelineSources.get(renderPipeline);

		if (customSource != null) {
			return compileCustomOverride(device, renderPipeline, customSource);
		}

		if (renderPipeline == CompositeRenderer.COMPOSITE_PIPELINE) {
			return Optional.empty();
		}

		if (renderPipeline == RenderPipelines.ANIMATE_SPRITE_BLIT || renderPipeline == RenderPipelines.ANIMATE_SPRITE_INTERPOLATE) {
			return Optional.empty();
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		IrisRenderingPipeline irisPipeline = pipeline instanceof IrisRenderingPipeline typedPipeline ? typedPipeline : null;

		if (ImmediateState.bypass || !shouldOverrideShaders(irisPipeline)) {
			return Optional.empty();
		}

		ShaderKey shaderKey = IrisPipelines.getPipeline(irisPipeline, renderPipeline);

		if (shaderKey == null) {
			if (missingShaders.add(renderPipeline)) {
				Iris.logger.warn("Missing Vulkan shader mapping for render pipeline {}", renderPipeline.getLocation());
			}

			return Optional.empty();
		}

		if (isNativeVulkanGbufferShader(shaderKey) && !shouldCaptureGbuffers()) {
			return Optional.empty();
		}

		if (!shouldAttemptVulkanOverride(shaderKey)) {
			return Optional.empty();
		}

		IrisShaderSource source = getSource(irisPipeline, shaderKey);

		if (mappedShaders.add(shaderKey)) {
			String sourceName = source == null ? "<missing source>" : source.name();

			Iris.logger.info("Mapped Vulkan render pipeline {} to Iris shader key {} / source {}.",
				renderPipeline.getLocation(), shaderKey.name(), sourceName);
		}

		return compileOverride(device, renderPipeline, shaderKey, source);
	}

	public static void clearDevicePipelineCache(VulkanDevice device) {
		compiledPipelines.entrySet().removeIf(entry -> {
			if (entry.getKey().device() != device) {
				return false;
			}

			entry.getValue().destroy();
			return true;
		});

		failedPipelines.removeIf(key -> key.device() == device);
	}

	public static void registerCustomPipelineSource(RenderPipeline renderPipeline, String name, String vertex, String fragment) {
		registerCustomPipelineSource(renderPipeline, name, vertex, fragment, true);
	}

	public static void registerCustomPipelineSource(RenderPipeline renderPipeline, String name, String vertex, String fragment,
													boolean collapseFragmentOutputs) {
		customPipelineSources.put(renderPipeline, new CustomPipelineSource(name, vertex, fragment, collapseFragmentOutputs));
	}

	public static void unregisterCustomPipelineSource(RenderPipeline renderPipeline) {
		customPipelineSources.remove(renderPipeline);
		compiledPipelines.entrySet().removeIf(entry -> {
			if (entry.getKey().renderPipeline() != renderPipeline) {
				return false;
			}

			entry.getValue().destroy();
			return true;
		});
		failedPipelines.removeIf(key -> key.renderPipeline() == renderPipeline);
	}

	public static RenderPipeline compatiblePipelineForGbufferPass(RenderPipeline renderPipeline, int attachmentCount) {
		if (attachmentCount <= 0 || renderPipeline.getColorTargetStates().length == attachmentCount) {
			return renderPipeline;
		}

		CompatiblePipelineKey key = new CompatiblePipelineKey(renderPipeline, attachmentCount);
		return compatiblePipelines.computeIfAbsent(key, ignored -> {
			ColorTargetState[] colorTargetStates = createCompatibleColorTargetStates(renderPipeline, attachmentCount);
			VertexFormat[] vertexFormats = renderPipeline.getVertexFormatBindings().clone();
			ShaderDefines shaderDefines = renderPipeline.getShaderDefines();
			RenderPipeline compatible = net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPipelineAccessor.iris$create(
				renderPipeline.getLocation(),
				renderPipeline.getVertexShader(),
				renderPipeline.getFragmentShader(),
				shaderDefines,
				renderPipeline.getBindGroupLayouts(),
				colorTargetStates,
				renderPipeline.getDepthStencilState(),
				renderPipeline.getPolygonMode(),
				renderPipeline.isCull(),
				vertexFormats,
				renderPipeline.getPrimitiveTopology(),
				renderPipeline.getSortKey());
			IrisPipelines.copyPipeline(renderPipeline, compatible);
			return compatible;
		});
	}

	private static ColorTargetState[] createCompatibleColorTargetStates(RenderPipeline renderPipeline, int attachmentCount) {
		ColorTargetState[] colorTargetStates = IrisVulkanShaderResources.createColorTargetStates(attachmentCount);
		ColorTargetState[] originalStates = renderPipeline.getColorTargetStates();

		if (originalStates.length > 0) {
			colorTargetStates[0] = originalStates[0];
		}

		return colorTargetStates;
	}

	public static int[] getDrawBuffersForCurrentPhase() {
		ShaderKey shaderKey = shaderKeyForCurrentPhase();

		if (shaderKey == null) {
			return new int[0];
		}

		IrisShaderSource source = getSource(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline irisPipeline ? irisPipeline : null,
			shaderKey);

		if (source == null || source.fragment() == null) {
			return new int[0];
		}

		return IrisVulkanShaderResources.drawBuffersFromSource(source.fragment());
	}

	public static void renderFinalPassIfReady() {
		if (finalPassRenderedThisFrame) {
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		if (!prepareFinalPassForFrame()) {
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		finalPassRenderedThisFrame = true;

		try {
			finalPassRenderer.render();
		} catch (RuntimeException e) {
			finalPassFailed = true;
			IrisVulkanGbufferTargets.finishFrame();
			destroyFinalPassRenderer();
			Iris.logger.warn("Disabling Iris native Vulkan final pass for this shaderpack after an error: {}", e.getMessage());
		}
	}

	public static boolean beginFinalPassFrame() {
		finalPassRenderedThisFrame = false;
		return prepareFinalPassForFrame();
	}

	public static boolean prepareFinalPassForFrame() {
		if (!IrisBackend.isVulkan(RenderSystem.getDevice())) {
			return false;
		}

		if (!screenPassGraphEnabled()) {
			destroyFinalPassRenderer();
			return false;
		}

		loadShaderpackIfReady();
		Optional<ShaderPack> currentPack = Iris.getCurrentPack();

		if (currentPack.isEmpty()) {
			destroyFinalPassRenderer();
			return false;
		}

		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null) {
			dimension = DimensionId.OVERWORLD;
		}

		ShaderPack pack = currentPack.get();
		boolean rendererChanged = finalPassPack != pack || !dimension.equals(finalPassDimension);

		if (rendererChanged) {
			destroyFinalPassRenderer();
			finalPassPack = pack;
			finalPassDimension = dimension;
			finalPassFailed = false;
		}

		if (finalPassFailed) {
			return false;
		}

		try {
			if (finalPassRenderer == null) {
				finalPassRenderer = new IrisVulkanFinalPassRenderer(pack.getProgramSet(dimension));

				if (!finalPassRenderer.hasRunnablePasses()) {
					finalPassFailed = true;
					destroyFinalPassRenderer();
					return false;
				}
			}

			return true;
		} catch (RuntimeException e) {
			finalPassFailed = true;
			destroyFinalPassRenderer();
			Iris.logger.warn("Disabling Iris native Vulkan final pass for this shaderpack after an error: {}", e.getMessage());
			return false;
		}
	}

	public static boolean shouldCaptureGbuffers() {
		return SCREEN_PASS_MODE.runsLogicalPasses()
			&& !finalPassFailed
			&& finalPassRenderer != null
			&& finalPassRenderer.requiresGbufferCapture();
	}

	public static ScreenPassMode screenPassMode() {
		return SCREEN_PASS_MODE;
	}

	public static String selectedScreenPassLabel() {
		return SELECTED_SCREEN_PASS;
	}

	private static boolean screenPassGraphEnabled() {
		if (SCREEN_PASS_MODE.buildsGraph()) {
			return true;
		}

		if (!loggedScreenPassesDisabled) {
			loggedScreenPassesDisabled = true;
			Iris.logger.info("Iris native Vulkan shaderpack screen pass graph is disabled.");
		}

		return false;
	}

	private static String selectedScreenPassFromProperties() {
		String selected = System.getProperty("iris.vulkan.screenPass");

		if (selected == null || selected.isBlank()) {
			return null;
		}

		return selected.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static void destroyFinalPassRenderer() {
		if (finalPassRenderer != null) {
			finalPassRenderer.destroy();
			finalPassRenderer = null;
		}

		finalPassRenderedThisFrame = false;
		IrisVulkanGbufferTargets.close();
		IrisVulkanRenderPassBindings.closePackResources();
	}

	private static void loadShaderpackIfReady() {
		if (attemptedInitialShaderpackLoad || Iris.getIrisConfig() == null) {
			return;
		}

		attemptedInitialShaderpackLoad = true;

		try {
			Iris.loadShaderpack();
		} catch (RuntimeException e) {
			Iris.logger.warn("Could not load Iris shaderpack for native Vulkan yet: {}", e.getMessage());
		}
	}

	private static boolean shouldOverrideShaders(IrisRenderingPipeline irisPipeline) {
		if (irisPipeline != null) {
			return irisPipeline.shouldOverrideShaders();
		}

		loadShaderpackIfReady();
		return Iris.getCurrentPack().isPresent();
	}

	private static boolean shouldAttemptVulkanOverride(ShaderKey shaderKey) {
		return shaderKey == ShaderKey.SODIUM_TERRAIN_SOLID
			|| shaderKey == ShaderKey.SODIUM_TERRAIN_CUTOUT;
	}

	private static ShaderKey shaderKeyForCurrentPhase() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		WorldRenderingPhase phase = pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase();

		return switch (phase) {
			case TERRAIN_SOLID -> ShaderKey.SODIUM_TERRAIN_SOLID;
			case TERRAIN_CUTOUT, TERRAIN_CUTOUT_MIPPED -> ShaderKey.SODIUM_TERRAIN_CUTOUT;
			default -> null;
		};
	}

	private static boolean isNativeVulkanGbufferShader(ShaderKey shaderKey) {
		return shaderKey == ShaderKey.SODIUM_TERRAIN_SOLID
			|| shaderKey == ShaderKey.SODIUM_TERRAIN_CUTOUT;
	}

	private static IrisShaderSource getSource(IrisRenderingPipeline irisPipeline, ShaderKey shaderKey) {
		if (irisPipeline != null) {
			return irisPipeline.getShaderMap().getSource(shaderKey);
		}

		Optional<ShaderPack> currentPack = Iris.getCurrentPack();

		if (currentPack.isEmpty()) {
			return null;
		}

		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null) {
			dimension = DimensionId.OVERWORLD;
		}

		ShaderPack pack = currentPack.get();

		if (sourceMap == null || sourcePack != pack || !dimension.equals(sourceDimension)) {
			sourcePack = pack;
			sourceDimension = dimension;
			sourceMap = new IrisVulkanShaderSourceMap(pack.getProgramSet(dimension));
		}

		return sourceMap.getSource(shaderKey);
	}

	private static Optional<VulkanRenderPipeline> compileCustomOverride(VulkanDevice device, RenderPipeline renderPipeline,
																		CustomPipelineSource source) {
		CacheKey cacheKey = new CacheKey(device, renderPipeline, source);

		if (failedPipelines.contains(cacheKey)) {
			return Optional.empty();
		}

		VulkanRenderPipeline cached = compiledPipelines.get(cacheKey);
		if (cached != null) {
			return Optional.of(cached);
		}

		try {
			VulkanRenderPipeline compiled = compileCustomVulkanPipeline(device, renderPipeline, source);

			if (!compiled.isValid()) {
				failedPipelines.add(cacheKey);
				Iris.logger.warn("Vulkan custom pass {} compiled to an invalid pipeline; falling back to vanilla.",
					source.name());
				return Optional.empty();
			}

			compiledPipelines.put(cacheKey, compiled);
			Iris.logger.info("Compiled Vulkan custom pass {} for pipeline {}.",
				source.name(), renderPipeline.getLocation());
			return Optional.of(compiled);
		} catch (ShaderCompileException | RuntimeException e) {
			failedPipelines.add(cacheKey);
			Iris.logger.warn("Failed to compile Vulkan custom pass {}: {}", source.name(), e.getMessage());
			logDeviceDebugMessages(device, renderPipeline);
			return Optional.empty();
		}
	}

	private static Optional<VulkanRenderPipeline> compileOverride(VulkanDevice device, RenderPipeline renderPipeline,
																  ShaderKey shaderKey, IrisShaderSource source) {
		if (source == null || source.vertex() == null || source.fragment() == null) {
			if (unsupportedShaders.add(shaderKey)) {
				Iris.logger.warn("Cannot compile Vulkan override for {} because Iris has no complete vertex/fragment source.",
					shaderKey.name());
			}

			return Optional.empty();
		}

		if (source.geometry() != null || source.tessControl() != null || source.tessEval() != null) {
			if (unsupportedShaders.add(shaderKey)) {
				Iris.logger.warn("Cannot compile Vulkan override for {} yet because geometry/tessellation stages are present.",
					shaderKey.name());
			}

			return Optional.empty();
		}

		CacheKey cacheKey = new CacheKey(device, renderPipeline, source);

		if (failedPipelines.contains(cacheKey)) {
			return Optional.empty();
		}

		VulkanRenderPipeline cached = compiledPipelines.get(cacheKey);
		if (cached != null) {
			return Optional.of(cached);
		}

		try {
			VulkanRenderPipeline compiled = compileVulkanPipeline(device, renderPipeline, shaderKey, source);

			if (!compiled.isValid()) {
				failedPipelines.add(cacheKey);
				Iris.logger.warn("Vulkan override for {} compiled to an invalid pipeline; falling back to vanilla.",
					renderPipeline.getLocation());
				return Optional.empty();
			}

			compiledPipelines.put(cacheKey, compiled);
			Iris.logger.info("Compiled Vulkan override for {} using Iris shader key {} / source {}.",
				renderPipeline.getLocation(), shaderKey.name(), source.name());
			return Optional.of(compiled);
		} catch (ShaderCompileException | RuntimeException e) {
			failedPipelines.add(cacheKey);
			Iris.logger.warn("Failed to compile Vulkan override for {} using Iris shader key {}: {}",
				renderPipeline.getLocation(), shaderKey.name(), e.getMessage());
			logDeviceDebugMessages(device, renderPipeline);
			return Optional.empty();
		}
	}

	private static VulkanRenderPipeline compileCustomVulkanPipeline(VulkanDevice device, RenderPipeline renderPipeline,
																	CustomPipelineSource source) throws ShaderCompileException {
		GlslCompiler compiler = ((VKOnly_VulkanDeviceAccessor) device).iris$getGlslCompiler();
		String name = "iris_custom_" + source.name() + "_" + renderPipeline.getLocation().toDebugFileName();
		String vertex = GlslPreprocessor.injectDefines(source.vertex(), renderPipeline.getShaderDefines());
		String fragment = GlslPreprocessor.injectDefines(source.fragment(), renderPipeline.getShaderDefines());
		IrisVulkanShaderResources.Prepared prepared = IrisVulkanShaderResources.prepareScreenPass(renderPipeline, vertex, fragment,
			source.collapseFragmentOutputs());

		try {
			try (IntermediaryShaderModule vertexModule = compiler.createIntermediary(name + "_vertex", prepared.vertex(), ShaderType.VERTEX);
				 IntermediaryShaderModule fragmentModule = compiler.createIntermediary(name + "_fragment", prepared.fragment(), ShaderType.FRAGMENT)) {
				GlslCompiler.CompiledModules modules = compiler.compile(device, prepared.pipeline(), vertexModule, fragmentModule);
				return VulkanRenderPipeline.compile(device, modules.layout(), prepared.pipeline(), modules.vertex(), modules.fragment());
			}
		} catch (ShaderCompileException | RuntimeException e) {
			dumpFailedShaderSources(name, prepared);
			throw e;
		}
	}

	private static VulkanRenderPipeline compileVulkanPipeline(VulkanDevice device, RenderPipeline renderPipeline,
															 ShaderKey shaderKey, IrisShaderSource source) throws ShaderCompileException {
		GlslCompiler compiler = ((VKOnly_VulkanDeviceAccessor) device).iris$getGlslCompiler();
		String name = "iris_" + shaderKey.name().toLowerCase() + "_" + renderPipeline.getLocation().toDebugFileName();
		String vertex = GlslPreprocessor.injectDefines(source.vertex(), renderPipeline.getShaderDefines());
		String fragment = GlslPreprocessor.injectDefines(source.fragment(), renderPipeline.getShaderDefines());
		boolean gbufferPass = isNativeVulkanGbufferShader(shaderKey) && renderPipeline.getColorTargetStates().length > 1;
		IrisVulkanShaderResources.Prepared prepared = gbufferPass
			? IrisVulkanShaderResources.prepareGbufferPass(renderPipeline, shaderKey, vertex, fragment,
				renderPipeline.getColorTargetStates().length)
			: IrisVulkanShaderResources.prepare(renderPipeline, shaderKey, vertex, fragment);

		try {
			try (IntermediaryShaderModule vertexModule = compiler.createIntermediary(name + "_vertex", prepared.vertex(), ShaderType.VERTEX);
				 IntermediaryShaderModule fragmentModule = compiler.createIntermediary(name + "_fragment", prepared.fragment(), ShaderType.FRAGMENT)) {
				GlslCompiler.CompiledModules modules = compiler.compile(device, prepared.pipeline(), vertexModule, fragmentModule);
				return VulkanRenderPipeline.compile(device, modules.layout(), prepared.pipeline(), modules.vertex(), modules.fragment());
			}
		} catch (ShaderCompileException | RuntimeException e) {
			dumpFailedShaderSources(name, prepared);
			throw e;
		}
	}

	private static void logDeviceDebugMessages(VulkanDevice device, RenderPipeline renderPipeline) {
		try {
			var debugMessages = device.getLastDebugMessages();

			if (debugMessages.isEmpty()) {
				return;
			}

			Iris.logger.warn("Vulkan debug messages after failed pipeline {}:", renderPipeline.getLocation());

			for (String message : debugMessages) {
				Iris.logger.warn("  {}", message);
			}
		} catch (RuntimeException e) {
			Iris.logger.warn("Could not read Vulkan debug messages after failed pipeline {}: {}",
				renderPipeline.getLocation(), e.getMessage());
		}
	}

	private static void dumpFailedShaderSources(String name, IrisVulkanShaderResources.Prepared prepared) {
		Path directory = Path.of("iris-vulkan-dumps");

		try {
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(name + ".vert.glsl"), prepared.vertex(), StandardCharsets.UTF_8);
			Files.writeString(directory.resolve(name + ".frag.glsl"), prepared.fragment(), StandardCharsets.UTF_8);
			Iris.logger.warn("Dumped failed Vulkan shader sources to {}", directory.toAbsolutePath());
		} catch (IOException e) {
			Iris.logger.warn("Could not dump failed Vulkan shader sources: {}", e.getMessage());
		}
	}

	private record CacheKey(VulkanDevice device, RenderPipeline renderPipeline, Object source) {
	}

	private record CompatiblePipelineKey(RenderPipeline renderPipeline, int attachmentCount) {
	}

	private record CustomPipelineSource(String name, String vertex, String fragment, boolean collapseFragmentOutputs) {
	}

	public enum ScreenPassMode {
		OFF(false, false, false),
		BUILD_ONLY(true, false, false),
		FINAL(true, true, false),
		ALL(true, true, true);

		private final boolean buildsGraph;
		private final boolean runsFinalPass;
		private final boolean runsLogicalPasses;

		ScreenPassMode(boolean buildsGraph, boolean runsFinalPass, boolean runsLogicalPasses) {
			this.buildsGraph = buildsGraph;
			this.runsFinalPass = runsFinalPass;
			this.runsLogicalPasses = runsLogicalPasses;
		}

		public boolean buildsGraph() {
			return buildsGraph;
		}

		public boolean runsFinalPass() {
			return runsFinalPass;
		}

		public boolean runsLogicalPasses() {
			return runsLogicalPasses;
		}

		private static ScreenPassMode fromProperties() {
			String configured = System.getProperty("iris.vulkan.screenPassMode");

			if (configured != null && !configured.isBlank()) {
				return switch (configured.trim().toLowerCase(java.util.Locale.ROOT)) {
					case "off", "false", "disabled" -> OFF;
					case "build", "build_only", "build-only", "diagnostic", "diagnostics" -> BUILD_ONLY;
					case "final", "final_only", "final-only" -> FINAL;
					case "all", "true", "enabled" -> ALL;
					default -> BUILD_ONLY;
				};
			}

			if (Boolean.getBoolean("iris.vulkan.experimentalScreenPasses")) {
				return FINAL;
			}

			return BUILD_ONLY;
		}
	}
}
