package org.figuramc.figura.lua.api.physics.solver;

import java.util.Arrays;

/**
 * 래그돌용 강체 월드 — 순차 임펄스 + 서브스텝 + soft constraint(Box2D v3 «soft step» 꼴).
 * <p>
 * 마인크래프트 · JOML · LuaJ 를 모른다(javac 하나로 오프라인 시험). 단위: 블록 · 초 · 라디안 · 사원수 (x, y, z, w) · 국소 → 월드 = R(q)·v.
 * 안에서는 생성자의 국소 원점을 뺀 double 로 계산하고 들고 나는 값은 모두 world 좌표다. 몸 0 이 관절 나무의 뿌리다.
 * step 은 예외를 밖으로 던지지 않는다 — NaN · 무한 · maxSpeed 초과면 step 앞으로 되돌리고 잠재운 뒤 healthy() = false.
 * 유한하지 않은 값을 넘긴 setter 는 아무것도 안 한다. 만들 때(addBody · addJoint)의 잘못은 IllegalArgumentException.
 */
public final class RigidWorld {
    private static final int FACES = 6;
    private static final int SLOT_PER_BODY = Body.SAMPLES * FACES;
    private static final int PROBE_TICKS = 10;        // 자는 동안 받침을 볼 간격(틱)
    private static final double LIMIT_WINDOW = 0.15;  // 라디안 — 범위까지 이만큼 남으면 미리 건다(추측 제약)
    private static final int SOLVE_ITERS = 2, RELAX_ITERS = 2;  // 서브스텝마다 풀기 · 이완 반복 — 1 · 1 이면 끼임 · 충돌에서 침투 · 범위 넘김이 커진다(오프라인 시험 실측)
    private static final double EPS = 1e-4;           // 면 너머 · 꼭짓점 이웃을 볼 거리(블록)
    private static final double FACE_WINDOW = 0.25;   // 가장 얕은 면보다 이만큼 더 깊은 면으로는 밀지 않는다(블록)

    private final Settings s;
    private final double ox, oy, oz;

    private Body[] bodies = new Body[8];
    private int nb;
    private Joint[] joints = new Joint[8];
    private int nj;
    private boolean[] noCol = new boolean[64];
    private int noColCap = 8;

    private double[] boxes = new double[6 * 64];      // 국소 좌표 x0 y0 z0 x1 y1 z1
    private int boxCount;
    private double qx0, qy0, qz0, qx1, qy1, qz1;      // 이번에 모은 범위(국소) — 밖은 모른다
    private int[] edgeMask = new int[64];             // 상자마다 볼록 모서리 12비트
    private final double[] e0 = new double[3], e1 = new double[3];
    private final BoxSink sink = this::addBox;

    private Contact[] contacts = new Contact[64];
    private int nc;

    // 웜스타트 칸 — [몸 · 표본점 · 면](블록) 다음에 [몸 · 상대 몸 · 표본점 · 면]
    private double[] warm = new double[0];
    private int[] warmStamp = new int[0], slotIdx = new int[0], slotStamp = new int[0];
    private int sub = 10;

    private double cBias, cMass, cImp, sBias, sMass, sImp, jBias, jMass, jImp, mBias, mMass, mImp, pushMax;

    private double muscleStrength, muscleHz, muscleZeta, muscleMaxTorque;

    private int grabBody = -1;
    private double gLx, gLy, gLz, gAx, gAy, gAz, gK, gC, gMax;

    private boolean asleep, healthy = true, anchorValid;
    private int ticksAwake, calm, probe;
    private double[] anchor = new double[0], snap = new double[0];
    private final double[] f6 = new double[FACES], m9 = new double[9], t9 = new double[9];

    public RigidWorld(Settings s, double originX, double originY, double originZ) {
        if (s == null) throw new IllegalArgumentException("settings 가 없다");
        if (!finite(originX) || !finite(originY) || !finite(originZ)) throw new IllegalArgumentException("국소 원점이 유한하지 않다");
        this.s = s;
        ox = originX;
        oy = originY;
        oz = originZ;
    }

    // ════════════════════════════════════════ 만들기

    public int addBody(double hx, double hy, double hz, double mass, double px, double py, double pz, double qx, double qy, double qz, double qw) {
        if (!(hx > 0 && hy > 0 && hz > 0 && mass > 0) || !finite(hx + hy + hz + mass))
            throw new IllegalArgumentException("몸: 반치수 · 질량은 0 보다 큰 유한한 수");
        double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (!(n > 1e-9) || !finite(n) || !finite(px) || !finite(py) || !finite(pz))
            throw new IllegalArgumentException("몸: 자리 · 회전이 유한하지 않거나 회전 길이가 0");
        Body b = new Body(hx, hy, hz, mass);
        b.px = px - ox;
        b.py = py - oy;
        b.pz = pz - oz;
        b.qx = qx / n;
        b.qy = qy / n;
        b.qz = qz / n;
        b.qw = qw / n;
        b.update();
        if (nb == bodies.length) bodies = Arrays.copyOf(bodies, nb * 2);
        bodies[nb] = b;
        growNoCol(nb + 1);
        wake();
        return nb++;
    }

    public int addJoint(int a, int b, double[] anchorA, double[] anchorB, JointLimit limit) {
        body(a);
        body(b);
        if (a == b) throw new IllegalArgumentException("관절: 같은 몸끼리는 못 잇는다");
        if (!vec3(anchorA) || !vec3(anchorB)) throw new IllegalArgumentException("관절: 앵커는 유한한 숫자 셋");
        Joint j = new Joint(a, b, anchorA, anchorB);
        if (limit != null) j.setLimit(limit);
        if (nj == joints.length) joints = Arrays.copyOf(joints, nj * 2);
        joints[nj] = j;
        setNoCollide(a, b);
        return nj++;
    }

    public void setNoCollide(int a, int b) {
        body(a);
        body(b);
        noCol[a * noColCap + b] = true;
        noCol[b * noColCap + a] = true;
    }

    // ════════════════════════════════════════ 넣기 · 읽기

    public void setVelocity(int i, double vx, double vy, double vz) {
        Body b = body(i);
        if (!finite(vx) || !finite(vy) || !finite(vz)) return;
        b.vx = vx;
        b.vy = vy;
        b.vz = vz;
        wake();
    }

    public void setAngularVelocity(int i, double wx, double wy, double wz) {
        Body b = body(i);
        if (!finite(wx) || !finite(wy) || !finite(wz)) return;
        b.wx = wx;
        b.wy = wy;
        b.wz = wz;
        wake();
    }

    public void getVelocity(int i, double[] out3) {
        Body b = body(i);
        out3[0] = b.vx;
        out3[1] = b.vy;
        out3[2] = b.vz;
    }

    public void getAngularVelocity(int i, double[] out3) {
        Body b = body(i);
        out3[0] = b.wx;
        out3[1] = b.wy;
        out3[2] = b.wz;
    }

    public void getBodyPos(int i, double[] out3) {
        Body b = body(i);
        out3[0] = b.px + ox;
        out3[1] = b.py + oy;
        out3[2] = b.pz + oz;
    }

    public void getBodyRot(int i, double[] out4) {
        Body b = body(i);
        out4[0] = b.qx;
        out4[1] = b.qy;
        out4[2] = b.qz;
        out4[3] = b.qw;
    }

    /** [몸 0 world 위치 xyz, 이어서 몸마다 사원수 xyzw] = 3 + 4n */
    public void getPose(double[] out) {
        if (out == null || out.length < 3 + 4 * nb) throw new IllegalArgumentException("getPose: 버퍼는 3 + 4 × 몸 수");
        if (nb == 0) return;
        Body r = bodies[0];
        out[0] = r.px + ox;
        out[1] = r.py + oy;
        out[2] = r.pz + oz;
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            out[3 + 4 * i] = b.qx;
            out[4 + 4 * i] = b.qy;
            out[5 + 4 * i] = b.qz;
            out[6 + 4 * i] = b.qw;
        }
    }

    /** getPose 꼴 — 몸 0 에서 관절 나무를 따라 나머지 위치를 다시 세운다 · 속도 0 · 깨움(나무에 안 닿는 몸은 제자리) */
    public void setPose(double[] in) {
        if (in == null || in.length < 3 + 4 * nb) throw new IllegalArgumentException("setPose: 버퍼는 3 + 4 × 몸 수");
        if (nb == 0) return;
        for (int k = 0; k < 3 + 4 * nb; k++) if (!finite(in[k])) return;
        for (int i = 0; i < nb; i++) {
            int o = 3 + 4 * i;
            if (!(in[o] * in[o] + in[o + 1] * in[o + 1] + in[o + 2] * in[o + 2] + in[o + 3] * in[o + 3] > 1e-18)) return;
        }
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            int o = 3 + 4 * i;
            double n = Math.sqrt(in[o] * in[o] + in[o + 1] * in[o + 1] + in[o + 2] * in[o + 2] + in[o + 3] * in[o + 3]);
            b.qx = in[o] / n;
            b.qy = in[o + 1] / n;
            b.qz = in[o + 2] / n;
            b.qw = in[o + 3] / n;
            b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0;
            b.update();
        }
        Body root = bodies[0];
        root.px = in[0] - ox;
        root.py = in[1] - oy;
        root.pz = in[2] - oz;
        boolean[] placed = new boolean[nb];
        placed[0] = true;
        for (int pass = 0; pass < nj; pass++) {
            boolean any = false;
            for (int k = 0; k < nj; k++) {
                Joint j = joints[k];
                if (placed[j.a] && !placed[j.b]) {
                    place(bodies[j.a], j.lax, j.lay, j.laz, bodies[j.b], j.lbx, j.lby, j.lbz);
                    placed[j.b] = any = true;
                } else if (placed[j.b] && !placed[j.a]) {
                    place(bodies[j.b], j.lbx, j.lby, j.lbz, bodies[j.a], j.lax, j.lay, j.laz);
                    placed[j.a] = any = true;
                }
            }
            if (!any) break;
        }
        for (int k = 0; k < nj; k++) joints[k].clearImpulses();
        sub += 2;
        wake();
    }

    // 앵커가 겹치게 child 를 놓는다 — p_c = p_p + R_p·l_p − R_c·l_c
    private static void place(Body p, double lpx, double lpy, double lpz, Body c, double lcx, double lcy, double lcz) {
        double[] r = p.r, q = c.r;
        c.px = p.px + r[0] * lpx + r[1] * lpy + r[2] * lpz - (q[0] * lcx + q[1] * lcy + q[2] * lcz);
        c.py = p.py + r[3] * lpx + r[4] * lpy + r[5] * lpz - (q[3] * lcx + q[4] * lcy + q[5] * lcz);
        c.pz = p.pz + r[6] * lpx + r[7] * lpy + r[8] * lpz - (q[6] * lcx + q[7] * lcy + q[8] * lcz);
    }

    /** 지금 관절마다 A 에서 본 B 의 회전을 근육 목표로. */
    public void captureMuscleTargets() {
        for (int k = 0; k < nj; k++) {
            Joint j = joints[k];
            Body A = bodies[j.a], B = bodies[j.b];
            j.tqx = A.qw * B.qx - A.qx * B.qw - A.qy * B.qz + A.qz * B.qy;
            j.tqy = A.qw * B.qy + A.qx * B.qz - A.qy * B.qw - A.qz * B.qx;
            j.tqz = A.qw * B.qz - A.qx * B.qy + A.qy * B.qx - A.qz * B.qw;
            j.tqw = A.qw * B.qw + A.qx * B.qx + A.qy * B.qy + A.qz * B.qz;
            j.hasTarget = true;
        }
    }

    /** strength 0 ~ 1(0 이면 끔) · 관절마다 토크 상한 maxTorque × strength. */
    public void setMuscle(double strength, double hz, double zeta, double maxTorque) {
        if (!finite(strength) || !finite(hz) || !finite(zeta) || !finite(maxTorque)) return;
        muscleStrength = Math.max(0, Math.min(1, strength));
        muscleHz = hz;
        muscleZeta = Math.max(0, zeta);
        muscleMaxTorque = maxTorque;
    }

    /** 몸 i 의 국소 점을 world 점 a 로 — 힘 = k·오차 − c·점 속도 · |힘| ≤ maxForce · 서브스텝마다. 깨운다. */
    public void setGrab(int i, double[] localPoint, double ax, double ay, double az, double k, double c, double maxForce) {
        body(i);
        if (localPoint == null || localPoint.length < 3) throw new IllegalArgumentException("setGrab: 국소 점은 숫자 셋");
        if (!vec3(localPoint) || !finite(ax) || !finite(ay) || !finite(az) || !finite(k) || !finite(c) || !finite(maxForce)) return;
        grabBody = i;
        gLx = localPoint[0];
        gLy = localPoint[1];
        gLz = localPoint[2];
        gAx = ax - ox;
        gAy = ay - oy;
        gAz = az - oz;
        gK = k;
        gC = c;
        gMax = maxForce;
        wake();
    }

    public void clearGrab() {
        if (grabBody < 0) return;
        grabBody = -1;
        wake();
    }

    public boolean isAsleep() {
        return asleep;
    }

    public void wake() {
        asleep = false;
        ticksAwake = 0;
        calm = 0;
        probe = 0;
        anchorValid = false;
    }

    public boolean healthy() {
        return healthy;
    }

    public int bodyCount() {
        return nb;
    }

    // ════════════════════════════════════════ 한 틱

    public void step(double dt, BoxSource src) {
        if (nb == 0 || !(dt > 0) || dt > 1) return;
        healthy = true;
        if (asleep) {
            if (++probe < PROBE_TICKS) return;
            probe = 0;
            boolean held;
            try {
                held = supported(src);
            } catch (RuntimeException e) {
                return;
            }
            if (held) return;
            wake();
        }
        saveSnapshot();
        try {
            simulate(dt, src);
        } catch (RuntimeException e) {
            rollback();
            return;
        }
        if (!sane()) {
            rollback();
            return;
        }
        ticksAwake++;
        sleepWindow();
    }

    private void simulate(double dt, BoxSource src) {
        int n = Math.max(1, s.substeps);
        double h = dt / n, invH = 1 / h;
        soft(s.contactHz, s.contactZeta, h, 0);
        soft(2 * s.contactHz, s.contactZeta, h, 3);  // 블록 접촉은 2배 — Box2D v3 staticSoftness
        soft(s.jointHz, s.jointZeta, h, 1);
        boolean muscle = muscleStrength > 0 && muscleHz > 0 && muscleMaxTorque > 0;
        if (muscle) soft(muscleHz, muscleZeta, h, 2);
        pushMax = s.contactPushMax > 0 ? s.contactPushMax : 0;
        ensureSlots();
        for (int i = 0; i < nb; i++) bodies[i].update();
        collect(src, dt, Math.max(0, s.margin));
        markEdges();
        for (int k = 0; k < n; k++) substep(h, invH, muscle);
    }

    private void substep(double h, double invH, boolean muscle) {
        sub++;
        for (int i = 0; i < nb; i++) bodies[i].update();
        for (int k = 0; k < nj; k++) prepareJoint(joints[k], muscle, h);
        detect(h);
        integrateVelocities(h);
        warmStart();
        for (int it = 0; it < SOLVE_ITERS; it++) solve(invH, true);
        integratePositions(h);
        prepareRelax(h);
        for (int it = 0; it < RELAX_ITERS; it++) solve(invH, false);
        storeWarm();
    }

    // Box2D v3 b2MakeSoft — 강성을 Hz · 감쇠비로, 유효질량으로 정규화
    private void soft(double hz, double zeta, double h, int which) {
        double bias = 0, mass = 1, imp = 0;
        if (hz > 0) {
            double omega = 2 * Math.PI * hz, a1 = 2 * Math.max(0, zeta) + h * omega, a2 = h * omega * a1, a3 = 1 / (1 + a2);
            bias = omega / a1;
            mass = a2 * a3;
            imp = a3;
        }
        if (which == 0) {
            cBias = bias; cMass = mass; cImp = imp;
        } else if (which == 1) {
            jBias = bias; jMass = mass; jImp = imp;
        } else if (which == 3) {
            sBias = bias; sMass = mass; sImp = imp;
        } else {
            mBias = bias; mMass = mass; mImp = imp;
        }
    }

    // ════════════════════════════════════════ 블록 상자

    // 몸마다 한 틱 갈 쪽으로만 늘린 경계 상자를 합쳐 한 번 모은다(속력은 maxSpeed 로 자른다)
    // ⚠ 사방으로 늘리면 발사(12 블록/틱)에 질의가 25블록 정육면체가 된다
    private void collect(BoxSource src, double dt, double pad) {
        boxCount = 0;
        if (src == null) return;
        double gy = Math.abs(s.gravity) * dt * dt, vmax = s.maxSpeed > 0 ? s.maxSpeed : 0;
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            double v = Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz), k = v > vmax ? vmax / v * dt : dt;
            double dx = b.vx * k, dy = b.vy * k, dz = b.vz * k, e = b.rmax + pad;
            x0 = Math.min(x0, b.px + Math.min(0, dx) - e); x1 = Math.max(x1, b.px + Math.max(0, dx) + e);
            y0 = Math.min(y0, b.py + Math.min(0, dy) - e - gy); y1 = Math.max(y1, b.py + Math.max(0, dy) + e + gy);
            z0 = Math.min(z0, b.pz + Math.min(0, dz) - e); z1 = Math.max(z1, b.pz + Math.max(0, dz) + e);
        }
        qx0 = x0; qy0 = y0; qz0 = z0; qx1 = x1; qy1 = y1; qz1 = z1;
        src.collect(x0 + ox, y0 + oy, z0 + oz, x1 + ox, y1 + oy, z1 + oz, sink);
    }

    private void addBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!(x1 > x0 && y1 > y0 && z1 > z0) || !finite(x0) || !finite(y0) || !finite(z0) || !finite(x1) || !finite(y1) || !finite(z1)) return;
        if (6 * boxCount + 6 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
        int o = 6 * boxCount++;
        boxes[o] = x0 - ox;
        boxes[o + 1] = y0 - oy;
        boxes[o + 2] = z0 - oz;
        boxes[o + 3] = x1 - ox;
        boxes[o + 4] = y1 - oy;
        boxes[o + 5] = z1 - oz;
    }

    // 상자 k 말고 다른 상자 안(경계 제외)인가
    private boolean insideOther(int k, double x, double y, double z) {
        for (int j = 0; j < boxCount; j++) {
            if (j == k) continue;
            int o = 6 * j;
            if (x > boxes[o] && x < boxes[o + 3] && y > boxes[o + 1] && y < boxes[o + 4] && z > boxes[o + 2] && z < boxes[o + 5]) return true;
        }
        return false;
    }

    // 안쪽 면 — 점을 그 면에 내린 자리의 바로 밖이 다른 상자 안이면 그 면은 쓰지 않는다
    private boolean faceHidden(int k, int face, double x, double y, double z) {
        int o = 6 * k;
        double qx = inset(x, boxes[o], boxes[o + 3]), qy = inset(y, boxes[o + 1], boxes[o + 4]), qz = inset(z, boxes[o + 2], boxes[o + 5]);
        boolean plus = (face & 1) == 0;
        switch (face >> 1) {
            case 0: qx = plus ? boxes[o + 3] + EPS : boxes[o] - EPS; break;
            case 1: qy = plus ? boxes[o + 4] + EPS : boxes[o + 1] - EPS; break;
            default: qz = plus ? boxes[o + 5] + EPS : boxes[o + 2] - EPS; break;
        }
        // ⚠ 모은 범위 밖은 이웃이 있는지 모른다 — 가려진 것으로 친다(빼면 먼 면이 «드러남» 으로 보여 점을 더 깊이 민다)
        if (qx < qx0 || qx > qx1 || qy < qy0 || qy > qy1 || qz < qz0 || qz > qz1) return true;
        return insideOther(k, qx, qy, qz);
    }

    private static double inset(double v, double lo, double hi) {
        if (hi - lo <= 2 * EPS) return (lo + hi) * 0.5;
        return Math.max(lo + EPS, Math.min(hi - EPS, v));
    }

    // 꼭짓점의 드러난 축(비트 x 1 · y 2 · z 4) — 둘 이상이 이웃 상자로 이어졌으면(평평한 면 위 · 이음매) −1
    private int cornerOpen(int k, int c, double cx, double cy, double cz) {
        double sx = (c & 1) != 0 ? EPS : -EPS, sy = (c & 2) != 0 ? EPS : -EPS, sz = (c & 4) != 0 ? EPS : -EPS;
        int open = 0, n = 0;
        if (insideOther(k, cx + sx, cy - sy, cz - sz)) n++;
        else open |= 1;
        if (insideOther(k, cx - sx, cy + sy, cz - sz)) n++;
        else open |= 2;
        if (n == 2) return -1;
        if (insideOther(k, cx - sx, cy - sy, cz + sz)) n++;
        else open |= 4;
        return n >= 2 ? -1 : open;
    }

    // 블록 꼭짓점 · 모서리가 몸 안 — 블록 바깥 쪽(out)으로 미는 몸 면 중 가장 얕은 면 · 그런 면이 없으면 가장 얕은 면
    // ⚠ 그냥 가장 얕은 면이면 반 넘게 박힌 팔다리를 반대쪽 면이 블록 안으로 민다
    private static int pushFace(double[] f, double[] r, double outX, double outY, double outZ) {
        int tried = 0;
        for (int t = 0; t < FACES; t++) {
            int best = -1;
            double bs = Double.NEGATIVE_INFINITY;
            for (int q = 0; q < FACES; q++) {
                if ((tried & (1 << q)) == 0 && f[q] > bs) {
                    bs = f[q];
                    best = q;
                }
            }
            int ax = best >> 1;
            double sg = (best & 1) == 0 ? -1 : 1;
            if (sg * (r[ax] * outX + r[3 + ax] * outY + r[6 + ax] * outZ) > 0) return best;
            tried |= 1 << best;
        }
        return argmax(f);
    }

    // 볼록 모서리 — 모서리 가운데에서 두 면 너머가 다 비었다(이음매 · 안쪽 모서리는 아니다) · 틱마다 한 번
    private void markEdges() {
        if (edgeMask.length < boxCount) edgeMask = new int[Math.max(boxCount, 2 * edgeMask.length)];
        double[] p = e0;
        for (int k = 0; k < boxCount; k++) {
            int o = 6 * k, mask = 0;
            for (int e = 0; e < 12; e++) {
                int d = e >> 2, a1 = (d + 1) % 3, a2 = (d + 2) % 3;
                double s1 = (e & 1) != 0 ? EPS : -EPS, s2 = (e & 2) != 0 ? EPS : -EPS;
                p[d] = (boxes[o + d] + boxes[o + 3 + d]) * 0.5;
                p[a1] = boxes[o + ((e & 1) != 0 ? 3 : 0) + a1] + s1;
                p[a2] = boxes[o + ((e & 2) != 0 ? 3 : 0) + a2] - s2;
                if (insideOther(k, p[0], p[1], p[2])) continue;
                p[a1] -= 2 * s1;
                p[a2] += 2 * s2;
                if (insideOther(k, p[0], p[1], p[2])) continue;
                mask |= 1 << e;
            }
            edgeMask[k] = mask;
        }
    }

    // 모서리 선분을 몸 상자(여유 b.spec 포함)로 자른 가운데의 매개변수 t(0 ~ 1) — 안 닿으면 −1 · e0 · e1 에 국소 끝점이 남는다
    private double clipEdge(Body b, int k, int e) {
        double[] r = b.r, u0 = e0, u1 = e1;
        int o = 6 * k, d = e >> 2, a1 = (d + 1) % 3, a2 = (d + 2) % 3;
        double c1 = boxes[o + ((e & 1) != 0 ? 3 : 0) + a1], c2 = boxes[o + ((e & 2) != 0 ? 3 : 0) + a2];
        u0[a1] = u1[a1] = c1;
        u0[a2] = u1[a2] = c2;
        u0[d] = boxes[o + d];
        u1[d] = boxes[o + 3 + d];
        toLocal(r, u0[0] - b.px, u0[1] - b.py, u0[2] - b.pz, u0);
        toLocal(r, u1[0] - b.px, u1[1] - b.py, u1[2] - b.pz, u1);
        double t0 = 0, t1 = 1;
        for (int a = 0; a < 3; a++) {
            double h = (a == 0 ? b.hx : a == 1 ? b.hy : b.hz) + b.spec, du = u1[a] - u0[a];
            if (Math.abs(du) < 1e-12) {
                if (Math.abs(u0[a]) > h) return -1;
                continue;
            }
            double ta = (-h - u0[a]) / du, tb = (h - u0[a]) / du;
            if (ta > tb) {
                double x = ta; ta = tb; tb = x;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return -1;
        }
        return (t0 + t1) * 0.5;
    }

    // 블록 볼록 모서리가 몸 면을 찌른다 — 모서리 선분을 몸 상자(여유 포함)로 자른 가운데 · 법선은 블록 바깥으로 미는 몸 면
    private void edgeContact(int i, int k, int e) {
        Body b = bodies[i];
        double t = clipEdge(b, k, e);
        if (t < 0) return;
        double[] r = b.r, f = f6, u0 = e0, u1 = e1;
        int d = e >> 2, a1 = (d + 1) % 3, a2 = (d + 2) % 3;
        double ux = u0[0] + t * (u1[0] - u0[0]), uy = u0[1] + t * (u1[1] - u0[1]), uz = u0[2] + t * (u1[2] - u0[2]);
        double wx = b.px + r[0] * ux + r[1] * uy + r[2] * uz, wy = b.py + r[3] * ux + r[4] * uy + r[5] * uz, wz = b.pz + r[6] * ux + r[7] * uy + r[8] * uz;
        f[0] = ux - b.hx; f[1] = -b.hx - ux; f[2] = uy - b.hy; f[3] = -b.hy - uy; f[4] = uz - b.hz; f[5] = -b.hz - uz;
        double[] out = u0;  // 모서리 바깥 방향 — 맞닿은 두 면 법선의 합
        out[d] = 0;
        out[a1] = (e & 1) != 0 ? 1 : -1;
        out[a2] = (e & 2) != 0 ? 1 : -1;
        int face = pushFace(f, r, out[0], out[1], out[2]), ax = face >> 1;
        double sg = (face & 1) == 0 ? -1 : 1;
        newContact(i, -1, wx, wy, wz, sg * r[ax], sg * r[3 + ax], sg * r[6 + ax], f[face], -1);
    }

    private static void toLocal(double[] r, double dx, double dy, double dz, double[] out) {
        out[0] = r[0] * dx + r[3] * dy + r[6] * dz;
        out[1] = r[1] * dx + r[4] * dy + r[7] * dz;
        out[2] = r[2] * dx + r[5] * dy + r[8] * dz;
    }

    // ════════════════════════════════════════ 접촉 찾기

    private void detect(double h) {
        nc = 0;
        double m = Math.max(0, s.margin), g = Math.abs(s.gravity);
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            double v = Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz) + g * h
                    + Math.sqrt(b.wx * b.wx + b.wy * b.wy + b.wz * b.wz) * b.rmax;
            b.samples(m + v * h);  // 이 서브스텝에 갈 수 있는 거리까지 미리 본다
        }
        for (int i = 0; i < nb; i++) staticContacts(i);
        for (int i = 0; i < nb; i++) {
            Body a = bodies[i];
            for (int j = i + 1; j < nb; j++) {
                if (noCol[i * noColCap + j]) continue;
                Body b = bodies[j];
                if (a.x1 < b.x0 || a.x0 > b.x1 || a.y1 < b.y0 || a.y0 > b.y1 || a.z1 < b.z0 || a.z0 > b.z1) continue;
                pairContacts(i, j, m);
                pairContacts(j, i, m);
            }
        }
    }

    private void staticContacts(int i) {
        Body b = bodies[i];
        double e = b.spec;
        double[] r = b.r, f = f6;
        for (int k = 0; k < boxCount; k++) {
            int o = 6 * k;
            double lx = boxes[o], ly = boxes[o + 1], lz = boxes[o + 2], hx = boxes[o + 3], hy = boxes[o + 4], hz = boxes[o + 5];
            if (hx < b.x0 || lx > b.x1 || hy < b.y0 || ly > b.y1 || hz < b.z0 || lz > b.z1) continue;
            // 몸 표본점이 상자 안 · 여유 안
            for (int p = 0; p < Body.SAMPLES; p++) {
                double x = b.sp[3 * p], y = b.sp[3 * p + 1], z = b.sp[3 * p + 2];
                if (x < lx - e || x > hx + e || y < ly - e || y > hy + e || z < lz - e || z > hz + e) continue;
                f[0] = x - hx; f[1] = lx - x; f[2] = y - hy; f[3] = ly - y; f[4] = z - hz; f[5] = lz - z;
                int face = pickFace(k, x, y, z, f);
                addStatic(i, p, face, x, y, z, f[face]);
            }
            // 상자 꼭짓점이 몸 안 · 여유 안 — 법선은 몸의 가장 얕은 면
            for (int c = 0; c < 8; c++) {
                double cx = (c & 1) != 0 ? hx : lx, cy = (c & 2) != 0 ? hy : ly, cz = (c & 4) != 0 ? hz : lz;
                double dx = cx - b.px, dy = cy - b.py, dz = cz - b.pz;
                double ux = r[0] * dx + r[3] * dy + r[6] * dz, uy = r[1] * dx + r[4] * dy + r[7] * dz, uz = r[2] * dx + r[5] * dy + r[8] * dz;
                if (Math.abs(ux) > b.hx + e || Math.abs(uy) > b.hy + e || Math.abs(uz) > b.hz + e) continue;
                int open = cornerOpen(k, c, cx, cy, cz);
                if (open < 0) continue;
                f[0] = ux - b.hx; f[1] = -b.hx - ux; f[2] = uy - b.hy; f[3] = -b.hy - uy; f[4] = uz - b.hz; f[5] = -b.hz - uz;
                int face = pushFace(f, r, (open & 1) == 0 ? 0 : (c & 1) != 0 ? 1 : -1, (open & 2) == 0 ? 0 : (c & 2) != 0 ? 1 : -1, (open & 4) == 0 ? 0 : (c & 4) != 0 ? 1 : -1);
                int ax = face >> 1;
                double sg = (face & 1) == 0 ? -1 : 1;  // 몸 면 바깥 법선의 반대 = 몸을 미는 쪽
                newContact(i, -1, cx, cy, cz, sg * r[ax], sg * r[3 + ax], sg * r[6 + ax], f[face], -1);
            }
            int mask = edgeMask[k];
            for (int g = 0; g < 12; g++) if ((mask & (1 << g)) != 0) edgeContact(i, k, g);
        }
    }

    // 가장 얕은 면부터 — 가려진 면은 건너뛴다 · 가까운 면이 다 가려졌으면(오목 모서리) 가장 얕은 면
    private int pickFace(int k, double x, double y, double z, double[] f) {
        int top = argmax(f), tried = 0;
        for (int t = 0; t < FACES; t++) {
            int best = -1;
            double bs = Double.NEGATIVE_INFINITY;
            for (int q = 0; q < FACES; q++) {
                if ((tried & (1 << q)) == 0 && f[q] > bs) {
                    bs = f[q];
                    best = q;
                }
            }
            if (bs < f[top] - FACE_WINDOW) break;
            if (!faceHidden(k, best, x, y, z)) return best;
            tried |= 1 << best;
        }
        return top;
    }

    private static int argmax(double[] f) {
        int best = 0;
        for (int q = 1; q < FACES; q++) if (f[q] > f[best]) best = q;
        return best;
    }

    // 표본점 하나 · 면 하나에 접촉 하나 — 같은 칸이 또 나오면(이웃 상자) 더 깊은 쪽만
    private void addStatic(int i, int p, int face, double x, double y, double z, double sep) {
        int slot = (i * Body.SAMPLES + p) * FACES + face;
        if (slotStamp[slot] == sub) {
            Contact c = contacts[slotIdx[slot]];
            if (sep < c.s) c.s = sep;
            return;
        }
        int ax = face >> 1;
        double sg = (face & 1) == 0 ? 1 : -1;
        newContact(i, -1, x, y, z, ax == 0 ? sg : 0, ax == 1 ? sg : 0, ax == 2 ? sg : 0, sep, slot);
        slotStamp[slot] = sub;
        slotIdx[slot] = nc - 1;
    }

    // 몸 i 의 표본점이 몸 j 안 — j 의 가장 얕은 면 법선으로 i 를 민다
    private void pairContacts(int i, int j, double m) {
        Body a = bodies[i], b = bodies[j];
        double e = a.spec + b.spec - m;
        double[] r = b.r, f = f6;
        for (int p = 0; p < Body.SAMPLES; p++) {
            double x = a.sp[3 * p], y = a.sp[3 * p + 1], z = a.sp[3 * p + 2];
            double dx = x - b.px, dy = y - b.py, dz = z - b.pz;
            double ux = r[0] * dx + r[3] * dy + r[6] * dz, uy = r[1] * dx + r[4] * dy + r[7] * dz, uz = r[2] * dx + r[5] * dy + r[8] * dz;
            if (Math.abs(ux) > b.hx + e || Math.abs(uy) > b.hy + e || Math.abs(uz) > b.hz + e) continue;
            f[0] = ux - b.hx; f[1] = -b.hx - ux; f[2] = uy - b.hy; f[3] = -b.hy - uy; f[4] = uz - b.hz; f[5] = -b.hz - uz;
            int face = argmax(f);
            int ax = face >> 1;
            double sg = (face & 1) == 0 ? 1 : -1;
            int slot = nb * SLOT_PER_BODY + ((i * nb + j) * Body.SAMPLES + p) * FACES + face;
            newContact(i, j, x, y, z, sg * r[ax], sg * r[3 + ax], sg * r[6 + ax], f[face], slot);
        }
    }

    private void newContact(int a, int b, double x, double y, double z, double nx, double ny, double nz, double sep, int slot) {
        if (nc == contacts.length) contacts = Arrays.copyOf(contacts, nc * 2);
        Contact c = contacts[nc];
        if (c == null) contacts[nc] = c = new Contact();
        nc++;
        Body A = bodies[a];
        c.a = a;
        c.b = b;
        c.slot = slot;
        c.s = sep;
        c.rax = x - A.px;
        c.ray = y - A.py;
        c.raz = z - A.pz;
        c.nx = nx;
        c.ny = ny;
        c.nz = nz;
        double tx, ty, tz;
        if (Math.abs(nx) > 0.57735) {
            tx = ny; ty = -nx; tz = 0;
        } else {
            tx = 0; ty = nz; tz = -ny;
        }
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        c.t1x = tx / tl; c.t1y = ty / tl; c.t1z = tz / tl;
        c.t2x = ny * c.t1z - nz * c.t1y;
        c.t2y = nz * c.t1x - nx * c.t1z;
        c.t2z = nx * c.t1y - ny * c.t1x;
        double kn = A.invMass + angTerm(A, c.rax, c.ray, c.raz, nx, ny, nz);
        double k1 = A.invMass + angTerm(A, c.rax, c.ray, c.raz, c.t1x, c.t1y, c.t1z);
        double k2 = A.invMass + angTerm(A, c.rax, c.ray, c.raz, c.t2x, c.t2y, c.t2z);
        if (b >= 0) {
            Body B = bodies[b];
            c.rbx = x - B.px;
            c.rby = y - B.py;
            c.rbz = z - B.pz;
            kn += B.invMass + angTerm(B, c.rbx, c.rby, c.rbz, nx, ny, nz);
            k1 += B.invMass + angTerm(B, c.rbx, c.rby, c.rbz, c.t1x, c.t1y, c.t1z);
            k2 += B.invMass + angTerm(B, c.rbx, c.rby, c.rbz, c.t2x, c.t2y, c.t2z);
        }
        c.nMass = 1 / kn;
        c.t1Mass = 1 / k1;
        c.t2Mass = 1 / k2;
        if (slot >= 0 && warmStamp[slot] == sub - 1) {
            c.ln = warm[3 * slot];
            c.lt1 = warm[3 * slot + 1];
            c.lt2 = warm[3 * slot + 2];
        } else {
            c.ln = c.lt1 = c.lt2 = 0;
        }
    }

    // (r × d) · I⁻¹ · (r × d)
    private static double angTerm(Body b, double rx, double ry, double rz, double dx, double dy, double dz) {
        double cx = ry * dz - rz * dy, cy = rz * dx - rx * dz, cz = rx * dy - ry * dx;
        double[] I = b.ii;
        return cx * (I[0] * cx + I[1] * cy + I[2] * cz) + cy * (I[3] * cx + I[4] * cy + I[5] * cz) + cz * (I[6] * cx + I[7] * cy + I[8] * cz);
    }

    private void ensureSlots() {
        int total = nb * SLOT_PER_BODY * (1 + nb);
        if (slotStamp.length == total) return;
        warm = new double[3 * total];
        warmStamp = new int[total];
        slotIdx = new int[total];
        slotStamp = new int[total];
        Arrays.fill(warmStamp, Integer.MIN_VALUE);
        Arrays.fill(slotStamp, Integer.MIN_VALUE);
    }

    private void storeWarm() {
        for (int k = 0; k < nc; k++) {
            Contact c = contacts[k];
            if (c.slot < 0) continue;
            warm[3 * c.slot] = c.ln;
            warm[3 * c.slot + 1] = c.lt1;
            warm[3 * c.slot + 2] = c.lt2;
            warmStamp[c.slot] = sub;
        }
    }

    // ════════════════════════════════════════ 관절 준비

    private void prepareJoint(Joint j, boolean muscle, double h) {
        Body A = bodies[j.a], B = bodies[j.b];
        double[] ra = A.r, rb = B.r;
        j.rax = ra[0] * j.lax + ra[1] * j.lay + ra[2] * j.laz;
        j.ray = ra[3] * j.lax + ra[4] * j.lay + ra[5] * j.laz;
        j.raz = ra[6] * j.lax + ra[7] * j.lay + ra[8] * j.laz;
        j.rbx = rb[0] * j.lbx + rb[1] * j.lby + rb[2] * j.lbz;
        j.rby = rb[3] * j.lbx + rb[4] * j.lby + rb[5] * j.lbz;
        j.rbz = rb[6] * j.lbx + rb[7] * j.lby + rb[8] * j.lbz;
        j.cx = B.px + j.rbx - A.px - j.rax;
        j.cy = B.py + j.rby - A.py - j.ray;
        j.cz = B.pz + j.rbz - A.pz - j.raz;
        // K = (1/mA + 1/mB)·I + S(rA)ᵀ·IA·S(rA) + S(rB)ᵀ·IB·S(rB)
        double m = A.invMass + B.invMass;
        double[] K = m9;
        Arrays.fill(K, 0);
        K[0] = K[4] = K[8] = m;
        addSkewInertia(K, A.ii, j.rax, j.ray, j.raz);
        addSkewInertia(K, B.ii, j.rbx, j.rby, j.rbz);
        inv3(K, j.k);
        if (j.limited) prepareLimit(j, A, B);
        else j.swOn = j.loOn = j.hiOn = false;
        if (muscle && j.hasTarget) prepareMuscle(j, A, B, h);
        else {
            j.muOn = false;
            j.mix = j.miy = j.miz = 0;
        }
    }

    private void addSkewInertia(double[] K, double[] I, double rx, double ry, double rz) {
        double[] S = t9;
        S[0] = 0; S[1] = -rz; S[2] = ry;
        S[3] = rz; S[4] = 0; S[5] = -rx;
        S[6] = -ry; S[7] = rx; S[8] = 0;
        for (int a = 0; a < 3; a++) {
            for (int c = 0; c < 3; c++) {
                double v = 0;
                for (int i = 0; i < 3; i++) {
                    double t = I[3 * i] * S[c] + I[3 * i + 1] * S[3 + c] + I[3 * i + 2] * S[6 + c];  // (I·S)[i][c]
                    v += S[3 * i + a] * t;
                }
                K[3 * a + c] += v;
            }
        }
    }

    // 대칭 3×3 역행렬 — 특이면 0(그 제약을 쉰다)
    private static void inv3(double[] m, double[] out) {
        double c00 = m[4] * m[8] - m[5] * m[7], c01 = m[5] * m[6] - m[3] * m[8], c02 = m[3] * m[7] - m[4] * m[6];
        double det = m[0] * c00 + m[1] * c01 + m[2] * c02;
        if (!(Math.abs(det) > 1e-30)) {
            Arrays.fill(out, 0);
            return;
        }
        double d = 1 / det;
        out[0] = c00 * d;
        out[1] = (m[2] * m[7] - m[1] * m[8]) * d;
        out[2] = (m[1] * m[5] - m[2] * m[4]) * d;
        out[3] = c01 * d;
        out[4] = (m[0] * m[8] - m[2] * m[6]) * d;
        out[5] = (m[2] * m[3] - m[0] * m[5]) * d;
        out[6] = c02 * d;
        out[7] = (m[1] * m[6] - m[0] * m[7]) * d;
        out[8] = (m[0] * m[4] - m[1] * m[3]) * d;
    }

    private static double angMass(Body A, Body B, double x, double y, double z) {
        double[] a = A.ii, b = B.ii;
        double k = x * ((a[0] + b[0]) * x + (a[1] + b[1]) * y + (a[2] + b[2]) * z)
                + y * ((a[3] + b[3]) * x + (a[4] + b[4]) * y + (a[5] + b[5]) * z)
                + z * ((a[6] + b[6]) * x + (a[7] + b[7]) * y + (a[8] + b[8]) * z);
        return k > 1e-30 ? 1 / k : 0;
    }

    // swing — 뼈 축이 원뿔 축에서 기운 각 θ 와 그 방향의 타원 반각 · twist — xA → dB 최단 회전으로 refA 를 옮겨 refB 와 잰 각
    private void prepareLimit(Joint j, Body A, Body B) {
        double[] ra = A.r, rb = B.r;
        double xx = ra[0] * j.axAx + ra[1] * j.axAy + ra[2] * j.axAz;
        double xy = ra[3] * j.axAx + ra[4] * j.axAy + ra[5] * j.axAz;
        double xz = ra[6] * j.axAx + ra[7] * j.axAy + ra[8] * j.axAz;
        double yx = ra[0] * j.rfAx + ra[1] * j.rfAy + ra[2] * j.rfAz;
        double yy = ra[3] * j.rfAx + ra[4] * j.rfAy + ra[5] * j.rfAz;
        double yz = ra[6] * j.rfAx + ra[7] * j.rfAy + ra[8] * j.rfAz;
        double zx = ra[0] * j.zAx + ra[1] * j.zAy + ra[2] * j.zAz;
        double zy = ra[3] * j.zAx + ra[4] * j.zAy + ra[5] * j.zAz;
        double zz = ra[6] * j.zAx + ra[7] * j.zAy + ra[8] * j.zAz;
        double dx = rb[0] * j.axBx + rb[1] * j.axBy + rb[2] * j.axBz;
        double dy = rb[3] * j.axBx + rb[4] * j.axBy + rb[5] * j.axBz;
        double dz = rb[6] * j.axBx + rb[7] * j.axBy + rb[8] * j.axBz;
        double bx = rb[0] * j.rfBx + rb[1] * j.rfBy + rb[2] * j.rfBz;
        double by = rb[3] * j.rfBx + rb[4] * j.rfBy + rb[5] * j.rfBz;
        double bz = rb[6] * j.rfBx + rb[7] * j.rfBy + rb[8] * j.rfBz;
        double cos = Math.max(-1, Math.min(1, xx * dx + xy * dy + xz * dz));

        j.swOn = false;
        double ax = xy * dz - xz * dy, ay = xz * dx - xx * dz, az = xx * dy - xy * dx;
        double sn = Math.sqrt(ax * ax + ay * ay + az * az);
        if (sn > 1e-9) {
            ax /= sn;
            ay /= sn;
            az /= sn;
            double cy = ax * yx + ay * yy + az * yz, cz = ax * zx + ay * zy + az * zz;  // 기울이는 회전축의 refA · zA 성분
            double lim = 1 / Math.sqrt((cz / j.sw1) * (cz / j.sw1) + (cy / j.sw2) * (cy / j.sw2));
            double C = lim - Math.atan2(sn, cos);
            if (C < LIMIT_WINDOW) {
                j.swOn = true;
                j.swJx = -ax;
                j.swJy = -ay;
                j.swJz = -az;
                j.swC = C;
                j.swMass = angMass(A, B, -ax, -ay, -az);
            }
        }
        if (!j.swOn) j.swAcc = 0;

        j.loOn = j.hiOn = false;
        double opc = 1 + cos;
        if (opc > 1e-6) {
            double ux = xx + dx, uy = xy + dy, uz = xz + dz;
            double kk = (yx * dx + yy * dy + yz * dz) / opc;
            double tx = yx - kk * ux, ty = yy - kk * uy, tz = yz - kk * uz;  // 옮긴 refA
            double crx = ty * bz - tz * by, cry = tz * bx - tx * bz, crz = tx * by - ty * bx;
            double phi = Math.atan2(dx * crx + dy * cry + dz * crz, tx * bx + ty * by + tz * bz);
            j.loC = phi - j.twMin;
            j.hiC = j.twMax - phi;
            j.loOn = j.loC < LIMIT_WINDOW;
            j.hiOn = j.hiC < LIMIT_WINDOW;
            if (j.loOn || j.hiOn) {
                j.twJx = ux / opc;  // dφ/dt = (xA + dB)/(1 + xA·dB) · (wB − wA)
                j.twJy = uy / opc;
                j.twJz = uz / opc;
                j.twMass = angMass(A, B, j.twJx, j.twJy, j.twJz);
            }
        }
        if (!j.loOn) j.loAcc = 0;
        if (!j.hiOn) j.hiAcc = 0;
    }

    // 오차 e = qB ⊗ (qA ⊗ 목표)⁻¹ 의 회전 벡터 — 근육은 이것을 0 쪽으로 끈다
    private void prepareMuscle(Joint j, Body A, Body B, double h) {
        double tx = A.qw * j.tqx + A.qx * j.tqw + A.qy * j.tqz - A.qz * j.tqy;
        double ty = A.qw * j.tqy - A.qx * j.tqz + A.qy * j.tqw + A.qz * j.tqx;
        double tz = A.qw * j.tqz + A.qx * j.tqy - A.qy * j.tqx + A.qz * j.tqw;
        double tw = A.qw * j.tqw - A.qx * j.tqx - A.qy * j.tqy - A.qz * j.tqz;
        double ex = -B.qw * tx + B.qx * tw - B.qy * tz + B.qz * ty;
        double ey = -B.qw * ty + B.qx * tz + B.qy * tw - B.qz * tx;
        double ez = -B.qw * tz - B.qx * ty + B.qy * tx + B.qz * tw;
        double ew = B.qw * tw + B.qx * tx + B.qy * ty + B.qz * tz;
        if (ew < 0) {
            ex = -ex; ey = -ey; ez = -ez; ew = -ew;
        }
        double sn = Math.sqrt(ex * ex + ey * ey + ez * ez);
        double f = sn > 1e-12 ? 2 * Math.atan2(sn, ew) / sn : 2;
        j.mcx = ex * f;
        j.mcy = ey * f;
        j.mcz = ez * f;
        double[] K = m9;
        for (int k = 0; k < 9; k++) K[k] = A.ii[k] + B.ii[k];
        inv3(K, j.mk);
        j.muMax = muscleMaxTorque * muscleStrength * h;
        j.muOn = true;
    }

    // ════════════════════════════════════════ 풀기

    private void integrateVelocities(double h) {
        double g = s.gravity * h;
        double ld = 1 / (1 + h * Math.max(0, s.linearDamping)), ad = 1 / (1 + h * Math.max(0, s.angularDamping));
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            b.vy += g;
            if (i == grabBody) grabForce(b, h);
            b.vx *= ld; b.vy *= ld; b.vz *= ld;
            b.wx *= ad; b.wy *= ad; b.wz *= ad;
        }
    }

    // 잡기 — 명시 스프링 · 댐퍼(지금 Lua 잡기와 같은 식) · 상한 maxForce
    private void grabForce(Body b, double h) {
        double[] r = b.r;
        double rx = r[0] * gLx + r[1] * gLy + r[2] * gLz;
        double ry = r[3] * gLx + r[4] * gLy + r[5] * gLz;
        double rz = r[6] * gLx + r[7] * gLy + r[8] * gLz;
        double vx = b.vx + b.wy * rz - b.wz * ry, vy = b.vy + b.wz * rx - b.wx * rz, vz = b.vz + b.wx * ry - b.wy * rx;
        double fx = gK * (gAx - b.px - rx) - gC * vx, fy = gK * (gAy - b.py - ry) - gC * vy, fz = gK * (gAz - b.pz - rz) - gC * vz;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz), cap = Math.max(0, gMax);
        if (fl > cap) {
            double k = cap / fl;
            fx *= k; fy *= k; fz *= k;
        }
        b.impulse(h, rx, ry, rz, fx, fy, fz);
    }

    private void warmStart() {
        for (int k = 0; k < nj; k++) {
            Joint j = joints[k];
            Body A = bodies[j.a], B = bodies[j.b];
            A.impulse(-1, j.rax, j.ray, j.raz, j.pix, j.piy, j.piz);
            B.impulse(1, j.rbx, j.rby, j.rbz, j.pix, j.piy, j.piz);
            double tw = j.loAcc - j.hiAcc;
            double lx = j.swJx * j.swAcc + j.twJx * tw + j.mix;
            double ly = j.swJy * j.swAcc + j.twJy * tw + j.miy;
            double lz = j.swJz * j.swAcc + j.twJz * tw + j.miz;
            A.addAngular(-1, lx, ly, lz);
            B.addAngular(1, lx, ly, lz);
        }
        for (int k = 0; k < nc; k++) {
            Contact c = contacts[k];
            double px = c.ln * c.nx + c.lt1 * c.t1x + c.lt2 * c.t2x;
            double py = c.ln * c.ny + c.lt1 * c.t1y + c.lt2 * c.t2y;
            double pz = c.ln * c.nz + c.lt1 * c.t1z + c.lt2 * c.t2z;
            push(c, px, py, pz);
        }
    }

    private void push(Contact c, double px, double py, double pz) {
        bodies[c.a].impulse(1, c.rax, c.ray, c.raz, px, py, pz);
        if (c.b >= 0) bodies[c.b].impulse(-1, c.rbx, c.rby, c.rbz, px, py, pz);
    }

    private void solve(double invH, boolean useBias) {
        for (int k = 0; k < nj; k++) {
            Joint j = joints[k];
            Body A = bodies[j.a], B = bodies[j.b];
            if (j.muOn) solveMuscle(j, A, B);
            if (j.swOn) j.swAcc = solveLimit(A, B, j.swJx, j.swJy, j.swJz, j.swMass, j.swC, j.swAcc, useBias, invH);
            if (j.loOn) j.loAcc = solveLimit(A, B, j.twJx, j.twJy, j.twJz, j.twMass, j.loC, j.loAcc, useBias, invH);
            if (j.hiOn) j.hiAcc = solveLimit(A, B, -j.twJx, -j.twJy, -j.twJz, j.twMass, j.hiC, j.hiAcc, useBias, invH);
            solvePoint(j, A, B, useBias);
        }
        for (int k = 0; k < nc; k++) solveContact(contacts[k], useBias, invH);
        // 범위를 접촉 뒤에 한 번 더 — 충돌 순간 접촉이 팔다리를 범위 밖으로 비튼 몫을 같은 반복 안에서 되돌린다(넉백 시험 실측)
        for (int k = 0; k < nj; k++) {
            Joint j = joints[k];
            Body A = bodies[j.a], B = bodies[j.b];
            if (j.swOn) j.swAcc = solveLimit(A, B, j.swJx, j.swJy, j.swJz, j.swMass, j.swC, j.swAcc, useBias, invH);
            if (j.loOn) j.loAcc = solveLimit(A, B, j.twJx, j.twJy, j.twJz, j.twMass, j.loC, j.loAcc, useBias, invH);
            if (j.hiOn) j.hiAcc = solveLimit(A, B, -j.twJx, -j.twJy, -j.twJz, j.twMass, j.hiC, j.hiAcc, useBias, invH);
        }
    }

    // 한쪽 제한 — C ≥ 0 이 허용 · 누적 임펄스 ≥ 0 · C > 0 이면 추측 제약(그 서브스텝에 넘을 만큼만 막는다)
    private double solveLimit(Body A, Body B, double jx, double jy, double jz, double mass, double C, double acc, boolean useBias, double invH) {
        double cdot = jx * (B.wx - A.wx) + jy * (B.wy - A.wy) + jz * (B.wz - A.wz);
        double bias = 0, ms = 1, is = 0;
        if (C > 0) bias = C * invH;
        else if (useBias) {
            bias = jBias * C;
            ms = jMass;
            is = jImp;
        }
        double imp = -mass * ms * (cdot + bias) - is * acc;
        double na = Math.max(acc + imp, 0);
        imp = na - acc;
        A.addAngular(-imp, jx, jy, jz);
        B.addAngular(imp, jx, jy, jz);
        return na;
    }

    private void solvePoint(Joint j, Body A, Body B, boolean useBias) {
        double cx = B.vx + B.wy * j.rbz - B.wz * j.rby - (A.vx + A.wy * j.raz - A.wz * j.ray);
        double cy = B.vy + B.wz * j.rbx - B.wx * j.rbz - (A.vy + A.wz * j.rax - A.wx * j.raz);
        double cz = B.vz + B.wx * j.rby - B.wy * j.rbx - (A.vz + A.wx * j.ray - A.wy * j.rax);
        double ms = 1, is = 0;
        if (useBias) {
            cx += jBias * j.cx;
            cy += jBias * j.cy;
            cz += jBias * j.cz;
            ms = jMass;
            is = jImp;
        }
        double[] k = j.k;
        double lx = -ms * (k[0] * cx + k[1] * cy + k[2] * cz) - is * j.pix;
        double ly = -ms * (k[3] * cx + k[4] * cy + k[5] * cz) - is * j.piy;
        double lz = -ms * (k[6] * cx + k[7] * cy + k[8] * cz) - is * j.piz;
        j.pix += lx;
        j.piy += ly;
        j.piz += lz;
        A.impulse(-1, j.rax, j.ray, j.raz, lx, ly, lz);
        B.impulse(1, j.rbx, j.rby, j.rbz, lx, ly, lz);
    }

    // 근육 — 목표 상대 회전 쪽 soft 각 제약(스프링이라 이완 단계에도 건다) · 누적 각 임펄스 ≤ maxTorque × strength × h
    private void solveMuscle(Joint j, Body A, Body B) {
        double cx = B.wx - A.wx + mBias * j.mcx, cy = B.wy - A.wy + mBias * j.mcy, cz = B.wz - A.wz + mBias * j.mcz;
        double[] k = j.mk;
        double nx = j.mix - mMass * (k[0] * cx + k[1] * cy + k[2] * cz) - mImp * j.mix;
        double ny = j.miy - mMass * (k[3] * cx + k[4] * cy + k[5] * cz) - mImp * j.miy;
        double nz = j.miz - mMass * (k[6] * cx + k[7] * cy + k[8] * cz) - mImp * j.miz;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l > j.muMax) {
            double f = j.muMax / l;
            nx *= f; ny *= f; nz *= f;
        }
        double dx = nx - j.mix, dy = ny - j.miy, dz = nz - j.miz;
        j.mix = nx;
        j.miy = ny;
        j.miz = nz;
        A.addAngular(-1, dx, dy, dz);
        B.addAngular(1, dx, dy, dz);
    }

    private void solveContact(Contact c, boolean useBias, double invH) {
        Body A = bodies[c.a], B = c.b >= 0 ? bodies[c.b] : null;
        double vx = A.vx + A.wy * c.raz - A.wz * c.ray, vy = A.vy + A.wz * c.rax - A.wx * c.raz, vz = A.vz + A.wx * c.ray - A.wy * c.rax;
        if (B != null) {
            vx -= B.vx + B.wy * c.rbz - B.wz * c.rby;
            vy -= B.vy + B.wz * c.rbx - B.wx * c.rbz;
            vz -= B.vz + B.wx * c.rby - B.wy * c.rbx;
        }
        double vn = vx * c.nx + vy * c.ny + vz * c.nz;
        double bias = 0, ms = 1, is = 0;
        if (c.s > 0) bias = c.s * invH;
        else if (useBias) {
            boolean block = c.b < 0;
            bias = Math.max((block ? sBias : cBias) * c.s, -pushMax);
            ms = block ? sMass : cMass;
            is = block ? sImp : cImp;
        }
        double imp = -c.nMass * ms * (vn + bias) - is * c.ln;
        double nl = Math.max(c.ln + imp, 0);
        imp = nl - c.ln;
        c.ln = nl;
        push(c, imp * c.nx, imp * c.ny, imp * c.nz);

        // 마찰 — 접선 둘 · 원 쿨롱(|λt| ≤ μ·λn)
        vx = A.vx + A.wy * c.raz - A.wz * c.ray;
        vy = A.vy + A.wz * c.rax - A.wx * c.raz;
        vz = A.vz + A.wx * c.ray - A.wy * c.rax;
        if (B != null) {
            vx -= B.vx + B.wy * c.rbz - B.wz * c.rby;
            vy -= B.vy + B.wz * c.rbx - B.wx * c.rbz;
            vz -= B.vz + B.wx * c.rby - B.wy * c.rbx;
        }
        double l1 = c.lt1 - c.t1Mass * (vx * c.t1x + vy * c.t1y + vz * c.t1z);
        double l2 = c.lt2 - c.t2Mass * (vx * c.t2x + vy * c.t2y + vz * c.t2z);
        double max = Math.max(0, s.friction) * c.ln, mag = Math.sqrt(l1 * l1 + l2 * l2);
        if (mag > max) {
            double f = max / mag;
            l1 *= f;
            l2 *= f;
        }
        double d1 = l1 - c.lt1, d2 = l2 - c.lt2;
        c.lt1 = l1;
        c.lt2 = l2;
        push(c, d1 * c.t1x + d2 * c.t2x, d1 * c.t1y + d2 * c.t2y, d1 * c.t1z + d2 * c.t2z);
    }

    private void integratePositions(double h) {
        double hh = 0.5 * h;
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            b.px += h * b.vx;
            b.py += h * b.vy;
            b.pz += h * b.vz;
            double x = b.qx, y = b.qy, z = b.qz, w = b.qw;
            x += hh * (b.wx * w + b.wy * z - b.wz * y);
            y += hh * (-b.wx * z + b.wy * w + b.wz * x);
            z += hh * (b.wx * y - b.wy * x + b.wz * w);
            w += hh * (-b.wx * x - b.wy * y - b.wz * z);
            double n = Math.sqrt(x * x + y * y + z * z + w * w);
            b.qx = x / n;
            b.qy = y / n;
            b.qz = z / n;
            b.qw = w / n;
        }
    }

    // 이완 단계 — 위치가 움직인 만큼 떨어짐 · 남은 여유를 고쳐 둔다(추측 제약이 그만큼만 막게)
    private void prepareRelax(double h) {
        for (int k = 0; k < nc; k++) {
            Contact c = contacts[k];
            Body A = bodies[c.a];
            double vx = A.vx + A.wy * c.raz - A.wz * c.ray, vy = A.vy + A.wz * c.rax - A.wx * c.raz, vz = A.vz + A.wx * c.ray - A.wy * c.rax;
            if (c.b >= 0) {
                Body B = bodies[c.b];
                vx -= B.vx + B.wy * c.rbz - B.wz * c.rby;
                vy -= B.vy + B.wz * c.rbx - B.wx * c.rbz;
                vz -= B.vz + B.wx * c.rby - B.wy * c.rbx;
            }
            c.s += h * (vx * c.nx + vy * c.ny + vz * c.nz);
        }
        for (int k = 0; k < nj; k++) {
            Joint j = joints[k];
            Body A = bodies[j.a], B = bodies[j.b];
            double rx = B.wx - A.wx, ry = B.wy - A.wy, rz = B.wz - A.wz;
            if (j.swOn) j.swC += h * (j.swJx * rx + j.swJy * ry + j.swJz * rz);
            double tw = h * (j.twJx * rx + j.twJy * ry + j.twJz * rz);
            if (j.loOn) j.loC += tw;
            if (j.hiOn) j.hiC -= tw;
            if (j.muOn) {
                j.mcx += h * rx;
                j.mcy += h * ry;
                j.mcz += h * rz;
            }
        }
    }

    // ════════════════════════════════════════ 잠 · 안전

    private void sleepWindow() {
        if (grabBody >= 0 || ticksAwake < s.minTicks) {
            anchorValid = false;
            calm = 0;
            return;
        }
        if (!anchorValid) {
            capture();
            return;
        }
        double move2 = s.sleepMove * s.sleepMove, cosHalf = Math.cos(Math.toRadians(s.sleepRotDeg) * 0.5);
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            int o = 7 * i;
            double dx = b.px - anchor[o], dy = b.py - anchor[o + 1], dz = b.pz - anchor[o + 2];
            double dot = Math.abs(b.qx * anchor[o + 3] + b.qy * anchor[o + 4] + b.qz * anchor[o + 5] + b.qw * anchor[o + 6]);
            if (dx * dx + dy * dy + dz * dz > move2 || dot < cosHalf) {
                capture();
                return;
            }
        }
        if (++calm >= s.sleepTicks) sleepNow();
    }

    private void capture() {
        if (anchor.length != 7 * nb) anchor = new double[7 * nb];
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            int o = 7 * i;
            anchor[o] = b.px; anchor[o + 1] = b.py; anchor[o + 2] = b.pz;
            anchor[o + 3] = b.qx; anchor[o + 4] = b.qy; anchor[o + 5] = b.qz; anchor[o + 6] = b.qw;
        }
        anchorValid = true;
        calm = 0;
    }

    private void sleepNow() {
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0;
        }
        for (int k = 0; k < nj; k++) joints[k].clearImpulses();
        sub += 2;
        asleep = true;
        probe = 0;
        anchorValid = false;
        calm = 0;
    }

    // 자는 동안 — 몸 하나라도 블록 상자에 닿아(여유 안) 있으면 받쳐 있다
    private boolean supported(BoxSource src) {
        double m = Math.max(0, s.margin);
        for (int i = 0; i < nb; i++) bodies[i].update();
        collect(src, 0, m);
        markEdges();
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            b.samples(m);
            double[] r = b.r;
            for (int k = 0; k < boxCount; k++) {
                int o = 6 * k;
                double lx = boxes[o], ly = boxes[o + 1], lz = boxes[o + 2], hx = boxes[o + 3], hy = boxes[o + 4], hz = boxes[o + 5];
                if (hx < b.x0 || lx > b.x1 || hy < b.y0 || ly > b.y1 || hz < b.z0 || lz > b.z1) continue;
                for (int p = 0; p < Body.SAMPLES; p++) {
                    double x = b.sp[3 * p], y = b.sp[3 * p + 1], z = b.sp[3 * p + 2];
                    if (x >= lx - m && x <= hx + m && y >= ly - m && y <= hy + m && z >= lz - m && z <= hz + m) return true;
                }
                for (int c = 0; c < 8; c++) {
                    double dx = ((c & 1) != 0 ? hx : lx) - b.px, dy = ((c & 2) != 0 ? hy : ly) - b.py, dz = ((c & 4) != 0 ? hz : lz) - b.pz;
                    double ux = r[0] * dx + r[3] * dy + r[6] * dz, uy = r[1] * dx + r[4] * dy + r[7] * dz, uz = r[2] * dx + r[5] * dy + r[8] * dz;
                    if (Math.abs(ux) <= b.hx + m && Math.abs(uy) <= b.hy + m && Math.abs(uz) <= b.hz + m) return true;
                }
                for (int e = 0; e < 12; e++) if ((edgeMask[k] & (1 << e)) != 0 && clipEdge(b, k, e) >= 0) return true;
            }
        }
        return false;
    }

    private void saveSnapshot() {
        if (snap.length != 13 * nb) snap = new double[13 * nb];
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            int o = 13 * i;
            snap[o] = b.px; snap[o + 1] = b.py; snap[o + 2] = b.pz;
            snap[o + 3] = b.qx; snap[o + 4] = b.qy; snap[o + 5] = b.qz; snap[o + 6] = b.qw;
            snap[o + 7] = b.vx; snap[o + 8] = b.vy; snap[o + 9] = b.vz;
            snap[o + 10] = b.wx; snap[o + 11] = b.wy; snap[o + 12] = b.wz;
        }
    }

    // step 앞 자리 · 회전으로 · 속도 0 · 잠재움
    private void rollback() {
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            int o = 13 * i;
            b.px = snap[o]; b.py = snap[o + 1]; b.pz = snap[o + 2];
            b.qx = snap[o + 3]; b.qy = snap[o + 4]; b.qz = snap[o + 5]; b.qw = snap[o + 6];
            b.update();
        }
        sleepNow();
        healthy = false;
    }

    private boolean sane() {
        double vm = s.maxSpeed, vm2 = vm * vm;
        for (int i = 0; i < nb; i++) {
            Body b = bodies[i];
            if (!finite(b.px) || !finite(b.py) || !finite(b.pz) || !finite(b.qx) || !finite(b.qy) || !finite(b.qz) || !finite(b.qw)
                    || !finite(b.vx) || !finite(b.vy) || !finite(b.vz) || !finite(b.wx) || !finite(b.wy) || !finite(b.wz)) return false;
            if (b.vx * b.vx + b.vy * b.vy + b.vz * b.vz > vm2) return false;
        }
        return true;
    }

    // ════════════════════════════════════════ 잔손

    private Body body(int i) {
        if (i < 0 || i >= nb) throw new IllegalArgumentException("몸 번호 " + i + " 가 범위(0 ~ " + (nb - 1) + ") 밖이다");
        return bodies[i];
    }

    private void growNoCol(int n) {
        if (n <= noColCap) return;
        int cap = Math.max(n, noColCap * 2);
        boolean[] m = new boolean[cap * cap];
        for (int i = 0; i < noColCap; i++) System.arraycopy(noCol, i * noColCap, m, i * cap, noColCap);
        noCol = m;
        noColCap = cap;
    }

    private static boolean vec3(double[] v) {
        return v != null && v.length >= 3 && finite(v[0]) && finite(v[1]) && finite(v[2]);
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
