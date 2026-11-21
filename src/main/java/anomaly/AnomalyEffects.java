package anomaly;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Всё, чем аномалия действует на игрока. Только ванильные каналы: эффекты, частицы, звук,
 * толчок скорости, молния. Клиент ничего не ставит, поэтому ни HUD, ни своих визуалов нет.
 * <p>
 * Решает не «тип зоны», а её набор компонентов: пассивные действуют внутри, видимые — у края и
 * рядом. Синергии поднимают силу на 25 % за пару, «ночной сбор» удваивает всё ночью.
 */
public final class AnomalyEffects {
	private AnomalyEffects() {
	}

	/** Внутри зоны: пассивные компоненты по дозе. */
	public static void apply(ServerLevel level, ServerPlayer player, Zone zone, AnomalyState state,
			AnomalyConfig config, double seconds) {
		List<AnomalyComponent> components = zone.components();
		if (components.isEmpty()) {
			return;
		}
		double intensity = AnomalyComponent.intensity(components);
		if (components.contains(AnomalyComponent.NIGHT_TOLL) && isNight(level)) {
			intensity *= 2.0;
		}
		for (AnomalyComponent component : components) {
			if (!component.isVisible()) {
				component.apply(level, player, zone, state, state.dose, intensity, seconds, config);
			}
		}
	}

	/** Видимое: и внутри, и у края — в пределах {@code view_range} от границы. */
	public static void ambience(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config,
			AnomalyState state) {
		if (zone.signedDistance(player.getX(), player.getZ()) > config.view_range) {
			return;
		}
		// light_wall не рисует вторую дугу поверх первой: он просто делает ту же стену выше и ярче
		boolean wall = zone.components().contains(AnomalyComponent.LIGHT_WALL);
		edgeBand(level, player, zone, config.view_range, wall ? 2.6f : 2.0f, wall ? 6 : 3, wall ? 4.0 : 1.5);
		mood(level, player, zone);
		for (AnomalyComponent component : zone.components()) {
			component.pulse(level, player, zone, config, state);
		}
	}

	/**
	 * Низкий фон внутри зоны: без него большая область ощущается только по эффектам. Играем у самого
	 * игрока — звук с расстояния 16 блоков до него не доходит (радиус слышимости = max(16, 16·vol)).
	 */
	private static void mood(ServerLevel level, ServerPlayer player, Zone zone) {
		if (zone.signedDistance(player.getX(), player.getZ()) > 0.0) {
			return;
		}
		if (player.getRandom().nextInt(32) != 0) {
			return;
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMBIENT_CAVE.value(),
				SoundSource.AMBIENT, 0.9f, 0.7f + player.getRandom().nextFloat() * 0.3f);
	}

	/**
	 * Дуга пыли по краю вблизи игрока: цвет — зоны, не класса.
	 * <p>
	 * Шаг ~4 блока (10 точек на дугу 192 блока — это пунктир, а не стена), высота каждой точки —
	 * по рельефу: на склоне дуга иначе уходит в землю или висит в воздухе. `count` разбрасывает
	 * частицы по вертикали, получается полоса стены. Пакет адресован одному игроку: общая рассылка
	 * уходила бы всем в измерении, а `overrideLimiter` ещё и на 512 блоков. Chunk-и не грузим:
	 * в незагруженном чанке {@code getHeight} отдаёт дно мира, а косметика не стоит генерации.
	 */
	static void edgeBand(ServerLevel level, ServerPlayer player, Zone zone, double reach, float scale,
			int count, double spread) {
		double radius = zone.radius();
		if (radius < 1.0) {
			return;
		}
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double half = radius <= reach ? Math.PI : Math.asin(Math.min(1.0, reach / radius));
		double arc = 2.0 * half * radius;
		int points = Math.max(24, Math.min(64, (int) (arc / 4.0)));
		var color = new net.minecraft.core.particles.DustParticleOptions(zone.edgeColor(), scale);
		var rnd = player.getRandom();
		for (int i = 0; i < points; i++) {
			double angle = toPlayer - half + 2.0 * half * (i + rnd.nextDouble()) / points;
			double[] point = zone.anomaly().boundaryPoint(angle, zone.ageHours(), radius);
			if (Math.hypot(point[0] - player.getX(), point[1] - player.getZ()) > reach) {
				continue;
			}
			int bx = (int) Math.floor(point[0]);
			int bz = (int) Math.floor(point[1]);
			if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
				continue;
			}
			double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + 0.6;
			level.sendParticles(player, color, true, true, point[0], y, point[1], count, 0.0, spread, 0.0, 0.0);
		}
	}

	/** Вход в аномалию: только звук. Текст в UI не выводим — механики объясняются средой. */
	public static void entry(ServerLevel level, ServerPlayer player, Zone zone) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 1.2f, 0.7f);
	}

	/**
	 * Доза уже спадает, но эффект ещё висит — «послевкусие». Общее, а не по компонентам: тело
	 * помнит, что было плохо, но уже не знает, от чего именно.
	 */
	public static void lingering(ServerLevel level, ServerPlayer player, AnomalyState state, AnomalyConfig config) {
		// в креативе игрок не болеет: после края зоны строителю не нужна слепота
		if (player.isCreative()) {
			return;
		}
		int amplifier = state.dose >= 0.5 ? 1 : 0;
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.SLOWNESS, 40, amplifier, false, true, true));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DARKNESS, 40, 0, false, true, true));
	}

	// --- движение ---

	/** Тяга: к ядру, а не к геометрическому центру — ядро смещено и ползёт. */
	static void shoveTowards(ServerPlayer player, Zone zone, double force) {
		double dx = zone.coreX() - player.getX();
		double dz = zone.coreZ() - player.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			return;
		}
		shove(player, dx / length * force, dz / length * force);
	}

	static void shoveAway(ServerPlayer player, Zone zone, double force) {
		double dx = player.getX() - zone.coreX();
		double dz = player.getZ() - zone.coreZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			// в самом ядре нечего делить — толкаем в случайную сторону
			double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
			shove(player, Math.cos(angle) * force, Math.sin(angle) * force);
			return;
		}
		shove(player, dx / length * force, dz / length * force);
	}

	/**
	 * Толчок игрока. Одного {@code push} мало: клиент — хозяин своей позиции, а
	 * {@code ChunkMap$TrackedEntity.updatePlayer} для самого игрока выходит сразу
	 * ({@code if (player == entity) return}), поэтому скорость надо отправить пакетом —
	 * ровно так же, как это делает ванильный взрыв.
	 */
	private static void shove(ServerPlayer player, double dx, double dz) {
		player.push(dx, 0.0, dz);
		if (player.connection != null) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	/** Молния без огня и урона: только вспышка и гром. */
	static void visualBolt(ServerLevel level, double x, double y, double z) {
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt == null) {
			return;
		}
		bolt.setVisualOnly(true);
		bolt.snapTo(x, y, z);
		level.addFreshEntity(bolt);
	}

	/** Ночь по мировым часам: 13000–23000 — тёмная половина суток. */
	static boolean isNight(ServerLevel level) {
		long time = level.getOverworldClockTime() % 24000L;
		return time >= 13000L && time <= 23000L;
	}
}
