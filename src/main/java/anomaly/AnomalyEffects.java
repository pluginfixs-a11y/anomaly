package anomaly;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
		// «рейды остатка»: с остатком в крови зона бьёт сильнее — идти в неё с непромытой
		// головой дороже, чем с чистой
		intensity *= AnomalyResidue.hazardScale(player);
		// у смертельной зоны есть и своя цена за время внутри: раз в 6 с. Кромка почти терпима
		// (2.0 HP с полом), ядро убивает (до 4.2 HP): иначе сытый игрок со стейками переживал ванильную
		// регенерацию (1 HP / 4 с) и «смертельная» была безобидной. Класс фиксируется при
		// рождении, а зона дрейфует: если она приехала в 6–10 км, она больше не «смертельная».
		// У своего респавна в домашней полосе deadly не бьёт: база не должна быть death loop.
		// И у самого узла кладовой — карман тишины: иначе приз стоит дороже дропа
		boolean deadlyActive = zone.cls() == AnomalyClass.DEADLY && !zone.remnant()
				&& Math.hypot(zone.x(), zone.z()) > config.deadly_from - config.class_blur
				&& !nearOwnRespawn(player, 32.0, config)
				&& !nearNodePocket(zone, player, 12.0);
		if (deadlyActive && !player.isCreative() && !player.isSpectator()) {
			state.hazardTicks += AnomalyState.stepTicks(seconds);
			if (state.hazardTicks >= 120) {
				state.hazardTicks = 0;
				// пол по урону: 2.0 HP за 6 с выше медленной регенерации (0.25 HP/с), иначе
				// внешняя четверть радиуса лечила в плюс и там можно было стоять с едой
				float damage = (float) Math.max(2.0, (0.6 + 3.6 * depth) * Math.max(0.4, state.dose));
				player.hurt(level.damageSources().magic(), damage);
				// зона высасывает силы: с полной сатурацией ванильная регенерация даёт до 2 HP/с,
				// и урон ядра её не обгонял — сытый игрок кемпил ядро на стейках
				player.getFoodData().setSaturation(0.0f);
			}
		}
		// карман тишины у кладовой: раз в ~3 с тихий звон. Без метки исчезновение урона
		// в 12 блоках и возвращение в 13 читается как баг мода, а не как «тут тихо».
		// Считаем по мировым тикам: pulseTicks зависит от tick_period и при 3 давал звон раз в 4 с
		if (zone.cls() == AnomalyClass.DEADLY && !zone.remnant() && !player.isCreative()
				&& !player.isSpectator() && nearNodePocket(zone, player, 12.0)
				&& player.tickCount % 60 == 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 0.35f, 0.55f);
		}
		for (AnomalyComponent component : components) {
			if (!component.isVisible()) {
				component.apply(level, player, zone, state, state.dose, intensity, seconds, config);
			}
		}
	}

	/**
	 * Видимое и слышимое: вызывается, когда игрок в пределах {@code view_range} от границы.
	 * <p>
	 * {@code draw} — рисовать ли край и частицы. Звук, компонентные пульсы (молния, гарь, левитация)
	 * идут всегда: это механика, а не косметика, и её нельзя выключить лимитом ближайших зон.
	 */
	public static void ambience(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config,
			AnomalyState state, boolean draw) {
		double sd = zone.signedDistance(player.getX(), player.getZ());
		if (sd > config.view_range) {
			return;
		}
		mood(level, player, zone, sd);
		for (AnomalyComponent component : zone.components()) {
			component.pulse(level, player, zone, config, state);
		}
		if (!draw) {
			return;
		}
		// каждая зона берёт свою фазу кадра: иначе все соседние стены рисуются в один тик,
		// и клиент получает залп из сотен пакетов вместо ровного потока
		int phase = Math.floorMod((int) (zone.id() & 0xFFFFL), 20);
		// пыль 26.2 живёт 3–18 тиков: кадр раз в секунду — это ровно столько, сколько нужно,
		// чтобы стена не мигала. Реже 1 Гц нельзя: на 96+ блоках стена пропадала бы на полкадра
		// (strobo — то, на что жаловался визуальный ревьюер). Экономия — в плотности, не в кадре.
		boolean close = sd < 48.0;
		boolean distant = sd > 96.0;
		int period = 20;
		boolean frame = frameDue(state.pulseTicks, phase, period, Math.max(1, state.lastStep));
		if (frame) {
			boolean wall = zone.components().contains(AnomalyComponent.LIGHT_WALL);
			// край — стена с переливом цвета, а не линия: пыль меняет оттенок к тёмному по жизни.
			// Высота и разброс — от класса в любом случае: иначе все зоны со световой стеной
			// (а это дешёвый SAFE-компонент) выглядели бы одинаково
			int density = wall ? 22 : 16;
			if (distant) {
				density = Math.max(6, density * 2 / 5);
			} else if (close) {
				density = density * 6 / 5;
			}
			float scale = scaleFor(zone) * (wall ? 1.1f : 1.0f);
			double spread = spreadFor(zone) * (wall ? 1.15 : 1.0);
			edgeBand(level, player, zone, config.view_range, scale, density, spread, 1.0);
			anchors(level, player, zone);
		}
		coreBeacon(level, player, zone, state);
		interior(level, player, zone, sd, state);
	}

	/**
	 * У своего респавна deadly не бьёт: база в зоне не должна быть death loop. Но только если
	 * точка возрождения в домашней полосе (ближе рубежа смертельных) и в Верхнем мире: иначе
	 * кровать, поставленная внутри смертельной зоны, давала бы круг 64 блока без урона.
	 */
	static boolean nearOwnRespawn(ServerPlayer player, double radius, AnomalyConfig config) {
		var respawn = player.getRespawnConfig();
		if (respawn == null || respawn.respawnData() == null) {
			return false;
		}
		var data = respawn.respawnData();
		if (config != null && data.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			return false;
		}
		var pos = data.pos();
		if (config != null && Math.hypot(pos.getX(), pos.getZ()) >= config.deadly_from - config.class_blur) {
			return false;
		}
		return Math.hypot(player.getX() - (pos.getX() + 0.5), player.getZ() - (pos.getZ() + 0.5)) < radius;
	}

	/**
	 * Карман тишины у кладовой: в смертельной зоне урон идёт везде, и без паузы у самого
	 * узла приз (незерит/тотем) стоил бы дороже, чем дроп. Пауза только на радиус узла и только
	 * в объёме кладовой: кемпинг всё равно невыгоден — она молчит, пока рядом кто-то есть.
	 */
	static boolean nearNodePocket(Zone zone, ServerPlayer player, double radius) {
		var anomaly = zone.anomaly();
		if (anomaly == null || !anomaly.node_cache_ok || !Double.isFinite(anomaly.node_cx)
				|| anomaly.node_r <= 0.0) {
			return false;
		}
		double r = Math.max(radius, 0.32 * anomaly.node_r + 2.0);
		if (Math.abs(player.getY() - anomaly.node_cy) > 16.0) {
			return false;
		}
		return Math.hypot(player.getX() - anomaly.node_cx, player.getZ() - anomaly.node_cz) < r;
	}

	/**
	 * Пора ли рисовать кадр стены. Вынесено из ambience, чтобы это проверялось тестом:	 * при {@code period == step} условие было истинным всегда, и стена у края рисовалась
	 * 2 раза в секунду вне зависимости от фаз зон — залп из сотен пакетов в одном тике.
	 */
	static boolean frameDue(int tick, int phase, int period, int step) {
		if (period <= 0) {
			return false;
		}
		return Math.floorMod(tick + phase, period) < Math.max(1, step);
	}

	/** Толщина стены и высота разброса — по классу: смертельные видны издалека. */
	private static float scaleFor(Zone zone) {		return switch (zone.cls()) {
			case SAFE -> 0.25f;
			case DANGER -> 0.3f;
			case DEADLY -> 0.35f;
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
	private static void mood(ServerLevel level, ServerPlayer player, Zone zone, double sd) {
		if (sd > 0.0) {
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
	 * а не только край. Раз в секунду. Если ядро далеко (или его чанк не загружен), вместо
	 * столба над ядром рисуется указатель у самого игрока — иначе из середины крупной зоны
	 * ориентира нет вовсе, а грузить чанки ради косметики нельзя.
	 */
	private static void coreBeacon(ServerLevel level, ServerPlayer player, Zone zone, AnomalyState state) {
		// не player.tickCount: его фаза относительно серверного тика эффектов может не совпасть
		// с нулём никогда — маяка не было бы у 9 игроков из 10. Фаза по id зоны: маяки разных
		// зон не бьют в один тик
		int phase = Math.floorMod((int) (zone.id() & 0x7L), 20);
		if (Math.floorMod(state.pulseTicks + phase, 20) >= Math.max(1, state.lastStep)) {
			return;
		}
		double dist = Math.hypot(zone.coreX() - player.getX(), zone.coreZ() - player.getZ());
		int bx = (int) Math.floor(zone.coreX());
		int bz = (int) Math.floor(zone.coreZ());
		boolean loaded = level.getChunkSource().hasChunk(bx >> 4, bz >> 4);
		if (dist > 448.0 || !loaded) {
			// ядро вне кадра или его чанк не загружен: указатель на него в 28 блоках от игрока,
			// туда же смотрит столб. Так ориентир есть и в середине трёхкилометровой зоны
			bearing(level, player, zone, dist);
			return;
		}
		double base = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
		// столб до 140 блоков: у крупной зоны дальний маяк должен читаться как столб света
		int top = (int) Math.min(140.0, Math.max(56.0, player.getY() + 20.0 - base + dist * 0.15));
		// основание: широкое пятно у земли, чтобы столб не выглядел ниткой
		int color = zone.edgeColor();
		var glow = new DustColorTransitionOptions(color, darken(color), 0.5f);
		level.sendParticles(player, glow, true, true, zone.coreX(), base + 1.2, zone.coreZ(),
				10, 1.5, 0.8, 1.5, 0.01);
		// сегментов по высоте, а не четыре: четыре пакета на 140 блоков — это четыре искры
		// с зазором 35 блоков, читается как обрывки, а не как шахта. Соседние сегменты
		// перекрываются по dy
		int segments = Math.max(4, Math.min(12, top / 12));
		int per = Math.max(6, top / segments);
		double dy = Math.max(1.0, top / (segments * 2.0));
		for (int k = 0; k < segments; k++) {
			level.sendParticles(player, ParticleTypes.END_ROD, true, true,
					zone.coreX(), base + top * (k + 0.5) / segments, zone.coreZ(), per, 0.8, dy, 0.8, 0.0);
		}
	}

	/**
	 * Указатель на ядро: короткий столб пыли в его сторону, в 24–30 блоках от игрока.
	 * Высоту берём по рельефу: указатель под землёй или над кронами не работает.
	 */
	private static void bearing(ServerLevel level, ServerPlayer player, Zone zone, double dist) {
		if (dist < 32.0) {
			return;
		}
		double dx = (zone.coreX() - player.getX()) / dist;
		double dz = (zone.coreZ() - player.getZ()) / dist;
		double reach = 24.0 + Math.floorMod(zone.id(), 3) * 3.0;
		double x = player.getX() + dx * reach;
		double z = player.getZ() + dz * reach;
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		double ground = level.getChunkSource().hasChunk(bx >> 4, bz >> 4)
				? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
				: player.getY() - 1.0;
		double y = Math.max(ground, player.getY() - 16.0) + 1.0;
		int color = zone.edgeColor();
		// яркая пыль класса, не затемнённая: указатель должен читаться и ночью
		var dust = new DustColorTransitionOptions(color, color, 0.6f);
		for (int k = 0; k < 4; k++) {
			level.sendParticles(player, dust, true, true, x, y + k * 3.0, z, 4, 0.6, 2.5, 0.6, 0.0);
		}
	}

	/**
	 * Неподвижные «якоря» зоны: 2–3 точки внутри, где всё время висит пыль. Без них внутри
	 * аномалии не на что смотреть — все частицы привязаны к игроку и уезжают вместе с ним.
	 */
	private static void anchors(ServerLevel level, ServerPlayer player, Zone zone) {
		int color = zone.edgeColor();
		var dust = new DustColorTransitionOptions(color, darken(color), 0.45f);
		double sd = zone.signedDistance(player.getX(), player.getZ());
		for (int i = 0; i < 3; i++) {
			double angle = noise01(zone.id() * 31L + i * 7919L) * Math.PI * 2.0;
			double dist = zone.radius() * (0.25 + noise01(zone.id() * 17L + i * 104729L) * 0.35);
			double ax = zone.x() + Math.cos(angle) * dist;
			double az = zone.z() + Math.sin(angle) * dist;
			if (Math.hypot(ax - player.getX(), az - player.getZ()) > 96.0) {
				// в трёхкилометровой зоне якорь на 0.25–0.6 радиуса — это 375–900 блоков от игрока:
				// внутри не на что смотреть. Ставим точку в сетке 64×64 (она не едет за игроком
				// в пределах клетки), но внутри зоны
				if (sd > 0.0) {
					continue;
				}
				// своя клетка на каждый якорь: иначе все три прыгают на 64 блока разом
				double cellX = Math.floor((player.getX() + i * 32.0) / 64.0) * 64.0 + 32.0;
				double cellZ = Math.floor((player.getZ() + i * 21.0) / 64.0) * 64.0 + 32.0;
				double jitterX = (noise01(zone.id() * 53L + i * 977L) - 0.5) * 48.0;
				double jitterZ = (noise01(zone.id() * 71L + i * 131L) - 0.5) * 48.0;
				ax = cellX + jitterX;
				az = cellZ + jitterZ;
			}
			// точка в сетке может выпасть за край формы: пыль вне аномалии — это баг на вид
			if (!zone.contains(ax, az)) {
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
	private static void interior(ServerLevel level, ServerPlayer player, Zone zone, double sd,
			AnomalyState state) {
		if (sd > 0.0) {
			return;
		}
		// каждый тик эффектов (0.5 с), без привязки к player.tickCount
		if (state.pulseTicks % 10 >= Math.max(1, state.lastStep)) {
			return;
		}
		var rnd = player.getRandom();
		int color = zone.edgeColor();
		ParticleOptions accent = accent(zone);
		double x = player.getX() + (rnd.nextDouble() - 0.5) * 18.0;
		double z = player.getZ() + (rnd.nextDouble() - 0.5) * 18.0;
		double y = player.getY() + rnd.nextDouble() * 4.0;
		level.sendParticles(player, new DustColorTransitionOptions(color, darken(color), 0.35f),
				true, true, x, y, z, 3, 0.6, 0.9, 0.6, 0.02);
		if (zone.cls() != AnomalyClass.SAFE) {
			level.sendParticles(player, accent, true, true, x, y, z, 1, 0.4, 0.8, 0.4, 0.0);
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
	 * Дуга пыли по краю вблизи игрока. Шаг ~3 блока, высота каждой точки — по рельефу: на склоне
	 * дуга иначе уходит в землю или висит в воздухе. {@code count} разбрасывает частицы по
	 * вертикали (полоса стены), {@code spread} — её высота. Пакет адресован одному игроку: общая
	 * рассылка уходила бы всем в измерении, а {@code overrideLimiter} ещё и на 512 блоков.
	 * Chunk-и не грузим: в незагруженном чанке {@code getHeight} отдаёт дно мира, а косметика
	 * не стоит генерации. {@code radiusFactor} — доля радиуса зоны (1.0: сам край).
	 */
	static void edgeBand(ServerLevel level, ServerPlayer player, Zone zone, double reach, float scale,
			int count, double spread, double radiusFactor) {
		double radius = zone.radius() * radiusFactor;
		if (radius < 1.0) {
			return;
		}
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		// мелкая зона целиком в пределах видимости: рисуем кольцо, иначе стоящий внутри видит
		// стену только с одной стороны — ровно то, на что жаловался владелец
		boolean full = radius <= reach;
		double half;
		if (full) {
			half = Math.PI;
		} else {
			half = Math.asin(Math.min(1.0, reach / radius));
			// у большой зоны — только дуга 224 блока вокруг игрока: остальное не в кадре
			half = Math.min(half, 112.0 / radius);
		}
		double arc = 2.0 * half * radius;
		// вытянутость растягивает контур до 2.6×: без поправки на длинной оси шаг точек
		// втрое больше, а дальняя сторона полного кольца выходит за reach и пропадает
		arc *= 0.5 * (1.0 + Math.min(2.0, Math.max(0.2, zone.anomaly().stretch)));
		boolean far = zone.signedDistance(player.getX(), player.getZ()) > 96.0;
		// полное кольцо у мелкой зоны: 128 точек. При 56 на r=150 шаг был 20 блоков и
		// кольцо читалось как пунктир; 128 даёт 7.4, а у мелких — 3–5. Лишние ветки
		// (вертикальный язык, акцент) на полном кольце режем: они нужны для читаемости
		// с 100+ блоков, а тут игрок и так близко
		double spacing = 3.0;
		int cap = far ? 28 : 128;
		int points = Math.max(24, Math.min(cap, (int) (arc / spacing)));
		// полное кольцо — это в 5 раз больше точек: плотность на точку вдвое ниже, иначе
		// один кадр такой зоны вытесняет все остальные частицы у клиента
		if (full) {
			count = Math.max(3, count / 2);
		}
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
			// у стены есть толщина: нулевой разброс давал плоскость в один пиксель, которую
			// видно только строго сбоку
			level.sendParticles(player, dust, true, true, point[0], y, point[1], count, 0.6, spread, 0.6, 0.0);
			if (i % (full ? 10 : 5) == 0) {
				// приземная полоса: в лесу и на склоне стена не должна висеть только на уровне глаз
				level.sendParticles(player, dust, true, true, point[0], y - 0.4, point[1],
						Math.max(2, count / 2), 0.5, 1.0, 0.5, 0.0);
			}
			if (i % (full ? 6 : 4) == 0 && !far) {
				// верхние клочья: стена выше и не обрывается ровной линией по грудь
				level.sendParticles(player, dust, true, true, point[0], y + spread + 2.5, point[1],
						2, 0.3, 1.5, 0.3, 0.0);
			}
			if (i % 12 == 0 && !far && (!full || radius > 120.0)) {
				// вертикальный «язык»: один пакет на дюжину точек. На полном кольце он нужен
				// только у крупных зон, где точек много и язык не превращает стену в лес столбов
				level.sendParticles(player, dust, true, true, point[0], y + 2.5, point[1],
						6, 0.25, 5.0, 0.25, 0.0);
			}
			if (i % (full ? 6 : 3) == 0) {
				level.sendParticles(player, accent, true, true, point[0], y + rnd.nextDouble() * spread,
						point[1], 1, 0.2, 0.3, 0.2, 0.0);
			}
		}
	}

	/** Сигнал перед следом: мир не должен менять блоки молча. */
	static void scarTelegraph(ServerLevel level, double x, double z, int color) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + 0.6;
		level.playSound(null, x, y, z, SoundEvents.STONE_BREAK, SoundSource.AMBIENT, 1.4f, 0.55f);
		level.sendParticles(new DustColorTransitionOptions(color, darken(color), 0.8f),
				x, y + 1.0, z, 26, 2.2, 1.4, 2.2, 0.02);
	}

	/** Вход в аномалию: звук и вспышка пыли у ног. Текст в UI не выводим — механики объясняются средой. */
	public static void entry(ServerLevel level, ServerPlayer player, Zone zone) {
		// звук входа не должен совпадать ни с одной подписью компонента: иначе «что это за
		// зона» не отличить от «я вошёл»
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BEACON_POWER_SELECT, SoundSource.AMBIENT, 1.1f, 0.7f);
		int color = zone.edgeColor();
		var dust = new DustColorTransitionOptions(color, darken(color), 0.5f);
		level.sendParticles(player, dust, true, true, player.getX(), player.getY() + 0.6, player.getZ(),
				24, 1.6, 0.5, 1.6, 0.05);
		if (zone.cls() == AnomalyClass.DEADLY) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ENDERMAN_STARE, SoundSource.AMBIENT, 0.5f, 0.8f);
		}
		// обучение без букв: у каждого компонента свой звук, и по входу слышно, что это за зона
		signatureSounds(level, player, zone);
		AnomalyProgress.onEnter(player, zone);
	}

	/** Звуки-подписи набора: два разных компонента, чтобы слух различал зоны без текста. */
	private static void signatureSounds(ServerLevel level, ServerPlayer player, Zone zone) {
		List<AnomalyComponent> components = zone.components();
		if (components.isEmpty()) {
			return;
		}
		// не первые два по ordinal: почти в любой зоне это были бы голод и пустая еда.
		// Сдвиг по id даёт разным зонам разные подписи, а внутри зоны порядок стабилен
		int start = Math.floorMod((int) (zone.id() % components.size()), components.size());
		int played = 0;
		for (int i = 0; i < components.size() && played < 2; i++) {
			AnomalyComponent component = components.get((start + i) % components.size());
			level.playSound(null, player.getX(), player.getY(), player.getZ(), component.signature(),
					SoundSource.AMBIENT, 0.6f, component.signaturePitch());
			played++;
		}
	}

	/**
	 * Рождение зоны: низкий гул и вспышка пыли в точке. Слышно и видно только тем, кто рядом —
	 * зона встаёт на место, и это не должно быть сюрпризом без знака.
	 */
	public static void birth(ServerLevel level, ServerPlayer player, double x, double z, int color) {
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
		if (y <= level.getMinY() + 1 || y >= level.getMaxY() - 2) {
			y = player.getY();
		}
		var dust = new DustColorTransitionOptions(color, darken(color), 0.5f);
		level.sendParticles(player, dust, true, true, x, y + 1.5, z, 40, 4.0, 2.0, 4.0, 0.02);
		level.playSound(null, x, y, z, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 0.9f, 0.65f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SCULK_BLOCK_SPREAD,
				SoundSource.AMBIENT, 0.7f, 0.5f);
	}

	/**
	 * Предвестник: за 10–30 минут до рождения зона уже шумит в точке — низкий гул, сбой пыли и
	 * «шаги» без источника. Ничего не наносит: это только знак, что здесь скоро будет плохо.
	 */
	public static void herald(ServerLevel level, ServerPlayer player, Anomaly anomaly, long now) {
		var rnd = player.getRandom();
		if (rnd.nextInt(40) != 0) {
			return;
		}
		double x = anomaly.x0;
		double z = anomaly.z0;
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
		if (y <= level.getMinY() + 1) {
			y = player.getY();
		}
		int color = AnomalyClass.byId(anomaly.cls).color;
		var dust = new DustColorTransitionOptions(color, darken(color), 0.3f);
		// пыль стоит кольцом радиусом 6–10: место «дышит», но границы ещё нет
		for (int i = 0; i < 6; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double radius = 6.0 + rnd.nextDouble() * 6.0;
			level.sendParticles(player, dust, true, true, x + Math.cos(angle) * radius,
					y + 1.0 + rnd.nextDouble() * 2.0, z + Math.sin(angle) * radius,
					2, 0.3, 1.2, 0.3, 0.0);
		}
		level.playSound(null, x, y, z, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.AMBIENT, 1.2f, 0.55f);
		if (rnd.nextInt(3) == 0) {
			// «шаги за спиной» — самый дешёвый и самый неуютный знак. Громкость ≥1: слышно
			// на 20+ блоках, а не в восьми, иначе предвестник не предупреждает ни о чём
			double behind = Math.atan2(player.getZ() - z, player.getX() - x) + Math.PI;
			level.playSound(null, x + Math.cos(behind) * 9.0, y, z + Math.sin(behind) * 9.0,
					SoundEvents.GRASS_STEP, SoundSource.AMBIENT, 1.0f, 0.7f);
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
		// одно «послевкусие» на выход, а не обновление каждые полсекунды: иначе после любого
		// касания зоны игрок полминуты идёт вслепую и это читается как баг, а не как атмосфера
		int amplifier = state.dose >= 0.6 ? 1 : 0;
		int slow = (int) Math.round(40 + 100 * state.dose);
		int dark = (int) Math.round(20 + 40 * state.dose);
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.SLOWNESS, slow, amplifier, false, true, true));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DARKNESS, dark, 0, false, true, true));
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
		// на элитре скорость клиента выше порога «телепорт», и любой motion-пакет её обнуляет:
		// тяга/отторжение элитру не трогают вовсе (иначе она встаёт колом)
		if (player.isFallFlying()) {
			return;
		}
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







