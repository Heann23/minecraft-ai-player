package me.herry.minecraftAI.ai.util;

/**
 * 블록 좌표. Bukkit 에 의존하지 않아서 경로 탐색이나 기억처럼 순수 계산만 하는 코드에서 그대로 쓸 수 있다.
 */
public record BlockPoint(int x, int y, int z) {

    public BlockPoint offset(int dx, int dy, int dz) {
        return new BlockPoint(x + dx, y + dy, z + dz);
    }

    public double distanceSq(BlockPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(BlockPoint other) {
        return Math.sqrt(distanceSq(other));
    }

    public long pack() {
        return pack(x, y, z);
    }

    // x, z 는 26비트, y 는 12비트로 접어서 하나의 long 키로 만든다.
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // 저장 파일에 쓰는 표기. parse() 로 되돌릴 수 있다.
    public String encode() {
        return x + "," + y + "," + z;
    }

    /**
     * @throws IllegalArgumentException 형식이 맞지 않을 때 (NumberFormatException 포함)
     */
    public static BlockPoint parse(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) throw new IllegalArgumentException("not a block point: " + text);
        return new BlockPoint(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
