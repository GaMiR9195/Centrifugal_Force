package com.playsi.aero_cam_sync.mixins;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Не применяет диагностические миксины в собранном моде.
 *
 * <h2>Зачем плагин, а не проверка внутри метода</h2>
 *
 * <p>Приём подсмотрен у Veil (отдельный {@code veil.debug.mixins.json}), но у него гейт стоит в
 * ТЕЛАХ миксинов — {@code isDevelopmentEnvironment()} первой строкой обработчика. Нам этого
 * мало: наши диагностические миксины мешают друг другу самим фактом применения. Живой пример —
 * {@code Method overwrite conflict for describe}: одноимённые приватные методы
 * {@code PickScopeMixin} и {@code DebugPickTailMixin} мержатся в один {@code GameRenderer}.
 * Проверка внутри тела такой конфликт не убирает, байткод-то уже вставлен. Плагин убирает: в
 * продакшене этих миксинов просто нет.</p>
 *
 * <h2>Как определяется среда</h2>
 *
 * <p>{@link FMLLoader#isProduction()} — то же, что у Veil под капотом. Различие задаёт не
 * эвристика, а launch target ModLauncher: дев-ран приходит с {@code forgeclientdev}, запуск из
 * лаунчера — с {@code forgeclient}, и дев-хендлеры помечают среду как непродакшен.</p>
 *
 * <h2>Ручной выключатель</h2>
 *
 * <p>{@code -Daero_cam_sync.debug=true} включает диагностику в РЕЛИЗНОЙ сборке,
 * {@code =false} выключает её в дев-ране. Это главное, ради чего стоило копировать Veil:
 * игрока с непонятным баг-репортом можно попросить запустить с одним JVM-аргументом и прислать
 * лог — не собирая ему специальную сборку.</p>
 */
public final class DebugMixinPlugin implements IMixinConfigPlugin {

    private static final String PROPERTY = "aero_cam_sync.debug";

    /** Логгер мода трогать нельзя: он потянул бы наши классы в слой трансформации. */
    private static final Logger LOGGER = LoggerFactory.getLogger("AeroCamSync");

    private final boolean enabled = resolve();

    private static boolean resolve() {
        String override = System.getProperty(PROPERTY);
        if (override != null) return !"false".equalsIgnoreCase(override);
        return !FMLLoader.isProduction();
    }

    @Override
    public void onLoad(String mixinPackage) {
        LOGGER.info("[AeroCamSync] diagnostic mixins: {} (-D{}=true|false to override)",
                enabled ? "ON" : "OFF", PROPERTY);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return enabled;
    }

    // ---- остальное контракта нам не нужно -----------------------------------

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode n, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode n, String m, IMixinInfo i) {}
}
