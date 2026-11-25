package org.figuramc.figura.lua.api.physics;

import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.lua.LuaNotNil;
import org.figuramc.figura.lua.LuaWhitelist;
import org.figuramc.figura.lua.api.physics.solver.JointLimit;
import org.figuramc.figura.lua.api.physics.solver.RigidWorld;
import org.figuramc.figura.lua.api.physics.solver.Settings;
import org.figuramc.figura.lua.docs.LuaMethodDoc;
import org.figuramc.figura.lua.docs.LuaMethodOverload;
import org.figuramc.figura.lua.docs.LuaTypeDoc;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 전역 `physics` — 래그돌 같은 작은 강체 묶음을 자바로 계산한다(하네스 래그돌 개편 RG1).
 * 숫자 · 표만 주고받는다 · 계산 본체는 마인크래프트와 무관한 `solver` 패키지.
 */
@LuaWhitelist
@LuaTypeDoc(
        name = "PhysicsAPI",
        value = "physics"
)
public class PhysicsAPI {

    /** 기능 판 — Lua 가 `physics.VERSION` 으로 가른다 */
    public static final int VERSION = 1;

    private final Avatar owner;

    public PhysicsAPI(Avatar owner) {
        this.owner = owner;
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(argumentTypes = LuaTable.class, argumentNames = "settings"),
            value = "physics.new_world"
    )
    public PhysicsWorldAPI newWorld(@LuaNotNil LuaTable t) {
        Settings s = new Settings();
        s.gravity = num(t, "gravity", s.gravity);
        s.friction = num(t, "friction", s.friction);
        s.linearDamping = num(t, "linearDamping", s.linearDamping);
        s.angularDamping = num(t, "angularDamping", s.angularDamping);
        s.substeps = (int) Math.max(1, Math.min(32, num(t, "substeps", s.substeps)));
        s.contactHz = num(t, "contactHz", s.contactHz);
        s.contactZeta = num(t, "contactZeta", s.contactZeta);
        s.contactPushMax = num(t, "contactPushMax", s.contactPushMax);
        s.margin = num(t, "margin", s.margin);
        s.jointHz = num(t, "jointHz", s.jointHz);
        s.jointZeta = num(t, "jointZeta", s.jointZeta);
        s.sleepMove = num(t, "sleepMove", s.sleepMove);
        s.sleepRotDeg = num(t, "sleepRot", s.sleepRotDeg);
        s.sleepTicks = (int) num(t, "sleepTicks", s.sleepTicks);
        s.minTicks = (int) num(t, "minTicks", s.minTicks);
        s.maxSpeed = num(t, "maxSpeed", s.maxSpeed);

        LuaTable bodies = table(t, "bodies");
        if (bodies.length() < 1 || bodies.length() > 16)
            throw new LuaError("physics.newWorld: bodies must have 1 to 16 entries");

        double[] o = vec(table(bodies.get(1).checktable(), "pos"), 3, "bodies[1].pos");
        RigidWorld w = new RigidWorld(s, o[0], o[1], o[2]);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 1; i <= bodies.length(); i++) {
            LuaTable b = bodies.get(i).checktable();
            String name = b.get("name").checkjstring();
            if (index.containsKey(name)) throw new LuaError("physics.newWorld: duplicate body name " + name);
            double[] h = vec(table(b, "half"), 3, name + ".half");
            double[] p = vec(table(b, "pos"), 3, name + ".pos");
            double[] q = vec(table(b, "rot"), 4, name + ".rot");
            double mass = num(b, "mass", 1);
            if (!(mass > 0) || !(h[0] > 0 && h[1] > 0 && h[2] > 0))
                throw new LuaError("physics.newWorld: " + name + " needs mass > 0 and half > 0");
            index.put(name, w.addBody(h[0], h[1], h[2], mass, p[0], p[1], p[2], q[0], q[1], q[2], q[3]));
        }

        LuaValue joints = t.get("joints");
        if (joints.istable()) {
            for (int i = 1; i <= joints.length(); i++) {
                LuaTable j = joints.get(i).checktable();
                int a = body(index, j.get("a"));
                int b = body(index, j.get("b"));
                double[] pa = vec(table(j, "pa"), 3, "joint.pa");
                double[] pb = vec(table(j, "pb"), 3, "joint.pb");
                JointLimit limit = null;
                LuaValue l = j.get("limit");
                if (l.istable()) {
                    LuaTable lt = l.checktable();
                    limit = new JointLimit();
                    limit.axisA = vec(table(lt, "axisA"), 3, "limit.axisA");
                    limit.axisB = vec(table(lt, "axisB"), 3, "limit.axisB");
                    limit.refA = vec(table(lt, "refA"), 3, "limit.refA");
                    limit.refB = vec(table(lt, "refB"), 3, "limit.refB");
                    double[] sw = vec(table(lt, "swing"), 2, "limit.swing");
                    double[] tw = vec(table(lt, "twist"), 2, "limit.twist");
                    limit.swing1Deg = sw[0];
                    limit.swing2Deg = sw[1];
                    limit.twistMinDeg = tw[0];
                    limit.twistMaxDeg = tw[1];
                }
                w.addJoint(a, b, pa, pb, limit);
            }
        }

        LuaValue nc = t.get("noCollide");
        if (nc.istable()) {
            for (int i = 1; i <= nc.length(); i++) {
                LuaTable pair = nc.get(i).checktable();
                w.setNoCollide(body(index, pair.get(1)), body(index, pair.get(2)));
            }
        }
        return new PhysicsWorldAPI(owner, w, index, s.substeps);
    }

    @LuaWhitelist
    public Object __index(String key) {
        if ("VERSION".equals(key)) return VERSION;
        return null;
    }

    @Override
    public String toString() {
        return "PhysicsAPI";
    }

    // ── 표 읽기 ──

    static double num(LuaTable t, String key, double def) {
        LuaValue v = t.get(key);
        if (v.isnil()) return def;
        double d = v.checkdouble();
        if (!Double.isFinite(d)) throw new LuaError("physics: " + key + " must be finite");
        return d;
    }

    static LuaTable table(LuaTable t, String key) {
        LuaValue v = t.get(key);
        if (!v.istable()) throw new LuaError("physics: " + key + " must be a table");
        return v.checktable();
    }

    static double[] vec(LuaTable t, int n, String what) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            LuaValue v = t.get(i + 1);
            if (!v.isnumber()) throw new LuaError("physics: " + what + " needs " + n + " numbers");
            out[i] = v.todouble();
            if (!Double.isFinite(out[i])) throw new LuaError("physics: " + what + " must be finite");
        }
        return out;
    }

    static int body(Map<String, Integer> index, LuaValue name) {
        Integer i = index.get(name.checkjstring());
        if (i == null) throw new LuaError("physics: unknown body " + name.tojstring());
        return i;
    }
}
