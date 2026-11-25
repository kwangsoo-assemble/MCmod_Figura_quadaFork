package org.figuramc.figura.lua.api.physics.solver;

/** 점 관절 하나 — 범위(swing-twist) · 근육 목표 · 서브스텝마다 다시 채우는 칸. */
final class Joint {
    final int a, b;
    final double lax, lay, laz, lbx, lby, lbz;   // 국소 앵커(블록)

    // 범위 — 국소 축 · 기준(정규 직교) · 라디안
    boolean limited;
    double axAx, axAy, axAz, rfAx, rfAy, rfAz, zAx, zAy, zAz;
    double axBx, axBy, axBz, rfBx, rfBy, rfBz;
    double sw1, sw2, twMin, twMax;

    // 근육 목표 — A 에서 본 B 의 상대 회전
    boolean hasTarget;
    double tqx, tqy, tqz, tqw;

    // ── 서브스텝 칸 ──
    double rax, ray, raz, rbx, rby, rbz, cx, cy, cz;
    final double[] k = new double[9];             // 점 제약 유효질량(역행렬)
    double pix, piy, piz;                          // 누적 임펄스(점)

    boolean swOn, loOn, hiOn;
    double swJx, swJy, swJz, swMass, swC, swAcc;  // swing — J 는 «여유가 커지는» 쪽
    double twJx, twJy, twJz, twMass, loC, loAcc, hiC, hiAcc;

    boolean muOn;
    final double[] mk = new double[9];
    double mcx, mcy, mcz, muMax, mix, miy, miz;

    Joint(int a, int b, double[] pa, double[] pb) {
        this.a = a;
        this.b = b;
        lax = pa[0]; lay = pa[1]; laz = pa[2];
        lbx = pb[0]; lby = pb[1]; lbz = pb[2];
    }

    void setLimit(JointLimit l) {
        double[] xa = unit(l.axisA, "axisA"), xb = unit(l.axisB, "axisB");
        double[] ya = perp(l.refA, xa, "refA"), yb = perp(l.refB, xb, "refB");
        axAx = xa[0]; axAy = xa[1]; axAz = xa[2];
        rfAx = ya[0]; rfAy = ya[1]; rfAz = ya[2];
        zAx = xa[1] * ya[2] - xa[2] * ya[1];
        zAy = xa[2] * ya[0] - xa[0] * ya[2];
        zAz = xa[0] * ya[1] - xa[1] * ya[0];
        axBx = xb[0]; axBy = xb[1]; axBz = xb[2];
        rfBx = yb[0]; rfBy = yb[1]; rfBz = yb[2];
        sw1 = Math.toRadians(clamp(l.swing1Deg, 1, 179));
        sw2 = Math.toRadians(clamp(l.swing2Deg, 1, 179));
        twMin = Math.toRadians(clamp(l.twistMinDeg, -179, 179));
        twMax = Math.toRadians(clamp(l.twistMaxDeg, -179, 179));
        if (!(twMin <= twMax)) throw new IllegalArgumentException("관절 범위: twistMinDeg 가 twistMaxDeg 보다 크다");
        limited = true;
    }

    void clearImpulses() {
        pix = piy = piz = 0;
        swAcc = loAcc = hiAcc = 0;
        mix = miy = miz = 0;
    }

    private static double clamp(double v, double lo, double hi) {
        if (!(v == v)) throw new IllegalArgumentException("관절 범위: 숫자가 아니다");
        return Math.max(lo, Math.min(hi, v));
    }

    private static double[] unit(double[] v, String name) {
        if (v == null || v.length < 3) throw new IllegalArgumentException("관절 범위: " + name + " 는 숫자 셋");
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(n > 1e-9) || Double.isInfinite(n)) throw new IllegalArgumentException("관절 범위: " + name + " 길이가 0 이다");
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    // 축에 수직인 성분만 — 사람이 넘긴 기준이 살짝 비스듬해도 쓰게
    private static double[] perp(double[] v, double[] axis, String name) {
        double[] u = unit(v, name);
        double d = u[0] * axis[0] + u[1] * axis[1] + u[2] * axis[2];
        double[] p = {u[0] - d * axis[0], u[1] - d * axis[1], u[2] - d * axis[2]};
        double n = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (!(n > 1e-6)) throw new IllegalArgumentException("관절 범위: " + name + " 가 축과 나란하다");
        return new double[]{p[0] / n, p[1] / n, p[2] / n};
    }
}
