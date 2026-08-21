package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.AcsHandle;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Дескрипторы по {@code modId}, один на мод.
 *
 * <p>{@link ConcurrentHashMap}: {@code forMod} мод может позвать откуда угодно (статический
 * инициализатор, клиентский setup, серверный тик), а карта одна на обе стороны.</p>
 */
public final class HandleRegistry {

    private HandleRegistry() {}

    private static final ConcurrentHashMap<String, AcsHandleImpl> HANDLES = new ConcurrentHashMap<>();

    public static AcsHandle forMod(String modId) {
        // Ошибка программиста, а не пользователя: падать надо здесь и громко, иначе в логе
        // появится дескриптор с пустым именем и вся диагностика по modId потеряет смысл.
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("AeroCamSyncApi.forMod: modId must not be null or blank");
        }
        return HANDLES.computeIfAbsent(modId, AcsHandleImpl::new);
    }

    /** Все выданные дескрипторы — для сводки обращений. */
    static Collection<AcsHandleImpl> all() {
        return HANDLES.values();
    }
}
