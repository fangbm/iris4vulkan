package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class IrisVulkanScreenPassGraph {
	private final List<Node> deferredPasses;
	private final List<Node> compositePasses;
	private final Node finalPass;
	private final List<Node> nodes;

	public IrisVulkanScreenPassGraph(List<Node> deferredPasses, List<Node> compositePasses, Node finalPass) {
		this.deferredPasses = List.copyOf(deferredPasses);
		this.compositePasses = List.copyOf(compositePasses);
		this.finalPass = finalPass;

		List<Node> allNodes = new ArrayList<>(this.deferredPasses.size() + this.compositePasses.size() + 1);
		allNodes.addAll(this.deferredPasses);
		allNodes.addAll(this.compositePasses);

		if (finalPass != null) {
			allNodes.add(finalPass);
		}

		this.nodes = List.copyOf(allNodes);
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

	public void destroy() {
		for (Node node : nodes) {
			if (node.ready()) {
				IrisNativeVulkan.unregisterCustomPipelineSource(node.pipeline());
			}
		}
	}

	public enum Kind {
		DEFERRED,
		COMPOSITE,
		FINAL
	}

	public enum Status {
		READY,
		SKIPPED
	}

	public record Node(Kind kind, String label, String sourceName, int[] drawBuffers, List<String> samplers,
					   boolean collapseOutputs, RenderPipeline pipeline, String vertexSource, String fragmentSource,
					   Status status, String failureReason) {
		public Node {
			drawBuffers = drawBuffers == null ? new int[0] : Arrays.copyOf(drawBuffers, drawBuffers.length);
			samplers = samplers == null ? List.of() : List.copyOf(samplers);
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
			return kind != Kind.FINAL;
		}
	}
}
