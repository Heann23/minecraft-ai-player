package me.herry.minecraftAI.ai.perception;

/**
 * 눈에서 어떤 블록이나 점까지 시선이 닿는지 계산한다.
 * 빛이 통과하지 않는 꽉 찬 블록(돌, 흙, 원목 등)이 사이에 있으면 보이지 않는다. 잎, 유리, 풀처럼 빛이 통과하는 블록은 가리지 않는다.
 * Bukkit 에 의존하지 않아서 서버 없이 테스트할 수 있다.
 */
public final class Visibility {
    @FunctionalInterface
    public interface Opacity {
        // 이 칸이 시선을 가리는지
        boolean isOpaque(int x, int y, int z);
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    // 면 쪽으로 옮긴 점이 블록 안에 남도록 0.5 보다 조금 작게 옮긴다.
    private static final double FACE_OFFSET = 0.45;
    private static final int MAX_STEPS = 512;

    private Visibility() {
    }

    /**
     * 눈에서 (tx, ty, tz) 블록이 보이는지. 블록 자체는 가리는 블록이어도 된다(광석 등).
     * 블록 중심이 가려져도 드러난 면이 보이면 보인다고 본다.
     */
    public static boolean canSeeBlock(Opacity opacity, double ex, double ey, double ez, int tx, int ty, int tz) {
        if (isClear(opacity, ex, ey, ez, tx + 0.5, ty + 0.5, tz + 0.5)) return true;
        for (int[] face : FACES) {
            // 다른 블록에 덮인 면은 어느 쪽에서도 보이지 않는다.
            if (opacity.isOpaque(tx + face[0], ty + face[1], tz + face[2])) continue;
            double px = tx + 0.5 + face[0] * FACE_OFFSET;
            double py = ty + 0.5 + face[1] * FACE_OFFSET;
            double pz = tz + 0.5 + face[2] * FACE_OFFSET;
            if (isClear(opacity, ex, ey, ez, px, py, pz)) return true;
        }
        return false;
    }

    /**
     * 눈에서 점 (px, py, pz) 까지 가리는 블록이 없는지. 엔티티가 보이는지 확인할 때 쓴다.
     */
    public static boolean canSeePoint(Opacity opacity, double ex, double ey, double ez, double px, double py, double pz) {
        int x = floor(px);
        int y = floor(py);
        int z = floor(pz);
        return !opacity.isOpaque(x, y, z) && isClear(opacity, ex, ey, ez, px, py, pz);
    }

    /**
     * 시작 칸과 끝 칸을 뺀, 선분이 지나는 칸들이 모두 가리지 않는 칸인지 확인한다.
     * 선분이 지나는 칸을 순서대로 빠짐없이 방문하는 격자 순회(Amanatides & Woo) 방식이다.
     */
    static boolean isClear(Opacity opacity, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = floor(x0);
        int y = floor(y0);
        int z = floor(z0);
        int endX = floor(x1);
        int endY = floor(y1);
        int endZ = floor(z1);

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double maxX = boundary(x0, x, stepX, dx);
        double maxY = boundary(y0, y, stepY, dy);
        double maxZ = boundary(z0, z, stepZ, dz);

        for (int steps = 0; steps < MAX_STEPS; steps++) {
            if (x == endX && y == endY && z == endZ) return true;
            if (maxX <= maxY && maxX <= maxZ) {
                if (maxX > 1.0) return true;
                x += stepX;
                maxX += deltaX;
            } else if (maxY <= maxZ) {
                if (maxY > 1.0) return true;
                y += stepY;
                maxY += deltaY;
            } else {
                if (maxZ > 1.0) return true;
                z += stepZ;
                maxZ += deltaZ;
            }
            if (x == endX && y == endY && z == endZ) return true;
            if (opacity.isOpaque(x, y, z)) return false;
        }
        return false;
    }

    // 선분 위의 매개변수 t(0~1)로 나타낸, 처음으로 다음 칸의 경계에 닿는 지점
    private static double boundary(double start, int cell, int step, double delta) {
        if (step > 0) return (cell + 1 - start) / delta;
        if (step < 0) return (start - cell) / -delta;
        return Double.POSITIVE_INFINITY;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
