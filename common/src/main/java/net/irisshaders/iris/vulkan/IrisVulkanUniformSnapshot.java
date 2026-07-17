package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Vulkan representation of legacy Iris loose uniforms. The shader patcher
 * turns these declarations into one std140 block, and this class snapshots the
 * values at bind time so a pass never observes a later pass' state.
 */
public final class IrisVulkanUniformSnapshot {
	public static final String BLOCK_NAME = "IrisUniforms";
	private static final Map<String, Matrix4f> CURRENT_MATRICES = new LinkedHashMap<>();
	private static final Map<String, Matrix4f> PREVIOUS_MATRICES = new LinkedHashMap<>();
	private static volatile CustomUniforms activeCustomUniforms;
	private static int previousFrame = Integer.MIN_VALUE;

	private IrisVulkanUniformSnapshot() {
	}

	public static synchronized void registerActiveCustomUniforms(CustomUniforms customUniforms) {
		activeCustomUniforms = Objects.requireNonNull(customUniforms, "customUniforms");
	}

	public static synchronized void unregisterActiveCustomUniforms(CustomUniforms customUniforms) {
		if (activeCustomUniforms == customUniforms) {
			activeCustomUniforms = null;
		}
	}

	public record Field(String name, String type) {
	}

	private record Layout(Field field, int offset, int size) {
	}

	public record Snapshot(ByteBuffer data, int size) {
		public GpuBuffer upload() {
			ByteBuffer source = data.duplicate();
			source.clear();
			return com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(
				() -> "Iris Vulkan per-pass uniforms", GpuBuffer.USAGE_UNIFORM, source);
		}
	}

	public static Optional<Field> field(String name, String type, String arraySuffix) {
		if (arraySuffix != null && !arraySuffix.isBlank()) {
			return Optional.empty();
		}

		if (!type.equals(expectedType(name))) {
			return Optional.empty();
		}

		return Optional.of(new Field(name, type));
	}

	public static String unsupportedReason(String name, String type, String arraySuffix) {
		if (arraySuffix != null && !arraySuffix.isBlank()) {
			return "uniform arrays are not represented";
		}

		String expected = expectedType(name);
		if (expected == null) {
			return "no live Iris/Mojang snapshot source";
		}

		return "snapshot source is " + expected;
	}

	public static String declaration(Collection<Field> fields) {
		List<Field> ordered = List.copyOf(fields);
		StringBuilder block = new StringBuilder("layout(std140) uniform ").append(BLOCK_NAME).append(" {\n");

		for (Field field : ordered) {
			block.append("    ").append(field.type()).append(' ').append(field.name()).append(";\n");
		}

		block.append("} iris_vulkan_uniforms;\n");

		for (Field field : ordered) {
			block.append("#define ").append(field.name()).append(" (iris_vulkan_uniforms.")
				.append(field.name()).append(")\n");
		}

		return block.toString();
	}

	public static Snapshot capture(Collection<Field> fields) {
		syncPreviousMatrices();
		List<Layout> layouts = layouts(fields);
		CustomUniforms active = activeCustomUniforms;
		if (active != null) {
			active.updateFor(layouts.stream().map(layout -> layout.field().name()).toList());
		}
		int size = layouts.isEmpty() ? 16 : align(layouts.getLast().offset() + layouts.getLast().size(), 16);
		ByteBuffer data = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());

		for (Layout layout : layouts) {
			write(data, layout.offset(), layout.field());
		}

		data.clear();
		return new Snapshot(data, size);
	}

	private static List<Layout> layouts(Collection<Field> fields) {
		List<Field> ordered = List.copyOf(fields);
		List<Layout> layouts = new ArrayList<>(ordered.size());
		int offset = 0;

		for (Field field : ordered) {
			int alignment = alignment(field.type());
			int size = size(field.type());
			offset = align(offset, alignment);
			layouts.add(new Layout(field, offset, size));
			offset += size;
		}

		return layouts;
	}

	private static void write(ByteBuffer data, int offset, Field field) {
		Object value = value(field.name(), field.type());
		ByteBuffer target = data.duplicate().order(ByteOrder.nativeOrder());
		target.position(offset);

		switch (field.type()) {
			case "float" -> target.putFloat(((Number) value).floatValue());
			case "int", "uint" -> target.putInt(((Number) value).intValue());
			case "bool" -> target.putInt((Boolean) value ? 1 : 0);
			case "vec2" -> put(target, (Vector2f) value);
			case "ivec2" -> put(target, (Vector2i) value);
			case "vec3" -> put(target, (Vector3f) value);
			case "ivec3" -> put(target, (Vector3i) value);
			case "vec4" -> put(target, (Vector4f) value);
			case "ivec4" -> put(target, (Vector4i) value);
			case "mat3" -> put(target, (Matrix3f) value);
			case "mat4" -> new Matrix4f((Matrix4fc) value).get(target);
			default -> throw unsupported(field.name(), field.type(), "no std140 writer");
		}
	}

	private static Object value(String name, String type) {
		Optional<CustomUniforms.Snapshot> custom = customUniform(name);
		if (custom.isPresent() && custom.get().type().equals(type)) {
			return custom.get().value();
		}

		Minecraft client = client();

		return switch (name) {
			case "gbufferModelView", "iris_ModelViewMatrix", "iris_ModelViewMat" -> requiredMatrix(
				CapturedRenderingState.INSTANCE.getGbufferModelView(), name);
			case "gbufferProjection", "iris_ProjectionMatrix", "iris_ProjMat" -> requiredMatrix(
				CapturedRenderingState.INSTANCE.getGbufferProjection(), name);
			case "gbufferModelViewInverse", "iris_ModelViewMatrixInverse", "iris_ModelViewMatInverse" ->
				requiredMatrix(CapturedRenderingState.INSTANCE.getGbufferModelView(), name).invert();
			case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse", "iris_ProjMatInverse" ->
				requiredMatrix(CapturedRenderingState.INSTANCE.getGbufferProjection(), name).invert();
			case "gbufferPreviousModelView" -> previousMatrix("gbufferModelView", name);
			case "gbufferPreviousProjection" -> previousMatrix("gbufferProjection", name);
			case "shadowModelView" -> requiredMatrix(ShadowRenderer.MODELVIEW, name);
			case "shadowProjection" -> requiredMatrix(ShadowRenderer.PROJECTION, name);
			case "shadowModelViewInverse" -> requiredMatrix(ShadowRenderer.MODELVIEW, name).invert();
			case "shadowProjectionInverse" -> requiredMatrix(ShadowRenderer.PROJECTION, name).invert();
			case "iris_NormalMatrix", "iris_NormalMat" -> normalMatrix(
				requiredMatrix(CapturedRenderingState.INSTANCE.getGbufferModelView(), name));
			case "iris_LightmapTextureMatrix" -> lightmapTextureMatrix();
			case "iris_currentAlphaTest" -> CapturedRenderingState.INSTANCE.getCurrentAlphaTest();
			case "iris_FogDensity" -> Math.max(0.0f, CapturedRenderingState.INSTANCE.getFogDensity());
			case "iris_FogColor" -> fogColor();
			case "iris_FogStart" -> fogParameters().environmentalStart();
			case "iris_FogEnd" -> fogParameters().environmentalEnd();
			case "iris_ScreenSize" -> screenSize();
			case "viewWidth" -> (float) client.gameRenderer.mainRenderTarget().width;
			case "viewHeight" -> (float) client.gameRenderer.mainRenderTarget().height;
			case "aspectRatio" -> (float) client.gameRenderer.mainRenderTarget().width /
				client.gameRenderer.mainRenderTarget().height;
			case "near" -> 0.05f;
			case "far" -> (float) client.options.getEffectiveRenderDistance() * 16.0f;
			case "frameTimeCounter" -> SystemTimeUniforms.TIMER.getFrameTimeCounter();
			case "frameTime" -> SystemTimeUniforms.TIMER.getLastFrameTime();
			case "frameCounter" -> SystemTimeUniforms.COUNTER.getAsInt();
			case "worldTime" -> worldTime(client);
			case "worldDay" -> worldDay(client);
			case "moonPhase" -> client.gameRenderer.mainCamera().attributeProbe()
				.getValue(EnvironmentAttributes.MOON_PHASE, CapturedRenderingState.INSTANCE.getTickDelta()).index();
			case "currentDate" -> date();
			case "currentTime" -> time();
			case "currentYearTime" -> yearTime();
			case "rainStrength", "wetness" -> client.level == null ? 0.0f : client.level.getRainLevel(
				CapturedRenderingState.INSTANCE.getTickDelta());
			case "eyeBrightness" -> eyeBrightness(client);
			case "skyColor" -> skyColor(client);
			case "isEyeInWater" -> eyeInWater(client);
			case "screenBrightness" -> client.options.gamma().get().floatValue();
			case "darknessLightFactor" -> CapturedRenderingState.INSTANCE.getDarknessLightFactor();
			case "cameraPositionFract" -> cameraPositionFract(client);
			case "cameraPositionInt" -> cameraPositionInt(client);
			case "cameraPosition", "eyeAltitude" -> cameraPosition(client, name);
			default -> throw unsupported(name, type, "no live Iris/Mojang source");
		};
	}

	private static Matrix4f previousMatrix(String key, String name) {
		Matrix4f previous = PREVIOUS_MATRICES.get(key);
		return previous == null ? new Matrix4f() : new Matrix4f(previous);
	}

	private static Matrix4f requiredMatrix(Matrix4fc matrix, String name) {
		if (matrix == null) {
			throw unsupported(name, "mat4", "captured matrix is unavailable for this pass");
		}

		return new Matrix4f(matrix);
	}

	private static void syncPreviousMatrices() {
		int frame = SystemTimeUniforms.COUNTER.getAsInt();
		if (frame == previousFrame) {
			return;
		}

		previousFrame = frame;
		PREVIOUS_MATRICES.clear();
		PREVIOUS_MATRICES.putAll(CURRENT_MATRICES);
		CURRENT_MATRICES.clear();

		Matrix4fc modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();
		Matrix4fc projection = CapturedRenderingState.INSTANCE.getGbufferProjection();
		if (modelView != null) CURRENT_MATRICES.put("gbufferModelView", new Matrix4f(modelView));
		if (projection != null) CURRENT_MATRICES.put("gbufferProjection", new Matrix4f(projection));
	}

	private static Matrix3f normalMatrix(Matrix4f modelView) {
		return modelView.invert().transpose3x3(new Matrix3f());
	}

	private static Matrix4f lightmapTextureMatrix() {
		return new Matrix4f().m00(0.00390625f).m11(0.00390625f).m22(0.00390625f)
			.m30(0.03125f).m31(0.03125f).m32(0.03125f);
	}

	private static Vector4f fogColor() {
		FogParameters fog = fogParameters();
		return fog == FogParameters.NONE
			? new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
			: new Vector4f(fog.red(), fog.green(), fog.blue(), fog.alpha());
	}

	private static FogParameters fogParameters() {
		return ((FogStorage) client().gameRenderer).sodium$getFogParameters();
	}

	private static Vector2f screenSize() {
		return new Vector2f(client().gameRenderer.mainRenderTarget().width, client().gameRenderer.mainRenderTarget().height);
	}

	private static Minecraft client() {
		return Minecraft.getInstance();
	}

	private static int worldTime(Minecraft client) {
		return client.level == null ? 0 : (int) (client.level.getDefaultClockTime() % 24000L);
	}

	private static int worldDay(Minecraft client) {
		return client.level == null ? 0 : (int) (client.level.getDefaultClockTime() / 24000L);
	}

	private static Vector3i date() {
		LocalDateTime now = LocalDateTime.now();
		return new Vector3i(now.getYear(), now.getMonthValue(), now.getDayOfMonth());
	}

	private static Vector3i time() {
		LocalDateTime now = LocalDateTime.now();
		return new Vector3i(now.getHour(), now.getMinute(), now.getSecond());
	}

	private static Vector2i yearTime() {
		LocalDateTime now = LocalDateTime.now();
		int elapsed = (now.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
		return new Vector2i(elapsed, now.toLocalDate().lengthOfYear() * 86400 - elapsed);
	}

	private static Vector2i eyeBrightness(Minecraft client) {
		if (client.level == null || client.getCameraEntity() == null) {
			return new Vector2i();
		}

		var entity = client.getCameraEntity();
		var position = entity.blockPosition();
		return new Vector2i(client.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, position) * 16,
			client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, position) * 16);
	}

	private static Vector3f skyColor(Minecraft client) {
		if (client.level == null || client.getCameraEntity() == null) {
			return new Vector3f();
		}

		int color = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR,
			CapturedRenderingState.INSTANCE.getTickDelta());
		return new Vector3f(((color >> 16) & 255) / 255.0f, ((color >> 8) & 255) / 255.0f, (color & 255) / 255.0f);
	}

	private static int eyeInWater(Minecraft client) {
		FogType type = client.gameRenderer.mainCamera().getFluidInCamera();
		if (type == FogType.WATER) return 1;
		if (type == FogType.LAVA && client.player != null && !client.player.isSpectator()) return 2;
		if (type == FogType.POWDER_SNOW) return 3;
		return 0;
	}

	private static Vector3f cameraPositionFract(Minecraft client) {
		var position = client.gameRenderer.mainCamera().position();
		return new Vector3f((float) (position.x - Math.floor(position.x)), (float) (position.y - Math.floor(position.y)),
			(float) (position.z - Math.floor(position.z)));
	}

	private static Vector3i cameraPositionInt(Minecraft client) {
		var position = client.gameRenderer.mainCamera().position();
		return new Vector3i((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
	}

	private static Object cameraPosition(Minecraft client, String name) {
		var position = client.gameRenderer.mainCamera().position();
		if (name.equals("eyeAltitude")) return (float) position.y;
		return new Vector3f((float) position.x, (float) position.y, (float) position.z);
	}

	private static Optional<CustomUniforms.Snapshot> customUniform(String name) {
		CustomUniforms active = activeCustomUniforms;
		if (active != null) {
			Optional<CustomUniforms.Snapshot> snapshot = active.lookup(name);
			if (snapshot.isPresent()) {
				return snapshot;
			}
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		if (pipeline instanceof IrisRenderingPipeline irisPipeline) {
			return irisPipeline.getCustomUniforms().lookup(name);
		}
		return Optional.empty();
	}

	private static String expectedType(String name) {
		Optional<String> customType = customType(name);
		return customType.orElseGet(() -> hardcodedExpectedType(name));
	}

	private static Optional<String> customType(String name) {
		CustomUniforms active = activeCustomUniforms;
		if (active != null) {
			Optional<String> type = active.typeOf(name);
			if (type.isPresent()) {
				return type;
			}
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		if (pipeline instanceof IrisRenderingPipeline irisPipeline) {
			return irisPipeline.getCustomUniforms().typeOf(name);
		}

		return Optional.empty();
	}

	private static String hardcodedExpectedType(String name) {
		return switch (name) {
			case "gbufferModelView", "gbufferProjection", "gbufferModelViewInverse", "gbufferProjectionInverse",
				"gbufferPreviousModelView", "gbufferPreviousProjection", "shadowModelView", "shadowProjection",
				"shadowModelViewInverse", "shadowProjectionInverse", "iris_ModelViewMatrix", "iris_ProjectionMatrix",
				"iris_ModelViewMat", "iris_ProjMat", "iris_ModelViewMatrixInverse", "iris_ProjectionMatrixInverse",
				"iris_ModelViewMatInverse", "iris_ProjMatInverse" -> "mat4";
			case "iris_NormalMatrix", "iris_NormalMat" -> "mat3";
			case "iris_LightmapTextureMatrix" -> "mat4";
			case "iris_currentAlphaTest", "iris_FogDensity", "iris_FogStart", "iris_FogEnd",
				"viewWidth", "viewHeight", "aspectRatio", "near", "far", "frameTimeCounter", "frameTime",
				"rainStrength", "wetness", "screenBrightness", "darknessLightFactor", "eyeAltitude" -> "float";
			case "iris_FogColor" -> "vec4";
			case "iris_ScreenSize" -> "vec2";
			case "frameCounter", "worldTime", "worldDay", "moonPhase", "isEyeInWater" -> "int";
			case "currentDate", "currentTime", "cameraPositionInt" -> "ivec3";
			case "currentYearTime", "eyeBrightness" -> "ivec2";
			case "skyColor", "cameraPositionFract", "cameraPosition" -> "vec3";
			default -> null;
		};
	}

	private static int alignment(String type) {
		return switch (type) {
			case "vec2", "ivec2" -> 8;
			case "vec3", "vec4", "ivec3", "ivec4", "mat3", "mat4" -> 16;
			default -> 4;
		};
	}

	private static int size(String type) {
		return switch (type) {
			case "vec2", "ivec2" -> 8;
			case "vec3", "vec4", "ivec3", "ivec4" -> 16;
			case "mat3" -> 48;
			case "mat4" -> 64;
			default -> 4;
		};
	}

	private static int align(int value, int alignment) {
		return (value + alignment - 1) & -alignment;
	}

	private static void put(ByteBuffer target, Vector2f value) {
		target.putFloat(value.x).putFloat(value.y);
	}

	private static void put(ByteBuffer target, Vector2i value) {
		target.putInt(value.x).putInt(value.y);
	}

	private static void put(ByteBuffer target, Vector3f value) {
		target.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(0.0f);
	}

	private static void put(ByteBuffer target, Vector3i value) {
		target.putInt(value.x).putInt(value.y).putInt(value.z).putInt(0);
	}

	private static void put(ByteBuffer target, Vector4f value) {
		target.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(value.w);
	}

	private static void put(ByteBuffer target, Vector4i value) {
		target.putInt(value.x).putInt(value.y).putInt(value.z).putInt(value.w);
	}

	private static void put(ByteBuffer target, Matrix3f value) {
		target.putFloat(value.m00()).putFloat(value.m01()).putFloat(value.m02()).putFloat(0.0f);
		target.putFloat(value.m10()).putFloat(value.m11()).putFloat(value.m12()).putFloat(0.0f);
		target.putFloat(value.m20()).putFloat(value.m21()).putFloat(value.m22()).putFloat(0.0f);
	}

	private static UnsupportedOperationException unsupported(String name, String type, String reason) {
		return new UnsupportedOperationException("Unsupported Vulkan uniform " + type + " " + name + ": " + reason);
	}
}
