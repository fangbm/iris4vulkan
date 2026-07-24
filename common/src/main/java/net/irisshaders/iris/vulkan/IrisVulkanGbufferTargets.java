package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisBackend;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class IrisVulkanGbufferTargets {
	public static final int COLOR_TARGET_COUNT = 8;
	public static final int FALLBACK_SCENE_TARGET = 4;
	public static final int FINAL_SOURCE_TARGET = 3;
	private static final int[] DEFAULT_TERRAIN_DRAW_BUFFERS = {6, 7, 3};

	private static final IrisVulkanTargetModel targetModel = new IrisVulkanTargetModel();
	private static boolean frameOpen;
	private static boolean frameActive;
	private static boolean currentFrameMainColorCopyAvailable;
	private static int[] activeDrawBuffers = DEFAULT_TERRAIN_DRAW_BUFFERS;
	private static boolean loggedCapture;
	private static final Set<String> loggedCaptureRejections = new HashSet<>();
	private static GpuTextureView depthTexture0;
	private static GpuTextureView depthTexture1;
	private static GpuTextureView depthTexture2;

	private IrisVulkanGbufferTargets() {
	}

	public static void beginFrameCapture() {
		if (!IrisBackend.isVulkan(RenderSystem.getDevice()) || !IrisNativeVulkan.shouldCaptureGbuffers()) {
			return;
		}

		frameOpen = true;
		frameActive = false;
		currentFrameMainColorCopyAvailable = false;
		activeDrawBuffers = Arrays.copyOf(DEFAULT_TERRAIN_DRAW_BUFFERS, DEFAULT_TERRAIN_DRAW_BUFFERS.length);
	}

	public static boolean rewriteMainRenderPass(CommandEncoder encoder, RenderPassDescriptor descriptor) {
		if (!IrisBackend.isVulkan(RenderSystem.getDevice())) {
			return rejectCapture("render backend is not Vulkan");
		}
		if (!frameOpen) {
			return rejectCapture("frame capture is not open");
		}
		if (Iris.getCurrentPack().isEmpty()) {
			return rejectCapture("no shaderpack is active");
		}

		String label = descriptor.label().get();
		if (label == null) {
			label = "";
		}
		WorldRenderingPhase phase = currentTerrainCapturePhase();
		boolean sodiumTerrainPass = label.equalsIgnoreCase("Terrain");
		if (phase == WorldRenderingPhase.NONE && !sodiumTerrainPass) {
			return rejectCapture("current Iris phase is not opaque terrain: " + label);
		}
		if (shouldSkipCaptureLabel(label)) {
			return rejectCapture("render-pass label is excluded: " + label);
		}

		if (descriptor.depthAttachment() == null || descriptor.colorAttachments().size() != 1) {
			return rejectCapture("render pass does not have one color attachment plus depth");
		}

		RenderPassDescriptor.Attachment<Optional<Vector4fc>> first = descriptor.colorAttachments().getFirst();

		if (first == null) {
			return rejectCapture("render pass has a null color attachment");
		}

		if (first.textureView() == null) {
			return rejectCapture("render pass has a null color texture view");
		}

		GpuTexture colorTexture = first.textureView().texture();
		GpuTexture mainColorTexture = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();

		if (!isMainSizedColorTarget(colorTexture, mainColorTexture)) {
			return rejectCapture("color attachment is not the copyable main-sized target");
		}

		int[] drawBuffers = phase == WorldRenderingPhase.NONE
			? Arrays.copyOf(DEFAULT_TERRAIN_DRAW_BUFFERS, DEFAULT_TERRAIN_DRAW_BUFFERS.length)
			: IrisNativeVulkan.getDrawBuffersForPhase(phase);

		if (drawBuffers.length == 0) {
			return rejectCapture("shaderpack terrain program has no draw buffers");
		}
		if (!validDrawBuffers(drawBuffers)) {
			Iris.logger.warn("Ignoring invalid native Vulkan draw-buffer selection {}; using the safe terrain defaults.",
				Arrays.toString(drawBuffers));
			drawBuffers = DEFAULT_TERRAIN_DRAW_BUFFERS;
		}

		if (ensure(colorTexture)) {
			frameActive = false;
			currentFrameMainColorCopyAvailable = false;
			loggedCapture = false;
		}
		if (!ready()) {
			return rejectCapture("gbuffer targets are not ready");
		}

		if (!frameActive) {
			currentFrameMainColorCopyAvailable = seedTargets(encoder, first.textureView());
			frameActive = true;
		}

		activeDrawBuffers = Arrays.copyOf(drawBuffers, drawBuffers.length);
		descriptor.colorAttachments.clear();

		for (int drawBuffer : activeDrawBuffers) {
			descriptor.withColorAttachment(currentView(drawBuffer));
		}

		if (!loggedCapture) {
			loggedCapture = true;
			Iris.logger.info("Capturing native Vulkan gbuffers into colortex draw buffers {} from {}.",
				Arrays.toString(activeDrawBuffers), label);
		}

		return true;
	}

	public static RenderPipeline adaptPipelineForGbufferPass(RenderPipeline pipeline,
															 List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		int attachmentCount = drawBuffersForAttachments(colorAttachments).length;

		if (attachmentCount == 0) {
			return pipeline;
		}

		List<GpuFormat> attachmentFormats = new ArrayList<>(attachmentCount);
		for (int i = 0; i < attachmentCount; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colorAttachments.get(i);
			GpuTextureView view = attachment.textureView();
			if (view == null || view.isClosed()) {
				return pipeline;
			}

			GpuTexture texture = view.texture();
			if (texture == null || texture.isClosed()) {
				return pipeline;
			}

			attachmentFormats.add(texture.getFormat());
		}

		return IrisNativeVulkan.compatiblePipelineForGbufferPass(pipeline, attachmentFormats);
	}

	public static int[] drawBuffersForAttachments(List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		if (!ready() || colorAttachments == null || colorAttachments.size() != activeDrawBuffers.length
			|| !validDrawBuffers(activeDrawBuffers)) {
			return new int[0];
		}

		for (int i = 0; i < activeDrawBuffers.length; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colorAttachments.get(i);

			if (attachment == null || attachment.textureView() != currentView(activeDrawBuffers[i])) {
				return new int[0];
			}
		}

		return Arrays.copyOf(activeDrawBuffers, activeDrawBuffers.length);
	}

	public static void ensureForFinalPass(CommandEncoder encoder, GpuTexture colorTexture) {
		if (ensure(colorTexture)) {
			frameActive = false;
			currentFrameMainColorCopyAvailable = false;
			loggedCapture = false;
		}

		if (!frameActive && ready()) {
			currentFrameMainColorCopyAvailable = seedTargets(encoder,
				Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView());
			frameActive = true;
		}
	}

	public static boolean hasCurrentFrameMainColorCopy() {
		return frameActive && currentFrameMainColorCopyAvailable;
	}

	public static boolean canCopyToMain(int target, GpuTexture mainColorTexture) {
		return targetModel.canCopyTo(target, mainColorTexture);
	}

	public static GpuTexture currentTexture(int index) {
		return targetModel.currentTexture(index);
	}

	public static GpuTextureView currentView(int index) {
		return targetModel.currentView(index);
	}

	public static GpuTextureView nextView(int index) {
		return targetModel.nextView(index);
	}

	public static GpuFormat effectiveFormat(int index, GpuFormat plannedFormat) {
		return targetModel.effectiveFormat(index, plannedFormat);
	}

	public static void swap(int index) {
		targetModel.swap(index);
	}

	public static void generateMipmaps(CommandEncoder encoder, int index) {
		targetModel.generateMipmaps(encoder, index);
	}

	public static void selectMain(int index) {
		targetModel.select(index, false);
	}

	public static void selectAlt(int index) {
		targetModel.select(index, true);
	}

	public static boolean isAlt(int index) {
		return targetModel.isAlt(index);
	}

	public static void applyExplicitFlips(String pass) {
		targetModel.applyExplicitFlips(pass);
	}

	public static void clearConfiguredTargets(CommandEncoder encoder) {
		targetModel.clearConfiguredTargets(encoder);
	}

	public static void bindSamplers(RenderPass pass, GpuTextureView depthView) {
		bindSamplers(pass, depthView, depthView, depthView);
	}

	public static void bindSamplers(RenderPass pass) {
		bindSamplers(pass, depthTexture0, depthTexture1, depthTexture2);
	}

	public static void bindSamplers(RenderPass pass, GpuTextureView depth0, GpuTextureView depth1, GpuTextureView depth2) {
		if (!ready()) {
			return;
		}

		GpuSampler sampler = IrisSamplers.getTerrainCache(1);

		for (int i = 0; i < COLOR_TARGET_COUNT; i++) {
			pass.bindTexture("colortex" + i, currentView(i), sampler);
		}

		bindAlias(pass, "InSampler", FALLBACK_SCENE_TARGET, sampler);
		bindAlias(pass, "Sampler0", FALLBACK_SCENE_TARGET, sampler);
		bindAlias(pass, "u_MainSampler", FALLBACK_SCENE_TARGET, sampler);
		bindAlias(pass, "texture", FALLBACK_SCENE_TARGET, sampler);
		bindAlias(pass, "tex", FALLBACK_SCENE_TARGET, sampler);
		bindAlias(pass, "composite", FALLBACK_SCENE_TARGET, sampler);
		bindAlias(pass, "gcolor", 0, sampler);
		bindAlias(pass, "gdepth", 1, sampler);
		bindAlias(pass, "gnormal", 3, sampler);
		bindAlias(pass, "gaux1", 1, sampler);
		bindAlias(pass, "gaux2", 2, sampler);
		bindAlias(pass, "gaux3", 3, sampler);
		bindAlias(pass, "gaux4", 4, sampler);

		bindDepth(pass, "depthtex0", depth0, sampler);
		bindDepth(pass, "depthtex1", depth1, sampler);
		bindDepth(pass, "depthtex2", depth2, sampler);
		bindDepth(pass, "gdepthtex", depth0, sampler);
	}

	public static void setDepthTextureViews(GpuTextureView depth0, GpuTextureView depth1, GpuTextureView depth2) {
		depthTexture0 = depth0;
		depthTexture1 = depth1;
		depthTexture2 = depth2;
	}

	public static void clearDepthTextureViews() {
		setDepthTextureViews(null, null, null);
	}

	public static GpuTextureView depthSamplerView(String sampler) {
		if (sampler == null) {
			return null;
		}

		return switch (sampler) {
			case "depthtex0", "gdepthtex" -> depthTexture0;
			case "depthtex1" -> depthTexture1;
			case "depthtex2" -> depthTexture2;
			default -> null;
		};
	}

	public static GpuTextureView colorSamplerView(String sampler) {
		int target = colorSamplerTarget(sampler);
		return ready() && target >= 0 ? currentView(target) : null;
	}

	public static int colorSamplerTarget(String sampler) {
		if (sampler == null) {
			return -1;
		}

		if (sampler.startsWith("colortex")) {
			try {
				int index = Integer.parseInt(sampler.substring("colortex".length()));
				return index >= 0 && index < COLOR_TARGET_COUNT ? index : -1;
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}

		return switch (sampler) {
			case "gcolor" -> 0;
			case "gdepth", "gaux1" -> 1;
			case "gnormal", "gaux3" -> 3;
			case "gaux2" -> 2;
			case "gaux4", "composite", "InSampler", "Sampler0", "u_MainSampler", "texture", "tex" -> FALLBACK_SCENE_TARGET;
			default -> -1;
		};
	}

	public static boolean isCurrentColorAttachment(int target, GpuTextureView view) {
		if (!ready() || target < 0 || target >= COLOR_TARGET_COUNT || view == null) {
			return false;
		}
		return currentView(target) == view;
	}

	public static void finishFrame() {
		frameOpen = false;
		frameActive = false;
		currentFrameMainColorCopyAvailable = false;
		activeDrawBuffers = Arrays.copyOf(DEFAULT_TERRAIN_DRAW_BUFFERS, DEFAULT_TERRAIN_DRAW_BUFFERS.length);
	}

	public static void close() {
		targetModel.close();
		frameOpen = false;
		frameActive = false;
		currentFrameMainColorCopyAvailable = false;
		activeDrawBuffers = Arrays.copyOf(DEFAULT_TERRAIN_DRAW_BUFFERS, DEFAULT_TERRAIN_DRAW_BUFFERS.length);
		loggedCapture = false;
		loggedCaptureRejections.clear();
		clearDepthTextureViews();
	}

	private static WorldRenderingPhase currentTerrainCapturePhase() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		WorldRenderingPhase phase = pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase();

		return phase == WorldRenderingPhase.TERRAIN_SOLID
			|| phase == WorldRenderingPhase.TERRAIN_CUTOUT
			|| phase == WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED
			? phase
			: WorldRenderingPhase.NONE;
	}

	private static boolean rejectCapture(String reason) {
		if (Boolean.getBoolean("iris.vulkan.logGbufferCaptureRejects") && loggedCaptureRejections.add(reason)) {
			Iris.logger.info("Native Vulkan gbuffer capture rejected once: {}.", reason);
		}
		return false;
	}

	private static boolean isMainSizedColorTarget(GpuTexture candidate, GpuTexture mainColorTexture) {
		if (candidate == null || candidate.isClosed() || mainColorTexture == null || mainColorTexture.isClosed()) {
			return false;
		}

		return candidate.getWidth(0) == mainColorTexture.getWidth(0)
			&& candidate.getHeight(0) == mainColorTexture.getHeight(0)
			&& candidate.getFormat() == mainColorTexture.getFormat()
			&& (candidate.usage() & GpuTexture.USAGE_COPY_SRC) != 0;
	}

	private static boolean shouldSkipCaptureLabel(String label) {
		if (label == null) {
			return false;
		}

		String normalized = label.toLowerCase(java.util.Locale.ROOT);
		return normalized.contains("sky")
			|| normalized.contains("cloud")
			|| normalized.contains("weather")
			|| normalized.contains("particle")
			|| normalized.contains("entity")
			|| normalized.contains("outline")
			|| normalized.contains("gui")
			|| normalized.contains("vignette")
			|| normalized.contains("crosshair")
			|| normalized.contains("blur")
			|| normalized.contains("lightmap")
			|| normalized.contains("sun")
			|| normalized.contains("moon")
			|| normalized.contains("stars")
			|| normalized.contains("horizon");
	}

	private static boolean ensure(GpuTexture source) {
		if (source == null || source.isClosed()) {
			return false;
		}

		ProgramSet programSet = Iris.getCurrentPack()
			.map(pack -> pack.getProgramSet(Iris.getCurrentDimension()))
			.orElse(null);
		if (programSet == null) {
			return targetModel.configureDefaults(source.getWidth(0), source.getHeight(0), source.getFormat());
		} else {
			return targetModel.configure(programSet, source.getWidth(0), source.getHeight(0), source.getFormat());
		}
	}

	private static boolean ready() {
		return targetModel.ready();
	}

	private static boolean validDrawBuffers(int[] drawBuffers) {
		boolean[] seen = new boolean[COLOR_TARGET_COUNT];
		for (int drawBuffer : drawBuffers) {
			if (drawBuffer < 0 || drawBuffer >= COLOR_TARGET_COUNT || seen[drawBuffer]) {
				return false;
			}
			seen[drawBuffer] = true;
		}
		return true;
	}

	private static boolean seedTargets(CommandEncoder encoder, GpuTextureView colorTextureView) {
		return targetModel.seed(encoder, colorTextureView);
	}

	private static void bindAlias(RenderPass pass, String name, int target, GpuSampler sampler) {
		pass.bindTexture(name, currentView(target), sampler);
	}

	private static void bindDepth(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		if (view != null && !view.isClosed()) {
			pass.bindTexture(name, view, sampler);
		}
	}
}
