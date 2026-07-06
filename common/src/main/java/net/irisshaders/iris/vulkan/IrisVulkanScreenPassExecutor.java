package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPass.RenderArea;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class IrisVulkanScreenPassExecutor {
	private static final int FINAL_SOURCE_TARGET = IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET;

	private final IrisVulkanScreenPassGraph graph;
	private final IrisNativeVulkan.ScreenPassMode mode;
	private final String selectedPass;
	private final Set<IrisVulkanScreenPassGraph.Node> failedPasses = new HashSet<>();
	private final Set<IrisVulkanScreenPassGraph.Node> preflightedPasses = new HashSet<>();
	private boolean loggedBuildOnlyFrame;
	private boolean preflightComplete;

	public IrisVulkanScreenPassExecutor(IrisVulkanScreenPassGraph graph, IrisNativeVulkan.ScreenPassMode mode,
										String selectedPass) {
		this.graph = graph;
		this.mode = mode;
		this.selectedPass = selectedPass == null || selectedPass.isBlank()
			? null
			: selectedPass.trim().toLowerCase(Locale.ROOT);
	}

	public void render() {
		if (!graph.hasReadyFinalPass()) {
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTexture colorTexture = main.getColorTexture();

		if (colorTexture == null || colorTexture.isClosed()) {
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		if ((colorTexture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
			Iris.logger.warn("Cannot run native Vulkan screen passes because the main color texture is not copyable.");
			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		IrisVulkanGbufferTargets.ensureForFinalPass(encoder, colorTexture);
		GpuTextureView depthView = main.getDepthTextureView();

		if (!mode.runsFinalPass()) {
			preflightScreenPasses(encoder, depthView);

			if (!loggedBuildOnlyFrame) {
				loggedBuildOnlyFrame = true;
				Iris.logger.info("Native Vulkan screen pass graph is in {} mode; pipelines were preflighted without drawing.",
					mode.name().toLowerCase(Locale.ROOT));
			}

			IrisVulkanGbufferTargets.finishFrame();
			return;
		}

		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
		IndexType indexType = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type();

		if (mode.runsLogicalPasses()) {
			for (IrisVulkanScreenPassGraph.Node screenPass : graph.logicalPasses()) {
				renderLogicalPassIfAvailable(encoder, screenPass, depthView, indices, indexType);
			}
		}

		boolean renderedFinal = renderSelectedFinalPassIfAvailable(encoder, main.getColorTextureView(), depthView,
			indices, indexType);

		if (!renderedFinal && (colorTexture.usage() & GpuTexture.USAGE_COPY_DST) != 0) {
			GpuTexture source = IrisVulkanGbufferTargets.currentTexture(FINAL_SOURCE_TARGET);
			encoder.copyTextureToTexture(source, colorTexture, 0, 0, 0, 0, 0,
				colorTexture.getWidth(0), colorTexture.getHeight(0));
		}

		IrisVulkanGbufferTargets.finishFrame();
	}

	public boolean requiresGbufferCapture() {
		if (!mode.runsLogicalPasses()) {
			return false;
		}

		for (IrisVulkanScreenPassGraph.Node node : graph.logicalPasses()) {
			if (node.ready() && matchesSelection(node)) {
				return true;
			}
		}

		return false;
	}

	private void preflightScreenPasses(CommandEncoder encoder, GpuTextureView depthView) {
		if (preflightComplete) {
			return;
		}

		for (IrisVulkanScreenPassGraph.Node screenPass : graph.nodes()) {
			preflightPass(encoder, screenPass, depthView);
		}

		preflightComplete = true;
	}

	private void preflightPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
							   GpuTextureView depthView) {
		if (!screenPass.ready() || failedPasses.contains(screenPass) || preflightedPasses.contains(screenPass)) {
			return;
		}

		try (RenderPass pass = createPreflightRenderPass(encoder, screenPass)) {
			bindPass(screenPass, pass, depthView);
			preflightedPasses.add(screenPass);
			Iris.logger.info("Preflighted native Vulkan screen pass {}.", screenPass.label());
		} catch (RuntimeException e) {
			failedPasses.add(screenPass);
			Iris.logger.warn("Failed to preflight native Vulkan screen pass {}: {}", screenPass.label(), e.getMessage());
		}
	}

	private RenderPass createPreflightRenderPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass) {
		if (screenPass.kind() == IrisVulkanScreenPassGraph.Kind.FINAL) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(FINAL_SOURCE_TARGET);
			return encoder.createRenderPass(() -> "Iris native Vulkan preflight " + screenPass.label(),
				outputView, java.util.Optional.empty());
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris native Vulkan preflight " + screenPass.label());
		GpuTextureView firstOutputView = null;

		for (int logicalTarget : screenPass.drawBuffers()) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(logicalTarget);

			if (firstOutputView == null) {
				firstOutputView = outputView;
			}

			descriptor.withColorAttachment(outputView);
		}

		if (firstOutputView != null) {
			descriptor.withRenderArea(new RenderArea(0, 0, firstOutputView.getWidth(0), firstOutputView.getHeight(0)));
		}

		return encoder.createRenderPass(descriptor);
	}

	private void renderLogicalPassIfAvailable(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
											  GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		if (!screenPass.ready() || failedPasses.contains(screenPass) || !matchesSelection(screenPass)) {
			return;
		}

		try {
			renderLogicalPass(encoder, screenPass, depthView, indices, indexType);
		} catch (RuntimeException e) {
			failedPasses.add(screenPass);
			Iris.logger.warn("Skipping native Vulkan screen pass {} after an error: {}", screenPass.label(), e.getMessage());
		}
	}

	private boolean renderSelectedFinalPassIfAvailable(CommandEncoder encoder, GpuTextureView outputView,
													   GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		IrisVulkanScreenPassGraph.Node finalPass = graph.finalPass();

		if (finalPass == null || !finalPass.ready() || failedPasses.contains(finalPass) || !matchesSelection(finalPass)) {
			return false;
		}

		try {
			renderFinalPass(encoder, finalPass, outputView, depthView, indices, indexType);
			return true;
		} catch (RuntimeException e) {
			failedPasses.add(finalPass);
			Iris.logger.warn("Skipping native Vulkan final pass {} after an error: {}", finalPass.label(), e.getMessage());
			return false;
		}
	}

	private void renderLogicalPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
								   GpuTextureView depthView, GpuBuffer indices, IndexType indexType) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris native Vulkan " + screenPass.label());
		GpuTextureView firstOutputView = null;

		for (int logicalTarget : screenPass.drawBuffers()) {
			GpuTextureView outputView = IrisVulkanGbufferTargets.nextView(logicalTarget);

			if (firstOutputView == null) {
				firstOutputView = outputView;
			}

			descriptor.withColorAttachment(outputView);
		}

		if (firstOutputView != null) {
			descriptor.withRenderArea(new RenderArea(0, 0, firstOutputView.getWidth(0), firstOutputView.getHeight(0)));
		}

		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			bindPass(screenPass, pass, depthView);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}

		for (int logicalTarget : screenPass.drawBuffers()) {
			IrisVulkanGbufferTargets.swap(logicalTarget);
		}
	}

	private void renderFinalPass(CommandEncoder encoder, IrisVulkanScreenPassGraph.Node screenPass,
								 GpuTextureView outputView, GpuTextureView depthView,
								 GpuBuffer indices, IndexType indexType) {
		try (RenderPass pass = encoder.createRenderPass(() -> "Iris native Vulkan " + screenPass.label(),
			outputView, java.util.Optional.empty())) {
			bindPass(screenPass, pass, depthView);
			pass.setIndexBuffer(indices, indexType);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	private void bindPass(IrisVulkanScreenPassGraph.Node screenPass, RenderPass pass, GpuTextureView depthView) {
		pass.setPipeline(screenPass.pipeline());
		IrisVulkanRenderPassBindings.bindScreenPassResources(pass, screenPass.pipeline(), depthView, screenPass.label());
	}

	private boolean matchesSelection(IrisVulkanScreenPassGraph.Node node) {
		if (selectedPass == null) {
			return true;
		}

		return node.label().equalsIgnoreCase(selectedPass)
			|| node.sourceName().equalsIgnoreCase(selectedPass)
			|| node.kind().name().toLowerCase(Locale.ROOT).equals(selectedPass);
	}
}
