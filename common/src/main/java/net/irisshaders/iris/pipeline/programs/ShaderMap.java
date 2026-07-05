package net.irisshaders.iris.pipeline.programs;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.objects.Object2BooleanFunction;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.lwjgl.opengl.GL46C;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A specialized map mapping {@link ShaderKey} to {@link CompiledShaderProgram}.
 * Avoids much of the complexity / overhead of an EnumMap while ultimately
 * fulfilling the same function.
 */
public class ShaderMap {
	private final GlProgram[] shaders;
	private final IrisShaderSource[] sources;

	public ShaderMap(ShaderLoadingMap loadingMap, Function<ShaderSupplier, Boolean> deletionFunction, Consumer<GlProgram> programConsumer) {
		ShaderKey[] ids = ShaderKey.values();

		this.shaders = new GlProgram[ids.length];
		this.sources = new IrisShaderSource[ids.length];

		loadingMap.forAllShaders((key, shader) -> {
			if (shader != null) {
				if (deletionFunction.apply(shader)) {
					GlStateManager.glDeleteProgram(shader.id().program());
					return;
				}

				checkLinkingState(key, shader);
				GlProgram shaderProgram = shader.shader().get();
				this.shaders[key.ordinal()] = shaderProgram;
				this.sources[key.ordinal()] = shader.irisSource();
				programConsumer.accept(shaderProgram);
			}
		});
	}

	private void checkLinkingState(ShaderKey key, ShaderSupplier shader) {
		int i = shader.id().program();

		int j = GlStateManager.glGetProgrami(i, 35714);
		if (j == GL46C.GL_FALSE) {
			String string = GlStateManager.glGetProgramInfoLog(i, 32768);
			throw new ShaderCompileException(
				key.name(), string
			);
		}
	}

	public GlProgram getShader(ShaderKey id) {
		return shaders[id.ordinal()];
	}

	public IrisShaderSource getSource(ShaderKey id) {
		return sources[id.ordinal()];
	}
}
