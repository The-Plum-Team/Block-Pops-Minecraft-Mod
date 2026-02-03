package software.bernie.geckolib.renderer;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.GeckoLibServices;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayersContainer;
import software.bernie.geckolib.util.RenderUtil;

import java.util.List;
import net.minecraft.class_1921;
import net.minecraft.class_2318;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_2383;
import net.minecraft.class_2586;
import net.minecraft.class_2680;
import net.minecraft.class_4587;
import net.minecraft.class_4588;
import net.minecraft.class_4597;
import net.minecraft.class_7833;
import net.minecraft.class_827;

/**
 * Base {@link GeoRenderer} class for rendering {@link class_2586 Blocks} specifically
 * <p>
 * All blocks added to be rendered by GeckoLib should use an instance of this class.
 */
public class GeoBlockRenderer<T extends class_2586 & GeoAnimatable> implements GeoRenderer<T>, class_827<T> {
	protected final GeoRenderLayersContainer<T> renderLayers = new GeoRenderLayersContainer<>(this);
	protected final GeoModel<T> model;

	protected T animatable;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f blockRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

	public GeoBlockRenderer(GeoModel<T> model) {
		this.model = model;
	}

	/**
	 * Gets the model instance for this renderer
	 */
	@Override
	public GeoModel<T> getGeoModel() {
		return this.model;
	}

	/**
	 * Gets the {@link GeoAnimatable} instance currently being rendered
	 */
	@Override
	public T getAnimatable() {
		return this.animatable;
	}

	/**
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 */
	@Override
	public long getInstanceId(T animatable) {
		return animatable.method_11016().hashCode();
	}

	/**
	 * Returns the list of registered {@link GeoRenderLayer GeoRenderLayers} for this renderer
	 */
	@Override
	public List<GeoRenderLayer<T>> getRenderLayers() {
		return this.renderLayers.getRenderLayers();
	}

	/**
	 * Adds a {@link GeoRenderLayer} to this renderer, to be called after the main model is rendered each frame
	 */
	public GeoBlockRenderer<T> addRenderLayer(GeoRenderLayer<T> renderLayer) {
		this.renderLayers.addLayer(renderLayer);

		return this;
	}

	/**
	 * Sets a scale override for this renderer, telling GeckoLib to pre-scale the model
	 */
	public GeoBlockRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling GeckoLib to pre-scale the model
	 */
	public GeoBlockRenderer<T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link class_4587} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(class_4587 poseStack, T animatable, BakedGeoModel model, @Nullable class_4597 bufferSource, @Nullable class_4588 buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int renderColor) {
		this.blockRenderTranslations = new Matrix4f(poseStack.method_23760().method_23761());

		if (!isReRender)
			poseStack.method_22904(0.5, 0, 0.5);

		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);
	}

	@Override
	@ApiStatus.Internal
	public void method_3569(T animatable, float partialTick, class_4587 poseStack, class_4597 bufferSource,
			int packedLight, int packedOverlay) {
		this.animatable = animatable;

		defaultRender(poseStack, this.animatable, bufferSource, null, null, partialTick, packedLight);
	}

	/**
	 * The actual render method that subtype renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link GeoRenderer#preRender} has already been called by this stage, and {@link GeoRenderer#postRender} will be called directly after
	 */
	@Override
	public void actuallyRender(class_4587 poseStack, T animatable, BakedGeoModel model, @Nullable class_1921 renderType,
							   class_4597 bufferSource, @Nullable class_4588 buffer, boolean isReRender, float partialTick, int packedLight,
							   int packedOverlay, int renderColor) {
		if (!isReRender) {
			long instanceId = getInstanceId(animatable);

			rotateBlock(getFacing(animatable), poseStack);
			getGeoModel().handleAnimations(animatable, instanceId, createAnimationState(animatable, instanceId, 0, 0, partialTick, false), partialTick);
		}

		this.modelRenderTranslations = new Matrix4f(poseStack.method_23760().method_23761());

		if (buffer != null)
			GeoRenderer.super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick,
					packedLight, packedOverlay, renderColor);
	}

	/**
	 * Construct the {@link AnimationState} for the given render pass, ready to pass onto the {@link GeoModel} for handling.
	 * <p>
	 * Override this method to add additional {@link software.bernie.geckolib.constant.DataTickets data} to the AnimationState as needed
	 */
	@Override
	public AnimationState<T> createAnimationState(T animatable, long instanceId, float limbSwing, float limbSwingAmount, float partialTick, boolean isMoving) {
		AnimationState<T> animationState = GeoRenderer.super.createAnimationState(animatable, instanceId, limbSwing, limbSwingAmount, partialTick, isMoving);

		animationState.setData(DataTickets.TICK, animatable.getTick(animatable));
		animationState.setData(DataTickets.BLOCK_ENTITY, animatable);

		return animationState;
	}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	@Override
	public void doPostRenderCleanup() {
		this.animatable = null;
	}

	/**
	 * Renders the provided {@link GeoBone} and its associated child bones
	 */
	@Override
	public void renderRecursively(class_4587 poseStack, T animatable, GeoBone bone, class_1921 renderType, class_4597 bufferSource, class_4588 buffer, boolean isReRender, float partialTick, int packedLight,
								  int packedOverlay, int renderColor) {
		if (bone.isTrackingMatrices()) {
			Matrix4f poseState = new Matrix4f(poseStack.method_23760().method_23761());
			Matrix4f localMatrix = RenderUtil.invertAndMultiplyMatrices(poseState, this.blockRenderTranslations);
			Matrix4f worldState = new Matrix4f(localMatrix);
			class_2338 pos = this.animatable.method_11016();

			bone.setModelSpaceMatrix(RenderUtil.invertAndMultiplyMatrices(poseState, this.modelRenderTranslations));
			bone.setLocalSpaceMatrix(localMatrix);
			bone.setWorldSpaceMatrix(worldState.translate(new Vector3f(pos.method_10263(), pos.method_10264(), pos.method_10260())));
		}

		GeoRenderer.super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay,
				renderColor);
	}

	/**
	 * Rotate the {@link class_4587} based on the determined {@link class_2350} the block is facing
	 */
	protected void rotateBlock(class_2350 facing, class_4587 poseStack) {
		switch (facing) {
			case field_11035 -> poseStack.method_22907(class_7833.field_40716.rotationDegrees(180));
			case field_11039 -> poseStack.method_22907(class_7833.field_40716.rotationDegrees(90));
			case field_11043 -> poseStack.method_22907(class_7833.field_40716.rotationDegrees(0));
			case field_11034 -> poseStack.method_22907(class_7833.field_40716.rotationDegrees(270));
			case field_11036 -> poseStack.method_22907(class_7833.field_40714.rotationDegrees(90));
			case field_11033 -> poseStack.method_22907(class_7833.field_40713.rotationDegrees(90));
		}
	}

	/**
	 * Attempt to extract a direction from the block so that the model can be oriented correctly
	 */
	protected class_2350 getFacing(T block) {
		class_2680 blockState = block.method_11010();

		if (blockState.method_28498(class_2383.field_11177))
			return blockState.method_11654(class_2383.field_11177);

		if (blockState.method_28498(class_2318.field_10927))
			return blockState.method_11654(class_2318.field_10927);

		return class_2350.field_11043;
	}

	/**
	 * Create and fire the relevant {@code CompileLayers} event hook for this renderer
	 */
	@Override
	public void fireCompileRenderLayersEvent() {
		GeckoLibServices.Client.EVENTS.fireCompileBlockRenderLayers(this);
	}

	/**
	 * Create and fire the relevant {@code Pre-Render} event hook for this renderer
	 *
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(class_4587 poseStack, BakedGeoModel model, class_4597 bufferSource, float partialTick, int packedLight) {
		return GeckoLibServices.Client.EVENTS.fireBlockPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(class_4587 poseStack, BakedGeoModel model, class_4597 bufferSource, float partialTick, int packedLight) {
		GeckoLibServices.Client.EVENTS.fireBlockPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
