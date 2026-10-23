package org.figuramc.figura.utils;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.lua.api.vanilla_model.VanillaPart;
import org.figuramc.figura.model.ParentType;
import org.figuramc.figura.permissions.Permissions;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class RenderUtils {

    /**
     * 지금 이 아바타의 렌더 패스에서 아웃라인 타겟에 그려도 되는가.
     *
     * <p>⚠⚠ 이 가드를 빼면 <b>크래시한다.</b> {@code RenderType.outline(...)} 의 출력 타겟은
     * {@code levelRenderer.entityOutlineTarget()} 인데, 레벨 프레임그래프가 끝나면 그 핸들이
     * null 이 된다. HUD·GUI·페이퍼돌·워드로브에서 그리면 NPE 다.
     *
     * <p>{@code FIRST_PERSON_WORLD} 는 안전하다 — 그 패스는 메인 패스 람다 안에서 돌아
     * {@code endOutlineBatch} 보다 앞이다 (인게임 실측으로도 확인됨).
     */
    /**
     * 지금 레벨 메인 패스 안인가. {@code LevelRendererMixinFabric} 이 진입/이탈에서 세운다.
     *
     * <p>★★ 예전에는 {@code avatar.renderMode} 로 판정했는데 <b>틀렸다.</b> 실측 결과
     * Iris 가 {@code renderEntities} 를 대체해 {@code renderMode = RENDER} 를 세팅하는
     * {@code @ModifyArg} 가 발화하지 않고, {@code worldRender} 는 끝에서 이전 값(OTHER)으로
     * 되돌리기 때문에 <b>레이어 렌더 시점의 renderMode 가 OTHER</b> 였다.
     * 가드가 실제로 묻고 싶은 것은 "아웃라인 타겟이 유효한가" 이므로 그것을 직접 잰다.
     */
    public static boolean figura$inLevelPass = false;

    /**
     * 아웃라인 타겟에 그려도 되는가.
     *
     * <p>⚠⚠ 이 가드를 빼면 <b>크래시한다.</b> {@code RenderType.outline(...)} 의 출력 타겟은
     * {@code levelRenderer.entityOutlineTarget()} 인데, 레벨 프레임그래프가 끝나면 그 핸들이
     * null 이 된다. HUD·GUI·페이퍼돌·1인칭 손 렌더에서 그리면 NPE 다.
     */
    public static boolean outlineTargetAvailable(Avatar avatar) {
        return avatar != null && figura$inLevelPass;
    }

    /**
     * 피벗 파츠에 발광이 걸려 있으면, 그 자리에 그려질 <b>바닐라</b> 요소(갑옷·아이템 등)도
     * 같이 발광하도록 버퍼 소스를 감싼다. 아니면 원본을 그대로 돌려준다.
     *
     * <p>{@code OutlineBufferSource.getBuffer} 는 아웃라인이 아닌 렌더타입에 대해
     * <b>정상 버퍼 + 아웃라인 버퍼</b> 양쪽에 쓰는 멀티 컨슈머를 돌려준다. 그래서 감싸기만 하면
     * 원래 모습 그대로 그려지면서 아웃라인 타겟에도 실루엣이 남는다.
     *
     * <p>⚠ 색은 {@code getBuffer} 시점에 캡처되므로 <b>반드시 감싸기 전에</b> setColor 한다.
     * ⚠ 이미 {@code OutlineBufferSource} 면 두 번 감싸지 않는다 (엔티티가 진짜 발광 중인 경우).
     */
    public static MultiBufferSource pivotGlowBuffer(Avatar avatar, MultiBufferSource source) {
        return figura$glowWrap(avatar, source, avatar == null ? null : avatar.currentPivotGlowColor);
    }

    /**
     * <b>부위</b>(머리·몸통·팔·다리) 발광을 바닐라 갑옷·아이템에 옮긴다.
     * 주어진 부위들 중 하나라도 발광 중이면 그 색으로 감싼다.
     *
     * <p>피벗이 없는 아바타에서 쓰는 경로다 — 그런 아바타는 갑옷이 조각 통짜로 그려지므로
     * <b>발광도 조각 통짜</b>가 된다. 부위별로 나누고 싶으면 모델에 피벗 그룹을 넣으면
     * 자동으로 {@link #pivotGlowBuffer} 경로를 타 정밀해진다.
     */
    public static MultiBufferSource vanillaPartGlowBuffer(Avatar avatar, MultiBufferSource source, ParentType... types) {
        if (avatar == null || avatar.renderer == null)
            return source;
        return figura$glowWrap(avatar, source, avatar.renderer.findVanillaPartGlow(types));
    }

    /**
     * 발광 색이 있으면 아웃라인 타겟에도 흘리는 버퍼로 감싼다.
     *
     * <p>{@code OutlineBufferSource.getBuffer} 는 아웃라인이 아닌 렌더타입에 대해
     * <b>정상 버퍼 + 아웃라인 버퍼</b> 양쪽에 쓰는 멀티 컨슈머를 돌려준다.
     * ⚠ 색은 {@code getBuffer} 시점에 캡처되므로 반드시 감싸기 <b>전에</b> setColor 한다.
     * ⚠ 이미 {@code OutlineBufferSource} 면 두 번 감싸지 않는다 (엔티티가 진짜 발광 버프를 받은 경우).
     */
    private static MultiBufferSource figura$glowWrap(Avatar avatar, MultiBufferSource source, FiguraVec3 color) {
        if (color == null || avatar == null || source instanceof OutlineBufferSource || !outlineTargetAvailable(avatar))
            return source;

        OutlineBufferSource outline = Minecraft.getInstance().renderBuffers().outlineBufferSource();
        outline.setColor(
                (int) (Math.min(Math.max(color.x, 0), 1) * 255),
                (int) (Math.min(Math.max(color.y, 0), 1) * 255),
                (int) (Math.min(Math.max(color.z, 0), 1) * 255),
                0xFF // 아웃라인 셰이더가 알파를 1로 고정한다
        );
        return outline;
    }

    public static boolean vanillaModel(Avatar avatar) {
        return avatar != null && avatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) >= 1;
    }

    public static boolean vanillaModelAndScript(Avatar avatar) {
        return avatar != null && avatar.luaRuntime != null && avatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) >= 1;
    }

    public static TextureAtlasSprite firstFireLayer(Avatar avatar) {
        if (!vanillaModelAndScript(avatar))
            return null;

        ResourceLocation layer = avatar.luaRuntime.renderer.fireLayer1;
        return layer != null ? Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(layer) : null;
    }

    public static TextureAtlasSprite secondFireLayer(Avatar avatar) {
        if (!vanillaModelAndScript(avatar))
            return null;

        ResourceLocation layer1 = avatar.luaRuntime.renderer.fireLayer1;
        ResourceLocation layer2 = avatar.luaRuntime.renderer.fireLayer2;

        if (layer2 != null)
            return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(layer2);
        if (layer1 != null)
            return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(layer1);

        return null;
    }

    public static VanillaPart partFromSlot(Avatar avatar, EquipmentSlot equipmentSlot) {
        if (!RenderUtils.vanillaModelAndScript(avatar))
            return null;

        return switch (equipmentSlot) {
            case HEAD -> avatar.luaRuntime.vanilla_model.HELMET;
            case CHEST -> avatar.luaRuntime.vanilla_model.CHESTPLATE;
            case LEGS -> avatar.luaRuntime.vanilla_model.LEGGINGS;
            case FEET -> avatar.luaRuntime.vanilla_model.BOOTS;
            default -> null;
        };
    }

    public static VanillaPart pivotToPart(Avatar avatar, ParentType type) {
        if (!RenderUtils.vanillaModelAndScript(avatar))
            return null;

        return switch (type) {
            case HelmetPivot -> avatar.luaRuntime.vanilla_model.HELMET;
            case ChestplatePivot -> avatar.luaRuntime.vanilla_model.CHESTPLATE;
            case LeftShoulderPivot -> avatar.luaRuntime.vanilla_model.CHESTPLATE_LEFT_ARM;
            case RightShoulderPivot -> avatar.luaRuntime.vanilla_model.CHESTPLATE_RIGHT_ARM;
            case LeggingsPivot -> avatar.luaRuntime.vanilla_model.LEGGINGS;
            case LeftLeggingPivot -> avatar.luaRuntime.vanilla_model.LEGGINGS_LEFT_LEG;
            case RightLeggingPivot -> avatar.luaRuntime.vanilla_model.LEGGINGS_RIGHT_LEG;
            case LeftBootPivot -> avatar.luaRuntime.vanilla_model.BOOTS_LEFT_LEG;
            case RightBootPivot -> avatar.luaRuntime.vanilla_model.BOOTS_RIGHT_LEG;
            case LeftElytraPivot -> avatar.luaRuntime.vanilla_model.LEFT_ELYTRA;
            case RightElytraPivot -> avatar.luaRuntime.vanilla_model.RIGHT_ELYTRA;
            default -> null;
        };
    }

    public static EquipmentSlot slotFromPart(ParentType type) {
        switch (type){
            case Head, HelmetItemPivot, HelmetPivot, Skull -> {
                return EquipmentSlot.HEAD;
            }
            case Body, ChestplatePivot, LeftShoulderPivot, RightShoulderPivot, LeftElytra, RightElytra, RightElytraPivot, LeftElytraPivot -> {
                return EquipmentSlot.CHEST;
            }
            case LeftArm, LeftItemPivot, LeftSpyglassPivot -> {
                return EquipmentSlot.OFFHAND;
            }
            case RightArm, RightItemPivot, RightSpyglassPivot -> {
                return EquipmentSlot.MAINHAND;
            }
            case LeftLeggingPivot, RightLeggingPivot, LeftLeg, RightLeg, LeggingsPivot -> {
                return EquipmentSlot.LEGS;
            }
            case LeftBootPivot, RightBootPivot -> {
                return EquipmentSlot.FEET;
            }
            default -> {
                return null;
            }
        }

    }

    public static boolean renderArmItem(Avatar avatar, boolean lefty, CallbackInfo ci) {
        if (!vanillaModel(avatar))
            return false;

        if (avatar.luaRuntime != null && (
                lefty && !avatar.luaRuntime.vanilla_model.LEFT_ITEM.checkVisible() ||
                !lefty && !avatar.luaRuntime.vanilla_model.RIGHT_ITEM.checkVisible()
        )) {
            ci.cancel();
            return false;
        }

        return true;
    }

    @ExpectPlatform
    public static ResourceLocation getPlayerSkinTexture(WingsLayer<?, ?> wingsLayer, HumanoidRenderState renderState) {
        throw new AssertionError();
    }
}
