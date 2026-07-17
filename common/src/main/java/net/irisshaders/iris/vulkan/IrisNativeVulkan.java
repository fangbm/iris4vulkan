package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
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
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.RenderPipelines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisNativeVulkan {
	private static final ScreenPassMode SCREEN_PASS_MODE = ScreenPassMode.fromProperties();
	private static final String SELECTED_SCREEN_PASS = selectedScreenPassFromProperties();
	private static final ScreenPassDrawMode SCREEN_PASS_DRAW_MODE = ScreenPassDrawMode.fromProperties();
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
	private static final Set<ShaderMappingLogKey> loggedCapabilityMappings = ConcurrentHashMap.newKeySet();
	private static final Set<WorldRenderingPhase> loggedPhaseMappings = ConcurrentHashMap.newKeySet();
	private static final Set<CacheKey> failedPipelines = ConcurrentHashMap.newKeySet();
	private static final Map<CacheKey, VulkanRenderPipeline> compiledPipelines = new ConcurrentHashMap<>();
	private static final Map<RenderPipeline, CustomPipelineSource> customPipelineSources = new ConcurrentHashMap<>();
	private static final Map<CompatiblePipelineKey, RenderPipeline> compatiblePipelines = new ConcurrentHashMap<>();
	private static final List<ShaderCapability> SHADER_CAPABILITY_TABLE = createShaderCapabilityTable();
	private static final Map<ShaderKey, ShaderCapability> SHADER_CAPABILITIES = indexShaderCapabilities();
	private static final ShaderCapability UNSUPPORTED_SHADER_CAPABILITY = new ShaderCapability(
		"gbuffers/other", CapabilityStatus.UNSUPPORTED, Set.of(), Set.of(), false,
		"No native Vulkan route is defined for these shader keys.");
	private static final Map<WorldRenderingPhase, ShaderKey> PHASE_SHADER_KEYS = createPhaseShaderKeys();
	private static boolean loggedCapabilitySummary;

	private IrisNativeVulkan() {
	}

	public static void onRendererInitialized(GpuDevice device) {
		if (!IrisBackend.isVulkan(device) || loggedRendererInit) {
			return;
		}

		loggedRendererInit = true;
		Iris.logger.info("Detected Minecraft's native Vulkan backend; Iris Vulkan migration hooks are active.");
		logCapabilitySummary();
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
			logCapabilityBlocked(renderPipeline, shaderKey);
			return Optional.empty();
		}

		IrisShaderSource source = getSource(irisPipeline, shaderKey);

		if (mappedShaders.add(shaderKey)) {
			String sourceName = source == null ? "<missing source>" : source.name();
			String sourceKind = source == null ? "missing" : source.fallback() ? "fallback" : "direct";
			ShaderCapability capability = capabilityFor(shaderKey);

			Iris.logger.info("Mapped Vulkan render pipeline {} to {} / category={} / phase={} / program={} / source={} ({}).",
				renderPipeline.getLocation(), shaderKey.name(), capability.category(), currentPhase(),
				shaderKey.getProgram().getSourceName(), sourceName, sourceKind);
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

	public static RenderPipeline compatiblePipelineForGbufferPass(RenderPipeline renderPipeline, List<GpuFormat> attachmentFormats) {
		if (attachmentFormats == null || attachmentFormats.isEmpty()) {
			return renderPipeline;
		}

		List<GpuFormat> formats = List.copyOf(attachmentFormats);
		ColorTargetState[] originalStates = renderPipeline.getColorTargetStates();
		if (originalStates.length == formats.size() && matchesFormats(originalStates, formats)) {
			return renderPipeline;
		}

		CompatiblePipelineKey key = new CompatiblePipelineKey(renderPipeline, formats);
		return compatiblePipelines.computeIfAbsent(key, ignored -> {
			ColorTargetState[] colorTargetStates = createCompatibleColorTargetStates(renderPipeline, formats);
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

	private static ColorTargetState[] createCompatibleColorTargetStates(RenderPipeline renderPipeline, List<GpuFormat> attachmentFormats) {
		ColorTargetState[] originalStates = renderPipeline.getColorTargetStates();
		ColorTargetState[] colorTargetStates = new ColorTargetState[attachmentFormats.size()];

		for (int i = 0; i < attachmentFormats.size(); i++) {
			ColorTargetState original = i < originalStates.length ? originalStates[i] : ColorTargetState.DEFAULT;
			colorTargetStates[i] = new ColorTargetState(original.blendFunction(), attachmentFormats.get(i), original.writeMask());
		}

		return colorTargetStates;
	}

	private static boolean matchesFormats(ColorTargetState[] colorTargetStates, List<GpuFormat> attachmentFormats) {
		for (int i = 0; i < attachmentFormats.size(); i++) {
			if (colorTargetStates[i].format() != attachmentFormats.get(i)) {
				return false;
			}
		}

		return true;
	}

	public static int[] getDrawBuffersForCurrentPhase() {
		ShaderKey shaderKey = shaderKeyForCurrentPhase();

		if (shaderKey == null) {
			return new int[0];
		}

		IrisVulkanShaderSourceMap currentSourceMap = getSourceMap();
		if (currentSourceMap == null) {
			return new int[0];
		}

		ProgramDirectives directives = currentSourceMap.getDirectives(shaderKey);
		return directives == null ? new int[0] : java.util.Arrays.stream(directives.getDrawBuffers())
			.filter(target -> target >= 0 && target < IrisVulkanGbufferTargets.COLOR_TARGET_COUNT)
			.toArray();
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

	public static ScreenPassDrawMode screenPassDrawMode() {
		return SCREEN_PASS_DRAW_MODE;
	}

	public static boolean drawShaderpackScreenPasses() {
		return SCREEN_PASS_DRAW_MODE.drawsShaderpack();
	}

	public static String screenPassDummySamplers() {
		return System.getProperty("iris.vulkan.screenPassDummySamplers", "");
	}

	public static boolean shouldDummyScreenPassSampler(String sampler) {
		String configured = screenPassDummySamplers();

		if (configured == null || configured.isBlank()) {
			return false;
		}

		String normalizedSampler = sampler.toLowerCase(java.util.Locale.ROOT);

		for (String token : configured.split("[,; ]+")) {
			String normalized = token.trim().toLowerCase(java.util.Locale.ROOT);

			if (normalized.isEmpty() || normalized.equals("false") || normalized.equals("off") || normalized.equals("none")) {
				continue;
			}

			if (normalized.equals("true") || normalized.equals("all") || normalized.equals("*")) {
				return true;
			}

			if ((normalized.equals("depth") || normalized.equals("depthtex")) && normalizedSampler.startsWith("depthtex")) {
				return true;
			}

			if (normalized.equals("depth") && normalizedSampler.equals("gdepthtex")) {
				return true;
			}

			if ((normalized.equals("color") || normalized.equals("colortex")) && normalizedSampler.startsWith("colortex")) {
				return true;
			}

			if ((normalized.equals("gbuffer") || normalized.equals("g")) && isLegacyGbufferSampler(normalizedSampler)) {
				return true;
			}

			if ((normalized.equals("noise") || normalized.equals("noisetex")) && normalizedSampler.equals("noisetex")) {
				return true;
			}

			if (normalized.equals("custom") && normalizedSampler.startsWith("custom")) {
				return true;
			}

			if (normalizedSampler.equals(normalized)) {
				return true;
			}
		}

		return false;
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
		return capabilityFor(shaderKey).status() == CapabilityStatus.SUPPORTED;
	}

	private static ShaderKey shaderKeyForCurrentPhase() {
		WorldRenderingPhase phase = currentPhase();
		ShaderKey shaderKey = PHASE_SHADER_KEYS.get(phase);

		if (shaderKey != null && loggedPhaseMappings.add(phase)) {
			ShaderCapability capability = capabilityFor(shaderKey);
			Iris.logger.info("Native Vulkan phase route: {} -> {} / category={} / program={} / status={}",
				phase, shaderKey.name(), capability.category(), shaderKey.getProgram().getSourceName(), capability.status());
		}

		return shaderKey != null && capabilityFor(shaderKey).status() == CapabilityStatus.SUPPORTED ? shaderKey : null;
	}

	private static boolean isNativeVulkanGbufferShader(ShaderKey shaderKey) {
		return capabilityFor(shaderKey).gbufferCapture();
	}

	private static WorldRenderingPhase currentPhase() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		return pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase();
	}

	private static ShaderCapability capabilityFor(ShaderKey shaderKey) {
		return SHADER_CAPABILITIES.getOrDefault(shaderKey, UNSUPPORTED_SHADER_CAPABILITY);
	}

	private static void logCapabilityBlocked(RenderPipeline renderPipeline, ShaderKey shaderKey) {
		ShaderCapability capability = capabilityFor(shaderKey);
		ShaderMappingLogKey key = new ShaderMappingLogKey(renderPipeline, shaderKey, capability.status());

		if (loggedCapabilityMappings.add(key)) {
			Iris.logger.info("Native Vulkan route is not enabled for {} -> {} / category={} / phase={} / program={} / status={}: {}",
				renderPipeline.getLocation(), shaderKey.name(), capability.category(), currentPhase(),
				shaderKey.getProgram().getSourceName(), capability.status(), capability.reason());
		}
	}

	private static void logCapabilitySummary() {
		if (loggedCapabilitySummary) {
			return;
		}

		loggedCapabilitySummary = true;
		Iris.logger.info("Native Vulkan shaderpack capability table (screenPassMode={}, execution remains gated):", SCREEN_PASS_MODE);

		for (ShaderCapability capability : SHADER_CAPABILITY_TABLE) {
			Iris.logger.info("  {}={} keys=[{}] programs=[{}]: {}", capability.category(), capability.status(),
				formatNames(capability.shaderKeys()), formatNames(capability.programs()), capability.reason());
		}

		Set<ShaderKey> unclassified = EnumSet.allOf(ShaderKey.class);
		unclassified.removeAll(SHADER_CAPABILITIES.keySet());
		Iris.logger.info("  {}={} keys=[{}]: {}", UNSUPPORTED_SHADER_CAPABILITY.category(),
			UNSUPPORTED_SHADER_CAPABILITY.status(), formatNames(unclassified), UNSUPPORTED_SHADER_CAPABILITY.reason());
	}

	private static String formatNames(Set<?> values) {
		return values.stream().map(Object::toString).sorted().reduce((first, second) -> first + "," + second).orElse("");
	}

	private static List<ShaderCapability> createShaderCapabilityTable() {
		return List.of(
			new ShaderCapability("gbuffers/terrain", CapabilityStatus.SUPPORTED,
				Set.of(ShaderKey.SODIUM_TERRAIN_SOLID, ShaderKey.SODIUM_TERRAIN_CUTOUT), Set.of(ProgramId.TerrainSolid, ProgramId.TerrainCutout), true,
				"Native gbuffer attachments and Sodium vertex/resource adaptation are available."),
			new ShaderCapability("gbuffers/water", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, ShaderKey.TERRAIN_TRANSLUCENT), Set.of(ProgramId.Water), false,
				"Water capture and translucent ordering resources are not wired yet."),
			new ShaderCapability("gbuffers/entities", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.ENTITIES_ALPHA, ShaderKey.ENTITIES_SOLID, ShaderKey.ENTITIES_SOLID_DIFFUSE, ShaderKey.ENTITIES_SOLID_BRIGHT,
					ShaderKey.ENTITIES_CUTOUT, ShaderKey.ENTITIES_CUTOUT_DIFFUSE, ShaderKey.ENTITIES_TRANSLUCENT,
					ShaderKey.ENTITIES_EYES, ShaderKey.ENTITIES_EYES_TRANS, ShaderKey.BLOCK_ENTITY, ShaderKey.BLOCK_ENTITY_BRIGHT,
					ShaderKey.BLOCK_ENTITY_DIFFUSE, ShaderKey.BE_TRANSLUCENT),
				Set.of(ProgramId.Entities, ProgramId.EntitiesTrans, ProgramId.SpiderEyes, ProgramId.Block, ProgramId.BlockTrans), false,
				"Entity and block-entity render-pass resources are not wired yet."),
			new ShaderCapability("gbuffers/hand", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.HAND_CUTOUT, ShaderKey.HAND_CUTOUT_BRIGHT, ShaderKey.HAND_CUTOUT_DIFFUSE,
					ShaderKey.HAND_TEXT, ShaderKey.HAND_TEXT_INTENSITY), Set.of(ProgramId.Hand), false,
				"Hand render-pass routing and bindings are not wired yet."),
			new ShaderCapability("gbuffers/hand_water", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.HAND_TRANSLUCENT, ShaderKey.HAND_WATER_BRIGHT, ShaderKey.HAND_WATER_DIFFUSE,
					ShaderKey.HAND_TEXT_TRANSLUCENT), Set.of(ProgramId.HandWater), false,
				"Hand-water translucent resources are not wired yet."),
			new ShaderCapability("gbuffers/sky", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.SKY_BASIC, ShaderKey.SKY_BASIC_COLOR, ShaderKey.SKY_TEXTURED, ShaderKey.SKY_TEXTURED_COLOR),
				Set.of(ProgramId.SkyBasic, ProgramId.SkyTextured), false,
				"Sky phases are deliberately left on vanilla Vulkan until their targets and uniforms are integrated."),
			new ShaderCapability("gbuffers/weather", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.WEATHER), Set.of(ProgramId.Weather), false,
				"Weather render-pass resources are not wired yet."),
			new ShaderCapability("gbuffers/beacon", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.BEACON), Set.of(ProgramId.BeaconBeam), false,
				"Beacon render-pass resources are not wired yet."),
			new ShaderCapability("gbuffers/glint", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.GLINT), Set.of(ProgramId.ArmorGlint), false,
				"Glint render-pass resources are not wired yet."),
			new ShaderCapability("shadow", CapabilityStatus.PLANNED,
				Set.of(ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID, ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
					ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT, ShaderKey.SHADOW_TERRAIN_CUTOUT, ShaderKey.SHADOW_TRANSLUCENT,
					ShaderKey.SHADOW_ENTITIES_CUTOUT, ShaderKey.SHADOW_BLOCK, ShaderKey.SHADOW_BEACON_BEAM,
					ShaderKey.SHADOW_BASIC, ShaderKey.SHADOW_BASIC_COLOR, ShaderKey.SHADOW_TEX, ShaderKey.SHADOW_TEX_COLOR,
					ShaderKey.SHADOW_CLOUDS, ShaderKey.SHADOW_LINES, ShaderKey.SHADOW_LEASH, ShaderKey.SHADOW_LIGHTNING,
					ShaderKey.SHADOW_PARTICLES, ShaderKey.SHADOW_TEXT, ShaderKey.SHADOW_TEXT_BG, ShaderKey.SHADOW_TEXT_INTENSITY,
					ShaderKey.MEKANISM_FLAME_SHADOW),
				Set.of(ProgramId.Shadow, ProgramId.ShadowSolid, ProgramId.ShadowCutout, ProgramId.ShadowWater,
					ProgramId.ShadowEntities, ProgramId.ShadowLightning, ProgramId.ShadowBlock), false,
				"Shadow targets, culling, and shadow-specific bindings are not wired yet."),
			new ShaderCapability("distant_horizons", CapabilityStatus.PLANNED, Set.of(),
				Set.of(ProgramId.DhTerrain, ProgramId.DhWater, ProgramId.DhGeneric, ProgramId.DhShadow), false,
				"DH owns separate framebuffers and integration hooks; native Vulkan execution is not enabled yet.")
		);
	}

	private static Map<ShaderKey, ShaderCapability> indexShaderCapabilities() {
		Map<ShaderKey, ShaderCapability> indexed = new EnumMap<>(ShaderKey.class);

		for (ShaderCapability capability : SHADER_CAPABILITY_TABLE) {
			for (ShaderKey shaderKey : capability.shaderKeys()) {
				indexed.put(shaderKey, capability);
			}
		}

		return Map.copyOf(indexed);
	}

	private static Map<WorldRenderingPhase, ShaderKey> createPhaseShaderKeys() {
		Map<WorldRenderingPhase, ShaderKey> routes = new EnumMap<>(WorldRenderingPhase.class);
		routes.put(WorldRenderingPhase.SKY, ShaderKey.SKY_BASIC);
		routes.put(WorldRenderingPhase.SUNSET, ShaderKey.SKY_BASIC_COLOR);
		routes.put(WorldRenderingPhase.SUN, ShaderKey.SKY_BASIC_COLOR);
		routes.put(WorldRenderingPhase.MOON, ShaderKey.SKY_TEXTURED);
		routes.put(WorldRenderingPhase.STARS, ShaderKey.SKY_BASIC);
		routes.put(WorldRenderingPhase.VOID, ShaderKey.SKY_BASIC);
		routes.put(WorldRenderingPhase.CUSTOM_SKY, ShaderKey.SKY_BASIC);
		routes.put(WorldRenderingPhase.TERRAIN_SOLID, ShaderKey.SODIUM_TERRAIN_SOLID);
		routes.put(WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED, ShaderKey.SODIUM_TERRAIN_CUTOUT);
		routes.put(WorldRenderingPhase.TERRAIN_CUTOUT, ShaderKey.SODIUM_TERRAIN_CUTOUT);
		routes.put(WorldRenderingPhase.TERRAIN_TRANSLUCENT, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT);
		routes.put(WorldRenderingPhase.ENTITIES, ShaderKey.ENTITIES_SOLID);
		routes.put(WorldRenderingPhase.BLOCK_ENTITIES, ShaderKey.BLOCK_ENTITY);
		routes.put(WorldRenderingPhase.HAND_SOLID, ShaderKey.HAND_CUTOUT);
		routes.put(WorldRenderingPhase.HAND_TRANSLUCENT, ShaderKey.HAND_TRANSLUCENT);
		routes.put(WorldRenderingPhase.PARTICLES, ShaderKey.PARTICLES);
		routes.put(WorldRenderingPhase.CLOUDS, ShaderKey.CLOUDS);
		routes.put(WorldRenderingPhase.RAIN_SNOW, ShaderKey.WEATHER);
		routes.put(WorldRenderingPhase.WORLD_BORDER, ShaderKey.TEXTURED);
		return Map.copyOf(routes);
	}

	private static IrisShaderSource getSource(IrisRenderingPipeline irisPipeline, ShaderKey shaderKey) {
		if (irisPipeline != null) {
			return irisPipeline.getShaderMap().getSource(shaderKey);
		}

		IrisVulkanShaderSourceMap currentSourceMap = getSourceMap();
		return currentSourceMap == null ? null : currentSourceMap.getSource(shaderKey);
	}

	private static IrisVulkanShaderSourceMap getSourceMap() {
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

		return sourceMap;
	}

	private static Optional<VulkanRenderPipeline> compileCustomOverride(VulkanDevice device, RenderPipeline renderPipeline,
																		CustomPipelineSource source) {
		CacheKey cacheKey = new CacheKey(device, renderPipeline, source);

		if (failedPipelines.contains(cacheKey)) {
			throw customPipelineFailure(renderPipeline, source, "was previously marked as failed", null);
		}

		VulkanRenderPipeline cached = compiledPipelines.get(cacheKey);
		if (cached != null) {
			return Optional.of(cached);
		}

		VulkanRenderPipeline compiled;
		try {
			compiled = compileCustomVulkanPipeline(device, renderPipeline, source);
		} catch (ShaderCompileException | RuntimeException e) {
			failedPipelines.add(cacheKey);
			RuntimeException failure = customPipelineFailure(renderPipeline, source, "failed to compile: " + failureReason(e), e);
			Iris.logger.warn(failure.getMessage());
			logDeviceDebugMessages(device, renderPipeline);
			throw failure;
		}

		if (!compiled.isValid()) {
			failedPipelines.add(cacheKey);
			RuntimeException failure = customPipelineFailure(renderPipeline, source, "compiled to an invalid pipeline", null);
			Iris.logger.warn(failure.getMessage());
			throw failure;
		}

		compiledPipelines.put(cacheKey, compiled);
		Iris.logger.info("Compiled Vulkan custom pass {} for pipeline {}.",
			source.name(), renderPipeline.getLocation());
		return Optional.of(compiled);
	}

	private static RuntimeException customPipelineFailure(RenderPipeline renderPipeline, CustomPipelineSource source,
																 String reason, Throwable cause) {
		String message = "Vulkan custom pass " + source.name() + " for pipeline " + renderPipeline.getLocation() + " " + reason;
		return cause == null ? new RuntimeException(message) : new RuntimeException(message, cause);
	}

	private static String failureReason(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
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
			if (Boolean.getBoolean("iris.vulkan.dumpCustomScreenPassShaders")) {
				dumpShaderSources(name, prepared, "prepared");
			}

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
		dumpShaderSources(name, prepared, "failed");
	}

	private static void dumpShaderSources(String name, IrisVulkanShaderResources.Prepared prepared, String reason) {
		Path directory = Path.of("iris-vulkan-dumps");
		String safeName = sanitizeDumpName(name);

		try {
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(safeName + ".vert.glsl"), prepared.vertex(), StandardCharsets.UTF_8);
			Files.writeString(directory.resolve(safeName + ".frag.glsl"), prepared.fragment(), StandardCharsets.UTF_8);
			Iris.logger.warn("Dumped {} Vulkan shader sources to {}", reason, directory.toAbsolutePath());
		} catch (IOException e) {
			Iris.logger.warn("Could not dump failed Vulkan shader sources: {}", e.getMessage());
		}
	}

	private static String sanitizeDumpName(String name) {
		return name.replace('\\', '_')
			.replace('/', '_')
			.replace(':', '_')
			.replace('*', '_')
			.replace('?', '_')
			.replace('"', '_')
			.replace('<', '_')
			.replace('>', '_')
			.replace('|', '_');
	}

	private static boolean isLegacyGbufferSampler(String sampler) {
		return sampler.equals("gcolor")
			|| sampler.equals("gdepth")
			|| sampler.equals("gnormal")
			|| sampler.equals("gaux1")
			|| sampler.equals("gaux2")
			|| sampler.equals("gaux3")
			|| sampler.equals("gaux4")
			|| sampler.equals("composite")
			|| sampler.equals("insampler")
			|| sampler.equals("sampler0")
			|| sampler.equals("u_mainsampler")
			|| sampler.equals("texture")
			|| sampler.equals("tex");
	}

	private enum CapabilityStatus {
		SUPPORTED,
		PLANNED,
		UNSUPPORTED
	}

	private record ShaderCapability(String category, CapabilityStatus status, Set<ShaderKey> shaderKeys,
								Set<ProgramId> programs, boolean gbufferCapture, String reason) {
	}

	private record ShaderMappingLogKey(RenderPipeline renderPipeline, ShaderKey shaderKey, CapabilityStatus status) {
	}

	private record CacheKey(VulkanDevice device, RenderPipeline renderPipeline, Object source) {
	}

	private record CompatiblePipelineKey(RenderPipeline renderPipeline, List<GpuFormat> attachmentFormats) {
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

	public enum ScreenPassDrawMode {
		OFF(false, false),
		COPY(true, false),
		PACK_VERTEX_COPY_FRAGMENT(true, false),
		COPY_VERTEX_PACK_FRAGMENT(true, false),
		COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION(true, false),
		COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION(true, false),
		COPY_VERTEX_FINAL_CONSTANT_FRAGMENT(true, false),
		COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT(true, false),
		COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT(true, false),
		COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT(true, false),
		COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT(true, false),
		COPY_VERTEX_FINAL_TEXTURE_FRAGMENT(true, false),
		SHADERPACK(true, true);

		private final boolean draws;
		private final boolean drawsShaderpack;

		ScreenPassDrawMode(boolean draws, boolean drawsShaderpack) {
			this.draws = draws;
			this.drawsShaderpack = drawsShaderpack;
		}

		public boolean draws() {
			return draws;
		}

		public boolean drawsShaderpack() {
			return drawsShaderpack;
		}

		private static ScreenPassDrawMode fromProperties() {
			String configured = System.getProperty("iris.vulkan.screenPassDrawMode");

			if (configured != null && !configured.isBlank()) {
				return switch (configured.trim().toLowerCase(java.util.Locale.ROOT)) {
					case "off", "false", "disabled", "none" -> OFF;
					case "copy", "diagnostic", "passthrough", "pass-through" -> COPY;
					case "pack_vertex_copy_fragment", "pack-vertex-copy-fragment", "vertex", "packvertex" ->
						PACK_VERTEX_COPY_FRAGMENT;
					case "copy_vertex_pack_fragment", "copy-vertex-pack-fragment", "fragment", "packfragment" ->
						COPY_VERTEX_PACK_FRAGMENT;
					case "copy_vertex_copy_fragment_final_version", "copy-vertex-copy-fragment-final-version",
						 "copy_final_version", "copy-final-version", "copy-450", "final-copy" ->
						COPY_VERTEX_COPY_FRAGMENT_FINAL_VERSION;
					case "copy_vertex_copy_fragment_core_version", "copy-vertex-copy-fragment-core-version",
						 "copy_core_version", "copy-core-version", "copy-450-core", "copy-core" ->
						COPY_VERTEX_COPY_FRAGMENT_CORE_VERSION;
					case "copy_vertex_final_constant_fragment", "copy-vertex-final-constant-fragment",
						 "final_constant", "final-constant", "constant", "constant-fragment" ->
						COPY_VERTEX_FINAL_CONSTANT_FRAGMENT;
					case "copy_vertex_final_texture_150_fragment", "copy-vertex-final-texture-150-fragment",
						 "final_texture_150", "final-texture-150", "texture-150" ->
						COPY_VERTEX_FINAL_TEXTURE_150_FRAGMENT;
					case "copy_vertex_final_texture_core_fragment", "copy-vertex-final-texture-core-fragment",
						 "final_texture_core", "final-texture-core", "texture-core" ->
						COPY_VERTEX_FINAL_TEXTURE_CORE_FRAGMENT;
					case "copy_vertex_final_texelfetch_fragment", "copy-vertex-final-texelfetch-fragment",
						 "final_texelfetch", "final-texelfetch", "texelfetch", "texel-fetch",
						 "final_texelfetch_clamped", "final-texelfetch-clamped" ->
						COPY_VERTEX_FINAL_TEXELFETCH_FRAGMENT;
					case "copy_vertex_final_texelfetch_raw_fragment", "copy-vertex-final-texelfetch-raw-fragment",
						 "final_texelfetch_raw", "final-texelfetch-raw", "texelfetch-raw", "texel-fetch-raw" ->
						COPY_VERTEX_FINAL_TEXELFETCH_RAW_FRAGMENT;
					case "copy_vertex_final_texture_fragment", "copy-vertex-final-texture-fragment",
						 "final_texture", "final-texture", "texture" ->
						COPY_VERTEX_FINAL_TEXTURE_FRAGMENT;
					case "shaderpack", "pack", "true", "enabled", "unsafe" -> SHADERPACK;
					default -> OFF;
				};
			}

			if (Boolean.getBoolean("iris.vulkan.drawShaderpackScreenPasses")) {
				return SHADERPACK;
			}

			return OFF;
		}
	}
}
