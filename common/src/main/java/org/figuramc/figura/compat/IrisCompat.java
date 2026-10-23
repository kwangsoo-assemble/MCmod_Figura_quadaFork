package org.figuramc.figura.compat;

import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import org.figuramc.figura.model.rendering.texture.FiguraRenderTypes;

public class IrisCompat {
    public static void assignPipelinesToIrisPrograms() {
        IrisApi.getInstance().assignPipeline(FiguraRenderTypes.FiguraRenderPipelines.FIGURA_SOLID, IrisProgram.ENTITIES);
        // 정하지 않으면 Iris 가 «Missing program … in override list» 를 내고 엉뚱한 프로그램으로 그린다(회색 반투명).
        // 텍스처 없는 꼭짓점 색 형식이라 BASIC(gbuffers_basic — 텍스처 없는 색 기하)
        IrisApi.getInstance().assignPipeline(FiguraRenderTypes.FiguraRenderPipelines.FIGURA_SOLID_COLOR, IrisProgram.BASIC);
    }
}
