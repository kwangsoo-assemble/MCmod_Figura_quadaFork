package org.figuramc.figura.lua.api.physics.solver;

/** 회전 상자 강체 하나 — 위치는 월드 국소 원점 기준. */
final class Body {
    static final int SAMPLES = 20;
    // 표본점 부호 — 꼭짓점 8 · 모서리 가운데 12
    static final double[] SIGN = {
            -1, -1, -1, 1, -1, -1, -1, 1, -1, 1, 1, -1, -1, -1, 1, 1, -1, 1, -1, 1, 1, 1, 1, 1,
            0, -1, -1, 0, 1, -1, 0, -1, 1, 0, 1, 1,
            -1, 0, -1, 1, 0, -1, -1, 0, 1, 1, 0, 1,
            -1, -1, 0, 1, -1, 0, -1, 1, 0, 1, 1, 0,
    };

    final double hx, hy, hz, mass, invMass, iix, iiy, iiz, rmax;
    double px, py, pz, qx, qy, qz, qw;
    double vx, vy, vz, wx, wy, wz;
    final double[] r = new double[9];    // 회전 행렬(행 우선) — 국소 → 월드
    final double[] ii = new double[9];   // 월드 역관성
    final double[] sp = new double[3 * SAMPLES];
    double spec, x0, y0, z0, x1, y1, z1; // 이 서브스텝 탐지 여유 · 경계 상자(여유 포함)

    Body(double hx, double hy, double hz, double mass) {
        this.hx = hx;
        this.hy = hy;
        this.hz = hz;
        this.mass = mass;
        invMass = 1 / mass;
        iix = 3 / (mass * (hy * hy + hz * hz));
        iiy = 3 / (mass * (hx * hx + hz * hz));
        iiz = 3 / (mass * (hx * hx + hy * hy));
        rmax = Math.sqrt(hx * hx + hy * hy + hz * hz);
    }

    /** 회전 행렬 · 월드 역관성을 지금 사원수로. */
    void update() {
        double x = qx, y = qy, z = qz, w = qw;
        double xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        r[0] = 1 - 2 * (yy + zz); r[1] = 2 * (xy - wz); r[2] = 2 * (xz + wy);
        r[3] = 2 * (xy + wz); r[4] = 1 - 2 * (xx + zz); r[5] = 2 * (yz - wx);
        r[6] = 2 * (xz - wy); r[7] = 2 * (yz + wx); r[8] = 1 - 2 * (xx + yy);
        for (int a = 0; a < 3; a++) {
            for (int b = a; b < 3; b++) {
                double v = r[3 * a] * r[3 * b] * iix + r[3 * a + 1] * r[3 * b + 1] * iiy + r[3 * a + 2] * r[3 * b + 2] * iiz;
                ii[3 * a + b] = v;
                ii[3 * b + a] = v;
            }
        }
    }

    /** 표본점 월드 자리 · 여유를 더한 경계 상자. */
    void samples(double spec) {
        this.spec = spec;
        for (int m = 0; m < SAMPLES; m++) {
            double lx = SIGN[3 * m] * hx, ly = SIGN[3 * m + 1] * hy, lz = SIGN[3 * m + 2] * hz;
            sp[3 * m] = px + r[0] * lx + r[1] * ly + r[2] * lz;
            sp[3 * m + 1] = py + r[3] * lx + r[4] * ly + r[5] * lz;
            sp[3 * m + 2] = pz + r[6] * lx + r[7] * ly + r[8] * lz;
        }
        double ex = Math.abs(r[0]) * hx + Math.abs(r[1]) * hy + Math.abs(r[2]) * hz + spec;
        double ey = Math.abs(r[3]) * hx + Math.abs(r[4]) * hy + Math.abs(r[5]) * hz + spec;
        double ez = Math.abs(r[6]) * hx + Math.abs(r[7]) * hy + Math.abs(r[8]) * hz + spec;
        x0 = px - ex; x1 = px + ex;
        y0 = py - ey; y1 = py + ey;
        z0 = pz - ez; z1 = pz + ez;
    }

    /** 각 임펄스(월드)를 각속도에 — w += s · I⁻¹ · (x, y, z). */
    void addAngular(double s, double x, double y, double z) {
        wx += s * (ii[0] * x + ii[1] * y + ii[2] * z);
        wy += s * (ii[3] * x + ii[4] * y + ii[5] * z);
        wz += s * (ii[6] * x + ii[7] * y + ii[8] * z);
    }

    /** 점 r(무게중심 기준)에 임펄스 s·P. */
    void impulse(double s, double rx, double ry, double rz, double x, double y, double z) {
        vx += s * invMass * x;
        vy += s * invMass * y;
        vz += s * invMass * z;
        addAngular(s, ry * z - rz * y, rz * x - rx * z, rx * y - ry * x);
    }
}
