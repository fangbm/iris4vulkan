package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.gl.framebuffer.ViewportData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IrisVulkanScreenPassGraph {
	private final List<Node> beginPasses;
	private final List<Node> preparePasses;
	private final List<Node> deferredPasses;
	private final List<Node> compositePasses;
	private final Node finalPass;
	private final List<Node> nodes;

	public IrisVulkanScreenPassGraph(List<Node> beginPasses, List<Node> preparePasses, List<Node> deferredPasses,
									  List<Node> compositePasses, Node finalPass) {
		this.beginPasses = List.copyOf(beginPasses);
		this.preparePasses = List.copyOf(preparePasses);
		this.deferredPasses = List.copyOf(deferredPasses);
		this.compositePasses = List.copyOf(compositePasses);
		this.finalPass = finalPass;

		List<Node> allNodes = new ArrayList<>(this.beginPasses.size() + this.preparePasses.size()
			+ this.deferredPasses.size() + this.compositePasses.size() + 1);
		allNodes.addAll(this.beginPasses);
		allNodes.addAll(this.preparePasses);
		allNodes.addAll(this.deferredPasses);
		allNodes.addAll(this.compositePasses);

		if (finalPass != null) {
			allNodes.add(finalPass);
		}

		this.nodes = List.copyOf(allNodes);
	}

	public List<Node> beginPasses() {
		return beginPasses;
	}

	public List<Node> preparePasses() {
		return preparePasses;
	}

	public List<Node> deferredPasses() {
		return deferredPasses;
	}

	public List<Node> compositePasses() {
		return compositePasses;
	}

	public Node finalPass() {
		return finalPass;
	}

	public List<Node> nodes() {
		return nodes;
	}

	public List<Node> logicalPasses() {
		// Begin and prepare are preflighted as part of the graph, but need dedicated
		// frame-stage hooks before they can be executed safely.
		List<Node> logical = new ArrayList<>(deferredPasses.size() + compositePasses.size());
		logical.addAll(deferredPasses);
		logical.addAll(compositePasses);
		return List.copyOf(logical);
	}

	public boolean hasReadyFinalPass() {
		return finalPass != null && finalPass.ready();
	}

	public boolean hasReadyLogicalPasses() {
		for (Node node : logicalPasses()) {
			if (node.ready()) {
				return true;
			}
		}

		return false;
	}

	public boolean hasRunnablePasses() {
		return hasReadyLogicalPasses() || hasReadyFinalPass();
	}

	public void destroy() {
		for (Node node : nodes) {
			if (node.ready()) {
				IrisNativeVulkan.unregisterCustomPipelineSource(node.pipeline());
			}
		}
	}

	public enum Kind {
		BEGIN,
		PREPARE,
		DEFERRED,
		COMPOSITE,
		FINAL
	}

	public enum Status {
		READY,
		SKIPPED
	}

	public record Node(Kind kind, String label, String sourceName, int[] drawBuffers, List<String> samplers,
					   Map<Integer, Boolean> explicitFlips, Set<Integer> mipmappedBuffers, ViewportData viewport,
					   boolean collapseOutputs, RenderPipeline pipeline, String vertexSource, String fragmentSource,
					   Status status, String failureReason) {
		public Node {
			drawBuffers = drawBuffers == null ? new int[0] : Arrays.copyOf(drawBuffers, drawBuffers.length);
			samplers = samplers == null ? List.of() : List.copyOf(samplers);
			explicitFlips = explicitFlips == null ? Map.of() : Map.copyOf(explicitFlips);
			mipmappedBuffers = mipmappedBuffers == null ? Set.of() : Set.copyOf(mipmappedBuffers);
			viewport = viewport == null ? ViewportData.defaultValue() : viewport;
			failureReason = failureReason == null ? "" : failureReason;

			if (status == Status.READY && pipeline == null) {
				throw new IllegalArgumentException("Ready Vulkan screen pass nodes must have a pipeline");
			}
		}

		@Override
		public int[] drawBuffers() {
			return Arrays.copyOf(drawBuffers, drawBuffers.length);
		}

		public boolean ready() {
			return status == Status.READY && pipeline != null;
		}

		public boolean logical() {
			return kind == Kind.DEFERRED || kind == Kind.COMPOSITE;
		}
	}
}
