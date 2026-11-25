package org.figuramc.figura.lua.api.physics.solver;

/**
 * 솔버 설정 — 길이 블록 · 시간 초 · 각은 도(여기 적힌 것만).
 * RigidWorld 는 이 객체를 복사하지 않고 step 마다 읽는다 — 도중에 바꾸면 다음 step 부터 먹는다.
 */
public final class Settings {
    public double gravity = -20, friction = 0.5, linearDamping = 0, angularDamping = 0.1;
    public int substeps = 8;
    public double contactHz = 30, contactZeta = 10, contactPushMax = 3, margin = 0.02;
    public double jointHz = 60, jointZeta = 2;
    public double sleepMove = 0.06, sleepRotDeg = 4.0; public int sleepTicks = 20, minTicks = 20;
    public double maxSpeed = 600;  // 블록/초 — 넘으면 건강하지 않음 · 넉백 상한 240 보다 크게
}
