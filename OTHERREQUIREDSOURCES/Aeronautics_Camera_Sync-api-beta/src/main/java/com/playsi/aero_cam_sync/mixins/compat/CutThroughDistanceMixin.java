package com.playsi.aero_cam_sync.mixins.compat;

import com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess;
import com.bawnorton.mixinsquared.TargetHandler;
import com.playsi.aero_cam_sync.client.config.Config;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Совместимость с Cut Through: его выбор «что ближе» — на саблевел-осведомлённую метрику.
 *
 * <h2>Кто и что делает с пиком — полный порядок</h2>
 *
 * <p><b>1. Ваниль.</b> {@code GameRenderer#pick(F)} зовёт приватный
 * {@code GameRenderer#pick(Entity,DDF)}, и тот считает два луча от одной точки:</p>
 * <pre>
 *   eye        = entity.getEyePosition(pt)
 *   blockHit   = entity.pick(d, pt, false)                 // блочный луч, OUTLINE
 *   f          = blockHit.getLocation().distanceToSqr(eye) // насколько далеко упёрлись
 *   entityHit  = ProjectileUtil.getEntityHitResult(...)    // сущностный луч, не дальше f
 *   return entityHit != null &amp;&amp; entityHit.getLocation().distanceToSqr(eye) &lt; f
 *              ? filterHitResult(entityHit, eye, entityRange)
 *              : filterHitResult(blockHit, eye, blockRange);
 * </pre>
 * <p>То есть ваниль сравнивает кандидатов расстоянием до глаза и обрезает результат по
 * дальности взаимодействия. Все три числа считаются в мировых координатах — в ванили других
 * систем координат нет.</p>
 *
 * <p><b>2. Sable.</b> Саблевел живёт в своём «плоте» — отдельном куске мира далеко от игрока
 * (координаты порядка 20 млн). Клип перехвачен, и для попадания по палубе он возвращает
 * {@code BlockHitResult} с точкой в ЛОКАЛЬНЫХ (plot) координатах — иначе взаимодействие
 * указывало бы не на тот блок. Поэтому Sable точечно чинит всё, что после этого меряет
 * расстояния ({@code clip_overwrite/GameRendererMixin}, {@code clip_overwrite/EntityMixin}):</p>
 * <ul>
 *   <li>{@code Entity#pick} и {@code GameRenderer#pick(Entity,DDF)}: {@code getEyePosition}
 *       → {@code Sable.HELPER.getEyePositionInterpolated} (воронка);</li>
 *   <li>{@code GameRenderer#pick(Entity,DDF)}: оба {@code Vec3#distanceToSqr}
 *       → {@code distanceSquaredWithSubLevels} (проецирует обе точки в мир и меряет честно);</li>
 *   <li>{@code GameRenderer#filterHitResult}: {@code Vec3#closerThan} → та же метрика.</li>
 * </ul>
 * <p>Все селекторы Sable привязаны к именам ванильных методов — и это та щель, в которую
 * проваливается Cut Through.</p>
 *
 * <p><b>3. Cut Through.</b> Вклинивается внутрь того же {@code pick(Entity,DDF)} двумя
 * обработчиками ({@code pick$0}, {@code pick$1} в
 * {@code fuzs.cutthrough.mixin.client.GameRendererMixin}) и добавляет третьего кандидата:
 * свой луч с {@code ClipContext.Block.COLLIDER} из {@code GameRendererPickHelper#pick},
 * который проходит сквозь блоки без коллизии (трава, костры). Если этот луч упёрся ДАЛЬШЕ
 * ванильного — он и побеждает, а ванильный убирается в {@code LocalRef} для сравнения
 * с сущностью в {@code pick$1}. Сравнение — ванильный {@code Vec3#distanceToSqr} от
 * ванильного {@code Entity#getEyePosition}: тела обработчиков живут отдельными методами,
 * и правки Sable, привязанные к именам ванильных методов, до них не достают.</p>
 *
 * <p><b>Отсюда баг, который выглядит как наш (§1.5 подводных камней).</b> На палубе
 * {@code blockHit} и хит Cut Through оба в plot-координатах, а {@code eye} — мировой:
 * оба расстояния — мусор порядка 20 млн², и какое из них больше, решает случай. Cut Through
 * на саблевелах то работает, то нет, в зависимости от угла. Воспроизводится без ACS вообще.</p>
 *
 * <p><b>4. ACS.</b> {@code PickScopeMixin} оборачивает {@code pick(F)} целиком, а
 * {@code SableEyeMixin} добавляет наклонную поправку на выходе воронки — так все, кто ходит
 * через воронку, целятся туда же, куда смотрит наклонённая камера. Cut Through через воронку
 * НЕ ходит, поэтому:</p>
 * <ul>
 *   <li>{@link CutThroughEyeMixin} заворачивает в воронку начало его собственного луча
 *       (в {@code GameRendererPickHelper#pick});</li>
 *   <li><b>этот миксин</b> чинит вторую половину — метрику и точку отсчёта в обработчиках.
 *       Обе половины нужны по отдельности: верный origin не делает сравнение честным, а
 *       честное сравнение не спасает луч, пущенный не оттуда.</li>
 * </ul>
 *
 * <h2>Почему тут MixinSquared, а не обычный селектор</h2>
 *
 * <p>Обработчики Cut Through объявлены с сахаром {@code @Local(ref = true)}, и MixinExtras
 * вставляет их в {@code GameRenderer} не при мерже миксина, а уже в фазе применения
 * инжекторов. Обычный инжектор ищет цели раньше — в момент мержа своего миксина, — поэтому
 * их для него не существует НИ ПРИ КАКОМ приоритете. Проверено вживую 2026-08-06 выгрузкой
 * трансформированного класса ({@code -Dmixin.debug.export=true}):</p>
 * <ul>
 *   <li>{@code priority = 500} (мержимся до Cut Through) — селектор {@code method = "*"}
 *       видит только ванильные вызовы; все три уходят к Sable по конфликту приоритетов,
 *       в обработчики не попадаем: «фикс есть, поведение не изменилось»;</li>
 *   <li>{@code priority = 1500} (мержимся после Cut Through) — обработчиков всё равно ещё
 *       нет; зато наши инжекторы применяются ПЕРВЫМИ (инжекторы идут по убыванию приоритета,
 *       мержи — по возрастанию) и отбирают ванильные цели у Sable. У того
 *       {@code injectors.defaultRequire = 1} → {@code MixinTransformerError} на старте,
 *       причём с именем Sable в стеке, а не нашим.</li>
 * </ul>
 *
 * <p>Целиться по имени нельзя и само по себе: в целевом классе обработчики называются
 * {@code localvar$cag000$cutthrough$pick$0} и
 * {@code modifyReturnValue$cag000$cutthrough$pick$1} — префикс зависит от вида инжектора,
 * {@code cag000} от числа обработанных классов, то есть от набора модов. Шаблонов по имени
 * в селекторах Mixin нет: хвостовая {@code *} — это квантификатор количества совпадений,
 * а имя сравнивается через {@code equals} ({@code MemberInfo#matches}).</p>
 *
 * <p>{@code @TargetHandler} из MixinSquared решает ровно эту задачу: он указывает на КЛАСС
 * миксина и ИСХОДНОЕ имя обработчика ({@code pick$0}), а применение откладывает до момента,
 * когда обработчик уже в целевом классе. {@code priority = 1500} обязателен — так работает
 * сама библиотека: её расширение должно отработать после обычных инжекторов.</p>
 *
 * <p>{@code require = 0, expect = 0} заданы явно: в {@code aero_cam_sync.mixins.json} стоит
 * {@code injectors.defaultRequireValue = 1}, а отсутствие Cut Through — штатная ситуация.
 * Цена — молчаливый отвал, если чужой обработчик переименуют; проверка живой связи вынесена
 * в {@code RELEASE-CHECKLIST.md} §8 и обязана прогоняться при бампе Cut Through, Sable
 * и MixinExtras.</p>
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class CutThroughDistanceMixin {

    /**
     * {@code pick$0}: свой COLLIDER-хит против ванильного — оба расстояния на честную метрику.
     */
    @TargetHandler(mixin = "fuzs.cutthrough.mixin.client.GameRendererMixin", name = "pick$0")
    @Redirect(
            method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"),
            require = 0,
            expect = 0
    )
    private double aero$sableDistanceOccluder(Vec3 self, Vec3 other) {
        return aero$distance(self, other);
    }

    /**
     * {@code pick$1}: сохранённый в {@code LocalRef} хит против сущностного — то же сравнение.
     */
    @TargetHandler(mixin = "fuzs.cutthrough.mixin.client.GameRendererMixin", name = "pick$1")
    @Redirect(
            method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"),
            require = 0,
            expect = 0
    )
    private double aero$sableDistanceEntity(Vec3 self, Vec3 other) {
        return aero$distance(self, other);
    }

    /**
     * Точка отсчёта в {@code pick$0} — из воронки Sable, а не ванильная.
     *
     * <p>Оба сравниваемых луча уже пущены из воронки (ванильный — самим Sable, луч Cut Through —
     * {@link CutThroughEyeMixin}), и внутри пика воронка отдаёт глаз с наклонной поправкой.
     * Мерить от ванильного глаза значило бы сравнивать расстояния от точки, из которой не
     * стреляли: при наклоне ~35° поправка около полублока — этого хватает, чтобы перевернуть
     * выбор в пограничном случае.</p>
     */
    @TargetHandler(mixin = "fuzs.cutthrough.mixin.client.GameRendererMixin", name = "pick$0")
    @Redirect(
            method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0,
            expect = 0
    )
    private Vec3 aero$sableEyeOccluder(Entity entity, float partialTick) {
        return aero$eye(entity, partialTick);
    }

    /** Точка отсчёта в {@code pick$1} — та же воронка. */
    @TargetHandler(mixin = "fuzs.cutthrough.mixin.client.GameRendererMixin", name = "pick$1")
    @Redirect(
            method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0,
            expect = 0
    )
    private Vec3 aero$sableEyeEntity(Entity entity, float partialTick) {
        return aero$eye(entity, partialTick);
    }

    private static double aero$distance(Vec3 self, Vec3 other) {
        if (aero$legacyPick()) return self.distanceToSqr(other);

        Level level = Minecraft.getInstance().level;
        if (level == null) return self.distanceToSqr(other);

        // Перегрузка с шестью double, а не с двумя Vec3, намеренно: Vec3 реализует Position,
        // поэтому вызов с объектами уходит в distanceSquaredWithSubLevels(Level,Position,Position),
        // а в DefaultSableCompanion (заглушка при неактивном Sable) она содержит опечатку —
        // считает d1/d2 как b.y()-b.y() и b.z()-b.z(), то есть возвращает только dx².
        return Sable.HELPER.distanceSquaredWithSubLevels(level,
                self.x, self.y, self.z, other.x, other.y, other.z);
    }

    private static Vec3 aero$eye(Entity entity, float partialTick) {
        if (aero$legacyPick()) return entity.getEyePosition(partialTick);
        return Sable.HELPER.getEyePositionInterpolated(entity, partialTick);
    }

    /**
     * Под аварийным откатом поведение обязано совпадать с 1.3.6: там окклюзию считает
     * {@code legacy.GameRendererPickMixin} саблевел-осведомлённо и сам, а результат
     * Cut Through всё равно затирается — чинить его метрику незачем и вредно.
     */
    private static boolean aero$legacyPick() {
        return ClientTiltAccess.isLegacyPick();
    }
}
