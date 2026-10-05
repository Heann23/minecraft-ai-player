package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * AI 가 조종하는 몸체. AI 로직은 이 인터페이스와 Bukkit Player 만 알고, 서버 내부 코드(NMS)는 구현체 안에 숨긴다.
 * 이동은 실제 플레이어의 키 입력처럼 "입력"을 넣는 방식이며 순간이동을 쓰지 않는다.
 */
public interface AIBody {
    Player getPlayer();

    // 접속해 있고 살아 있어서 조종할 수 있는 상태인지
    boolean isUsable();

    // forward: 앞(+)/뒤(-), strafe: 왼쪽(+)/오른쪽(-). 값의 범위는 -1 ~ 1
    void inputMove(float forward, float strafe);

    void inputJump(boolean jumping);

    void inputSprint(boolean sprinting);

    // 웅크리기. 웅크린 동안에는 블록 가장자리에서 떨어지지 않는다.
    void inputSneak(boolean sneaking);

    // 목표 각도를 지정하면 몸체가 매 틱 조금씩 그 방향으로 돌아선다.
    void inputLook(float yaw, float pitch);

    void clearInputs();

    boolean isGrounded();

    // 벽 등에 수평으로 부딪혀 있는지
    boolean isBlockedHorizontally();

    /**
     * 손에 든 아이템을 바라보는 쪽으로 쓴다 (빈 양동이로 물 뜨기 등). 진짜 플레이어의 우클릭과 같은 경로를 탄다.
     * 서버가 받아들였으면 true. 무엇이 바뀌었는지는 부른 쪽이 월드와 가방을 보고 확인한다.
     */
    boolean useItem();

    default void lookAt(double x, double y, double z) {
        Location eye = getPlayer().getEyeLocation();
        double dx = x - eye.getX();
        double dy = y - eye.getY();
        double dz = z - eye.getZ();
        if (dx * dx + dz * dz < 1.0E-6) {
            inputLook(eye.getYaw(), dy > 0 ? -90.0F : 90.0F);
            return;
        }
        inputLook(Positions.yawTo(dx, dz), Positions.pitchTo(dx, dy, dz));
    }

    default boolean isFacing(double x, double y, double z, float tolerance) {
        Location eye = getPlayer().getEyeLocation();
        double dx = x - eye.getX();
        double dy = y - eye.getY();
        double dz = z - eye.getZ();
        float pitchDiff = Math.abs(Positions.pitchTo(dx, dy, dz) - eye.getPitch());
        if (dx * dx + dz * dz < 1.0E-6) return pitchDiff <= tolerance;
        float yawDiff = Math.abs(Positions.angleDifference(eye.getYaw(), Positions.yawTo(dx, dz)));
        return yawDiff <= tolerance && pitchDiff <= tolerance;
    }
}
