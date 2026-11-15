package com.skozi.roomsmod;

import net.minecraft.core.BlockPos;

/** Прямоугольная область (включительно) в конкретном измерении. */
public final class Box {
    public final String dim;
    public final int x1, y1, z1, x2, y2, z2;

    public Box(String dim, int ax, int ay, int az, int bx, int by, int bz) {
        this.dim = dim;
        this.x1 = Math.min(ax, bx);
        this.y1 = Math.min(ay, by);
        this.z1 = Math.min(az, bz);
        this.x2 = Math.max(ax, bx);
        this.y2 = Math.max(ay, by);
        this.z2 = Math.max(az, bz);
    }

    public static Box of(String dim, BlockPos a, BlockPos b) {
        return new Box(dim, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= x1 && p.getX() <= x2
                && p.getY() >= y1 && p.getY() <= y2
                && p.getZ() >= z1 && p.getZ() <= z2;
    }

    public boolean intersects(Box o) {
        if (!dim.equals(o.dim)) return false;
        return x1 <= o.x2 && x2 >= o.x1
                && y1 <= o.y2 && y2 >= o.y1
                && z1 <= o.z2 && z2 >= o.z1;
    }

    public BlockPos min() { return new BlockPos(x1, y1, z1); }

    public BlockPos max() { return new BlockPos(x2, y2, z2); }
}
