package org.figuramc.figura.lua.api.physics;

import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.lua.LuaNotNil;
import org.figuramc.figura.lua.LuaWhitelist;
import org.figuramc.figura.lua.api.physics.solver.RigidWorld;
import org.figuramc.figura.lua.docs.LuaMethodDoc;
import org.figuramc.figura.lua.docs.LuaMethodOverload;
import org.figuramc.figura.lua.docs.LuaTypeDoc;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Map;

/**
 * `physics:newWorld` 가 돌려주는 강체 묶음 하나 — 이름으로 몸을 가리킨다.
 * 단위: 위치 블록 · 선속도 블록/틱 · 각속도 라디안/틱(안에서 초 단위로 바꾼다) · 사원수 (x, y, z, w).
 */
@LuaWhitelist
@LuaTypeDoc(
        name = "PhysicsWorld",
        value = "physics_world"
)
public class PhysicsWorldAPI {

    private static final double TPS = 20.0;
    /** step 한 번에 아바타 명령 수로 매길 값 — 몸 하나 · 서브스텝 하나당 */
    private static final int COST_PER_BODY_SUBSTEP = 40;

    private final Avatar owner;
    private final RigidWorld world;
    private final Map<String, Integer> index;
    private final int substeps;
    private final LevelBoxSource boxes = new LevelBoxSource();
    private final double[] tmp3 = new double[3];
    private final double[] tmp4 = new double[4];
    private boolean removed;

    PhysicsWorldAPI(Avatar owner, RigidWorld world, Map<String, Integer> index, int substeps) {
        this.owner = owner;
        this.world = world;
        this.index = index;
        this.substeps = substeps;
    }

    private int id(LuaValue name) {
        if (removed) throw new LuaError("physics world was removed");
        return PhysicsAPI.body(index, name);
    }

    private void alive() {
        if (removed) throw new LuaError("physics world was removed");
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.step")
    public boolean step() {
        alive();
        owner.punish(world.bodyCount() * substeps * COST_PER_BODY_SUBSTEP);
        world.step(1.0 / TPS, boxes);
        return world.isAsleep();
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(argumentTypes = {String.class, Double.class, Double.class, Double.class}, argumentNames = {"body", "x", "y", "z"}),
            value = "physics_world.set_velocity"
    )
    public void setVelocity(@LuaNotNil LuaValue body, double x, double y, double z) {
        world.setVelocity(id(body), x * TPS, y * TPS, z * TPS);
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(argumentTypes = {String.class, Double.class, Double.class, Double.class}, argumentNames = {"body", "x", "y", "z"}),
            value = "physics_world.set_angular_velocity"
    )
    public void setAngularVelocity(@LuaNotNil LuaValue body, double x, double y, double z) {
        world.setAngularVelocity(id(body), x * TPS, y * TPS, z * TPS);
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = @LuaMethodOverload(argumentTypes = String.class, argumentNames = "body"), value = "physics_world.get_velocity")
    public Object[] getVelocity(@LuaNotNil LuaValue body) {
        world.getVelocity(id(body), tmp3);
        return new Object[]{tmp3[0] / TPS, tmp3[1] / TPS, tmp3[2] / TPS};
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = @LuaMethodOverload(argumentTypes = String.class, argumentNames = "body"), value = "physics_world.get_angular_velocity")
    public Object[] getAngularVelocity(@LuaNotNil LuaValue body) {
        world.getAngularVelocity(id(body), tmp3);
        return new Object[]{tmp3[0] / TPS, tmp3[1] / TPS, tmp3[2] / TPS};
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = @LuaMethodOverload(argumentTypes = String.class, argumentNames = "body"), value = "physics_world.get_body_pos")
    public Object[] getBodyPos(@LuaNotNil LuaValue body) {
        world.getBodyPos(id(body), tmp3);
        return new Object[]{tmp3[0], tmp3[1], tmp3[2]};
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = @LuaMethodOverload(argumentTypes = String.class, argumentNames = "body"), value = "physics_world.get_body_rot")
    public Object[] getBodyRot(@LuaNotNil LuaValue body) {
        world.getBodyRot(id(body), tmp4);
        return new Object[]{tmp4[0], tmp4[1], tmp4[2], tmp4[3]};
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = @LuaMethodOverload(argumentTypes = LuaTable.class, argumentNames = "buffer"), value = "physics_world.get_state")
    public boolean getState(@LuaNotNil LuaTable buf) {
        alive();
        double[] out = new double[3 + 4 * world.bodyCount()];
        world.getPose(out);
        for (int i = 0; i < out.length; i++) buf.set(i + 1, LuaValue.valueOf(out[i]));
        return world.isAsleep();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = @LuaMethodOverload(argumentTypes = LuaTable.class, argumentNames = "buffer"), value = "physics_world.set_state")
    public void setState(@LuaNotNil LuaTable buf) {
        alive();
        double[] in = PhysicsAPI.vec(buf, 3 + 4 * world.bodyCount(), "state");
        world.setPose(in);
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.clamp_to_limits")
    public int clampToLimits() {
        alive();
        return world.clampToLimits();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.ignore_start_overlaps")
    public int ignoreStartOverlaps() {
        alive();
        return world.ignoreStartOverlaps();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.capture_muscle_targets")
    public void captureMuscleTargets() {
        alive();
        world.captureMuscleTargets();
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(argumentTypes = {Double.class, Double.class, Double.class, Double.class}, argumentNames = {"strength", "hz", "zeta", "maxTorque"}),
            value = "physics_world.set_muscle"
    )
    public void setMuscle(double strength, double hz, double zeta, double maxTorque) {
        alive();
        world.setMuscle(Math.max(0, Math.min(1, strength)), hz, zeta, maxTorque);
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(
                    argumentTypes = {String.class, Double.class, Double.class, Double.class, Double.class, Double.class, Double.class, Double.class, Double.class, Double.class},
                    argumentNames = {"body", "localX", "localY", "localZ", "anchorX", "anchorY", "anchorZ", "stiffness", "damping", "maxForce"}),
            value = "physics_world.set_grab"
    )
    public void setGrab(@LuaNotNil LuaValue body, double lx, double ly, double lz, double ax, double ay, double az, double k, double c, double maxForce) {
        world.setGrab(id(body), new double[]{lx, ly, lz}, ax, ay, az, k, c, maxForce);
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.clear_grab")
    public void clearGrab() {
        alive();
        world.clearGrab();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.is_asleep")
    public boolean isAsleep() {
        return !removed && world.isAsleep();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.wake")
    public void wake() {
        alive();
        world.wake();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.is_healthy")
    public boolean isHealthy() {
        return !removed && world.healthy();
    }

    @LuaWhitelist
    @LuaMethodDoc(overloads = {}, value = "physics_world.remove")
    public void remove() {
        removed = true;
    }

    @Override
    public String toString() {
        return "PhysicsWorld(" + index.size() + " bodies)";
    }
}
