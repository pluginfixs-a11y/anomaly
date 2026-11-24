package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Дальние порталы: портал, который ведёт за предел поля, не работает и рассыпается.
 * <p>
 * В Нижнем мире 1 блок — это 8 блоков Верхнего, поэтому «дальность» портала считается в
 * эквиваленте Верхнего мира: портал на 1.4 тыс. блоков ада — это 11 км, то есть дальше
 * границы, где аномалии становятся смертельными. Иначе ад работал бы как бесплатный
 * телепорт в дальние земли: три минуты по коридору — и ты за 10 км без дозы, времени и риска.
 * <p>
 * Гасим только блоки портала, а не обсидиановую раму: игрок сам решает, разбирать её или нет,
 * а зажечь снова можно — портал погаснет опять, пока стоит за пределом.
 */
public final class AnomalyPortals {
	private AnomalyPortals() {
	}

	/** Эквивалент расстояния в блоках Верхнего мира: ад идёт 1:8. */
	public static double equivalent(ServerLevel level, double x, double z) {
		double distance = Math.hypot(x, z);
		return level.dimension() == Level.NETHER ? distance * 8.0 : distance;
	}

	/** Предел в блоках Верхнего мира; 0 — правило выключено. */
	public static int limit() {
		AnomalyConfig config = AnomalyMod.config;
		return config == null ? 10_000 : config.portal_limit;
	}

	public static boolean tooFar(ServerLevel level, ServerPlayer player) {
		int limit = limit();
		return limit > 0 && equivalent(level, player.getX(), player.getZ()) > limit;
	}

	/**
	 * Погасить портал вокруг точки: блоки в кубе 9×7×9 (портал максимум 4×5 блоков, но игрок
	 * может стоять в углу многоблочной рамы). Обсидиан не трогаем — рама остаётся.
	 */
	public static int collapseAt(ServerLevel level, BlockPos center) {
		int removed = 0;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -3, -4), center.offset(4, 3, 4))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.NETHER_PORTAL)) {
				level.removeBlock(pos.immutable(), false);
				removed++;
			}
		}
		if (removed > 0) {
			level.playSound(null, center.getX() + 0.5, center.getY(), center.getZ() + 0.5,
					SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.1f, 0.45f);
			level.playSound(null, center.getX() + 0.5, center.getY(), center.getZ() + 0.5,
					SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.9f, 0.55f);
		}
		return removed;
	}

	/** То же для игрока: погасить и не дать войти снова сразу же. */
	public static void collapse(ServerLevel level, ServerPlayer player) {
		int removed = collapseAt(level, player.blockPosition());
		player.setPortalCooldown(80);
		if (removed > 0) {
			AnomalyMod.LOGGER.info("Anomaly: портал за пределом {} погашен у {}",
					Math.round(equivalent(level, player.getX(), player.getZ())),
					player.getName().getString());
		}
	}
}
