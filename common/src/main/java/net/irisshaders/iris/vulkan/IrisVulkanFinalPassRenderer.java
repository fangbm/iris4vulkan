package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;

public final class IrisVulkanFinalPassRenderer {
	private final IrisNativeVulkan.ScreenPassMode mode;
	private final IrisVulkanScreenPassGraph graph;
	private final IrisVulkanScreenPassExecutor executor;

	public IrisVulkanFinalPassRenderer(ProgramSet programSet) {
		this.mode = IrisNativeVulkan.screenPassMode();
		this.graph = IrisVulkanScreenPassPlanner.create(programSet);
		this.executor = new IrisVulkanScreenPassExecutor(graph, mode, IrisNativeVulkan.selectedScreenPassLabel());

		if (!graph.hasReadyFinalPass()) {
			Iris.logger.info("Shaderpack has no supported native Vulkan final pass; screen pass execution is disabled.");
		}
	}

	public void render() {
		executor.render();
	}

	public void destroy() {
		graph.destroy();
	}

	public boolean hasRunnablePasses() {
		return graph.hasReadyFinalPass();
	}

	public boolean requiresGbufferCapture() {
		return executor.requiresGbufferCapture();
	}
}
