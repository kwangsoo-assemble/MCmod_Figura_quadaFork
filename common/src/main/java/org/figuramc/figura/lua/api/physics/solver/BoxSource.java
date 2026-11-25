package org.figuramc.figura.lua.api.physics.solver;

/**
 * 블록 충돌 상자를 모아 주는 쪽 — 주어진 world 경계 상자와 겹치는 상자를 sink 에 낱낱이 넘긴다.
 * ⚠ 상자를 합치지 말고 블록마다 따로 넘긴다 — 솔버가 이웃 상자로 안쪽 면을 가린다.
 */
public interface BoxSource {
    void collect(double x0, double y0, double z0, double x1, double y1, double z1, BoxSink sink);
}
