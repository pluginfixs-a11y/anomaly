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
		// глубже — сильнее: 30 % у края, 100 % в ядре. Без градиента не было разницы,
		// где стоять внутри зоны, и «идти к центру» не имело смысла.
		double depth = Math.min(1.0, Math.max(0.0,
				-zone.signedDistance(player.getX(), player.getZ()) / Math.max(1.0, zone.radius())));
		intensity *= 0.3 + 0.7 * depth;
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
		// light_wall не рисует вторую дугу поверх первой: он делает стену выше и добавляет
		// внутренний контур, чтобы большая зона читалась как область, а не как забор
		boolean wall = zone.components().contains(AnomalyComponent.LIGHT_WALL);
		edgeBand(level, player, zone, config.view_range, wall ? 2.6f : 2.0f, wall ? 8 : 5,
				wall ? 5.0 : 3.0, 1.0);
		if (wall) {
			edgeBand(level, player, zone, config.view_range, 2.0f, 4, 3.0, 0.82);
		}
		anchors(level, player, zone);
		coreBeacon(level, player, zone);
		mood(level, player, zone);
		for (AnomalyComponent component : zone.components()) {
			component.pulse(level, player, zone, config, state);
		}
	}

	/**
	 * Низкий фон внутри зоны: без него большая область ощущается только по эффектам. Играем у самого
	 * игрока — звук с расстояния 16 блоков до него не доходит (радиус слышимости = max(16, 16·vol)).
	 * Набор компонентов задаёт характер: холод — души, гроза — базальт, голод — багровый лес.
	 */
	private static void mood(ServerLevel level, ServerPlayer player, Zone zone) {
		if (zone.signedDistance(player.getX(), player.getZ()) > 0.0) {
			return;
		}
		if (player.getRandom().nextInt(32) != 0) {
			return;
		}
		net.minecraft.sounds.SoundEvent sound = SoundEvents.AMBIENT_CAVE.value();
		for (AnomalyComponent component : zone.components()) {
			if (component == AnomalyComponent.COLD_CREEP) {
				sound = SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD.value();
				break;
			}
			if (component == AnomalyComponent.STORM_CORE) {
				sound = SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value();
				break;
			}
			if (component == AnomalyComponent.HUNGER_DRAIN || component == AnomalyComponent.SATURATION_BURN) {
				sound = SoundEvents.AMBIENT_CRIMSON_FOREST_MOOD.value();
				break;
			}
		}
		float pitch = 0.7f + (zone.id() % 4) * 0.12f;
		level.playSound(null, player.getX(), player.getY(), player.getZ(), sound,
				SoundSource.AMBIENT, 0.9f, pitch);
	}

	/**
	 * Маяк ядра: столб end rod от земли. Издалека видно, куда тянет зона, и что у неё есть центр,
	 * а не только край. Раз в секунду, если ядро в пределах 320 блоков; в незагруженном чанке
	 * высота берётся от игрока — маяк всё равно виден, а генерация ради косметики не нужна.
	 */
	private static void coreBeacon(ServerLevel level, ServerPlayer player, Zone zone) {
		if (player.tickCount % 20 != 0) {
			return;
		}
		double dist = Math.hypot(zone.coreX() - player.getX(), zone.coreZ() - player.getZ());
		if (dist > 320.0) {
			return;
		}
		int bx = (int) Math.floor(zone.coreX());
		int bz = (int) Math.floor(zone.coreZ());
		double base = player.getY();
		if (level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			base = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		}
		for (int i = 2; i <= 40; i += 4) {
			level.sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD,
					true, true, zone.coreX(), base + i, zone.coreZ(), 2, 0.15, 0.15, 0.15, 0.0);
		}
	}

	/**
	 * Неподвижные «якоря» зоны: 2–3 точки внутри, где всё время висит пыль. Без них внутри
	 * аномалии не на что смотреть — все частицы привязаны к игроку и уезжают вместе с ним.
	 */
	private static void anchors(ServerLevel level, ServerPlayer player, Zone zone) {
		var color = new net.minecraft.core.particles.DustParticleOptions(zone.edgeColor(), 1.6f);
		for (int i = 0; i < 3; i++) {
			double angle = noise01(zone.id() * 31L + i * 7919L) * Math.PI * 2.0;
			double dist = zone.radius() * (0.25 + noise01(zone.id() * 17L + i * 104729L) * 0.35);
			double ax = zone.x() + Math.cos(angle) * dist;
			double az = zone.z() + Math.sin(angle) * dist;
			if (Math.hypot(ax - player.getX(), az - player.getZ()) > 96.0) {
				continue;
			}
			int bx = (int) Math.floor(ax);
			int bz = (int) Math.floor(az);
			if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
				continue;
			}
			double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			level.sendParticles(player, color, true, true, ax, y + 0.5, az,
					4, 0.3, 1.8, 0.3, 0.01);
		}
	}

	private static double noise01(long seed) {
		long x = seed * 0x9E3779B97F4A7C15L;
		x ^= x >>> 30;
		x *= 0xBF58476D1CE4E5B9L;
		x ^= x >>> 27;
		return (x >>> 11) / (double) (1L << 53);
	}

	/**
	 * Дуга пыли по краю вблизи игрока: цвет — зоны, не класса.
	 * <p>
	 * Шаг ~4 блока (10 точек на дугу 192 блока — это пунктир, а не стена), высота каждой точки —
	 * по рельефу: на склоне дуга иначе уходит в землю или висит в воздухе. `count` разбрасывает
	 * частицы по вертикали, получается полоса стены. Пакет адресован одному игроку: общая рассылка
	 * уходила бы всем в измерении, а `overrideLimiter` ещё и на 512 блоков. Chunk-и не грузим:
	 * в незагруженном чанке {@code getHeight} отдаёт дно мира, а косметика не стоит генерации.
	 * {@code radiusFactor} — второй контур внутри зоны (для light_wall).
	 */
	static void edgeBand(ServerLevel level, ServerPlayer player, Zone zone, double reach, float scale,
			int count, double spread, double radiusFactor) {
		double radius = zone.radius() * radiusFactor;
		if (radius < 1.0) {
			return;
		}
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double half = radius <= reach ? Math.PI : Math.asin(Math.min(1.0, reach / radius));
		// рисуем не больше ~256 блоков дуги вокруг направления на игрока: у мелкой зоны полный круг
		// при шаге 4 блока — это 10–30 блоков между точками и «пунктир», а лишние пакеты не видны
		half = Math.min(half, 128.0 / radius);
		double arc = 2.0 * half * radius;
		int points = Math.max(16, Math.min(96, (int) (arc / 3.0)));
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
	static void shoveTowards(ServerPlayer player, Zone zone, double force, AnomalyState state) {
		double dx = zone.coreX() - player.getX();
		double dz = zone.coreZ() - player.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			return;
		}
		shove(player, dx / length * force, dz / length * force, state);
	}

	static void shoveAway(ServerPlayer player, Zone zone, double force, AnomalyState state) {
		double dx = player.getX() - zone.coreX();
		double dz = player.getZ() - zone.coreZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			// в самом ядре нечего делить — толкаем в случайную сторону
			double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
			shove(player, Math.cos(angle) * force, Math.sin(angle) * force, state);
			return;
		}
		shove(player, dx / length * force, dz / length * force, state);
	}

	/**
	 * Толчок, который **не обнуляет** скорость.
	 * <p>
	 * Сервер не знает реальную скорость игрока: позицией владеет клиент, а
	 * {@code ClientboundSetEntityMotionPacket} задаёт вектор движения целиком. Прошлая версия
	 * прибавляла тягу к почти нулевому серверному вектору и отправляла его — бегущий игрок каждые
	 * полсекунды останавливался и получал пинок. Поэтому скорость восстанавливается по смещению
	 * с прошлого эффектного тика (позиция на сервере всё-таки точная) и только потом к ней
	 * прибавляется тяга.
	 */
	private static void shove(ServerPlayer player, double dx, double dz, AnomalyState state) {
		long now = System.currentTimeMillis();
		double vx;
		double vy;
		double vz;
		// два толчка в одном тике (перекрывающиеся зоны) не должны мерить нулевое смещение
		if (state.hasLast && now - state.lastMoveMs < 40L) {
			vx = state.velX;
			vy = state.velY;
			vz = state.velZ;
		} else {
			vx = 0.0;
			vy = 0.0;
			vz = 0.0;
			if (state.hasLast) {
				long elapsed = now - state.lastMoveMs;
				// от 1 до 20 тиков: минутный разрыв не должен превращаться в реактивный старт
				double ticks = Math.max(1.0, Math.min(20.0, elapsed / 50.0));
				vx = (player.getX() - state.lastX) / ticks;
				vy = (player.getY() - state.lastY) / ticks;
				vz = (player.getZ() - state.lastZ) / ticks;
			}
			// телепорт, респавн, портал, возврат в зону после долгой прогулки: смещение в сотни
			// блоков — это не скорость. Без сброса пакет задал бы клиенту рывок на 10–50 бл/тик.
			boolean jumped = Math.hypot(vx, vz) > 1.5 || Math.abs(vy) > 5.0;
			if (jumped) {
				vx = 0.0;
				vy = 0.0;
				vz = 0.0;
			}
			state.lastX = player.getX();
			state.lastY = player.getY();
			state.lastZ = player.getZ();
			state.lastMoveMs = now;
			state.velX = vx;
			state.velY = vy;
			state.velZ = vz;
			// после прыжка замер пропускается: следующий тик начнёт счёт с новой позиции
			state.hasLast = !jumped;
		}

		double nx = vx + dx;
		double nz = vz + dz;
		// не больше +0.3 к текущей скорости и не больше 1.2 блока за тик для медленных:
		// бег сохраняется, элитры не тормозят, накопления «тяга + тяга» не бывает
		double base = Math.hypot(vx, vz);
		double speed = Math.hypot(nx, nz);
		double cap = Math.max(base + 0.3, 1.2);
		if (speed > cap) {
			nx = nx / speed * cap;
			nz = nz / speed * cap;
		}
		// вертикаль оставляем как была (в разумных пределах): иначе прыжок и падение рвутся
		double ny = Math.max(-4.0, Math.min(0.6, vy));
		player.setDeltaMovement(nx, ny, nz);
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
