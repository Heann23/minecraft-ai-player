package me.herry.minecraftAI.nms;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * 클라이언트 없이 서버 안에서만 움직이는 플레이어.
 * 진짜 플레이어는 클라이언트가 이동을 계산해서 보내 주지만, 이 플레이어는 입력값을 받아 서버가 직접 물리를 계산한다.
 */
final class FakePlayer extends ServerPlayer {
    private static final float MAX_YAW_PER_TICK = 35.0F;
    private static final float MAX_PITCH_PER_TICK = 25.0F;

    private float inputForward;
    private float inputStrafe;
    private boolean inputJump;
    private boolean inputSprint;
    private float targetYaw;
    private float targetPitch;

    FakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    // 이동의 주도권이 클라이언트가 아니라 서버에 있다고 알려서 걷기, 낙하 피해 등이 서버에서 계산되게 한다.
    @Override
    public boolean isClientAuthoritative() {
        return false;
    }

    // 진짜 플레이어의 doTick() 은 접속(Connection)이 매 틱 불러 주는데, 가짜 접속은 틱이 돌지 않으므로 여기서 직접 부른다.
    @Override
    public void tick() {
        super.tick();
        // 차원 이동 후에는 클라이언트의 확인 패킷이 와야 "이동 중" 상태가 풀린다.
        // 가짜 플레이어는 그 패킷을 보낼 수 없으므로 직접 풀어 준다. 그대로 두면 계속 무적 상태가 된다.
        if (this.isChangingDimension()) {
            this.hasChangedDimension();
            syncLookTarget();
        }
        applyInputs();

        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        this.doTick();
        // 이동 통계와 그에 따른 허기 소모는 원래 이동 패킷 처리에서 계산된다.
        this.checkMovementStatistics(this.getX() - x, this.getY() - y, this.getZ() - z);
    }

    private void applyInputs() {
        if (this.isDeadOrDying()) {
            this.xxa = 0.0F;
            this.zza = 0.0F;
            this.setJumping(false);
            return;
        }

        this.setYRot(Mth.approachDegrees(this.getYRot(), this.targetYaw, MAX_YAW_PER_TICK));
        this.setXRot(Mth.approach(this.getXRot(), this.targetPitch, MAX_PITCH_PER_TICK));
        this.setYHeadRot(this.getYRot());

        this.xxa = this.inputStrafe;
        this.zza = this.inputForward;
        this.setJumping(this.inputJump);
        if (this.isSprinting() != this.inputSprint) this.setSprinting(this.inputSprint);
        // A connected client's movement loop applies the crouch key's water descent.
        // This body owns that loop, so use the same vanilla fluid physics here too.
        if (this.isShiftKeyDown() && this.isInWater()) this.goDownInWater();
    }

    void setMoveInput(float forward, float strafe) {
        this.inputForward = Mth.clamp(forward, -1.0F, 1.0F);
        this.inputStrafe = Mth.clamp(strafe, -1.0F, 1.0F);
    }

    void setJumpInput(boolean jumping) {
        this.inputJump = jumping;
    }

    void setSprintInput(boolean sprinting) {
        this.inputSprint = sprinting;
    }

    // 웅크린 플레이어는 서버의 이동 계산에서 블록 가장자리 밖으로 밀려 나가지 않는다.
    void setSneakInput(boolean sneaking) {
        if (this.isShiftKeyDown() != sneaking) this.setShiftKeyDown(sneaking);
    }

    void setLookTarget(float yaw, float pitch) {
        this.targetYaw = Mth.wrapDegrees(yaw);
        this.targetPitch = Mth.clamp(pitch, -90.0F, 90.0F);
    }

    // 순간이동이나 리스폰 뒤에는 현재 각도를 목표로 삼아야 몸이 엉뚱한 방향으로 돌아가지 않는다.
    void syncLookTarget() {
        this.targetYaw = this.getYRot();
        this.targetPitch = this.getXRot();
    }

    void clearInputs() {
        this.inputForward = 0.0F;
        this.inputStrafe = 0.0F;
        this.inputJump = false;
        this.inputSprint = false;
        setSneakInput(false);
        syncLookTarget();
    }
}
