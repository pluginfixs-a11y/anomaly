package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Всё, чем аномалия действует на игрока. Только ванильные каналы: эффекты, частицы, звук,
 * толчок скорости, молния. Клиент ничего не ставит, поэтому ни HUD, ни своих визуалов нет.
 * <p>
 * Решает не «тип зоны», а её набор компонентов: пассивные действуют внутри, видимые — у края и
 * рядом. Синергии поднимают силу на 25 % за пару, «ночной сбор» удваивает всё ночью.
 * <p>
 * Визуал сознательно перегружен по сравнению с 0.2.0-alpha3: зона должна читаться как стена
 * с цветом и высотой, а не как пунктир пыли. Но платится за это не производительностью, а
 * редкими плотными кадрами: край рисуется раз в секунду (частицы живут дольше), а не каждый
 * тик эффектов; пакет идёт одному игроку и только если точка в пределах видимости.
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
		// кадр раз в секунду: пыль живёт 10–20 тиков, а пакетов за это время уходит в два раза меньше
		boolean frame = state.pulseTicks % 20 < Math.max(1, state.lastStep);
		boolean wall = zone.components().contains(AnomalyComponent.LIGHT_WALL);
		if (frame) {
			// край — стена с переливом цвета, а не линия: пыль меняет оттенок к тёмному по жизни
			edgeBand(level, player, zone, config.view_range, wall ? 2.8f : scaleFor(zone), wall ? 12 : 9,
					wall ? 4.5 : spreadFor(zone), 1.0);
			if (wall) {
				// световая стена даёт второй контур внутри: большая область читается как область
				edgeBand(level, player, zone, config.view_range, 2.1f, 6, 3.5, 0.84);
			}
			anchors(level, player, zone);
			seam(level, player, zone, config);
		}
		coreBeacon(level, player, zone);
		corePillar(level, player, zone);
		interior(level, player, zone, config, state);
		pull(level, player, zone);
		mood(level, player, zone);
		for (AnomalyComponent component : zone.components()) {
			component.pulse(level, player, zone, config, state);
		}
	}

	/** Толщина стены и высота разброса — по классу: смертельные видны издалека. */
	private static float scaleFor(Zone zone) {
		return switch (zone.cls()) {
			case SAFE -> 1.9f;
			case DANGER -> 2.3f;
			case DEADLY -> 2.8f;
		};
	}

	private static double spreadFor(Zone zone) {
		return switch (zone.cls()) {
			case SAFE -> 2.6;
			case DANGER -> 3.4;
			case DEADLY -> 4.2;
		};
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
		var rnd = player.getRandom();
		// смертельная зона внутри даёт сердцебиение: его слышно телом, и его невозможно
		// спутать с обычным эмбиентом пещеры
		if (zone.cls() == AnomalyClass.DEADLY && rnd.nextInt(5) == 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 0.65f, 0.85f);
		}
		if (rnd.nextInt(12) != 0) {
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
	 * косметику не рисуем — генерация ради одного столба не стоит того.
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
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		double base = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		// столб до 40 блоков над землёй, но не выше 64 и не ниже уровня глаз игрока в горах
		int top = (int) Math.min(64.0, Math.max(40.0, player.getY() + 16.0 - base));
		for (int i = 2; i <= top; i += 4) {
			level.sendParticles(player, ParticleTypes.END_ROD,
					true, true, zone.coreX(), base + i, zone.coreZ(), 2, 0.2, 0.2, 0.2, 0.0);
		}
	}

	/**
	 * Пылевой столб из настоящего блока под ядром: не светящаяся метка, а поднятая с земли порода.
	 * Один пакет на два такта, зато видно, что ядро «дышит» и стоит на этой земле, а не парит.
	 */
	private static void corePillar(ServerLevel level, ServerPlayer player, Zone zone) {
		if (player.tickCount % 40 != 0) {
			return;
		}
		if (Math.hypot(zone.coreX() - player.getX(), zone.coreZ() - player.getZ()) > 160.0) {
			return;
		}
		int bx = (int) Math.floor(zone.coreX());
		int bz = (int) Math.floor(zone.coreZ());
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		BlockState below = level.getBlockState(new BlockPos(bx, surface - 1, bz));
		if (below.isAir()) {
			return;
		}
		level.sendParticles(player, new BlockParticleOption(ParticleTypes.DUST_PILLAR, below),
				true, true, zone.coreX(), surface, zone.coreZ(), 1, 0.0, 0.0, 0.0, 0.0);
	}

	/**
	 * Неподвижные «якоря» зоны: 2–3 точки внутри, где всё время висит пыль. Без них внутри
	 * аномалии не на что смотреть — все частицы привязаны к игроку и уезжают вместе с ним.
	 */
	private static void anchors(ServerLevel level, ServerPlayer player, Zone zone) {
		int color = zone.edgeColor();
		var dust = new DustColorTransitionOptions(color, darken(color), 2.2f);
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
			level.sendParticles(player, dust, true, true, ax, y + 0.5, az,
					6, 0.35, 2.2, 0.35, 0.01);
		}
	}

	/**
	 * Внутри зоны: редкая взвесь вокруг игрока, у каждого класса своя. Это не «полоса у края»,
	 * а воздух, в котором стоишь: без него внутри большой зоны вообще нет картинки.
	 */
	private static void interior(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config,
			AnomalyState state) {
		if (zone.signedDistance(player.getX(), player.getZ()) > 0.0) {
			return;
		}
		// раз в полсекунды, независимо от периода тика эффектов
		if (player.tickCount % 10 != 0) {
			return;
		}
		var rnd = player.getRandom();
		int color = zone.edgeColor();
		ParticleOptions accent = accent(zone);
		double x = player.getX() + (rnd.nextDouble() - 0.5) * 18.0;
		double z = player.getZ() + (rnd.nextDouble() - 0.5) * 18.0;
		double y = player.getY() + rnd.nextDouble() * 4.0;
		level.sendParticles(player, new DustColorTransitionOptions(color, darken(color), 2.4f),
				true, true, x, y, z, 3, 0.6, 0.9, 0.6, 0.02);
		if (zone.cls() != AnomalyClass.SAFE) {
			level.sendParticles(player, accent, true, true, x, y, z, 1, 0.4, 0.8, 0.4, 0.0);
		}
	}

	/**
	 * Тяга видна: пылинки идут к ядру по прямой, а не просто висят. Раньше тягу можно было
	 * заметить только по собственной скорости — и она выглядела как «ничего не происходит».
	 */
	private static void pull(ServerLevel level, ServerPlayer player, Zone zone) {
		if (zone.components().contains(AnomalyComponent.REPULSION)) {
			return;
		}
		if (!zone.components().contains(AnomalyComponent.TRACTION) && zone.cls() != AnomalyClass.DEADLY) {
			return;
		}
		if (player.tickCount % 15 != 0) {
			return;
		}
		double dx = zone.coreX() - player.getX();
		double dz = zone.coreZ() - player.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 1.0) {
			return;
		}
		double ux = dx / length;
		double uz = dz / length;
		var rnd = player.getRandom();
		int count = zone.cls() == AnomalyClass.DEADLY ? 4 : 2;
		int color = 0xFF000000 | zone.edgeColor();
		for (int i = 0; i < count; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double radius = 5.0 + rnd.nextDouble() * 17.0;
			double sx = player.getX() + Math.cos(angle) * radius;
			double sz = player.getZ() + Math.sin(angle) * radius;
			double sy = player.getY() + 1.0 + rnd.nextDouble() * 3.0;
			// цель — 18 блоков к ядру: дальше не надо, пылинка всё равно живёт секунду
			level.sendParticles(player,
					new TrailParticleOption(new Vec3(sx + ux * 18.0, sy + 1.5, sz + uz * 18.0), color,
							16 + rnd.nextInt(8)),
					true, true, sx, sy, sz, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Цветная искра класса: издалека именно она отличает смертельную стену от безопасной. */
	private static ParticleOptions accent(Zone zone) {
		List<AnomalyComponent> components = zone.components();
		if (components.contains(AnomalyComponent.COLD_CREEP)) {
			return ParticleTypes.SNOWFLAKE;
		}
		if (components.contains(AnomalyComponent.STORM_CORE)) {
			return ParticleTypes.ELECTRIC_SPARK;
		}
		if (components.contains(AnomalyComponent.ASH_FALL)) {
			return ParticleTypes.ASH;
		}
		if (components.contains(AnomalyComponent.NIGHT_TOLL)) {
			return ParticleTypes.SOUL;
		}
		return switch (zone.cls()) {
			case SAFE -> ParticleTypes.FIREFLY;
			case DANGER -> ParticleTypes.SMALL_FLAME;
			case DEADLY -> ParticleTypes.SOUL_FIRE_FLAME;
		};
	}

	private static double noise01(long seed) {
		long x = seed * 0x9E3779B97F4A7C15L;
		x ^= x >>> 30;
		x *= 0xBF58476D1CE4E5B9L;
		x ^= x >>> 27;
		return (x >>> 11) / (double) (1L << 53);
	}

	/** Затемнение цвета для второй фазы частицы: низ стены темнее верха. */
	private static int darken(int color) {
		int r = (int) (((color >> 16) & 0xFF) * 0.35);
		int g = (int) (((color >> 8) & 0xFF) * 0.35);
		int b = (int) ((color & 0xFF) * 0.35);
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * Дуга пыли по краю вблизи игрока. Шаг ~4 блока, высота каждой точки — по рельефу: на склоне
	 * дуга иначе уходит в землю или висит в воздухе. {@code count} разбрасывает частицы по
	 * вертикали (полоса стены), {@code spread} — её высота. Пакет адресован одному игроку: общая
	 * рассылка уходила бы всем в измерении, а {@code overrideLimiter} ещё и на 512 блоков.
	 * Chunk-и не грузим: в незагруженном чанке {@code getHeight} отдаёт дно мира, а косметика
	 * не стоит генерации. {@code radiusFactor} — второй контур внутри зоны (для light_wall).
	 */
	static void edgeBand(ServerLevel level, ServerPlayer player, Zone zone, double reach, float scale,
			int count, double spread, double radiusFactor) {
		double radius = zone.radius() * radiusFactor;
		if (radius < 1.0) {
			return;
		}
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double half = radius <= reach ? Math.PI : Math.asin(Math.min(1.0, reach / radius));
		// рисуем не больше ~224 блоков дуги вокруг направления на игрока: у мелкой зоны полный круг
		// при шаге 4 блока — это 10–30 блоков между точками и «пунктир», а лишние пакеты не видны
		half = Math.min(half, 112.0 / radius);
		double arc = 2.0 * half * radius;
		int points = Math.max(18, Math.min(56, (int) (arc / 4.0)));
		int color = zone.edgeColor();
		var dust = new DustColorTransitionOptions(color, darken(color), scale);
		ParticleOptions accent = accent(zone);
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
			level.sendParticles(player, dust, true, true, point[0], y, point[1], count, 0.0, spread, 0.0, 0.0);
			if (i % 3 == 0) {
				// верхние клочья: стена выше и не обрывается ровной линией по грудь
				level.sendParticles(player, dust, true, true, point[0], y + spread + 2.5, point[1],
						2, 0.0, 1.5, 0.0, 0.0);
			}
			if (i % 2 == 0) {
				level.sendParticles(player, accent, true, true, point[0], y + rnd.nextDouble() * spread,
						point[1], 1, 0.2, 0.3, 0.2, 0.0);
			}
		}
	}

	/**
	 * «Шов»: раз в несколько секунд вдоль края проходит яркая волна. Один кадр даёт зоне
	 * ощущение живого барьера, которого нет у статичной пыли.
	 */
	private static void seam(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config) {
		if (player.tickCount % 100 != 0) {
			return;
		}
		double radius = zone.radius();
		if (radius < 2.0) {
			return;
		}
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double half = radius <= config.view_range ? Math.PI : Math.asin(Math.min(1.0, config.view_range / radius));
		if (half < 0.05) {
			return;
		}
		// волна идёт по видимой дуге за ~1 секунду: 10 шагов на кадр, а не кольцо сразу
		int steps = 10;
		double start = toPlayer - half;
		double step = 2.0 * half / steps;
		var rnd = player.getRandom();
		for (int i = 0; i <= steps; i++) {
			double angle = start + step * i + step * rnd.nextDouble();
			double[] point = zone.anomaly().boundaryPoint(angle, zone.ageHours(), radius);
			if (Math.hypot(point[0] - player.getX(), point[1] - player.getZ()) > config.view_range) {
				continue;
			}
			int bx = (int) Math.floor(point[0]);
			int bz = (int) Math.floor(point[1]);
			if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
				continue;
			}
			double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + 1.2;
			// end rod не тускнеет ни днём, ни ночью — волна видна и в полдень
			level.sendParticles(player, ParticleTypes.END_ROD, true, true, point[0], y, point[1],
					3, 0.0, 2.4, 0.0, 0.02);
		}
	}

	/** Вход в аномалию: звук и вспышка пыли у ног. Текст в UI не выводим — механики объясняются средой. */
	public static void entry(ServerLevel level, ServerPlayer player, Zone zone) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 1.2f, 0.7f);
		int color = zone.edgeColor();
		var dust = new DustColorTransitionOptions(color, darken(color), 2.8f);
		level.sendParticles(player, dust, true, true, player.getX(), player.getY() + 0.6, player.getZ(),
				24, 1.6, 0.5, 1.6, 0.05);
		if (zone.cls() == AnomalyClass.DEADLY) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ENDERMAN_STARE, SoundSource.AMBIENT, 0.5f, 0.8f);
		}
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
