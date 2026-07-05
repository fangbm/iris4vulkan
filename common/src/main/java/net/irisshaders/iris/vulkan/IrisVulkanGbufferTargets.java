package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
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
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
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

	private static final Target[] targets = new Target[COLOR_TARGET_COUNT];
	private static int width;
	private static int height;
	private static GpuFormat format;
	private static boolean frameOpen;
	private static boolean frameActive;
	private static int[] activeDrawBuffers = DEFAULT_TERRAIN_DRAW_BUFFERS;
	private static boolean loggedCapture;

	private IrisVulkanGbufferTargets() {
	}

	public static void beginFrameCapture() {
		if (!IrisBackend.isVulkan(RenderSystem.getDevice()) || !IrisNativeVulkan.shouldCaptureGbuffers()) {
			return;
		}

		frameOpen = true;
		frameActive = false;
	}

	public static boolean rewriteMainRenderPass(CommandEncoder encoder, RenderPassDescriptor descriptor) {
		if (!IrisBackend.isVulkan(RenderSystem.getDevice()) || !shouldCaptureCurrentPhase()) {
			return false;
		}

		String label = descriptor.label().get();
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

		GpuTexture colorTexture = first.textureView().texture();
		GpuTexture mainColorTexture = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();

		if (!isMainSizedColorTarget(colorTexture, mainColorTexture)) {
			return false;
		}

		int[] drawBuffers = IrisNativeVulkan.getDrawBuffersForCurrentPhase();

		if (drawBuffers.length == 0) {
			drawBuffers = DEFAULT_TERRAIN_DRAW_BUFFERS;
		}

		ensure(colorTexture);

		if (!frameActive) {
			seedTargets(encoder, colorTexture);
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
		if (!ready() || colorAttachments.size() != activeDrawBuffers.length) {
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
		ensure(colorTexture);

		if (!frameActive) {
			seedTargets(encoder, colorTexture);
			frameActive = true;
		}
	}

	public static GpuTexture currentTexture(int index) {
		return targets[index].currentTexture();
	}

	public static GpuTextureView currentView(int index) {
		return targets[index].currentView();
	}

	public static GpuTextureView nextView(int index) {
		return targets[index].nextView();
	}

	public static void swap(int index) {
		targets[index].swap();
	}

	public static void bindSamplers(RenderPass pass, GpuTextureView depthView) {
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

		if (depthView != null && !depthView.isClosed()) {
			pass.bindTexture("depthtex0", depthView, sampler);
			pass.bindTexture("depthtex1", depthView, sampler);
			pass.bindTexture("depthtex2", depthView, sampler);
			pass.bindTexture("gdepthtex", depthView, sampler);
		}
	}

	public static GpuTextureView colorSamplerView(String sampler) {
		if (!ready()) {
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
	}

	public static void close() {
		destroyTargets();
		frameOpen = false;
		frameActive = false;
	}

	private static void destroyTargets() {
		for (int i = 0; i < targets.length; i++) {
			if (targets[i] != null) {
				targets[i].close();
				targets[i] = null;
			}
		}

		width = 0;
		height = 0;
		format = null;
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

	private static void ensure(GpuTexture source) {
		int newWidth = source.getWidth(0);
		int newHeight = source.getHeight(0);
		GpuFormat newFormat = source.getFormat();

		if (ready() && width == newWidth && height == newHeight && format == newFormat) {
			return;
		}

		destroyTargets();
		width = newWidth;
		height = newHeight;
		format = newFormat;
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

		for (int i = 0; i < COLOR_TARGET_COUNT; i++) {
			targets[i] = new Target(i, usage, newFormat, newWidth, newHeight);
		}
	}

	private static boolean ready() {
		for (Target target : targets) {
			if (target == null || !target.ready()) {
				return false;
			}
		}

		return true;
	}

	private static void seedTargets(CommandEncoder encoder, GpuTexture colorTexture) {
		for (Target target : targets) {
			encoder.copyTextureToTexture(colorTexture, target.currentTexture(), 0, 0, 0, 0, 0, width, height);
		}
	}

	private static void bindAlias(RenderPass pass, String name, int target, GpuSampler sampler) {
		pass.bindTexture(name, currentView(target), sampler);
	}

	private static final class Target {
		private final GpuTexture[] textures = new GpuTexture[2];
		private final GpuTextureView[] views = new GpuTextureView[2];
		private int current;

		private Target(int index, int usage, GpuFormat format, int width, int height) {
			textures[0] = RenderSystem.getDevice().createTexture(() -> "Iris native Vulkan colortex" + index + " A",
				usage, format, width, height, 1, 1);
			textures[1] = RenderSystem.getDevice().createTexture(() -> "Iris native Vulkan colortex" + index + " B",
				usage, format, width, height, 1, 1);
			views[0] = RenderSystem.getDevice().createTextureView(textures[0]);
			views[1] = RenderSystem.getDevice().createTextureView(textures[1]);
		}

		private boolean ready() {
			return textures[0] != null && !textures[0].isClosed()
				&& textures[1] != null && !textures[1].isClosed()
				&& views[0] != null && !views[0].isClosed()
				&& views[1] != null && !views[1].isClosed();
		}

		private GpuTexture currentTexture() {
			return textures[current];
		}

		private GpuTextureView currentView() {
			return views[current];
		}

		private GpuTextureView nextView() {
			return views[1 - current];
		}

		private void swap() {
			current = 1 - current;
		}

		private void close() {
			for (GpuTextureView view : views) {
				if (view != null) {
					view.close();
				}
			}

			for (GpuTexture texture : textures) {
				if (texture != null) {
					texture.close();
				}
			}
		}
	}
}
