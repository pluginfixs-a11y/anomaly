package anomaly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Поле аномалий: загрузка, тик, рождения и смерти, поиск вокруг игрока.
 * <p>
 * Поле лежит в {@code world/data/anomaly_field.json} — обычным текстом, чтобы его можно было
 * посмотреть глазами и поправить руками. Пишется атомарно (временный файл + переименование),
 * а сохраняется: при рождении/смерти, раз в минуту и на остановке сервера.
 * <p>
 * Тик дешёвый: у аномалии нет пошагового состояния, поэтому обход списка — это проверка возраста
 * и сжатия у центра, а не расчёт формы. Форма и позиция считаются только тогда, когда о них
 * спросили (эффекты игрока, отладочный контур).
 */
public final class AnomalyField {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "anomaly_field.json";
	private static final long HOUR_MS = 3_600_000L;

	private static AnomalyFieldData data = new AnomalyFieldData();
	private static Path path;
	private static Random random = new Random(1L);
	private static long lastSaveMs;
	private static long lastTickFieldMs;
	private static boolean dirty;
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
		// выключенный мод не должен ни сеять население, ни писать файл в мир
		if (config != null && config.enabled && (fresh || data.anomalies.isEmpty())) {
			seedPopulation(data, config, random, AnomalyClock.now());
			saveNow();
		}
		AnomalyMod.LOGGER.info("Anomaly: поле загружено — {} аномалий, население {}, время поля {} ч{}",
				data.anomalies.size(), config == null ? 0 : config.population,
				String.format("%.1f", AnomalyClock.fieldMs() / (double) HOUR_MS),
				fresh ? " (новое поле, население посеяно)" : "");
	}

	private static long newestBorn() {
		long newest = 0L;
		for (Anomaly anomaly : data.anomalies) {
			newest = Math.max(newest, anomaly.born);
		}
		return newest;
	}

	/** Раз в секунду: снять умершие, сжать забредшие к центру, добавить новые. */
	public static void tick(MinecraftServer server) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled) {
			return;
		}
		AnomalyClock.commit();
		long now = AnomalyClock.now();
		double hours = Math.max(0.0, (now - lastTickFieldMs) / (double) HOUR_MS);
		lastTickFieldMs = now;

		boolean changed = tickData(data, config, random, now, hours);
		if (changed) {
			dirty = true;
		}
		long real = System.currentTimeMillis();
		// файл на 250 зон — ~400 КБ, поэтому не чаще раза в 30 с на изменения и раз в пять
		// минут для часов; на остановке сервера пишем всегда
		boolean due = real - lastSaveMs > 30_000L;
		if ((dirty && due) || real - lastSaveMs > 5 * 60_000L) {
			saveNow();
		}
	}

	/**
	 * Один шаг поля без сервера — так его гоняет самопроверка. Возвращает true, если состав
	 * поля изменился (кого-то сняли или кто-то родился).
	 */
	static boolean tickData(AnomalyFieldData field, AnomalyConfig config, Random rnd, long now, double hours) {
		boolean changed = false;
		Iterator<Anomaly> iterator = field.anomalies.iterator();
		while (iterator.hasNext()) {
			Anomaly anomaly = iterator.next();
			// тестовая зона приколота: не стареет, не тает и не снимается сама
			if (anomaly.pinned) {
				continue;
			}
			if (!anomaly.alive(now)) {
				iterator.remove();
				changed = true;
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
		int deficit = config.population - field.anomalies.size();
		if (deficit > 0 && hours > 0.0) {
			int births = Math.min(deficit, Math.max(1, (int) Math.ceil(deficit * 0.5 * hours)));
			for (int i = 0; i < births; i++) {
				if (spawnAuto(field, config, rnd, now) != null) {
					changed = true;
				}
			}
		}
		return changed;
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

	/** Все зоны сейчас — для отладки и статистики. */
	public static List<Zone> zones() {
		AnomalyConfig config = AnomalyMod.config;
		long now = AnomalyClock.now();
		List<Zone> out = new ArrayList<>(data.anomalies.size());
		for (Anomaly anomaly : data.anomalies) {
			out.add(anomaly.zone(now));
		}
		return out;
	}

	/** Копия списка параметров: наружу не отдаём живую коллекцию. */
	public static List<Anomaly> all() {
		return List.copyOf(data.anomalies);
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
		AnomalyConfig config = AnomalyMod.config;
		long now = AnomalyClock.now();
		Anomaly best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Anomaly anomaly : data.anomalies) {
			double ageHours = anomaly.ageHours(now);
			double[] pos = anomaly.position(ageHours);
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

	public static Anomaly biggest() {
		Anomaly best = null;
		for (Anomaly anomaly : data.anomalies) {
			if (best == null || anomaly.r0 > best.r0) {
				best = anomaly;
			}
		}
		return best;
	}

	public static Anomaly oldest(long now) {
		Anomaly best = null;
		for (Anomaly anomaly : data.anomalies) {
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
			saveNow();
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

	public record Stats(int total, int safe, int danger, int deadly, int pinned, double coveragePercent,
			double budget) {
	}

	public static Stats stats() {
		AnomalyConfig config = AnomalyMod.config;
		long now = AnomalyClock.now();
		int safe = 0;
		int danger = 0;
		int deadly = 0;
		int pinned = 0;
		double area = 0.0;
		double budget = 0.0;
		for (Anomaly anomaly : data.anomalies) {
			Zone zone = anomaly.zone(now);
			AnomalyClass cls = zone.cls();
			switch (cls) {
				case SAFE -> safe++;
				case DANGER -> danger++;
				case DEADLY -> deadly++;
			}
			if (anomaly.pinned) {
				pinned++;
			}
			// площадь эллипса, а не круга: вытянутость до 2.25 заметно меняет покрытие
			area += Math.PI * zone.radius() * zone.radius() * Math.max(1.0, anomaly.stretch);
			budget += zone.budget();
		}
		double worldArea = Math.PI * (config == null ? 20000.0 : config.world_radius) * (config == null ? 20000.0 : config.world_radius);
		return new Stats(data.anomalies.size(), safe, danger, deadly, pinned,
				worldArea <= 0 ? 0.0 : area / worldArea * 100.0, budget);
	}

	// --- сохранение ---

	static boolean saveNow() {
		data.clock_ms = AnomalyClock.now();
		lastSaveMs = System.currentTimeMillis();
		dirty = false;
		return path != null && write(path, data);
	}

	public static boolean save() {
		return saveNow();
	}

	static boolean write(Path file, AnomalyFieldData field) {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(field), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Anomaly: не смог записать поле в {}", file, e);
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
			Anomaly anomaly = spawnAuto(field, config, rnd, now);
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
		double inner = Math.max(0, config.spawn_min_radius);
		double outer = Math.max(inner + 1.0, config.world_radius * 0.98);
		for (int attempt = 0; attempt < 24; attempt++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			// равномерно по площади, а не по радиусу: иначе центр кольца перегружен
			double distance = Math.sqrt(inner * inner + rnd.nextDouble() * (outer * outer - inner * inner));
			double x = Math.cos(angle) * distance;
			double z = Math.sin(angle) * distance;
			Anomaly candidate = create(field, config, rnd, now, x, z, null, null, false);
			if (!clashes(field, candidate)) {
				field.anomalies.add(candidate);
				return candidate;
			}
		}
		return null;
	}

	private static boolean clashes(AnomalyFieldData field, Anomaly candidate) {
		for (Anomaly other : field.anomalies) {
			if (other.pinned || !other.cls.equals(candidate.cls)) {
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

		anomaly.stretch = 0.55 + rnd.nextDouble() * 1.7;
		anomaly.rotation = rnd.nextDouble() * Math.PI * 2.0;

		int count = 2 + rnd.nextInt(4);
		for (int i = 0; i < count; i++) {
			int k = 2 + i;
			anomaly.harmonics.add(new Harmonic(k,
					0.05 + rnd.nextDouble() * 0.20,
					rnd.nextDouble() * Math.PI * 2.0,
					(rnd.nextDouble() * 2.0 - 1.0) * 0.009,
					0.10 + rnd.nextDouble() * 0.20,
					0.10 + rnd.nextDouble() * 0.30,
					rnd.nextDouble() * Math.PI * 2.0));
		}
		anomaly.breath = 0.01 + rnd.nextDouble() * 0.02;
		anomaly.breath_speed = 0.2 + rnd.nextDouble() * 0.4;

		anomaly.speed = pinned ? 0.0 : config.driftSpeedPerHour(anomaly.r0);
		anomaly.dir = rnd.nextDouble() * Math.PI * 2.0;
		// за всю жизнь не больше 10° поворота, поэтому дуга — это медленный изгиб, а не спираль
		double maxTurn = Math.toRadians(10.0);
		anomaly.arc = (rnd.nextDouble() * 2.0 - 1.0) * maxTurn / Math.max(0.5, anomaly.life_hours);

		anomaly.core_offset = 0.12 + rnd.nextDouble() * 0.25;
		anomaly.core_angle = rnd.nextDouble() * Math.PI * 2.0;
		anomaly.core_spin = (rnd.nextDouble() * 2.0 - 1.0) * 0.03;
		anomaly.type = pickType(config, cls, rnd).id;
		return anomaly;
	}

	/** Мелких много, гигантов единицы. Доли подобраны так, чтобы поле читалось, а не заливало карту. */
	private static double pickRadius(AnomalyConfig config, AnomalyFieldData field, Random rnd) {
		double roll = rnd.nextDouble();
		double radius;
		if (roll < 0.81) {
			radius = 100.0 + rnd.nextDouble() * 200.0;
		} else if (roll < 0.98) {
			radius = 300.0 + rnd.nextDouble() * 500.0;
		} else if (roll < 0.999) {
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
			if (!anomaly.pinned && anomaly.r0 >= radius) {
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

	private static AnomalyType pickType(AnomalyConfig config, AnomalyClass cls, Random rnd) {
		List<AnomalyType> allowed = new ArrayList<>(4);
		for (AnomalyType type : cls.types()) {
			if (config.isEnabled(type)) {
				allowed.add(type);
			}
		}
		if (allowed.isEmpty()) {
			for (AnomalyType type : AnomalyType.values()) {
				if (config.isEnabled(type)) {
					allowed.add(type);
				}
			}
		}
		return allowed.isEmpty() ? AnomalyType.SHADOW : allowed.get(rnd.nextInt(allowed.size()));
	}
}


