package org.figuramc.figura.lua.api.physics.solver;

/** 충돌 상자 하나를 받는다 — world 좌표(블록) · 최소 모서리 → 최대 모서리. */
public interface BoxSink {
    void box(double x0, double y0, double z0, double x1, double y1, double z1);
}
