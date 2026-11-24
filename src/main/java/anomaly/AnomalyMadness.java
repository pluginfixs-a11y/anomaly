package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Шиза, слои 1–2: звук и фигуры. Работает только на серверных каналах, клиенту ставить нечего.
 * <p>
 * Включается не «вообще», а состоянием игрока: остаток (от «дрожи» и выше) или доза в зоне.
 * Сила — максимум из двух, и чем она больше, тем чаще события: сначала редкие шаги за спиной,
 * потом копка под ногами, шёпот, эхо, чужой сундук; на пике — фигура вдалеке и ложный тотем.
 * <p>
 * Место важно: ближе {@code madness_min_distance} от центра мира шиза не включается вовсе —
 * у дома должно быть тихо, иначе мод портит обычную игру.
 * <p>
 * Всё, что здесь происходит, возвращается к правде само: фигура исчезает, когда к ней подходят,
 * ложный тотем ничего не лечит. Текста в интерфейсе нет.
 */
public final class AnomalyMadness {
	private static final int MAX_FIGURE_MS = 45_000;
	private static final double FIGURE_RANGE = 8.0;

	/** Одна фигура на игрока: больше — это уже не «что-то вдалеке», а толпа. */
	private static final Map<UUID, Figure> FIGURES = new HashMap<>();

	private record Figure(int entityId, long spawnedMs) {
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

	/** Счётчик вызовов: уборка сирот идёт не чаще раза в 10 минут на игрока. */
	private static int sweeps;

	public static void tick(ServerPlayer player, AnomalyState state, AnomalyConfig config) {
		if (config == null || !config.enabled || !config.madness_enabled) {
			return;
		}
		ServerLevel level = player.level();
		if (level.dimension() != Level.OVERWORLD) {
			return;
		}
		// уборка сирот прошлой сессии: чанк мог загрузиться раньше, чем сущность попала в список
		if (++sweeps % 1200 == 0) {
			sweepNear(level, player);
		}
		if (player.isCreative() || player.isSpectator()) {
			cleanup(player.getUUID());
			return;
		}
		double distance = Math.hypot(player.getX(), player.getZ());
		double intensity = intensity(AnomalyResidue.hours(player), state.dose, distance,
				config.madness_min_distance);
		if (intensity <= 0.0) {
			// вернулся к дому — фигура не должна остаться стоять в поле
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
		// ложный тотем: только на пике, когда игрок уже «не в себе» (высокая доза)
		if (state.dose >= 0.6 && player.getHealth() > 8.0f
				&& window(sec, phase + 29L, period(240, intensity), 1)) {
			fakeTotem(level, player);
		}
		tickFigure(player);
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
	 * Фигура: армор-стенд в 18–28 блоках, лицом к игроку. Исчезает, когда подходишь ближе
	 * {@link #FIGURE_RANGE}, или через 45 с — то есть «была и нет», без следов.
	 */
	private static void figure(ServerLevel level, ServerPlayer player) {
		UUID key = player.getUUID();
		Figure existing = FIGURES.get(key);
		if (existing != null && level.getEntity(existing.entityId()) != null) {
			return;
		}
		FIGURES.remove(key);
		var rnd = player.getRandom();
		double yaw = Math.toRadians(player.getYRot());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
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
		ArmorStand stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.TRIGGERED);
		if (stand == null) {
			return;
		}
		float facing = (float) (Math.toDegrees(Math.atan2(player.getX() - x, player.getZ() - z)) - 180.0);
		stand.snapTo(x, y + 0.1, z, facing, 0.0f);
		stand.setInvulnerable(true);
		stand.setSilent(true);
		stand.setNoGravity(false);
		stand.addTag(TAG);
		level.addFreshEntity(stand);
		FIGURES.put(key, new Figure(stand.getId(), System.currentTimeMillis()));
	}

	/** Фигура уходит, когда к ней подошли или когда истекло время: правда восстанавливается сама. */
	private static void tickFigure(ServerPlayer player) {
		Figure figure = FIGURES.get(player.getUUID());
		if (figure == null) {
			return;
		}
		var entity = player.level().getEntity(figure.entityId());
		if (entity == null) {
			FIGURES.remove(player.getUUID());
			return;
		}
		long age = System.currentTimeMillis() - figure.spawnedMs();
		if (age > MAX_FIGURE_MS || entity.distanceToSqr(player) < FIGURE_RANGE * FIGURE_RANGE
				|| player.level().dimension() != Level.OVERWORLD) {
			entity.discard();
			FIGURES.remove(player.getUUID());
		}
	}

	/** Ложный тотем: анимация и звук настоящего, а лечения нет. Обманка, а не спасение. */
	private static void fakeTotem(ServerLevel level, ServerPlayer player) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TOTEM_USE,
				SoundSource.PLAYERS, 1.0f, 1.0f);
		level.sendParticles(player, ParticleTypes.TOTEM_OF_UNDYING, true, true,
				player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 0.8, 0.5, 0.3);
	}

	/** Убрать фигуру игрока: при выходе, смерти, телепорте и когда шиза выключилась. */
	public static void cleanup(UUID player) {
		Figure figure = FIGURES.remove(player);
		if (figure == null) {
			return;
		}
		var server = AnomalyMod.server();
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		var entity = overworld.getEntity(figure.entityId());
		if (entity != null) {
			entity.discard();
		}
	}

	/** Сколько фигур сейчас живо: для команды и самопроверки. */
	public static int figures() {
		return FIGURES.size();
	}

	/** Тег фигуры: по нему убираются сироты прошлой сессии. */
	static final String TAG = "anomaly:figure";

	/** Живая ли это фигура текущей сессии (а не сирота из чанка). */
	static boolean known(int entityId) {
		for (Figure figure : FIGURES.values()) {
			if (figure.entityId() == entityId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Уборка сирот: сервер упал или его выключили с фигурой на поле — армор-стенд остался бы
	 * в мире навсегда и читался бы как чужая постройка. При загрузке чанка убираем всех
	 * помеченных, кого не знает текущая сессия. Запрос ограничен одним чанком.
	 */
	public static void sweep(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
		var pos = chunk.getPos();
		AABB box = new AABB(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(),
				pos.getMaxBlockX() + 1, level.getMaxY(), pos.getMaxBlockZ() + 1);
		for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box,
				entity -> entity.entityTags().contains(TAG))) {
			if (!known(stand.getId())) {
				stand.discard();
			}
		}
	}

	/**
	 * Уборка сирот рядом с игроком: сервер упал или его выключили с фигурой на поле — армор-стенд
	 * остался бы в мире навсегда и читался бы как чужая постройка. Раз в 10 минут на игрока,
	 * запрос ограничен 64 блоками: это дешевле, чем сканировать чанки при каждой загрузке.
	 */
	static void sweepNear(ServerLevel level, ServerPlayer player) {
		AABB box = player.getBoundingBox().inflate(64.0);
		for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box,
				entity -> entity.entityTags().contains(TAG))) {
			if (!known(stand.getId())) {
				stand.discard();
			}
		}
	}

	/** Отладочный показ: один звук, фигура и ложный тотем — не дожидаясь остатка в 12 часов. */
	public static void demo(ServerPlayer player) {
		ServerLevel level = player.level();
		sounds(level, player, 0.8);
		figure(level, player);
		fakeTotem(level, player);
	}
}
