package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;
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
import java.util.List;
import java.util.Optional;

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
		loggedCapture = false;
		activeDrawBuffers = Arrays.copyOf(DEFAULT_TERRAIN_DRAW_BUFFERS, DEFAULT_TERRAIN_DRAW_BUFFERS.length);
	}

	public static boolean rewriteMainRenderPass(CommandEncoder encoder, RenderPassDescriptor descriptor) {
		if (!IrisBackend.isVulkan(RenderSystem.getDevice()) || !shouldCaptureCurrentPhase()) {
			return false;
		}

		String label = descriptor.label().get();
		if (label == null) {
			label = "";
		}
		if (shouldSkipCaptureLabel(label)) {
			return false;
		}

		if (descriptor.depthAttachment() == null || descriptor.colorAttachments().size() != 1) {
			return false;
		}

		RenderPassDescriptor.Attachment<Optional<Vector4fc>> first = descriptor.colorAttachments().getFirst();

		if (first == null) {
			return false;
		}

		if (first.textureView() == null) {
			return false;
		}

		GpuTexture colorTexture = first.textureView().texture();
		GpuTexture mainColorTexture = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();

		if (!isMainSizedColorTarget(colorTexture, mainColorTexture)) {
			return false;
		}

		int[] drawBuffers = IrisNativeVulkan.getDrawBuffersForCurrentPhase();

		if (drawBuffers.length == 0) {
			drawBuffers = DEFAULT_TERRAIN_DRAW_BUFFERS;
		}
		if (!validDrawBuffers(drawBuffers)) {
			Iris.logger.warn("Ignoring invalid native Vulkan draw-buffer selection {}; using the safe terrain defaults.",
				Arrays.toString(drawBuffers));
			drawBuffers = DEFAULT_TERRAIN_DRAW_BUFFERS;
		}

		if (ensure(colorTexture)) {
			frameActive = false;
			currentFrameMainColorCopyAvailable = false;
		}
		if (!ready()) {
			return false;
		}

		if (!frameActive) {
			currentFrameMainColorCopyAvailable = seedTargets(encoder, colorTexture);
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

		return IrisNativeVulkan.compatiblePipelineForGbufferPass(pipeline, attachmentCount);
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
		}

		if (!frameActive && ready()) {
			currentFrameMainColorCopyAvailable = seedTargets(encoder, colorTexture);
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

	public static void swap(int index) {
		targetModel.swap(index);
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
		if (!ready() || sampler == null) {
			return null;
		}

		if (sampler.startsWith("colortex")) {
			try {
				int index = Integer.parseInt(sampler.substring("colortex".length()));

				if (index >= 0 && index < COLOR_TARGET_COUNT) {
					return currentView(index);
				}
			} catch (NumberFormatException ignored) {
				return null;
			}
		}

		return switch (sampler) {
			case "gcolor" -> currentView(0);
			case "gdepth", "gaux1" -> currentView(1);
			case "gnormal", "gaux3" -> currentView(3);
			case "gaux2" -> currentView(2);
			case "gaux4", "composite", "InSampler", "Sampler0", "u_MainSampler", "texture", "tex" -> currentView(FALLBACK_SCENE_TARGET);
			default -> null;
		};
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
		clearDepthTextureViews();
	}

	private static boolean shouldCaptureCurrentPhase() {
		if (!frameOpen || Iris.getCurrentPack().isEmpty()) {
			return false;
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		WorldRenderingPhase phase = pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase();

		if (phase == WorldRenderingPhase.TERRAIN_TRANSLUCENT) {
			return false;
		}

		return phase == WorldRenderingPhase.NONE
			|| phase == WorldRenderingPhase.TERRAIN_SOLID
			|| phase == WorldRenderingPhase.TERRAIN_CUTOUT
			|| phase == WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED;
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

	private static boolean seedTargets(CommandEncoder encoder, GpuTexture colorTexture) {
		return targetModel.seed(encoder, colorTexture);
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
