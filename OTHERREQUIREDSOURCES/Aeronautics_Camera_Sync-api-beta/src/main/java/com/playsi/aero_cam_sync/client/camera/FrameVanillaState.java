package com.playsi.aero_cam_sync.client.camera;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Ванильные позиция и поворот главной камеры этого кадра — снятые ДО наклона.
 *
 * <h2>Почему сохраняем, а не восстанавливаем</h2>
 *
 * <p>Обратный поворот текущим кватернионом неверен: применяемый наклон урезан
 * {@code wallScale}, а тот меняется в течение кадра. Инверсия сегодняшним значением не обязана
 * отменять то, что применили в момент записи, — разница ровно та, на которую камера успела
 * подъехать к стене. Поэтому значение пишется один раз, в {@code CameraMixin}, прямо перед
 * {@code applyTiltToCamera}, пока объект {@code Camera} ещё ванильный.</p>
 *
 * <h2>Номер кадра</h2>
 *
 * <p>{@code Camera#setup} происходит поздно в кадре, а спросить снимок могут раньше — тогда
 * честный ответ «это значение прошлого кадра». Чтобы отличить одно от другого, нужен
 * независимый счётчик кадров: {@link #beginFrame()} зовётся из {@code RenderFrameEvent.Pre},
 * то есть заведомо раньше {@code Camera#setup}, и {@link #isFresh()} сравнивает два числа.</p>
 */
public final class FrameVanillaState {

    private FrameVanillaState() {}

    private static long currentFrame = 0L;
    private static long capturedFrame = -1L;

    private static Vec3 pos = Vec3.ZERO;
    private static final Quaternionf rot = new Quaternionf();

    /** Начало кадра — из {@code RenderFrameEvent.Pre}, раньше любой камеры. */
    public static void beginFrame() {
        currentFrame++;
    }

    /** Снимок ванильной камеры. Зовётся из {@code CameraMixin} перед наложением наклона. */
    public static void capture(Camera camera) {
        pos = camera.getPosition();
        rot.set(camera.rotation());
        capturedFrame = currentFrame;
    }

    /** Было ли вообще что-то снято за сессию. */
    public static boolean hasValue() {
        return capturedFrame >= 0;
    }

    /** Снято ли значение в этом кадре (а не в прошлом). */
    public static boolean isFresh() {
        return capturedFrame == currentFrame;
    }

    /** Копия — наружу уходит в публичный снимок, портить общее состояние ему нельзя. */
    public static Vec3 pos() {
        return pos;
    }

    /** Копия по той же причине: {@code Quaternionf} мутабелен. */
    public static Quaternionf rot() {
        return new Quaternionf(rot);
    }

    /** Смена мира: прошлокадровое значение из другого мира хуже отсутствующего. */
    public static void reset() {
        capturedFrame = -1L;
        pos = Vec3.ZERO;
        rot.identity();
    }
}
