package anomaly.mixin;

import anomaly.AnomalySpawnBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * На грибных блоках и бамбуке не спавнится.
 * <p>
 * Ванильная проверка «можно ли поставить моба на этот блок» спрашивает у состояния блока
 * {@code isValidSpawn}, и для шляпки гриба отвечает «да»: блок непрозрачный и полный, в отличие
 * от листвы. Здесь ответ отменяется для списка из {@link AnomalySpawnBlocks}.
 * <p>
 * Точный контракт: «на них не встаёт никто» и «под шляпкой тоже никто не встаёт». Листва
 * защищена иначе — её просто нет в {@code MOTION_BLOCKING_NO_LEAVES}, поэтому кандидат встаёт
 * на землю под кроной; шляпка в этой карте высот есть, и отбраковка колонки убирает спавн и под
 * ней. Спавнеры, trial-спавнеры и структурные спавны этот путь вообще не спрашивают — там мобы
 * ставятся как раньше.
 * <p>
 * Инжектор только возвращает {@code false}, ничего не подменяя: если блок не из списка,
 * ванильная логика идёт своим ходом.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSpawnMixin {
	@Inject(method = "isValidSpawn", at = @At("HEAD"), cancellable = true)
	private void anomaly$noSpawnOnMushroom(BlockGetter level, BlockPos pos, EntityType<?> type,
			CallbackInfoReturnable<Boolean> cir) {
		BlockState state = (BlockState) (Object) this;
		if (AnomalySpawnBlocks.blocksSpawn(state.getBlock())) {
			cir.setReturnValue(false);
		}
	}
}
