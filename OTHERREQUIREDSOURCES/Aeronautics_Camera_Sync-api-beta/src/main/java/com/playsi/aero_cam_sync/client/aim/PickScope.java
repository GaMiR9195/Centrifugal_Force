package com.playsi.aero_cam_sync.client.aim;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Окно, внутри которого «глаз игрока» — это позиция наклонённой камеры.
 *
 * <p>Открывается ровно вокруг вызова {@code GameRenderer#pick(F)} в {@code renderLevel}
 * ({@code PickScopeMixin}) и закрывается сразу после. Внутрь попадает весь пик целиком:
 * ванильный блочный и сущностный лучи, обёртки Cut Through, TAIL-инжекты сторонних модов
 * (throttle lever из Create Simulated). Никто из них не знает про ACS — они просто получают
 * origin, совпадающий с перекрестием.</p>
 *
 * <p>Почему окно, а не глобальная подмена: тот же хелпер Sable зовут рендерные пути
 * (световой пробник сущностей, смещение рендера). Подмена вне пика сдвинула бы освещение
 * и ники.</p>
 *
 * <p>Состояние статическое и только для главного потока — пик живёт в рендер-потоке.
 * Фоновые лучи (Sound Physics Perfected) сюда не заходят, потому что открывает окно только
 * рендер-поток, а читатель дополнительно проверяет поток.</p>
 */
public final class PickScope {

    private PickScope() {}

    private static boolean active = false;

    @Nullable
    private static Vec3 origin = null;

    /** Сколько раз за последний пик подменили точку глаза. Только для диагностики. */
    private static int substitutions = 0;

    public static void open(Vec3 pickOrigin) {
        origin = pickOrigin;
        substitutions = 0;
        active = true;
    }

    public static void close() {
        active = false;
        origin = null;
    }

    /** Зовётся из {@code SableEyeMixin} при каждой удачной подмене. */
    public static void countSubstitution() {
        substitutions++;
    }

    /**
     * Сколько подмен пришлось на последний пик. Ноль означает, что окно открывалось, но
     * до воронки Sable дело не дошло — то есть луч построили мимо неё.
     */
    public static int substitutions() {
        return substitutions;
    }

    public static boolean isActive() {
        return active;
    }

    /** Позиция камеры на время пика, либо {@code null} — окно закрыто. Только для отладки. */
    @Nullable
    public static Vec3 origin() {
        return origin;
    }
}
