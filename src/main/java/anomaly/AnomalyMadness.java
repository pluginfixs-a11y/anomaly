package anomaly;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Шиза, слои 1–2: звук и фигура. Работает только на серверных каналах, клиенту ставить нечего.
 * <p>
 * Включается не «вообще», а состоянием игрока: остаток (от «дрожи» и выше) или доза в зоне.
 * Сила — максимум из двух, и чем она больше, тем чаще события: сначала редкие шаги за спиной,
 * потом копка под ногами, шёпот, эхо, чужой сундук; на пике — фигура вдалеке.
 * <p>
 * Место важно: ближе {@code madness_min_distance} от центра мира шиза не включается вовсе —
 * у дома должно быть тихо, иначе мод портит обычную игру.
 * <p>
 * Фигура — <b>частицы, а не сущность</b>. Армор-стенд читался днём как манекен (без экипировки,
 * с плитой, неуязвимый), его пришлось бы убирать руками, а после краша сервера он остался бы
 * в мире навсегда. Силуэт из пыли исчезает вместе с игроком и не оставляет следов — по тем же
 * причинам, по которым на краю зоны нет сущностей. Ложного тотема нет: обманка, которая играет
 * звук настоящего спасения и не спасает, учит игнорировать настоящий тотем.
 * <p>
 * Всё, что здесь происходит, возвращается к правде само: фигура исчезает, когда к ней подходят.
 * Текста в интерфейсе нет.
 */
public final class AnomalyMadness {
	private static final int MAX_FIGURE_MS = 45_000;
	private static final double FIGURE_RANGE = 8.0;

	/** Одна фигура на игрока: больше — это уже не «что-то вдалеке», а толпа. */
	private static final Map<UUID, Figure> FIGURES = new HashMap<>();

	private record Figure(double x, double y, double z, long spawnedMs) {
	}

	private AnomalyMadness() {
	}

	// --- чистая часть: сила и окна ---

	/**
	 * Сила шизы 0..1. Ноль означает «тихо»: игрок в своём доме, у него нет остатка и он не в зоне.
	 */
	static double intensity(double residueHours, double dose, double distance, double minDistance) {
		if (!Double.isFinite(distance) || distance < minDistance) {
			return 0.0;
		}
		double fromResidue = (residueHours - AnomalyResidue.TREMOR)
				/ Math.max(1.0, AnomalyResidue.DEEP_HUNGER - AnomalyResidue.TREMOR);
		double fromDose = (dose - 0.25) / 0.75;
		double value = Math.max(fromResidue, fromDose);
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}

	/** Период события в секундах: чем сильнее шиза, тем чаще, но не чаще раза в 6 с. */
	static int period(int baseSeconds, double intensity) {
		if (intensity <= 0.0) {
			return Integer.MAX_VALUE;
		}
		return Math.max(6, (int) Math.round(baseSeconds / Math.max(0.15, intensity)));
	}

	static boolean window(long sec, long phase, int period, int length) {
		if (period == Integer.MAX_VALUE || period <= 0) {
			return false;
		}
		return Math.floorMod(sec + phase, period) < Math.max(1, length);
	}

	// --- шаг на игроке ---

	public static void tick(ServerPlayer player, AnomalyState state, AnomalyConfig config) {
		if (config == null || !config.enabled || !config.madness_enabled) {
			return;
		}
		ServerLevel level = player.level();
		if (level.dimension() != Level.OVERWORLD) {
			cleanup(player.getUUID());
			return;
		}
		if (player.isCreative() || player.isSpectator()) {
			cleanup(player.getUUID());
			return;
		}
		double distance = Math.hypot(player.getX(), player.getZ());
		double intensity = intensity(AnomalyResidue.hours(player), state.dose, distance,
				config.madness_min_distance);
		if (intensity <= 0.0) {
			if (distance < config.madness_min_distance) {
				cleanup(player.getUUID());
			}
			return;
		}
		long sec = System.currentTimeMillis() / 1000L;
		long phase = Math.floorMod(player.getUUID().getLeastSignificantBits(), 3600L);
		// слой 1: звук. Он дешевле всего и работает всегда — это и есть «шиза» на минималке
		if (window(sec, phase, period(45, intensity), 1)) {
			sounds(level, player, intensity);
		}
		// слой 2: фигура вдалеке. Появляется не чаще раза в полторы-три минуты
		if (window(sec, phase + 17L, period(150, intensity), 2)) {
			figure(level, player);
		}
		tickFigure(level, player);
	}

	/** Один звук из пяти: шаги за спиной, копка, шёпот, эхо, чужой сундук. */
	private static void sounds(ServerLevel level, ServerPlayer player, double intensity) {
		var rnd = player.getRandom();
		double yaw = Math.toRadians(player.getYRot());
		// вперёд в 26.2: (-sin yaw, cos yaw), поэтому за спиной — плюс эти же оси
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double side = (rnd.nextDouble() - 0.5) * 4.0;
		double bx = player.getX() - fx * 3.5 + fz * side;
		double bz = player.getZ() - fz * 3.5 - fx * side;
		switch (rnd.nextInt(5)) {
			case 0 -> level.playSound(null, bx, player.getY(), bz, SoundEvents.GRASS_STEP,
					SoundSource.AMBIENT, 0.35f + 0.2f * (float) intensity, 0.7f);
			case 1 -> level.playSound(null, player.getX() + (rnd.nextDouble() - 0.5) * 10.0,
					player.getY() - 1.0, player.getZ() + (rnd.nextDouble() - 0.5) * 10.0,
					SoundEvents.BONE_BLOCK_BREAK, SoundSource.AMBIENT, 0.3f, 0.6f);
			case 2 -> level.playSound(null, bx, player.getY() + 0.4, bz, SoundEvents.SCULK_CLICKING,
					SoundSource.AMBIENT, 0.3f, 1.5f);
			case 3 -> level.playSound(null, player.getX() + (rnd.nextDouble() - 0.5) * 12.0,
					player.getY(), player.getZ() + (rnd.nextDouble() - 0.5) * 12.0,
					SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 0.4f, 0.8f);
			default -> level.playSound(null, bx * 1.4 - player.getX() * 0.4, player.getY() + 0.5,
					bz * 1.4 - player.getZ() * 0.4, SoundEvents.CHEST_OPEN, SoundSource.AMBIENT, 0.35f, 0.8f);
		}
	}

	/**
	 * Фигура: место запоминается, а рисуется силуэт из пыли — голова, плечи, туловище, ноги.
	 * Исчезает, когда подходишь ближе {@link #FIGURE_RANGE}, или через 45 с.
	 */
	private static void figure(ServerLevel level, ServerPlayer player) {
		if (FIGURES.containsKey(player.getUUID())) {
			return;
		}
		var rnd = player.getRandom();
		double yaw = Math.toRadians(player.getYRot());
		double angle = yaw + (rnd.nextDouble() - 0.5) * 0.9;
		double dist = 18.0 + rnd.nextDouble() * 10.0;
		double x = player.getX() - Math.sin(angle) * dist;
		double z = player.getZ() + Math.cos(angle) * dist;
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		if (y <= level.getMinY() + 2 || y >= level.getMaxY() - 4) {
			return;
		}
		FIGURES.put(player.getUUID(), new Figure(x, y, z, System.currentTimeMillis()));
	}

	/** Рисуем силуэт раз в эффектный тик; ушла — стираем. Никаких сущностей в мире. */
	private static void tickFigure(ServerLevel level, ServerPlayer player) {
		Figure figure = FIGURES.get(player.getUUID());
		if (figure == null) {
			return;
		}
		long age = System.currentTimeMillis() - figure.spawnedMs();
		double dx = figure.x() - player.getX();
		double dz = figure.z() - player.getZ();
		if (age > MAX_FIGURE_MS || dx * dx + dz * dz < FIGURE_RANGE * FIGURE_RANGE) {
			FIGURES.remove(player.getUUID());
			return;
		}
		// тёмная пыль: десять точек дают читаемый человеческий силуэт в 20–30 блоках
		var ink = new DustParticleOptions(0x0C0C12, 1.8f);
		double x = figure.x();
		double y = figure.y();
		double z = figure.z();
		double yaw = Math.atan2(player.getX() - x, player.getZ() - z);
		double shoulder = Math.sin(yaw + Math.PI / 2.0) * 0.28;
		double shoulderZ = Math.cos(yaw + Math.PI / 2.0) * 0.28;
		level.sendParticles(player, ink, true, true, x, y + 1.75, z, 2, 0.10, 0.10, 0.10, 0.0);
		level.sendParticles(player, ink, true, true, x - shoulder, y + 1.35, z - shoulderZ,
				1, 0.06, 0.10, 0.06, 0.0);
		level.sendParticles(player, ink, true, true, x + shoulder, y + 1.35, z + shoulderZ,
				1, 0.06, 0.10, 0.06, 0.0);
		level.sendParticles(player, ink, true, true, x, y + 1.20, z, 3, 0.16, 0.22, 0.16, 0.0);
		level.sendParticles(player, ink, true, true, x - 0.12, y + 0.55, z, 1, 0.05, 0.28, 0.05, 0.0);
		level.sendParticles(player, ink, true, true, x + 0.12, y + 0.55, z, 1, 0.05, 0.28, 0.05, 0.0);
		level.sendParticles(player, ink, true, true, x, y + 0.08, z, 1, 0.10, 0.05, 0.10, 0.0);
	}

	/** Убрать фигуру игрока: при выходе, смерти, телепорте и когда шиза выключилась. */
	public static void cleanup(UUID player) {
		FIGURES.remove(player);
	}

	/** Сколько фигур сейчас живо: для команды и самопроверки. */
	public static int figures() {
		return FIGURES.size();
	}

	/** Отладочный показ: один звук и фигура — не дожидаясь остатка в 12 часов. */
	public static void demo(ServerPlayer player) {
		ServerLevel level = player.level();
		sounds(level, player, 0.8);
		figure(level, player);
	}
}

