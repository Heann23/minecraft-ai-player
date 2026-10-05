package me.herry.minecraftAI.nms;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AISkin;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * FakePlayer 를 AIBody 로 감싼 구현체. 서버 내부 코드(NMS)를 쓰는 곳은 이 패키지뿐이다.
 */
public final class FakePlayerBody implements AIBody {
    // 겉옷, 모자 등 스킨의 모든 부위를 표시한다.
    private static final int ALL_SKIN_PARTS = 0x7F;

    private final FakePlayer handle;
    private final FakeConnection connection;
    private boolean removed;

    private FakePlayerBody(FakePlayer handle, FakeConnection connection) {
        this.handle = handle;
        this.connection = connection;
    }

    /**
     * 가짜 플레이어를 만들어 서버에 접속시킨다. 메인 스레드에서만 호출해야 한다.
     */
    public static FakePlayerBody spawn(String name, Location location, int viewDistance, @Nullable AISkin skin) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        GameProfile profile = UUIDUtil.createOfflineProfile(name);
        if (skin != null) {
            // 스킨은 프로필의 textures 속성으로 클라이언트에 전달된다.
            Property textures = new Property("textures", skin.value(), skin.signature());
            profile = new GameProfile(profile.id(), profile.name(), new PropertyMap(ImmutableMultimap.of("textures", textures)));
        }
        ClientInformation information = new ClientInformation(
                "ko_kr", viewDistance, ChatVisiblity.FULL, true, ALL_SKIN_PARTS, HumanoidArm.RIGHT, false, true, ParticleStatus.MINIMAL
        );

        FakePlayer player = new FakePlayer(server, level, profile, information);
        player.snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        player.syncLookTarget();

        FakeConnection connection = new FakeConnection();
        CommonListenerCookie cookie = new CommonListenerCookie(
                profile, 0, information, false, null, new java.util.HashSet<>(), new io.papermc.paper.util.KeepAlive()
        );
        server.getPlayerList().placeNewPlayer(connection, player, cookie);

        Player bukkitPlayer = player.getBukkitEntity();
        bukkitPlayer.setGameMode(GameMode.SURVIVAL);
        // 다른 플레이어가 잠을 자서 밤을 넘길 때 AI 때문에 막히지 않게 한다.
        bukkitPlayer.setSleepingIgnored(true);
        return new FakePlayerBody(player, connection);
    }

    /**
     * 플레이어를 서버에서 내보낸다. 진짜 플레이어가 나갈 때와 같은 절차(퇴장 이벤트, 데이터 저장)를 거친다.
     */
    public void remove() {
        if (removed) return;
        removed = true;
        handle.clearInputs();
        if (handle.connection != null && !handle.hasDisconnected()) {
            handle.connection.onDisconnect(new DisconnectionDetails(Component.literal("AI removed")));
        }
        connection.close();
    }

    /**
     * 죽은 플레이어를 리스폰 지점에서 되살린다. 클라이언트가 "리스폰" 버튼을 눌렀을 때 서버가 하는 일과 같다.
     */
    public boolean respawn() {
        if (removed || handle.hasDisconnected() || handle.getHealth() > 0.0F) return false;
        handle.clearInputs();
        handle.level().getServer().getPlayerList().respawn(handle, false, Entity.RemovalReason.KILLED, PlayerRespawnEvent.RespawnReason.DEATH);
        handle.connection.resetPosition();
        handle.connection.restartClientLoadTimerAfterRespawn();
        handle.syncLookTarget();
        return true;
    }

    public boolean isRemoved() {
        return removed;
    }

    @Override
    public Player getPlayer() {
        return handle.getBukkitEntity();
    }

    @Override
    public boolean isUsable() {
        return !removed && !handle.hasDisconnected() && handle.isAlive() && handle.valid;
    }

    @Override
    public void inputMove(float forward, float strafe) {
        handle.setMoveInput(forward, strafe);
    }

    @Override
    public void inputJump(boolean jumping) {
        handle.setJumpInput(jumping);
    }

    @Override
    public void inputSprint(boolean sprinting) {
        handle.setSprintInput(sprinting);
    }

    @Override
    public void inputSneak(boolean sneaking) {
        handle.setSneakInput(sneaking);
    }

    @Override
    public void inputLook(float yaw, float pitch) {
        handle.setLookTarget(yaw, pitch);
    }

    @Override
    public void clearInputs() {
        handle.clearInputs();
    }

    @Override
    public boolean isGrounded() {
        return handle.onGround();
    }

    @Override
    public boolean isBlockedHorizontally() {
        return handle.horizontalCollision;
    }

    // 클라이언트가 우클릭 패킷을 보냈을 때 서버가 타는 경로를 그대로 부른다. 시선은 지금의 회전으로 계산된다.
    @Override
    public boolean useItem() {
        if (!isUsable()) return false;
        ItemStack stack = handle.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) return false;
        return handle.gameMode.useItem(handle, handle.level(), stack, InteractionHand.MAIN_HAND).consumesAction();
    }
}
