package anomaly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Поле аномалий: загрузка, тик, рождения и смерти, поиск вокруг игрока.
 * <p>
 * Поле лежит в {@code world/data/anomaly_field.json} — обычным текстом, чтобы его можно было
 * посмотреть глазами и поправить руками. Пишется атомарно (временный файл + переименование),
 * а сохраняется: на изменения не чаще раза в 30 секунд, раз в пять минут для часов и на остановке.
 * <p>
 * Тик дешёвый: у аномалии нет пошагового состояния, поэтому обход списка — это проверка возраста
 * и сжатия у центра, а не расчёт формы. Форма и позиция считаются только тогда, когда о них
 * спросили (эффекты игрока, отладочный контур).
 */
public final class AnomalyField {
	// без pretty: файл на 600 зон весит втрое меньше, а пишется он каждые 30 секунд
	private static final Gson GSON = new GsonBuilder().create();
	private static final String FILE_NAME = "anomaly_field.json";
	private static final long HOUR_MS = 3_600_000L;
	/** Одна сутка поля: по ней считается суточный лимит генерации чанков под следы. */
	static final long DAY_MS = 24L * HOUR_MS;

	private static AnomalyFieldData data = new AnomalyFieldData();
	private static Path path;
	private static Random random = new Random(1L);
	private static long lastSaveMs;
	/** Период снимка поля: сериализация 1.3 МБ идёт на тик-потоке, чаще нельзя. */
	private static final long SAVE_INTERVAL_MS = 5L * 60_000L;
	/** Размер последнего снимка: по нему растягивается интервал, чтобы большой файл стоил реже. */
	private static long lastBytes;
	/** Жёсткий предел: если MSPT не даёт сохранить, пишем всё равно — терять данные дороже. */
	private static final long FORCED_SAVE_MS = 15L * 60_000L;

	/** Интервал снимка: от 5 минут на 1 МБ до 12 минут на 8 МБ. */
	private static long saveIntervalMs() {
		long over = Math.max(0L, lastBytes - 1_000_000L);
		return Math.min(12L * 60_000L, SAVE_INTERVAL_MS + over / 1_000_000L * 60_000L);
	}
	private static long lastTickFieldMs;
	private static volatile boolean dirty;
	private AnomalyField() {
	}

	// --- жизненный цикл ---

	public static void load(MinecraftServer server) {
		path = server.getWorldPath(LevelResource.DATA).resolve(FILE_NAME).toAbsolutePath().normalize();
		AnomalyFieldData loaded = read(path);
		boolean fresh = loaded == null;
		data = loaded != null ? loaded : new AnomalyFieldData();
		if (data.anomalies == null) {
			data.anomalies = new ArrayList<>();
		}
		data.anomalies.removeIf(anomaly -> anomaly == null || !anomaly.valid());
		if (data.next_id < 1L) {
			data.next_id = 1L;
		}
		for (Anomaly anomaly : data.anomalies) {
			data.next_id = Math.max(data.next_id, anomaly.id + 1L);
		}
		if (data.clock_ms <= 0L) {
			// без сохранённых часов продолжаем от самой поздней аномалии; отрицательный сдвиг
			// сделал бы всех моложе, чем они есть
			data.clock_ms = Math.max(0L, newestBorn() + 1L);
		}
		AnomalyClock.setFieldMs(data.clock_ms);
		// множитель времени — отладочный, он не должен переезжать из мира в мир этого JVM
		AnomalyClock.resetScale();
		lastTickFieldMs = data.clock_ms;
		lastSaveMs = System.currentTimeMillis();
		random = new Random(server.overworld().getSeed() ^ 0x9E3779B97F4A7C15L ^ System.nanoTime());

		AnomalyConfig config = AnomalyMod.config;
		// файл 0.2.0-alpha1 не знал компонентов: раскатываем набор по id — детерминированно, без хаоса
		boolean migrated = false;
		if (config != null) {
			for (Anomaly anomaly : data.anomalies) {
				// осколок — уже готовый шрам: ни компонентов, ни следов ему заново не роллим
				if (anomaly.remnant || anomaly.precursor) {
					continue;
				}
				if (anomaly.components == null || anomaly.components.isEmpty()) {
					List<AnomalyComponent> rolled = AnomalyComponent.roll(
							AnomalyClass.byId(anomaly.cls),
							new Random(anomaly.id * 0x9E3779B97F4A7C15L),
							config, anomaly.r0);
					// пустой пул (всё выключено) не должен переписывать файл на каждом старте
					if (!rolled.isEmpty()) {
						anomaly.components = AnomalyComponent.ids(rolled);
						anomaly.invalidateComponents();
						migrated = true;
					}
				}
				// зоны, рождённые до 0.2.0-alpha3, приходят без плана следов — планируем на загрузке
				if (!anomaly.pinned && (anomaly.scars == null || anomaly.scars.isEmpty())
						&& anomaly.scar_next == 0 && anomaly.scar_cursor == 0) {
					// за 50–150 ч дрейф уводит зону на километры: следы планируем вокруг нынешнего места,
					// иначе они окажутся вне зоны и в чужих землях
					double[] center = anomaly.position(anomaly.ageHours(AnomalyClock.now()));
					List<ScarOp> planned = AnomalyScars.planAt(center[0], center[1], anomaly.r0,
							AnomalyClass.byId(anomaly.cls), new Random(anomaly.id * 0x2545F4914F6CDD1DL),
							false, anomaly.components);
					if (!planned.isEmpty()) {
						anomaly.scars = planned;
						migrated = true;
					}
					// точка привязки следов: файлы до 0.3.0 её не знали, иначе первая проверка
					// дрейфа решила бы, что зона уехала на пол-жизни
					anomaly.scar_cx = center[0];
					anomaly.scar_cz = center[1];
				}
			}
			if (AnomalyComponent.allowed(AnomalyClass.DANGER, config).isEmpty()
					|| AnomalyComponent.allowed(AnomalyClass.DANGER, config).stream()
							.noneMatch(AnomalyComponent::isVisible)) {
				AnomalyMod.LOGGER.warn("Anomaly: все видимые компоненты выключены — зоны будут невидимыми");
			}
		}
		// выключенный мод не должен ни сеять население, ни писать файл в мир
		if (config != null && config.enabled) {
			if (fresh || data.anomalies.isEmpty()) {
				seedPopulation(data, config, random, AnomalyClock.now());
				saveNow();
			} else if (migrated) {
				saveNow();
			}
		}
		AnomalyMod.LOGGER.info("Anomaly: поле загружено — {} аномалий, население {}, время поля {} ч{}",
				data.anomalies.size(), config == null ? 0 : config.population,
				String.format("%.1f", AnomalyClock.fieldMs() / (double) HOUR_MS),
				fresh ? " (новое поле, население посеяно)" : "");
		// замер сразу после загрузки: покрытие — единственное число, которое нельзя проверить глазами
		Stats measured = stats();
		AnomalyMod.LOGGER.info("Anomaly: покрытие {} %, классов safe/danger/deadly {}/{}/{}, бюджет {} блоков",
				String.format("%.1f", measured.coveragePercent()),
				measured.safe(), measured.danger(), measured.deadly(), Math.round(measured.budget()));
	}

	private static long newestBorn() {
		long newest = 0L;
		for (Anomaly anomaly : data.anomalies) {
			newest = Math.max(newest, anomaly.born);
		}
		return newest;
	}

	/** Раз в секунду: снять умерших, сжать забредшие к центру, добавить новые. */
	public static void tick(MinecraftServer server) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled) {
			return;
		}
		AnomalyClock.commit();
		long now = AnomalyClock.now();
		double hours = Math.max(0.0, (now - lastTickFieldMs) / (double) HOUR_MS);
		lastTickFieldMs = now;

		ServerLevel overworld = server.overworld();
		Avoid avoid = settled(server, overworld, config);
		boolean changed = tickData(data, config, random, now, hours, avoid);
		// один бросок внутри прохода не должен убивать тик сервера: ловим и пишем одну строку
		try {
			changed |= AnomalyScars.tick(server, overworld, data, config);
		} catch (RuntimeException e) {
			warnOnce("следы", e);
		}
		try {
			AnomalyNodes.tick(server, overworld, config);
		} catch (RuntimeException e) {
			warnOnce("узлы", e);
		}
		changed |= bornSignals(server, overworld, now);
		if (changed) {
			dirty = true;
		}
		long real = System.currentTimeMillis();
		// Файл на 600 зон — 1.3 МБ: Gson-сериализация идёт на тик-потоке (20–50 мс), поэтому
		// не чаще раза в 5 минут и только когда сервер не задыхается. Запись на диск — в фоне
		boolean due = real - lastSaveMs > saveIntervalMs();
		boolean calm = server.getAverageTickTimeNanos() / 1_000_000.0 < 40.0;
		// на живом сервере MSPT 40–50 держится постоянно: без жёсткого предела сейв не
		// случался бы никогда, и падение теряло бы все рождения и прогресс следов
		boolean overdue = real - lastSaveMs > FORCED_SAVE_MS;
		if (dirty && (overdue || (due && calm))) {
			if (overdue && !calm) {
				warnOnce("снимок поля", new IllegalStateException(
						"сейв откладывался " + Math.round((real - lastSaveMs) / 60_000.0) + " мин"));
			}
			saveAsync();
		}
	}

	// --- кемпинг ---

	/** Одна строка на проход, а не на тик: устойчивое исключение иначе топит лог. */
	private static long lastWarnMs;

	private static void warnOnce(String what, RuntimeException e) {
		long now = System.currentTimeMillis();
		if (now - lastWarnMs < 60_000L) {
			return;
		}
		lastWarnMs = now;
		AnomalyMod.LOGGER.warn("Anomaly: проход «{}» упал ({}), поле продолжает жить", what, e.toString());
	}

	/** Пометить поле грязным: зовут те, кто меняет данные без изменения состава (кормление, генерации). */
	public static void markDirty() {
		dirty = true;
	}

	/** Чистая часть кормления: по ней же гоняется самопроверка. */
	public static double feedHours(double realSeconds, double ratePerHour) {
		if (!Double.isFinite(realSeconds) || !Double.isFinite(ratePerHour) || ratePerHour <= 0.0
				|| realSeconds <= 0.0) {
			return 0.0;
		}
		return realSeconds / 3600.0 * ratePerHour;
	}

	static void feedZone(Anomaly anomaly, double realSeconds, double ratePerHour) {
		if (anomaly == null || anomaly.pinned) {
			return;
		}
		anomaly.fed_hours = Math.min(Math.max(1.0, anomaly.life_hours) * 4.0,
				Math.max(0.0, anomaly.fed_hours + feedHours(realSeconds, ratePerHour)));
	}

	// --- места, где зона не родится ---

	/**
	 * Где рождаться нельзя: игрок, его точка возрождения (кровать или якорь — это и есть дом),
	 * и постройки в уже загруженном чанке. Незагруженные чанки не читаем: генерация мира ради
	 * поиска чужого дома дороже, чем польза от неё.
	 */
	public record Avoid(List<double[]> points, ServerLevel level, int buildRadius) {
		public static Avoid none() {
			return new Avoid(List.of(), null, 0);
		}

		boolean blocks(double x, double z) {
			return blocks(x, z, 0.0);
		}

		/** {@code extra} — будущий радиус зоны: клиренс должен учитывать, во что она вырастет. */
		boolean blocks(double x, double z, double extra) {
			for (double[] point : points) {
				double radius = Math.hypot(x - point[0], z - point[1]);
				if (radius < point[2] + extra) {
					return true;
				}
			}
			return level != null && builtNear(level, x, z, Math.max(8, buildRadius));
		}
	}

	/** Точки, вокруг которых не рождаются зоны, с их радиусами. */
	static Avoid settled(MinecraftServer server, ServerLevel level, AnomalyConfig config) {
		List<double[]> points = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != level) {
				continue;
			}
			points.add(new double[]{player.getX(), player.getZ(), config.spawn_clear_players});
			var respawn = player.getRespawnConfig();
			if (respawn != null && respawn.respawnData() != null
					&& respawn.respawnData().dimension() == Level.OVERWORLD) {
				var pos = respawn.respawnData().pos();
				points.add(new double[]{pos.getX(), pos.getZ(), config.spawn_clear_beds});
			}
		}
		return new Avoid(points, level, config.spawn_clear_build);
	}

	/** Есть ли постройка в загруженных чанках вокруг точки: 9 проб по спирали, не больше. */
	static boolean builtNear(ServerLevel level, double x, double z, int radius) {
		if (level == null) {
			return false;
		}
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				int sx = bx + i * radius;
				int sz = bz + j * radius;
				if (!level.getChunkSource().hasChunk(sx >> 4, sz >> 4)) {
					continue;
				}
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
				if (manMade(level, sx, y, sz)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean manMade(ServerLevel level, int x, int y, int z) {
		net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		for (int dy = 0; dy >= -2; dy--) {
			var state = level.getBlockState(pos.set(x, Math.max(level.getMinY(), y - 1 + dy), z));
			if (state.isAir()) {
				continue;
			}
			if (AnomalyScars.builtBlock(state) || state.hasBlockEntity()
					|| state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
					|| state.is(net.minecraft.world.level.block.Blocks.FURNACE)
					|| state.is(net.minecraft.world.level.block.Blocks.TORCH)
					|| state.is(net.minecraft.world.level.block.Blocks.WALL_TORCH)
					|| state.is(net.minecraft.tags.BlockTags.BEDS)) {
				return true;
			}
		}
		return false;
	}

	// --- один шаг поля ---

	/**
	 * Один шаг поля без сервера — так его гоняет самопроверка. Возвращает true, если состав
	 * поля изменился (кого-то сняли или кто-то родился).
	 */
	static boolean tickData(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, double hours) {
		return tickData(field, config, rnd, now, hours, Avoid.none());
	}

	static boolean tickData(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, double hours,
			Avoid avoid) {
		boolean changed = false;
		Iterator<Anomaly> iterator = field.anomalies.iterator();
		while (iterator.hasNext()) {
			Anomaly anomaly = iterator.next();
			// тестовая зона приколота: не стареет, не тает и не снимается сама
			if (anomaly.pinned) {
				continue;
			}
			// предвестник: шумит, но ещё не зона. Дорос до своей даты — рождается по-настоящему
			if (anomaly.precursor) {
				long at = anomaly.birth_at > 0L ? anomaly.birth_at : now;
				if (now >= at) {
					born(anomaly, now, config, rnd);
					changed = true;
				}
				continue;
			}
			if (!anomaly.alive(now)) {
				// умерла — на 1–6 часов остаётся осколок: то же место и форма, но без урона
				if (!anomaly.remnant && anomaly.r0 >= 120.0) {
					makeRemnant(anomaly, now, rnd);
					changed = true;
				} else {
					iterator.remove();
					changed = true;
				}
				continue;
			}
			double ageHours = anomaly.ageHours(now);
			double[] pos = anomaly.position(ageHours);
			if (!Double.isFinite(pos[0]) || !Double.isFinite(pos[1])) {
				// битая запись (например dir=NaN из ручного JSON) не должна навсегда занимать слот
				iterator.remove();
				changed = true;
				continue;
			}
			double distance = Math.hypot(pos[0], pos[1]);
			if (distance > config.world_radius + 500.0) {
				iterator.remove();
				changed = true;
				continue;
			}
			// в полосе подавления аномалия активно рассасывается, и тем быстрее, чем ближе к центру:
			// 25 % скорости на 6к, полная — на 3к
			if (distance < config.suppress_until) {
				double span = Math.max(1.0, config.suppress_until - config.dissipate_radius);
				double closeness = Math.max(0.0, Math.min(1.0, (config.suppress_until - distance) / span));
				double perMinute = 1.0 - config.dissipate_rate_per_min * (0.25 + 0.75 * closeness) / 100.0;
				anomaly.shrink *= Math.pow(perMinute, hours * 60.0);
			}
			// сжалась в точку — тихо уходит из поля, без шрама
			if (anomaly.shrink < 1.0 && anomaly.radius(ageHours) < config.dissipate_min_radius) {
				iterator.remove();
				changed = true;
			}
		}

		// Население: рожаем ровно столько, сколько не хватает до цели. Никакой демографии —
		// попытка оценить смертность по сумме 1/жизнь врёт на сдвинутой возрастной структуре
		// (поле то выгорает, то раздувается). Разрыв восполняется за ~два часа, а на ускоренном
		// времени — за один тик, и выше цели поле не растёт.
		int deficit = config.population - reservedCount(field);
		if (deficit > 0 && hours > 0.0) {
			int births = Math.min(deficit, Math.max(1, (int) Math.ceil(deficit * 0.5 * hours)));
			for (int i = 0; i < births; i++) {
				if (spawnAuto(field, config, rnd, now, avoid, config.herald_enabled) != null) {
					changed = true;
				}
			}
		}
		return changed;
	}

	/** Предвестник дорос: он становится настоящей зоной, с компонентами и планом следов. */
	static void born(Anomaly anomaly, long now, AnomalyConfig config, Random rnd) {
		anomaly.precursor = false;
		anomaly.born = anomaly.birth_at > 0L && anomaly.birth_at <= now ? anomaly.birth_at : now;
		anomaly.birth_at = 0L;
		anomaly.justBorn = true;
		if (anomaly.components == null || anomaly.components.isEmpty()) {
			AnomalyClass cls = AnomalyClass.byId(anomaly.cls);
			anomaly.components = AnomalyComponent.ids(AnomalyComponent.roll(cls,
					new Random(anomaly.id * 0x9E3779B97F4A7C15L), config, anomaly.r0));
			anomaly.invalidateComponents();
		}
		// план следов не создаём на нулевом радиусе: зона растёт первые часы, и следы легли бы
		// в 1–2 км от точки, где аномалии ещё нет. Планирует trail(), когда зона доросла;
		// исключение — тестовая (pinned) зона, она сразу полноразмерная
		if ((anomaly.scars == null || anomaly.scars.isEmpty())
				&& (anomaly.pinned || anomaly.radius(anomaly.ageHours(now)) >= anomaly.r0 * 0.85)) {
			anomaly.scars = AnomalyScars.planAt(anomaly.x0, anomaly.z0, anomaly.r0, AnomalyClass.byId(anomaly.cls),
					new Random(anomaly.id * 0x2545F4914F6CDD1DL), false, anomaly.components);
			anomaly.scar_cx = anomaly.x0;
			anomaly.scar_cz = anomaly.z0;
			// опоясывающая рана по настоящему контуру: один раз при рождении, по boundaryPoint
			anomaly.scars.addAll(AnomalyScars.woundRing(anomaly, anomaly.ageHours(now),
					new Random(anomaly.id * 0x5DEECE66DL)));
		}
		anomaly.scar_next = 0;
		anomaly.scar_cursor = 0;
		// план новый — проверка воды для op 0 должна пройти заново
		anomaly.scar_wet_for = -1;
	}

	/** Сколько живых зон, не считая осколков и не родившихся: иначе они занимают слоты. */
	private static int liveCount(AnomalyFieldData field) {
		int count = 0;
		for (Anomaly anomaly : field.anomalies) {
			if (!anomaly.remnant && !anomaly.precursor) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Занятые слоты населения: живые зоны плюс предвестники. Предвестник — это уже обещанная
	 * зона, и без его учёта поле во время окна в 10–30 минут рожало бы новую каждый такт.
	 */
	private static int reservedCount(AnomalyFieldData field) {
		int count = 0;
		for (Anomaly anomaly : field.anomalies) {
			if (!anomaly.remnant) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Осколок умершей зоны: та же точка и форма, но меньше, без дрейфа, без урона и без новых следов.
	 * Компоненты остаются только видимые и безвредные — по ним видно, что здесь было.
	 */
	private static void makeRemnant(Anomaly anomaly, long now, Random rnd) {
		// сначала переносим точку рождения туда, где зона умерла: иначе осколок всплывёт за
		// километры от места смерти, потому что дрейф считается от x0/z0
		double[] death = anomaly.position(anomaly.ageHours(now));
		if (Double.isFinite(death[0]) && Double.isFinite(death[1])) {
			anomaly.x0 = death[0];
			anomaly.z0 = death[1];
		}
		anomaly.remnant = true;
		anomaly.fed_hours = 0.0;
		anomaly.r0 = Math.max(60.0, anomaly.r0 * (0.25 + rnd.nextDouble() * 0.15));
		anomaly.born = now;
		anomaly.life_hours = 1.0 + rnd.nextDouble() * 5.0;
		anomaly.speed = 0.0;
		anomaly.arc = 0.0;
		anomaly.shrink = 1.0;
		anomaly.scars = new ArrayList<>();
		anomaly.scar_next = 0;
		anomaly.scar_cursor = 0;
		anomaly.scar_wet_for = -1;
		List<String> keep = new ArrayList<>();
		for (String id : anomaly.components) {
			AnomalyComponent component = AnomalyComponent.byId(id);
			if (component != null && component.isVisible() && component != AnomalyComponent.ECHO_BURSTS
					&& component != AnomalyComponent.STORM_CORE && component != AnomalyComponent.FLOAT_ALL) {
				keep.add(id);
			}
		}
		anomaly.components = keep;
		anomaly.invalidateComponents();
		// население считает только живые зоны, поэтому id не трогаем и класс оставляем ради цвета
	}

	/** Знак рождения: кто рядом, тот слышит и видит, что зона встала на место. */
	private static boolean bornSignals(MinecraftServer server, ServerLevel level, long now) {
		Set<Long> played = new HashSet<>();
		boolean any = false;
		for (Anomaly anomaly : data.anomalies) {
			if (!anomaly.justBorn) {
				continue;
			}
			any = true;
			double[] pos = anomaly.position(anomaly.ageHours(now));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.level() != level || played.contains(anomaly.id)) {
					continue;
				}
				if (Math.hypot(player.getX() - pos[0], player.getZ() - pos[1]) > 192.0) {
					continue;
				}
				AnomalyEffects.birth(level, player, pos[0], pos[1], AnomalyClass.byId(anomaly.cls).color);
				played.add(anomaly.id);
			}
			anomaly.justBorn = false;
		}
		return any;
	}

	// --- запросы ---

	/** Все аномалии, чья граница ближе {@code range} к точке. Учитывается форма, а не только центр. */
	public static List<Zone> near(ServerLevel level, double px, double pz, double range) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled || data.anomalies.isEmpty()) {
			return List.of();
		}
		if (config.overworld_only && level.dimension() != Level.OVERWORLD) {
			return List.of();
		}
		long now = AnomalyClock.now();
		List<Zone> out = new ArrayList<>();
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				continue;
			}
			// грубый отсев по нынешнему центру: иначе 800 зон на каждого игрока дважды в секунду
			// считают полный signedDistance с суммой синусов
			double[] center = anomaly.position(anomaly.ageHours(now));
			if (Math.hypot(px - center[0], pz - center[1])
					> anomaly.maxRadius() * anomaly.shrink + range) {
				continue;
			}
			Zone zone = anomaly.zone(now);
			if (zone.radius() <= 0.0) {
				continue;
			}
			if (zone.signedDistance(px, pz) <= range) {
				out.add(zone);
			}
		}
		return out;
	}

	/** Не родившиеся зоны рядом: по ним играет предвестник. Они не зоны и дозы не дают. */
	public static List<Anomaly> heralds(ServerLevel level, double px, double pz, double range) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled || !config.herald_enabled) {
			return List.of();
		}
		if (config.overworld_only && level.dimension() != Level.OVERWORLD) {
			return List.of();
		}
		List<Anomaly> out = new ArrayList<>();
		for (Anomaly anomaly : data.anomalies) {
			if (!anomaly.precursor) {
				continue;
			}
			// предвестник стоит на точке рождения: дрейфа у него ещё нет
			if (Math.hypot(px - anomaly.x0, pz - anomaly.z0) <= range + anomaly.r0 * 0.5) {
				out.add(anomaly);
			}
		}
		return out;
	}

	/** Все зоны сейчас — для отладки и статистики. Предвестники сюда не входят. */
	public static List<Zone> zones() {
		long now = AnomalyClock.now();
		List<Zone> out = new ArrayList<>(data.anomalies.size());
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				continue;
			}
			out.add(anomaly.zone(now));
		}
		return out;
	}

	/** Копия списка параметров: наружу не отдаём живую коллекцию. */
	public static List<Anomaly> all() {
		return List.copyOf(data.anomalies);
	}

	/** Живой список для внутренних читателей (узлы, отладка): без копии 600 ссылок в секунду. */
	static List<Anomaly> raw() {
		return data.anomalies;
	}

	/** Живые предвестники: для команды и для отладки. */
	public static List<Anomaly> precursors() {
		List<Anomaly> out = new ArrayList<>();
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				out.add(anomaly);
			}
		}
		return out;
	}

	public static Anomaly byId(long id) {
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.id == id) {
				return anomaly;
			}
		}
		return null;
	}

	/** Ближайшая по границе (а не по центру): у гиганта центр может быть за горизонтом. */
	public static Anomaly nearest(double px, double pz) {
		long now = AnomalyClock.now();
		Anomaly best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				continue;
			}
			double ageHours = anomaly.ageHours(now);
			// сжатие считается в точке самой аномалии, а не в точке вопроса
			double radius = anomaly.radius(ageHours);
			double distance = Math.abs(anomaly.signedDistance(px, pz, ageHours, radius));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = anomaly;
			}
		}
		return best;
	}

	/** Зона, внутри которой точка: выбирается самая сильная из перекрывающихся. */
	public static Anomaly containing(double px, double pz) {
		long now = AnomalyClock.now();
		Anomaly best = null;
		double bestStrength = -1.0;
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				continue;
			}
			Zone zone = anomaly.zone(now);
			if (zone.radius() > 0.0 && zone.contains(px, pz) && zone.strength() > bestStrength) {
				bestStrength = zone.strength();
				best = anomaly;
			}
		}
		return best;
	}

	public static Anomaly biggest() {
		Anomaly best = null;
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor || anomaly.remnant) {
				continue;
			}
			if (best == null || anomaly.r0 > best.r0) {
				best = anomaly;
			}
		}
		return best;
	}

	public static Anomaly oldest(long now) {
		Anomaly best = null;
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				continue;
			}
			if (best == null || anomaly.born < best.born) {
				best = anomaly;
			}
		}
		return best;
	}

	/** Поиск по слову: id, nearest, biggest, oldest. */
	public static Anomaly find(String token, double px, double pz) {
		if (token == null || token.isBlank()) {
			return nearest(px, pz);
		}
		String value = token.trim().toLowerCase(java.util.Locale.ROOT);
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		switch (value) {
			case "nearest", "ближ", "ближайшая" -> {
				return nearest(px, pz);
			}
			case "biggest", "крупнейшая" -> {
				return biggest();
			}
			case "oldest", "старейшая" -> {
				return oldest(AnomalyClock.now());
			}
			default -> {
			}
		}
		try {
			return byId(Long.parseLong(value));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Список по возрастанию расстояния до игрока: позицию считаем один раз, а не в компараторе. */
	public static List<Anomaly> sortedByDistance(double px, double pz) {
		long now = AnomalyClock.now();
		record Entry(Anomaly anomaly, double distance) {
		}
		List<Entry> entries = new ArrayList<>(data.anomalies.size());
		for (Anomaly anomaly : data.anomalies) {
			double[] pos = anomaly.position(anomaly.ageHours(now));
			entries.add(new Entry(anomaly, Math.hypot(pos[0] - px, pos[1] - pz)));
		}
		entries.sort(Comparator.comparingDouble(Entry::distance));
		List<Anomaly> out = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			out.add(entry.anomaly());
		}
		return out;
	}

	// --- изменения ---

	public static boolean remove(long id) {
		boolean removed = data.anomalies.removeIf(anomaly -> anomaly.id == id);
		if (removed) {
			// не saveNow: удаление зоны третьим якорем идёт из тика игрока, а снимок поля
			// в 1.3 МБ — это стол. Периодика и остановка сервера его запишут
			markDirty();
		}
		return removed;
	}

	/** Тестовая аномалия из команды: приколота к точке, не дрейфует и не умирает. */
	public static Anomaly spawnTest(double x, double z, Double radius, AnomalyClass cls) {
		Anomaly anomaly = create(data, AnomalyMod.config, random, AnomalyClock.now(), x, z, radius, cls, true);
		data.anomalies.add(anomaly);
		saveNow();
		return anomaly;
	}

	public static Path path() {
		return path;
	}

	public static int count() {
		return data.anomalies.size();
	}

	/** Сколько FULL-генераций чанков под следы потрачено за текущие сутки поля. */
	public static int genCount() {
		return data.gen_count;
	}

	public record Stats(int total, int safe, int danger, int deadly, int pinned, int remnants,
			double coveragePercent, double budget, int precursors, int nested) {
	}

	public static Stats stats() {
		AnomalyConfig config = AnomalyMod.config;
		long now = AnomalyClock.now();
		int safe = 0;
		int danger = 0;
		int deadly = 0;
		int pinned = 0;
		int remnants = 0;
		int precursors = 0;
		int nested = 0;
		double area = 0.0;
		double budget = 0.0;
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.precursor) {
				precursors++;
				continue;
			}
			Zone zone = anomaly.zone(now);
			if (anomaly.remnant) {
				// осколок не зона: он идёт отдельным счётчиком и не раздувает покрытие с бюджетом
				remnants++;
				continue;
			}
			AnomalyClass cls = zone.cls();
			switch (cls) {
				case SAFE -> safe++;
				case DANGER -> danger++;
				case DEADLY -> deadly++;
			}
			if (anomaly.pinned) {
				pinned++;
			}
			if (anomaly.parent_id != 0L) {
				nested++;
			}
			// площадь эллипса, а не круга: вытянутость 0.45..2.6 заметно меняет покрытие
			area += Math.PI * zone.radius() * zone.radius() * Math.max(0.2, anomaly.stretch);
			budget += zone.budget();
		}
		double worldArea = Math.PI * (config == null ? 20000.0 : config.world_radius) * (config == null ? 20000.0 : config.world_radius);
		return new Stats(data.anomalies.size() - precursors, safe, danger, deadly, pinned, remnants,
				worldArea <= 0 ? 0.0 : area / worldArea * 100.0, budget, precursors, nested);
	}

	// --- сохранение ---

	/**
	 * Один поток на запись: тик-поток только сериализует снимок (это десяток миллисекунд раз
	 * в 30 с), а диск и переименование делает воркер. Иначе блокирующая запись 1.3 МБ входила
	 * в серверный тик и давала рывок ровно тогда, когда в зоне кто-то стоит.
	 */
	private static final java.util.concurrent.ExecutorService WRITER =
			java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "anomaly-save");
				thread.setDaemon(true);
				return thread;
			});
	/** Один замок на запись и порядок снимков: файл всегда последний, а не «кто успел». */
	private static final Object SAVE_LOCK = new Object();
	private static long writeSeq;
	private static long lastWrittenSeq;
	private static volatile boolean saving;

	static boolean saveNow() {
		if (path == null) {
			return false;
		}
		String json;
		long seq;
		Path file = path;
		synchronized (SAVE_LOCK) {
			data.clock_ms = AnomalyClock.now();
			lastSaveMs = System.currentTimeMillis();
			// номер снимка выдаём под замком, а сериализацию и диск держим вне его
			seq = ++writeSeq;
		}
		json = GSON.toJson(data);
		boolean ok = writeLocked(file, json, seq);
		// не сбросили dirty при сбое: следующая попытка будет, а не через пять минут
		dirty = !ok;
		return ok;
	}

	/**
	 * Снимок на тик-потоке, запись — в фоне. Снимки нумеруются: воркер, доехавший позже
	 * более нового синхронного снимка, не должен откатить файл назад (так терялись рождения
	 * и флаги узлов на /stop).
	 */
	static void saveAsync() {
		if (path == null) {
			return;
		}
		String json;
		long seq;
		Path file = path;
		synchronized (SAVE_LOCK) {
			if (saving) {
				return;
			}
			data.clock_ms = AnomalyClock.now();
			lastSaveMs = System.currentTimeMillis();
			try {
				long started = System.nanoTime();
				json = GSON.toJson(data);
				long ms = (System.nanoTime() - started) / 1_000_000L;
				lastBytes = json.length();
				// Потолка на размер нет (population до 5000) — тогда сейв может стоить сотни мс.
				// Роняем не тик, а знание: длительность видна в логе, а интервал растёт с файлом
				if (ms > 25L) {
					warnOnce("сериализация поля " + ms + " мс / " + (lastBytes / 1024) + " КБ",
							new IllegalStateException("большое поле"));
				}
			} catch (RuntimeException e) {
				AnomalyMod.LOGGER.error("Anomaly: не смог сериализовать поле", e);
				return;
			}
			seq = ++writeSeq;
			saving = true;
			dirty = false;
		}
		try {
			WRITER.execute(() -> {
				try {
					// без внешнего замка: writeLocked сам берёт его коротко на сверку и move,
					// а запись 1.3 МБ идёт вне лока — иначе /stop ждал бы диск
					boolean ok = writeLocked(file, json, seq);
					if (!ok) {
						dirty = true;
					}
				} catch (RuntimeException e) {
					// воркер не должен умереть молча: иначе поле перестанет сохраняться совсем
					AnomalyMod.LOGGER.error("Anomaly: фоновая запись поля упала", e);
					dirty = true;
				} finally {
					saving = false;
				}
			});
		} catch (RuntimeException rejected) {
			// пул отказал: иначе saving останется true и поле перестанет сохраняться до рестарта
			saving = false;
			dirty = true;
			AnomalyMod.LOGGER.error("Anomaly: не смог поставить запись поля в очередь", rejected);
		}
	}

	public static boolean save() {
		return saveNow();
	}

	/** Пишем под замком: снимок старше уже записанного пропускаем. */
	private static boolean writeLocked(Path file, String json, long seq) {
		synchronized (SAVE_LOCK) {
			if (seq < lastWrittenSeq) {
				return true;
			}
		}
		// файл пишем ВНЕ замка: иначе синхронное сохранение на /stop ждёт дисковый I/O
		Path tmp = writeTmp(file, json);
		if (tmp == null) {
			return false;
		}
		synchronized (SAVE_LOCK) {
			if (seq < lastWrittenSeq) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// временный файл уберёт следующая запись
				}
				return true;
			}
			boolean ok = move(tmp, file);
			if (ok) {
				lastWrittenSeq = seq;
			}
			return ok;
		}
	}

	static boolean write(Path file, AnomalyFieldData field) {
		return writeText(file, GSON.toJson(field));
	}

	private static boolean writeText(Path file, String json) {
		Path tmp = writeTmp(file, json);
		return tmp != null && move(tmp, file);
	}

	/** Записать строку в уникальный временный файл рядом с целью. null — не вышло. */
	private static Path writeTmp(Path file, String json) {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			cleanTmp(parent, file);
			// уникальный tmp: фоновый воркер и синхронная запись на stop не должны
			// писать один и тот же временный файл
			Path tmp = file.resolveSibling(file.getFileName() + "." + System.nanoTime() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			return tmp;
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Anomaly: не смог записать поле в {}", file, e);
			return null;
		}
	}

	/**
	 * Убрать брошенные временные файлы: kill -9 в момент записи оставлял 1.3 МБ в world/data,
	 * и они копились. Чужие (свежие) не трогаем — их может писать остановленный поток.
	 */
	private static void cleanTmp(Path parent, Path file) {
		if (parent == null) {
			return;
		}
		String prefix = file.getFileName().toString() + ".";
		long cutoff = System.currentTimeMillis() - 30 * 60_000L;
		try (var list = Files.newDirectoryStream(parent, prefix + "*.tmp")) {
			for (Path stale : list) {
				try {
					if (Files.getLastModifiedTime(stale).toMillis() < cutoff) {
						Files.deleteIfExists(stale);
					}
				} catch (IOException ignored) {
					// следующий сейв попробует снова
				}
			}
		} catch (IOException ignored) {
			// каталога нет или нет прав — не повод падать
		}
	}

	/** Атомарно подменить файл. */
	private static boolean move(Path tmp, Path file) {
		try {
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Anomaly: не смог заменить поле {}", file, e);
			return false;
		}
	}

	static AnomalyFieldData read(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AnomalyFieldData.class);
		} catch (Exception e) {
			AnomalyMod.LOGGER.error("Anomaly: поле {} не читается ({}), начинаю новое (старый файл в .bak)",
					file, e.getMessage());
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// копия не обязательна: файл всё равно битый
			}
			return null;
		}
	}

	// --- генерация ---

	static void seedPopulation(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now) {
		int target = config.population;
		for (int i = 0; i < target; i++) {
			// заселение старого мира — без предвестников: поле не может быть пустым на входе
			Anomaly anomaly = spawnAuto(field, config, rnd, now, Avoid.none(), false);
			if (anomaly == null) {
				continue;
			}
			// мир не пустой на старте: часть аномалий уже прожила сколько-то внутри своей жизни
			double ageHours = rnd.nextDouble() * anomaly.life_hours;
			double[] drift = Anomaly.position(0.0, 0.0, anomaly.speed, anomaly.dir, anomaly.arc, ageHours);
			anomaly.born = now - (long) (ageHours * HOUR_MS);
			anomaly.x0 -= drift[0];
			anomaly.z0 -= drift[1];
		}
	}

	/** Случайная аномалия в кольце рождения; null, если за 24 попытки не нашлось свободного места. */
	static Anomaly spawnAuto(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now) {
		return spawnAuto(field, config, rnd, now, Avoid.none(), false);
	}

	static Anomaly spawnAuto(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, Avoid avoid) {
		return spawnAuto(field, config, rnd, now, avoid, config != null && config.herald_enabled);
	}

	static Anomaly spawnAuto(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, Avoid avoid,
			boolean herald) {
		// вложенность: иногда зона рождается внутри большой, и там уже своё разрушение
		if (config.nested_chance > 0.0 && rnd.nextDouble() < config.nested_chance) {
			Anomaly nested = spawnNested(field, config, rnd, now, avoid);
			if (nested != null) {
				if (herald) {
					herald(nested, now, config, rnd);
				}
				field.anomalies.add(nested);
				return nested;
			}
		}
		double inner = Math.max(0, config.spawn_min_radius);
		double outer = Math.max(inner + 1.0, config.world_radius * 0.98);
		for (int attempt = 0; attempt < 24; attempt++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			// равномерно по площади, а не по радиусу: иначе центр кольца перегружен
			double distance = Math.sqrt(inner * inner + rnd.nextDouble() * (outer * outer - inner * inner));
			double x = Math.cos(angle) * distance;
			double z = Math.sin(angle) * distance;
			if (avoid.blocks(x, z)) {
				continue;
			}
			Anomaly candidate = create(field, config, rnd, now, x, z, null, null, false);
			// клиренс считается не по точке рождения, а по будущему радиусу: зона на 3000
			// дорастает до базы за часы, и «260 блоков от игрока» её не спасают
			if (avoid.blocks(x, z, candidate.r0)) {
				continue;
			}			if (!clashes(field, candidate)) {
				if (herald) {
					herald(candidate, now, config, rnd);
				}
				field.anomalies.add(candidate);
				return candidate;
			}
		}
		return null;
	}

	/** Вложенная зона: маленькая внутри большой, со своим набором компонентов и своим классом. */
	static Anomaly spawnNested(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, Avoid avoid) {
		List<Anomaly> hosts = new ArrayList<>();
		for (Anomaly anomaly : field.anomalies) {
			if (!anomaly.pinned && !anomaly.remnant && !anomaly.precursor && anomaly.r0 >= 600.0) {
				hosts.add(anomaly);
			}
		}
		if (hosts.isEmpty()) {
			return null;
		}
		Anomaly host = hosts.get(rnd.nextInt(hosts.size()));
		double age = host.ageHours(now);
		double[] center = host.position(age);
		double hostRadius = host.radius(age);
		if (hostRadius < 300.0) {
			return null;
		}
		double angle = rnd.nextDouble() * Math.PI * 2.0;
		double distance = hostRadius * 0.45 * Math.sqrt(rnd.nextDouble());
		double x = center[0] + Math.cos(angle) * distance;
		double z = center[1] + Math.sin(angle) * distance;
		double radius = Math.max(config.radius_min, hostRadius * (0.16 + rnd.nextDouble() * 0.14));
		// ребёнок не должен родиться на базе: клиренс действует и для вложенных зон
		if (avoid.blocks(x, z, radius * 0.5)) {
			return null;
		}
		Anomaly child = create(field, config, rnd, now, x, z, radius, AnomalyClass.byId(host.cls), false);
		child.parent_id = host.id;
		return child;
	}

	/** +10–30 минут шума на точке будущего рождения: зона не появляется молча. */
	private static void herald(Anomaly candidate, long now, AnomalyConfig config, Random rnd) {
		int min = Math.max(1, config.herald_min_minutes);
		int max = Math.max(min, config.herald_max_minutes);
		long minutes = min + rnd.nextInt(Math.max(1, max - min + 1));
		candidate.precursor = true;
		candidate.birth_at = now + minutes * 60_000L;
		candidate.born = now;
		candidate.scars = new ArrayList<>();
		candidate.scar_next = 0;
		candidate.scar_cursor = 0;
		candidate.scar_cx = candidate.x0;
		candidate.scar_cz = candidate.z0;
	}

	private static boolean clashes(AnomalyFieldData field, Anomaly candidate) {
		for (Anomaly other : field.anomalies) {
			if (other.pinned || !other.cls.equals(candidate.cls)) {
				continue;
			}
			// вложенная зона перекрывается с родителем по определению — это не столкновение
			if (candidate.parent_id != 0L && candidate.parent_id == other.id) {
				continue;
			}
			double need = (other.r0 + candidate.r0) * 0.45;
			if (Math.hypot(other.x0 - candidate.x0, other.z0 - candidate.z0) < need) {
				return true;
			}
		}
		return false;
	}

	static Anomaly create(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, double x, double z,
			Double radius, AnomalyClass forcedClass, boolean pinned) {
		Anomaly anomaly = new Anomaly();
		anomaly.id = field.next_id++;
		anomaly.born = now;
		anomaly.pinned = pinned;
		anomaly.x0 = x;
		anomaly.z0 = z;

		double distance = Math.hypot(x, z);
		AnomalyClass cls = forcedClass != null
				? forcedClass
				: config.classFor(distance, (rnd.nextDouble() * 2.0 - 1.0) * config.class_blur);
		anomaly.cls = cls.id;

		double r = radius != null ? radius : pickRadius(config, field, rnd);
		anomaly.r0 = Math.max(config.radius_min, Math.min(config.radius_max, r));
		anomaly.life_hours = pinned
				? Math.max(24.0 * 365.0, config.lifetime_max_hours)
				: lifeFor(anomaly.r0, config, rnd);

		anomaly.stretch = 0.45 + rnd.nextDouble() * 2.15;
		anomaly.rotation = rnd.nextDouble() * Math.PI * 2.0;

		// Гармоники с длиной волны в блоках, а не с фиксированным k: у зоны R=2000 лепесток
		// k=3 растянут на 4000 блоков, и в кадре он выглядит прямой стеной. Низкие гармоники
		// задают общий характер, «зубцы» считаются так, чтобы волна была ~110 блоков, а
		// отклонение — 12–42 блока независимо от радиуса.
		int count = 3 + rnd.nextInt(2);
		for (int i = 0; i < count; i++) {
			anomaly.harmonics.add(new Harmonic(2 + i,
					0.08 + rnd.nextDouble() * 0.22,
					rnd.nextDouble() * Math.PI * 2.0,
					(rnd.nextDouble() * 2.0 - 1.0) * 0.009,
					0.10 + rnd.nextDouble() * 0.20,
					0.10 + rnd.nextDouble() * 0.30,
					rnd.nextDouble() * Math.PI * 2.0));
		}
		int teeth = 1 + rnd.nextInt(2);
		for (int i = 0; i < teeth; i++) {
			int k = (int) Math.round(2.0 * Math.PI * anomaly.r0 / 110.0) + i;
			k = Math.max(4, Math.min(120, k));
			double amp = (12.0 + rnd.nextDouble() * 30.0) / anomaly.r0;
			anomaly.harmonics.add(new Harmonic(k, amp,
					rnd.nextDouble() * Math.PI * 2.0,
					(rnd.nextDouble() * 2.0 - 1.0) * 0.02,
					0.20 + rnd.nextDouble() * 0.30,
					0.10 + rnd.nextDouble() * 0.40,
					rnd.nextDouble() * Math.PI * 2.0));
		}
		anomaly.breath = 0.015 + rnd.nextDouble() * 0.025;
		anomaly.breath_speed = 0.2 + rnd.nextDouble() * 0.4;

		anomaly.speed = pinned ? 0.0 : config.driftSpeedPerHour(anomaly.r0);
		anomaly.dir = rnd.nextDouble() * Math.PI * 2.0;
		// за всю жизнь не больше 10° поворота, поэтому дуга — это медленный изгиб, а не спираль
		double maxTurn = Math.toRadians(10.0);
		anomaly.arc = (rnd.nextDouble() * 2.0 - 1.0) * maxTurn / Math.max(0.5, anomaly.life_hours);

		anomaly.core_offset = 0.12 + rnd.nextDouble() * 0.25;
		anomaly.core_angle = rnd.nextDouble() * Math.PI * 2.0;
		anomaly.core_spin = (rnd.nextDouble() * 2.0 - 1.0) * 0.03;
		anomaly.components = AnomalyComponent.ids(AnomalyComponent.roll(cls, rnd, config, anomaly.r0));
		// следы планируются сразу: они применяются лениво, по 1–2 чанка в секунду
		anomaly.scars = AnomalyScars.plan(anomaly, cls, new Random(anomaly.id * 0x9E3779B97F4A7C15L));
		anomaly.scar_cx = x;
		anomaly.scar_cz = z;
		return anomaly;
	}

	/** Мелких много, гигантов единицы. Доли подобраны так, чтобы поле читалось, а не заливало карту. */
	private static double pickRadius(AnomalyConfig config, AnomalyFieldData field, Random rnd) {
		double roll = rnd.nextDouble();
		double radius;
		// 81 % рождений приходилось на 100–300 блоков: поле из мелких пятен, которые не запоминаются.
		// Теперь крупных заметно больше, а мелкие остаются как «рябь» между ними.
		if (roll < 0.75) {
			radius = 100.0 + rnd.nextDouble() * 200.0;
		} else if (roll < 0.94) {
			radius = 300.0 + rnd.nextDouble() * 500.0;
		} else if (roll < 0.99) {
			radius = 800.0 + rnd.nextDouble() * 1200.0;
		} else {
			radius = 2000.0 + rnd.nextDouble() * 1000.0;
		}
		// «гигант» — это единицы на всё поле: один на сотню живых, крупных — один на дюжину.
		// Без потолка случайные выбросы складываются в треть мира, потому что площадь растёт
		// квадратично (три тысячи блоков — это 1.6 % карты каждая).
		if (radius >= 2000.0 && countAtLeast(field, 2000.0) >= Math.max(1, config.population / 100)) {
			radius = 800.0 + rnd.nextDouble() * 700.0;
		}
		if (radius >= 800.0 && countAtLeast(field, 800.0) >= Math.max(2, config.population / 12)) {
			radius = 300.0 + rnd.nextDouble() * 500.0;
		}
		return Math.max(config.radius_min, Math.min(config.radius_max, radius));
	}

	private static int countAtLeast(AnomalyFieldData field, double radius) {
		int count = 0;
		for (Anomaly anomaly : field.anomalies) {
			// осколки умерших не считаются: иначе они держали бы потолок крупных зон
			if (!anomaly.pinned && !anomaly.remnant && !anomaly.precursor && anomaly.r0 >= radius) {
				count++;
			}
		}
		return count;
	}

	/** Жизнь растёт с размером, но не выше потолка (168 ч). */
	private static double lifeFor(double radius, AnomalyConfig config, Random rnd) {
		double hours;
		if (radius < 300.0) {
			hours = 2.0 + rnd.nextDouble() * 6.0;
		} else if (radius < 800.0) {
			hours = 12.0 + rnd.nextDouble() * 36.0;
		} else if (radius < 2000.0) {
			hours = 48.0 + rnd.nextDouble() * 48.0;
		} else {
			hours = 96.0 + rnd.nextDouble() * 72.0;
		}
		return Math.max(0.2, Math.min(config.lifetime_max_hours, hours));
	}
}



