package org.figuramc.figura.lua.api.physics;

import org.figuramc.figura.lua.api.physics.solver.BoxSink;
import org.figuramc.figura.lua.api.physics.solver.BoxSource;
import org.figuramc.figura.lua.api.world.WorldAPI;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 지금 클라 월드의 블록 충돌 모양을 상자로 넘긴다 — 아바타 Lua 를 거치지 않아 칸마다 객체를 만들지 않는다.
 * 청크가 없는 곳은 비어 있다(바닐라 `getBlockCollisions` 그대로).
 */
final class LevelBoxSource implements BoxSource {

    @Override
    public void collect(double x0, double y0, double z0, double x1, double y1, double z1, BoxSink sink) {
        Level level = WorldAPI.getCurrentWorld();
        if (level == null) return;
        for (VoxelShape shape : level.getBlockCollisions(null, new AABB(x0, y0, z0, x1, y1, z1)))
            shape.forAllBoxes((ax, ay, az, bx, by, bz) -> sink.box(ax, ay, az, bx, by, bz));
    }
}
