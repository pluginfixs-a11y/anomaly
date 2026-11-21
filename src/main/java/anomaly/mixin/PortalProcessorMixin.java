package anomaly.mixin;

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
	@Inject(method = "processPortalTeleportation", at = @At("HEAD"), cancellable = true)
	private void anomaly$refuseNether(ServerLevel level, Entity entity, boolean isPlayer,
			CallbackInfoReturnable<Boolean> cir) {
		if (level.dimension() == Level.OVERWORLD && entity instanceof ServerPlayer player
				&& AnomalyResidue.sealsNether(player)) {
			AnomalyResidue.onPortalRefused(player);
			cir.setReturnValue(false);
		}
	}
}
