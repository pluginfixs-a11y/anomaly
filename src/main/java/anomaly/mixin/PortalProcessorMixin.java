package anomaly.mixin;

import anomaly.AnomalyPortals;
import anomaly.AnomalyResidue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * «Печать ада»: с остатком 8+ часов портал в Нижний не берёт игрока.
 * <p>
 * Fabric API не даёт события «можно ли пройти через портал»: телепортация живёт внутри
 * {@code PortalProcessor}, между «стоишь в портале» и «тебя перенесло». Проверить это можно
 * только в самом методе, поэтому здесь единственный инжектор мода — и он отменяет переход,
 * ничего не заменяя.
 * <p>
 * Блокируем только выход из Верхнего мира: возврат из Нижнего работает всегда, иначе игрок
 * остался бы там запертым без единого способа выбраться.
 */
@Mixin(PortalProcessor.class)
public abstract class PortalProcessorMixin {
	/**
	 * Проверяем на выходе, а не на входе: за время работы метода ваниль списывает
	 * {@code insidePortalThisTick} и накручивает {@code portalTime}. Если отменить метод целиком,
	 * нагретый процессор остаётся «внутри портала» без входного блока — и когда печать спадёт,
	 * игрока телепортирует из любой точки мира. Возврат {@code false} при этом безопасен:
	 * сам метод ничего не телепортирует, решение принимает вызывающий {@code Entity.handlePortal}.
	 */
	@Inject(method = "processPortalTeleportation", at = @At("RETURN"), cancellable = true)
	private void anomaly$refuseNether(ServerLevel level, Entity entity, boolean canUsePortal,
			CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue() && level.dimension() == Level.OVERWORLD
				&& entity instanceof ServerPlayer player && AnomalyResidue.sealsNether(player)) {
			AnomalyResidue.onPortalRefused(player);
			cir.setReturnValue(false);
			return;
		}
		// дальний портал не работает и гаснет: иначе ад — бесплатный телепорт за 10 км.
		// Предел считается в блоках Верхнего мира, из ада — ×8.
		// Гасим только при выходе ИЗ Верхнего мира: игрок, который уже забрался в ад дальше
		// 1250 блоков, должен иметь возможность вернуться, иначе он заперт в Нижнем
		if (cir.getReturnValue() && level.dimension() == Level.OVERWORLD
				&& entity instanceof ServerPlayer far
				&& AnomalyPortals.tooFar(level, far)) {
			AnomalyPortals.collapse(level, far);
			cir.setReturnValue(false);
			return;
		}
		// Обратный ход из Нижнего не запрещаем (иначе игрок заперт), но дальний портал
		// сгорает сразу после прибытия: иначе правило обходится порталом в аду за 1250 блоков
		if (cir.getReturnValue() && level.dimension() == Level.NETHER
				&& entity instanceof ServerPlayer back
				&& AnomalyPortals.tooFar(level, back)) {
			AnomalyPortals.collapseAfterArrival(back);
		}
	}
}

