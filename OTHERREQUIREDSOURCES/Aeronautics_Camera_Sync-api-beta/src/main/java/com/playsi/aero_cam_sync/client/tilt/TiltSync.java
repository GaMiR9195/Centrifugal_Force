package com.playsi.aero_cam_sync.client.tilt;

import com.playsi.aero_cam_sync.ServerTiltStore;
import com.playsi.aero_cam_sync.SideManager;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.network.Payload.TiltSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;

/**
 * Отправка наклона на сервер — вторая половина бывшего {@code SideManager} (долг 12.7).
 *
 * <p>Тот класс делал две работы: держал состояние стороны и слал тилт. Из-за второй он тянул
 * {@link Minecraft} и {@link CameraController} в общий пакет — то есть общий по расположению
 * класс был клиентским по зависимостям. Здесь эта половина лежит там, где ей место, а
 * {@code SideManager} остался чистым состоянием.</p>
 *
 * <p>Зовётся раз в клиентский тик из {@code AeroCamSyncClient}.</p>
 */
public final class TiltSync {

    private TiltSync() {}

    /**
     * Кладёт текущий наклон туда, где его увидит авторитетная сторона: напрямую в
     * {@link ServerTiltStore} в одиночке, пакетом — в мультиплеере с модом на сервере.
     */
    public static void sendToServer() {
        Minecraft mc = Minecraft.getInstance();
        boolean enabled = CameraController.shouldApplyTilt();
        // Два НЕЗАВИСИМЫХ флага: поворот камеры наклоняет направление снаряда/взгляда,
        // сдвиг позиции — точку вылета. Любой из них может работать в одиночку.
        //
        // Спрашиваем те же методы, что и камера с лучами, а не настройки напрямую: под чужим
        // источником настройки наклон не режут, и прочти мы их здесь отдельно — сервер считал бы
        // попадания по наклону, которого игрок не видит.
        boolean rotActive = enabled && CameraController.rotationActive();
        boolean posShift  = enabled && CameraController.posShiftActive();
        boolean dropFromCamera = Config.DROP_FROM_CAMERA.get();
        boolean present = rotActive || posShift;
        Quaternionf q = present
                ? new Quaternionf(CameraController.getSmoothedTilt())
                : new Quaternionf();

        if (mc.hasSingleplayerServer()) {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null && mc.player != null) {
                ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (sp != null) {
                    if (present) {
                        ServerTiltStore.set(sp.getUUID(), q, rotActive, posShift, dropFromCamera);
                    } else {
                        ServerTiltStore.clear(sp.getUUID()); // раньше было set(..., null) → NPE
                    }
                }
            }
            return;
        }

        // Мультиплеер с модом на сервере
        if (SideManager.getSide() != SideManager.Side.CLIENT_SERVER) return;
        if (mc.getConnection() == null) return;

        PacketDistributor.sendToServer(TiltSyncPayload.from(q, rotActive, posShift, dropFromCamera));
    }
}
