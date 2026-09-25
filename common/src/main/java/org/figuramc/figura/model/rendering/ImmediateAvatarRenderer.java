package org.figuramc.figura.model.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.lua.api.ClientAPI;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.math.vector.FiguraVec4;
import org.figuramc.figura.model.*;
import org.figuramc.figura.model.rendering.texture.FiguraTexture;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet;
import org.figuramc.figura.model.rendering.texture.RenderTypes;
import org.figuramc.figura.model.rendertasks.RenderTask;
import org.figuramc.figura.utils.ColorUtils;
import org.figuramc.figura.utils.ui.UIHelper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class ImmediateAvatarRenderer extends AvatarRenderer {

    protected final PartCustomization.PartCustomizationStack customizationStack = new PartCustomization.PartCustomizationStack();

    public static final FiguraMat4 CAMERA_POS_TO_WORLD_MATRIX = FiguraMat4.of();

    private static final PartCustomization pivotOffsetter = new PartCustomization();
    protected static final VertexBuffer VERTEX_BUFFER = new VertexBuffer();

    public ImmediateAvatarRenderer(Avatar avatar) {
        super(avatar);

        // Vertex data, read model parts
        root = FiguraModelPartReader.read(avatar, avatar.nbt.getCompoundOrEmpty("models"), textureSets, false);

        sortParts();
    }

    public void checkEmpty() {
        if (!customizationStack.isEmpty())
            throw new IllegalStateException("Customization stack not empty!");
    }

    @Override
    public int render() {
        return commonRender(1.5d);
    }

    @Override
    public int renderSpecialParts() {
        return commonRender(0);
    }

    @Override
    public void updateMatrices() {
        // flag rendering state
        this.isRendering = true;

        // setup root customizations
        PartCustomization customization = setupRootCustomization(1.5d);

        // Push transform
        customizationStack.push(customization);

        // world matrices
        CAMERA_POS_TO_WORLD_MATRIX.set(AvatarRenderer.worldToCameraPosMatrix().invert());

        // calculate each part matrices
        calculatePartMatrices(root);

        // finish rendering
        customizationStack.pop();
        checkEmpty();

        this.isRendering = false;
    }

    protected int commonRender(double vertOffset) {
        // flag rendering state
        this.isRendering = true;

        // iris fix
        int irisConfig = UIHelper.paperdoll || !ClientAPI.hasShaderPackMod() ? 0 : Configs.IRIS_COMPATIBILITY_FIX.value;
        doIrisEmissiveFix = (irisConfig >= 2 && ClientAPI.hasShaderPack()) || (avatar.renderMode != EntityRenderMode.RENDER && avatar.renderMode != EntityRenderMode.WORLD);
        offsetRenderLayers = irisConfig >= 1;

        // custom textures
        for (FiguraTextureSet set : textureSets)
            set.uploadIfNeeded();
        for (FiguraTexture texture : customTextures.values())
            texture.uploadIfDirty();

        // Set shouldRenderPivots
        int config = Configs.RENDER_DEBUG_PARTS_PIVOT.value;
        if (!Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes() || (!avatar.isHost && config < 2))
            shouldRenderPivots = 0;
        else
            shouldRenderPivots = config;

        // world matrices
        if (allowMatrixUpdate) {
            CAMERA_POS_TO_WORLD_MATRIX.set(AvatarRenderer.worldToCameraPosMatrix().invert());
        }

        // complexity
        int prev = avatar.complexity.remaining;
        int[] remainingComplexity = new int[] {prev};

        // render all model parts
        if (root.customization.visible) {
            if (currentFilterScheme.parentType.isSeparate) {
                List<FiguraModelPart> parts = separatedParts.get(currentFilterScheme.parentType);
                if (parts != null) {
                    boolean renderLayer = !currentFilterScheme.parentType.isRenderLayer;
                    if (renderLayer) {
                        PartCustomization customization = setupRootCustomization(vertOffset);
                        customizationStack.push(customization); // push root
                        customizationStack.push(root.customization); // push "models"
                    }

                    for (FiguraModelPart part : parts) {
                        if (currentFilterScheme.parentType == ParentType.Item && part != itemToRender)
                            continue;

                        boolean saved = part.savedCustomization != null;
                        if (saved) {
                            customizationStack.push(part.savedCustomization);
                            part.savedCustomization = null;
                        }

                        renderPart(part, remainingComplexity, currentFilterScheme.initialValue);

                        if (saved) customizationStack.pop();
                    }

                    if (renderLayer) {
                        customizationStack.pop(); // pop "models"
                        customizationStack.pop(); // pop root
                    }
                }
            } else {
                PartCustomization customization = setupRootCustomization(vertOffset);
                customizationStack.push(customization);
                renderPart(root, remainingComplexity, currentFilterScheme.initialValue);
                customizationStack.pop();
            }

            // push vertices to vertex consumer
            FiguraMod.pushProfiler("draw");
            FiguraMod.pushProfiler("primary");
            VERTEX_BUFFER.consume(true, bufferSource);
            FiguraMod.popPushProfiler("secondary");
            VERTEX_BUFFER.consume(false, bufferSource);
            FiguraMod.popPushProfiler("glowOutline");
            VERTEX_BUFFER.consumeOutline(bufferSource);
            FiguraMod.popProfiler(2);

            // finish rendering
            checkEmpty();
        }

        this.isRendering = false;
        if (this.dirty)
            clean();

        return prev - Math.max(remainingComplexity[0], 0);
    }

    protected PartCustomization setupRootCustomization(double vertOffset) {
        PartCustomization customization = new PartCustomization();

        customization.setPrimaryRenderType(RenderTypes.TRANSLUCENT);
        customization.setSecondaryRenderType(RenderTypes.EMISSIVE);

        double s = 1.0 / 16;
        customization.positionMatrix.scale(s, s, s);
        customization.positionMatrix.rotateZ(180);
        customization.positionMatrix.translate(0, vertOffset, 0);
        customization.normalMatrix.rotateZ(180);

        customization.positionMatrix.multiply(posMat);
        customization.normalMatrix.multiply(normalMat);

        customization.light = light;
        customization.alpha = alpha;
        customization.overlay = overlay;

        customization.primaryTexture = new TextureCustomization(FiguraTextureSet.OverrideType.PRIMARY, null);
        customization.secondaryTexture = new TextureCustomization(FiguraTextureSet.OverrideType.SECONDARY, null);

        return customization;
    }

    protected boolean renderPart(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate) {
        FiguraMod.pushProfiler(part.name);

        PartCustomization custom = part.customization;

        // test the current filter scheme
        FiguraMod.pushProfiler("predicate");
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, prevPredicate);
        if (thisPassedPredicate == null || (!custom.visible)) {
            if (part.parentType.isRenderLayer)
                part.savedCustomization = customizationStack.peek();
            FiguraMod.popProfiler(2);
            return true;
        }

        // calculate part transforms

        // calculate vanilla parent
        FiguraMod.popPushProfiler("copyVanillaPart");
        part.applyVanillaTransforms(vanillaModelData);
        part.applyExtraTransforms(customizationStack.peek());

        // visibility
        FiguraMod.popPushProfiler("checkVanillaVisible");
        if (!ignoreVanillaVisibility && custom.vanillaVisible != null && !custom.vanillaVisible) {
            FiguraMod.popPushProfiler("removeVanillaTransforms");
            part.resetVanillaTransforms();
            FiguraMod.popProfiler(2);
            return true;
        }

        // pre render function
        if (part.preRender != null) {
            FiguraMod.popPushProfiler("preRenderFunction");
            avatar.run(part.preRender, avatar.render, tickDelta, avatar.renderMode.name(), part);
        }

        // recalculate stuff
        FiguraMod.popPushProfiler("calculatePartMatrices");
        custom.recalculate();

        // void blocked matrices
        // that's right, check only for previous predicate
        FiguraMat4 positionCopy = null;
        FiguraMat3 normalCopy = null;
        boolean voidMatrices = !allowHiddenTransforms && !prevPredicate;
        if (voidMatrices) {
            FiguraMod.popPushProfiler("clearMatrices");
            positionCopy = custom.positionMatrix.copy();
            normalCopy = custom.normalMatrix.copy();
            custom.positionMatrix.reset();
            custom.normalMatrix.reset();
        }

        // push stack
        FiguraMod.popPushProfiler("pushCustomizationStack");
        customizationStack.push(custom);

        // restore variables
        if (voidMatrices) {
            FiguraMod.popPushProfiler("restoreMatrices");
            custom.positionMatrix.set(positionCopy);
            custom.normalMatrix.set(normalCopy);
        }

        if (thisPassedPredicate) {
            // recalculate world matrices
            FiguraMod.popPushProfiler("worldMatrices");
            if (allowMatrixUpdate) {
                FiguraMat4 mat = partToWorldMatrices(custom);
                part.savedPartToWorldMat.set(mat);
            }

            // recalculate light
            FiguraMod.popPushProfiler("calculateLight");
            Level l;
            if (custom.light != null) {
                updateLight = false;
                pivotOffsetter.light = custom.light;
            }
            else if (updateLight && (l = Minecraft.getInstance().level) != null) {
                FiguraVec3 pos = part.savedPartToWorldMat.apply(0d, 0d, 0d);
                int block = l.getBrightness(LightLayer.BLOCK, pos.asBlockPos());
                int sky = l.getBrightness(LightLayer.SKY, pos.asBlockPos());
                customizationStack.peek().light = LightTexture.pack(block, sky);
            }

            if (custom.alpha != null)
                pivotOffsetter.alpha = custom.alpha;
            if (custom.overlay != null)
                pivotOffsetter.overlay = custom.overlay;
        }

        // mid render function
        if (part.midRender != null) {
            FiguraMod.popPushProfiler("midRenderFunction");
            avatar.run(part.midRender, avatar.render, tickDelta, avatar.renderMode.name(), part);
        }

        // render this
        FiguraMod.popPushProfiler("pushVertices");
        boolean breakRender = thisPassedPredicate && !part.pushVerticesImmediate(this, remainingComplexity);

        // render extras
        FiguraMod.popPushProfiler("extras");
        if (!breakRender && thisPassedPredicate) {
            boolean renderPivot = shouldRenderPivots > 0;
            boolean renderTasks = !part.renderTasks.isEmpty();
            boolean renderPivotParts = part.parentType.isPivot && allowPivotParts;

            // Note: record the glow of body-part model parts; the vanilla armor/item layers that run later read it.
            //   This is the channel that makes "left arm glows => left-arm armor and item glow" work even on
            //   avatars without pivot parts.
            if (VANILLA_GLOW_PARTS.contains(part.parentType)) {
                PartCustomization own = customizationStack.peek();
                if (Boolean.TRUE.equals(own.glow))
                    vanillaPartGlow.putIfAbsent(part.parentType,
                            own.glowColor != null ? own.glowColor.copy() : FiguraVec3.of(1, 1, 1));
            }

            if (renderPivot || renderTasks || renderPivotParts) {
                // fix light and overlay
                PartCustomization parent = customizationStack.peek();
                int light = parent.light != null ? parent.light : LightTexture.FULL_BRIGHT;
                int overlay = parent.overlay != null ? parent.overlay : OverlayTexture.NO_OVERLAY;

                // fix pivots
                FiguraMod.pushProfiler("fixMatricesPivot");

                FiguraVec3 pivot = custom.getPivot().copy().add(custom.getOffsetPivot());
                pivotOffsetter.setPos(pivot);
                pivotOffsetter.recalculate();
                customizationStack.push(pivotOffsetter);

                PartCustomization peek = customizationStack.peek();

                // render pivot indicators
                if (renderPivot) {
                    FiguraMod.popPushProfiler("renderPivotCube");
                    renderPivot(part, peek);
                }

                // render tasks
                if (renderTasks) {
                    FiguraMod.popPushProfiler("renderTasks");
                    interceptRendersIntoFigura = false;
                    for (RenderTask task : part.renderTasks.values()) {
                        if (!task.shouldRender())
                            continue;
                        int neededComplexity = task.getComplexity();
                        if (neededComplexity > remainingComplexity[0])
                            break;
                        FiguraMod.pushProfiler(task.getName());
                        task.render(customizationStack, bufferSource, light, overlay);
                        remainingComplexity[0] -= neededComplexity;
                        FiguraMod.popProfiler();
                    }
                    interceptRendersIntoFigura = true;
                }

                // render pivot parts
                if (renderPivotParts && part.parentType.isPivot) {
                    FiguraMod.popPushProfiler("savePivotParts");
                    savePivotTransform(part.parentType, peek);
                }

                customizationStack.pop();
                FiguraMod.popProfiler();
            }
        }

        // render children
        FiguraMod.popPushProfiler("children");
        for (FiguraModelPart child : List.copyOf(part.children)) {
            if (!renderPart(child, remainingComplexity, thisPassedPredicate)) {
                breakRender = true;
                break;
            }
        }

        // reset the parent
        FiguraMod.popPushProfiler("removeVanillaTransforms");
        part.resetVanillaTransforms();

        // post render function
        if (part.postRender != null) {
            FiguraMod.popPushProfiler("postRenderFunction");
            avatar.run(part.postRender, avatar.render, tickDelta, avatar.renderMode.name(), part);
        }

        // pop
        customizationStack.pop();
        FiguraMod.popProfiler(2);

        return !breakRender;
    }

    protected void renderPivot(FiguraModelPart part, PartCustomization customization) {
        boolean group = part.customization.partType == PartCustomization.PartType.GROUP;
        FiguraVec3 color = group ? ColorUtils.Colors.FIGURA_BLUE.vec : ColorUtils.Colors.AWESOME_BLUE.vec;
        double boxSize = group ? 1 / 16d : 1 / 32d;
        boxSize /= Math.max(Math.cbrt(part.savedPartToWorldMat.det()), 0.02);

        PoseStack stack = customization.copyIntoGlobalPoseStack();

        ShapeRenderer.renderLineBox(stack, bufferSource.getBuffer(RenderType.LINES),
                -boxSize, -boxSize, -boxSize,
                boxSize, boxSize, boxSize,
                (float) color.x, (float) color.y, (float) color.z, 1f);
    }

    protected void savePivotTransform(ParentType parentType, PartCustomization customization) {
        FiguraMat4 currentPosMat = customization.getPositionMatrix();
        FiguraMat3 currentNormalMat = customization.getNormalMatrix();
        ConcurrentLinkedQueue<PivotTransform> queue = pivotCustomizations.computeIfAbsent(parentType, p -> new ConcurrentLinkedQueue<>());
        // Note: the glow state is carried in the **same element** as the transform. Several entries can pile up
        //   in the queue, so a separate field would lose track of which transform each color belongs to.
        FiguraVec3 glow = Boolean.TRUE.equals(customization.glow)
                ? (customization.glowColor != null ? customization.glowColor.copy() : FiguraVec3.of(1, 1, 1))
                : null;
        queue.add(new PivotTransform(currentPosMat, currentNormalMat, glow)); // These are COPIES, so ok to add
    }

    protected FiguraMat4 partToWorldMatrices(PartCustomization cust) {
        FiguraMat4 customizePeek = customizationStack.peek().positionMatrix.copy();
        // Translate by the inverse matrix of the camera position, as of 1.20.5 it is no longer dependent on camera rot.
        customizePeek.multiply(CAMERA_POS_TO_WORLD_MATRIX);
        FiguraVec3 piv = cust.getPivot();

        FiguraMat4 translation = FiguraMat4.of();
        translation.translate(piv);
        customizePeek.rightMultiply(translation);

        return customizePeek;
    }

    protected void calculatePartMatrices(FiguraModelPart part) {
        FiguraMod.pushProfiler(part.name);

        PartCustomization custom = part.customization;

        // Store old visibility, but overwrite it in case we only want to render certain parts
        FiguraMod.pushProfiler("predicate");
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, true);
        if (thisPassedPredicate == null) {
            FiguraMod.popProfiler(2);
            return;
        }

        // FPS (2026-09-10, R&D fork): skip hidden subtrees, mirroring renderPart().
        //
        // This method only runs for the LOCAL player's avatar in first person
        // (LivingEntityRendererMixin.preRender -> Avatar.firstPerson), and it was the ONLY
        // place that still walked invisible parts: renderPart() has bailed on !custom.visible
        // since forever, so a hidden part's savedPartToWorldMat was already frozen in every
        // other path. Doing the same here makes first person consistent with third person
        // and skips whole hidden subtrees (a ragdoll shell alone is 121 nodes).
        //
        // WARNING: a hidden part's savedPartToWorldMat now stops updating in first person too.
        // Lua that reads partToWorldMatrix()/getPositionMatrix() on a HIDDEN part gets the last
        // rendered value. That was already true outside first person, so no script can have
        // depended on it being fresh -- but if a first-person-only consumer ever appears,
        // this is the line to look at.
        if (!custom.visible) {
            FiguraMod.popProfiler(2);
            return;
        }

        // calculate part transforms

        // calculate vanilla parent
        FiguraMod.popPushProfiler("copyVanillaPart");
        part.applyVanillaTransforms(vanillaModelData);
        part.applyExtraTransforms(customizationStack.peek());

        // push customization stack
        FiguraMod.popPushProfiler("calculatePartMatrices");
        custom.recalculate();
        FiguraMod.popPushProfiler("applyOnStack");
        customizationStack.push(custom);

        // render extras
        if (thisPassedPredicate) {
            // part to world matrices
            FiguraMod.popPushProfiler("worldMatrices");
            FiguraMat4 mat = partToWorldMatrices(custom);
            part.savedPartToWorldMat.set(mat);
        }

        // render children
        FiguraMod.popPushProfiler("children");
        for (FiguraModelPart child : part.children)
            calculatePartMatrices(child);

        // reset the parent
        part.resetVanillaTransforms();

        // pop
        customizationStack.pop();
        FiguraMod.popProfiler(2);
    }

    public void pushFaces(int faceCount, int[] remainingComplexity, FiguraTextureSet textureSet, List<Vertex> vertices) {
        // Handle cases that we can quickly
        if (faceCount == 0 || vertices.isEmpty())
            return;

        PartCustomization customization = customizationStack.peek();

        VertexData primary = getTexture(customization, textureSet, true);
        VertexData secondary = getTexture(customization, textureSet, false);

        if (primary.renderType == null && secondary.renderType == null) {
            remainingComplexity[0] += faceCount;
            return;
        }

        if (primary.renderType != null)
            pushToBuffer(faceCount, primary, customization, textureSet, vertices);
        if (secondary.renderType != null)
            pushToBuffer(faceCount, secondary, customization, textureSet, vertices);

        // Note: per-part glow draws the same faces once more, into the outline target.
        //   The primary/secondary passes already exist, so this is a symmetric extension of them.
        if (Boolean.TRUE.equals(customization.glow) && glowOutlineAllowed()) {
            VertexData outline = getGlowOutline(customization, textureSet);
            if (outline.renderType != null)
                pushToBuffer(faceCount, outline, customization, textureSet, vertices);
        }
    }

    /**
     * Whether the current render pass may draw to the outline target.
     *
     * <p>Warning: removing this guard <b>causes a crash.</b> {@code RenderType.outline(...)} holds
     * {@code levelRenderer.entityOutlineTarget()} as its output target, and that handle becomes null once the
     * level frame graph finishes. Drawing from the HUD, GUI, paper doll or wardrobe then throws an NPE.
     *
     * <p>{@code FIRST_PERSON_WORLD} is allowed: that pass runs inside {@code method_62214} (the main pass
     * lambda), before {@code endOutlineBatch}. The first-person <b>hand</b> render, by contrast, runs after
     * {@code renderLevel} returns, so it cannot get in here.
     */
    private boolean glowOutlineAllowed() {
        return org.figuramc.figura.utils.RenderUtils.outlineTargetAvailable(avatar);
    }

    private static final FiguraVec3 GLOW_DEFAULT_COLOR = FiguraVec3.of(1, 1, 1);

    /** Body parts that vanilla armor and items can follow. These are <b>vanilla model parts</b>, not pivots. */
    private static final java.util.EnumSet<ParentType> VANILLA_GLOW_PARTS = java.util.EnumSet.of(
            ParentType.Head, ParentType.Body,
            ParentType.LeftArm, ParentType.RightArm,
            ParentType.LeftLeg, ParentType.RightLeg);

    /**
     * Note: {@code customization.color} is deliberately not used. That keeps the glow color fully separate
     * from setColor's hierarchical multiplication, so it acts as an override with no interference.
     */
    private VertexData getGlowOutline(PartCustomization customization, FiguraTextureSet textureSet) {
        VertexData ret = new VertexData();
        ResourceLocation id = textureSet.getOverrideTexture(avatar.owner, customization.primaryTexture);
        if (id == null)
            return ret;
        ret.outline = true;
        ret.renderType = RenderType.outline(id);
        ret.color = customization.glowColor != null ? customization.glowColor : GLOW_DEFAULT_COLOR;
        return ret;
    }

    private VertexData getTexture(PartCustomization customization, FiguraTextureSet textureSet, boolean primary) {
        RenderTypes types = primary ? customization.getPrimaryRenderType() : customization.getSecondaryRenderType();
        TextureCustomization texture = primary ? customization.primaryTexture : customization.secondaryTexture;
        VertexData ret = new VertexData();

        if (types == RenderTypes.NONE)
            return ret;

        // get texture
        ResourceLocation id = textureSet.getOverrideTexture(avatar.owner, texture);

        // color
        ret.color = primary ? customization.color : customization.color2;

        // primary
        ret.primary = primary;

        // get render type
        if (id != null) {
            if (translucent) {
                ret.renderType = RenderType.itemEntityTranslucentCull(id);
                return ret;
            }
            if (glowing) {
                ret.renderType = RenderType.outline(id);
                return ret;
            }
        }

        if (types == null)
            return ret;

        if (offsetRenderLayers && !primary && types.isOffset())
            ret.vertexOffset = FiguraMod.VERTEX_OFFSET;

        // Switch to cutout with fullbright if the iris emissive fix is enabled
        if (doIrisEmissiveFix && types == RenderTypes.EMISSIVE) {
            ret.fullBright = true;
            ret.renderType = RenderTypes.TRANSLUCENT_CULL.get(id);
        } else {
            // Note: the glint of parts painted with a front-render marker (#FFFFFB family) has its depth test
            //   turned off (2026-09-14). See the RenderTypes#frontLayerGlint comment for why; in short, the shader
            //   pulls only the **base layer's** depth forward, so the glint's EQUAL_DEPTH_TEST never matches.
            //   Warning: the base layer's vertex color is in the **primary slot**, so this always reads
            //   customization.color. Using ret.color (color2 when secondary) would test an unrelated color.
            RenderType frontGlint = RenderTypes.frontLayerGlint(types, customization.color, id);
            ret.renderType = frontGlint != null ? frontGlint : types.get(id);
        }

        return ret;
    }

    private static final FiguraVec4 pos = FiguraVec4.of();
    private static final FiguraVec3 normal = FiguraVec3.of();
    private static final FiguraVec3 uv = FiguraVec3.of(0, 0, 1);
    private void pushToBuffer(int faceCount, VertexData vertexData, PartCustomization customization, FiguraTextureSet textureSet, List<Vertex> vertices) {
        int vertCount = faceCount * 4;

        FiguraVec3 uvFixer = FiguraVec3.of();
        uvFixer.set(textureSet.getWidth(), textureSet.getHeight(), 1); // Dividing by this makes uv 0 to 1

        int overlay = customization.overlay;
        int light = vertexData.fullBright ? LightTexture.FULL_BRIGHT : customization.light;

        Consumer<VertexConsumer> writer = vertexConsumer -> {
            for (int i = 0; i < vertCount; i++) {
                Vertex vertex = vertices.get(i);

                pos.set(vertex.x, vertex.y, vertex.z, 1);
                pos.transform(customization.positionMatrix);
                pos.add(pos.normalized().scale(vertexData.vertexOffset));
                normal.set(vertex.nx, vertex.ny, vertex.nz);
                normal.transform(customization.normalMatrix);
                uv.set(vertex.u, vertex.v, 1);
                uv.divide(uvFixer);
                uv.transform(customization.uvMatrix);

                vertexConsumer
                        .addVertex((float) pos.x, (float) pos.y, (float) pos.z)
                        .setColor((float) vertexData.color.x, (float) vertexData.color.y, (float) vertexData.color.z, customization.alpha)
                        .setUv((float) uv.x, (float) uv.y)
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
            }
        };

        if (vertexData.outline)
            VERTEX_BUFFER.getOutlineBufferFor(vertexData.renderType, writer);
        else
            VERTEX_BUFFER.getBufferFor(vertexData.renderType, vertexData.primary, writer);
    }

    private static class VertexData {
        public RenderType renderType;
        public boolean fullBright;
        public float vertexOffset;
        public FiguraVec3 color;
        public boolean primary;
        /** If true, this is the third pass, for the outline target (per-part glow). */
        public boolean outline;
    }

    private static class VertexBuffer {
        private final HashMap<RenderType, List<Consumer<VertexConsumer>>> primaryBuffers = new LinkedHashMap<>();
        private final HashMap<RenderType, List<Consumer<VertexConsumer>>> secondaryBuffers = new LinkedHashMap<>();
        private final HashMap<RenderType, List<Consumer<VertexConsumer>>> outlineBuffers = new LinkedHashMap<>();

        public void getBufferFor(RenderType renderType, boolean primary, Consumer<VertexConsumer> consumer) {
            HashMap<RenderType, List<Consumer<VertexConsumer>>> buffer = primary ? primaryBuffers : secondaryBuffers;
            List<Consumer<VertexConsumer>> list = buffer.computeIfAbsent(renderType, renderType1 -> new ArrayList<>());
            list.add(consumer);
        }

        public void getOutlineBufferFor(RenderType renderType, Consumer<VertexConsumer> consumer) {
            outlineBuffers.computeIfAbsent(renderType, r -> new ArrayList<>()).add(consumer);
        }

        public void consumeOutline(MultiBufferSource bufferSource) {
            drain(outlineBuffers, bufferSource);
        }

        public void consume(boolean primary, MultiBufferSource bufferSource) {
            drain(primary ? primaryBuffers : secondaryBuffers, bufferSource);
        }

        private static void drain(HashMap<RenderType, List<Consumer<VertexConsumer>>> map, MultiBufferSource bufferSource) {
            for (Map.Entry<RenderType, List<Consumer<VertexConsumer>>> entry : map.entrySet()) {
                VertexConsumer vertexConsumer = bufferSource.getBuffer(entry.getKey());
                List<Consumer<VertexConsumer>> consumers = entry.getValue();
                for (Consumer<VertexConsumer> consumer : consumers)
                    consumer.accept(vertexConsumer);
            }
            map.clear();
        }
    }
}
