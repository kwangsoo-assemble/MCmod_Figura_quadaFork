package org.figuramc.figura.lua.api.physics.solver;

/** 접촉점 하나 — 서브스텝마다 다시 찾는다. n 은 a 를 미는 쪽 · b < 0 이면 블록. */
final class Contact {
    int a, b, slot;
    double nx, ny, nz, t1x, t1y, t1z, t2x, t2y, t2z;
    double rax, ray, raz, rbx, rby, rbz;
    double s, sRelax;                 // 떨어짐(+) · 파묻힘(−)
    double nMass, t1Mass, t2Mass;
    double ln, lt1, lt2;              // 누적 임펄스
}
