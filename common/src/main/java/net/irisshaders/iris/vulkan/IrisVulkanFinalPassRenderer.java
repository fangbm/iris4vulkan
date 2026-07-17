package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;

public final class IrisVulkanFinalPassRenderer {
	private final IrisNativeVulkan.ScreenPassMode mode;
	private final IrisVulkanScreenPassGraph graph;
	private final IrisVulkanScreenPassExecutor executor;
	private final FrameUpdateNotifier frameUpdateNotifier;
	private final CustomUniforms customUniforms;

	public IrisVulkanFinalPassRenderer(ProgramSet programSet) {
		this.mode = IrisNativeVulkan.screenPassMode();
		FrameUpdateNotifier frameUpdateNotifier = new FrameUpdateNotifier();
		CustomUniforms customUniforms = null;
		IrisVulkanScreenPassGraph graph = null;
		boolean registered = false;

		try {
			var pack = programSet.getPack();
			customUniforms = pack.customUniforms.build(holder -> CommonUniforms.addNonDynamicUniforms(
				holder, pack.getIdMap(), programSet.getPackDirectives(), frameUpdateNotifier));
			IrisVulkanUniformSnapshot.registerActiveCustomUniforms(customUniforms);
			registered = true;

			graph = IrisVulkanScreenPassPlanner.create(programSet);
			this.graph = graph;
			this.executor = new IrisVulkanScreenPassExecutor(graph, mode, IrisNativeVulkan.selectedScreenPassLabel());
			this.frameUpdateNotifier = frameUpdateNotifier;
			this.customUniforms = customUniforms;
		} catch (RuntimeException | Error e) {
			if (registered) {
				IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(customUniforms);
			}
			if (graph != null) {
				graph.destroy();
			}
			throw e;
		}

		if (!graph.hasRunnablePasses()) {
			Iris.logger.info("Shaderpack has no supported native Vulkan screen passes; screen pass execution is disabled.");
		} else if (!graph.hasReadyFinalPass()) {
			Iris.logger.info("Shaderpack has no supported native Vulkan final pass; logical passes will fall back to colortex0.");
		}
	}

	public void render() {
		frameUpdateNotifier.onNewFrame();
		customUniforms.beginFrame();
		executor.render();
	}

	public void destroy() {
		try {
			executor.destroy();
		} finally {
			try {
				graph.destroy();
			} finally {
				IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(customUniforms);
			}
		}
	}

	public boolean hasRunnablePasses() {
		return graph.hasRunnablePasses();
	}

	public boolean requiresGbufferCapture() {
		return executor.requiresGbufferCapture();
	}
}
