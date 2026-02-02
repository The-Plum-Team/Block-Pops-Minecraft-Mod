package software.bernie.geckolib.renderer;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.GeckoLibServices;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayersContainer;
import software.bernie.geckolib.util.RenderUtil;

import java.util.List;
import net.minecraft.class_1921;
import net.minecraft.class_2318;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_2383;
import net.minecraft.class_243;
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
public class GeoBlockRenderer<T extends class_2586 & GeoAnimatable> implements GeoRenderer<T, Void, GeoRenderState>, class_827<T> {
	protected final GeoRenderLayersContainer<T, Void, GeoRenderState> renderLayers = new GeoRenderLayersContainer<>(this);
	protected final GeoModel<T> model;

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
	 * Returns the list of registered {@link GeoRenderLayer GeoRenderLayers} for this renderer
	 */
	@Override
	public List<GeoRenderLayer<T, Void, GeoRenderState>> getRenderLayers() {
		return this.renderLayers.getRenderLayers();
	}

	/**
	 * Adds a {@link GeoRenderLayer} to this renderer, to be called after the main model is rendered each frame
	 */
	public GeoBlockRenderer<T> addRenderLayer(GeoRenderLayer<T, Void, GeoRenderState> renderLayer) {
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
	 * Gets the id that represents the current animatable's instance for animation purposes.
	 * <p>
	 * You generally shouldn't need to override this
	 *
	 * @param animatable The Animatable instance being renderer
	 * @param relatedObject An object related to the render pass or null if not applicable.
	 *                         (E.G. ItemStack for GeoItemRenderer, entity instance for GeoReplacedEntityRenderer).
	 */
	@ApiStatus.Internal
	@Override
	public long getInstanceId(T animatable, Void relatedObject) {
		return animatable.method_11016().hashCode();
	}

	@ApiStatus.Internal
	@Override
	public GeoRenderState captureDefaultRenderState(T animatable, Void relatedObject, GeoRenderState renderState, float partialTick) {
		GeoRenderer.super.captureDefaultRenderState(animatable, relatedObject, renderState, partialTick);

		renderState.addGeckolibData(DataTickets.BLOCKSTATE, animatable.method_11010());
		renderState.addGeckolibData(DataTickets.BLOCKPOS, animatable.method_11016());
		renderState.addGeckolibData(DataTickets.POSITION, class_243.method_24953(animatable.method_11016()));
		renderState.addGeckolibData(DataTickets.BLOCK_FACING, getFacing(animatable));

		return renderState;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link class_4587} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(GeoRenderState renderState, class_4587 poseStack, BakedGeoModel model, @Nullable class_4597 bufferSource, @Nullable class_4588 buffer, boolean isReRender,
						  int packedLight, int packedOverlay, int renderColor) {
		if (!isReRender)
			this.blockRenderTranslations = new Matrix4f(poseStack.method_23760().method_23761());
	}

	/**
	 * Transform the {@link class_4587} in preparation for rendering the model, excluding when re-rendering the model as part of a {@link GeoRenderLayer} or external render call
	 */
	@Override
	public void adjustPositionForRender(GeoRenderState renderState, class_4587 poseStack, BakedGeoModel model, boolean isReRender) {
		if (!isReRender)
			poseStack.method_22904(0.5, 0, 0.5);
	}

	/**
	 * Scales the {@link class_4587} in preparation for rendering the model, excluding when re-rendering the model as part of a {@link GeoRenderLayer} or external render call
	 * <p>
	 * Override and call super with modified scale values as needed to further modify the scale of the model (E.G. child entities)
	 */
	@Override
	public void scaleModelForRender(GeoRenderState renderState, float widthScale, float heightScale, class_4587 poseStack, BakedGeoModel model, boolean isReRender) {
		GeoRenderer.super.scaleModelForRender(renderState, widthScale * this.scaleWidth, heightScale * this.scaleHeight, poseStack, model, isReRender);
	}

	@Override
	public void method_3569(T animatable, float partialTick, class_4587 poseStack, class_4597 bufferSource, int packedLight, int packedOverlay, class_243 cameraPosition) {
		GeoRenderState renderState = fillRenderState(animatable, null, new GeoRenderState.Impl(), partialTick);

		renderState.addGeckolibData(DataTickets.PACKED_LIGHT, packedLight);

		defaultRender(renderState, poseStack, bufferSource, null, null);
	}

	/**
	 * The actual render method that subtype renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link GeoRenderer#preRender} has already been called by this stage, and {@link GeoRenderer#postRender} will be called directly after
	 */
	@Override
	public void actuallyRender(GeoRenderState renderState, class_4587 poseStack, BakedGeoModel model, @Nullable class_1921 renderType,
							   class_4597 bufferSource, @Nullable class_4588 buffer, boolean isReRender, int packedLight, int packedOverlay, int renderColor) {
		if (!isReRender) {
			rotateBlock(renderState.getGeckolibData(DataTickets.BLOCK_FACING), poseStack);
			getGeoModel().handleAnimations(createAnimationState(renderState));
		}

		this.modelRenderTranslations = new Matrix4f(poseStack.method_23760().method_23761());

		if (buffer != null)
			GeoRenderer.super.actuallyRender(renderState, poseStack, model, renderType, bufferSource, buffer, isReRender, packedLight, packedOverlay, renderColor);
	}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	@Override
	public void doPostRenderCleanup() {
		this.blockRenderTranslations = null;
		this.modelRenderTranslations = null;
	}

	/**
	 * Renders the provided {@link GeoBone} and its associated child bones
	 */
	@Override
	public void renderRecursively(GeoRenderState renderState, class_4587 poseStack, GeoBone bone, class_1921 renderType, class_4597 bufferSource, class_4588 buffer, boolean isReRender, int packedLight, int packedOverlay, int renderColor) {
		if (bone.isTrackingMatrices()) {
			Matrix4f poseState = new Matrix4f(poseStack.method_23760().method_23761());
			Matrix4f localMatrix = RenderUtil.invertAndMultiplyMatrices(poseState, this.blockRenderTranslations);
			Matrix4f worldState = new Matrix4f(localMatrix);
			class_2338 pos = renderState.getGeckolibData(DataTickets.BLOCKPOS);

			bone.setModelSpaceMatrix(RenderUtil.invertAndMultiplyMatrices(poseState, this.modelRenderTranslations));
			bone.setLocalSpaceMatrix(localMatrix);
			bone.setWorldSpaceMatrix(worldState.translate(new Vector3f(pos.method_10263(), pos.method_10264(), pos.method_10260())));
		}

		GeoRenderer.super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender, packedLight, packedOverlay, renderColor);
	}

	/**
	 * Rotate the {@link class_4587} based on the determined {@link class_2350} the block is facing
	 */
	protected void rotateBlock(class_2350 facing, class_4587 poseStack) {
		switch (facing) {
			case field_11035 -> poseStack.method_22907(class_7833.field_40716.rotationDegrees(180));
			case field_11039 -> poseStack.method_22907(class_7833.field_40716.rotationDegrees(90));
			case field_11034 -> poseStack.method_22907(class_7833.field_40715.rotationDegrees(90));
			case field_11036 -> poseStack.method_22907(class_7833.field_40714.rotationDegrees(90));
			case field_11033 -> poseStack.method_22907(class_7833.field_40713.rotationDegrees(90));
			default -> {}
		}
	}

	/**
	 * Attempt to extract a direction from the block so that the model can be oriented correctly
	 */
	protected class_2350 getFacing(T blockEntity) {
		class_2680 blockState = blockEntity.method_11010();

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
	 * Create and fire the relevant {@code CompileRenderState} event hook for this renderer
	 */
	@Override
	public void fireCompileRenderStateEvent(T animatable, Void relatedObject, GeoRenderState renderState) {
		GeckoLibServices.Client.EVENTS.fireCompileBlockRenderState(this, renderState, animatable);
	}

	/**
	 * Create and fire the relevant {@code Pre-Render} event hook for this renderer
	 *
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(GeoRenderState renderState, class_4587 poseStack, BakedGeoModel model, class_4597 bufferSource) {
		return GeckoLibServices.Client.EVENTS.fireBlockPreRender(this, renderState, poseStack, model, bufferSource);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(GeoRenderState renderState, class_4587 poseStack, BakedGeoModel model, class_4597 bufferSource) {
		GeckoLibServices.Client.EVENTS.fireBlockPostRender(this, renderState, poseStack, model, bufferSource);
	}
}
