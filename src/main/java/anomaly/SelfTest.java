package anomaly;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Самопроверка ядра без клиента и без игрока: форма, дрейф, жизнь, классы, бюджет, рассеивание,
 * население и сохранение. Запускается системным свойством/переменной {@code ANOMALY_SELFTEST}
 * при старте или командой {@code /anomaly selftest} прямо в игре.
 * <p>
 * Проверки не трогают живое поле: всё гоняется на локальной копии {@link AnomalyFieldData}.
 */
public final class SelfTest {
	private static boolean done;

	private SelfTest() {
	}

	/** Итог прогона: счётчики и тексты провалов — их показывает команда. */
	public static final class Result {
		public int passed;
		public int failed;
		public final List<String> failures = new ArrayList<>();

		public int total() {
			return passed + failed;
		}
	}

	public static void onTick(MinecraftServer server) {
		if (done) {
			return;
		}
		done = true;
		Result result = run(server);
		AnomalyMod.LOGGER.info("SELFTEST: {} из {} — {}", result.passed, result.total(),
				result.failed == 0 ? "PASS" : "FAILED");
		for (String failure : result.failures) {
			AnomalyMod.LOGGER.error("SELFTEST FAIL: {}", failure);
		}
	}

	public static Result run(MinecraftServer server) {
		Result result = new Result();
		AnomalyConfig config = new AnomalyConfig();
		Random rnd = new Random(20261120L);

		runGroup(result, "форма", () -> checkShape(result, config, rnd));
		runGroup(result, "дрейф", () -> checkDrift(result, config, rnd));
		runGroup(result, "жизнь", () -> checkLife(result, config, rnd));
		runGroup(result, "классы", () -> checkClasses(result, config, rnd));
		runGroup(result, "бюджет", () -> checkBudget(result));
		runGroup(result, "рассеивание", () -> checkSuppression(result, config));
		runGroup(result, "население", () -> checkPopulation(result, config, rnd));
		runGroup(result, "сохранение", () -> checkPersistence(result, config, rnd));
		runGroup(result, "края", () -> checkEdges(result, config, rnd));
		if (server != null) {
			// живое поле должно быть читаемым: если оно битое, это видно здесь, а не в игре
			runGroup(result, "живое поле", () -> report(result,
					"поле читается (" + AnomalyField.count() + " зон)", AnomalyField.count() >= 0));
		}
		return result;
	}

	private static void runGroup(Result result, String name, Runnable body) {
		try {
			body.run();
		} catch (Throwable t) {
			report(result, "группа «" + name + "» упала: " + t, false);
		}
	}

	// --- форма ---

	private static void checkShape(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 9000, 0, 900.0, AnomalyClass.DANGER, false);
		// ставим в середину жизни: в момент рождения радиуса ещё нет
		anomaly.life_hours = 24.0;
		anomaly.born = -12L * 3_600_000L;
		Zone zone = anomaly.zone(0L);
		double radius = zone.radius();

		boolean onEdge = true;
		double previousX = 0;
		double previousZ = 0;
		double maxStep = 0;
		int points = 360;
		for (int i = 0; i <= points; i++) {
			double angle = i * Math.PI * 2.0 / points;
			double[] point = anomaly.boundaryPoint(angle, zone.ageHours(), radius);
			if (Math.abs(anomaly.signedDistance(point[0], point[1], zone.ageHours(), radius)) > 0.5) {
				onEdge = false;
			}
			if (i > 0) {
				maxStep = Math.max(maxStep, Math.hypot(point[0] - previousX, point[1] - previousZ));
			}
			previousX = point[0];
			previousZ = point[1];
		}
		report(result, "граница замкнута и точки лежат на ней", onEdge);
		report(result, "контур без разрывов (шаг " + String.format("%.1f", maxStep) + " при радиусе "
				+ Math.round(radius) + ")", maxStep < radius * 0.1);

		// форма меняется во времени: это «край дышит», а не статичный многоугольник
		boolean morphs = false;
		for (int i = 0; i < 48 && !morphs; i++) {
			double theta = i * Math.PI * 2.0 / 48;
			double now = anomaly.shapeFactor(theta, 0.0);
			double later = anomaly.shapeFactor(theta, 24.0);
			if (Math.abs(now - later) > 0.005) {
				morphs = true;
			}
		}
		report(result, "форма меняется за сутки", morphs);

		boolean factorSane = true;
		for (int i = 0; i < 180; i++) {
			double value = anomaly.shapeFactor(i * Math.PI * 2.0 / 180, 10.0);
			if (!(value >= 0.35 && value < 4.0)) {
				factorSane = false;
			}
		}
		report(result, "множитель формы в 0.35..4", factorSane);

		boolean harmonicsOk = anomaly.harmonics.size() >= 2 && anomaly.harmonics.size() <= 5;
		for (Harmonic harmonic : anomaly.harmonics) {
			harmonicsOk &= harmonic.valid();
		}
		report(result, "гармоник 2–5 и все корректны", harmonicsOk);
	}

	// --- дрейф ---

	private static void checkDrift(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		boolean turnsOk = true;
		boolean lifetimeOk = true;
		boolean speedOk = true;
		for (int i = 0; i < 200; i++) {
			Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 12000, 0, null, null, false);
			double turn = Math.toDegrees(anomaly.arc * anomaly.life_hours);
			if (Math.abs(turn) > 10.0 + 1e-6) {
				turnsOk = false;
			}
			if (anomaly.life_hours > config.lifetime_max_hours + 1e-6) {
				lifetimeOk = false;
			}
			double perMinute = anomaly.speed / 60.0;
			if (perMinute < config.drift_min_per_minute - 0.01 || perMinute > config.drift_max_per_minute + 0.01) {
				speedOk = false;
			}
		}
		report(result, "дуга не больше 10° за жизнь", turnsOk);
		report(result, "жизнь не длиннее потолка " + Math.round(config.lifetime_max_hours) + " ч", lifetimeOk);
		report(result, "дрейф в " + config.drift_min_per_minute + "–" + config.drift_max_per_minute + " бл/мин", speedOk);

		Anomaly straight = new Anomaly();
		straight.x0 = 1000;
		straight.z0 = -500;
		straight.speed = 60;
		straight.dir = 0;
		straight.arc = 0;
		straight.r0 = 200;
		straight.life_hours = 10;
		double[] start = straight.position(0.0);
		double[] after = straight.position(5.0);
		report(result, "без кривизны путь прямой (60 бл/ч за 5 ч = 300)",
				Math.abs(after[0] - start[0] - 300.0) < 1e-6 && Math.abs(after[1] - start[1]) < 1e-6);

		// дуга 10°: хорда короче пути меньше чем на 0.2 %, иначе это уже не «почти прямая»
		Anomaly arc = new Anomaly();
		arc.x0 = 0;
		arc.z0 = 0;
		arc.speed = 60;
		arc.dir = 0;
		arc.arc = Math.toRadians(10.0) / 24.0;
		arc.r0 = 200;
		arc.life_hours = 24;
		double[] pos = arc.position(24.0);
		double chord = Math.hypot(pos[0], pos[1]);
		report(result, "дуга 10°: пройдено " + String.format("%.1f", chord) + " из 1440",
				chord > 1440.0 * 0.998);

		double[] again = arc.position(24.0);
		report(result, "позиция детерминирована", pos[0] == again[0] && pos[1] == again[1]);
	}

	// --- жизнь ---

	private static void checkLife(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 12000, 0, 800.0, AnomalyClass.DEADLY, false);
		anomaly.life_hours = 100.0;

		check(result, "в начале жизни радиуса нет", 0.0, anomaly.envelope(0.0), 1e-9);
		check(result, "в середине — полный радиус", 1.0, anomaly.envelope(50.0), 1e-9);
		check(result, "на исходе — снова ноль", 0.0, anomaly.envelope(100.0), 1e-9);

		double early = anomaly.radius(10.0);
		double middle = anomaly.radius(50.0);
		double late = anomaly.radius(95.0);
		report(result, "растёт: " + Math.round(early) + " < " + Math.round(middle), early < middle * 0.6);
		report(result, "сжимается: " + Math.round(late) + " < " + Math.round(middle), late < middle * 0.3);

		long now = 10_000_000L;
		anomaly.born = now - (long) (100.0 * 3_600_000L);
		report(result, "после срока жизни не живёт", !anomaly.alive(now));
		report(result, "за час до конца ещё жива", anomaly.alive(now - 3_600_000L));
	}

	// --- классы ---

	private static void checkClasses(Result result, AnomalyConfig config, Random rnd) {
		report(result, "до " + config.danger_from + " — безопасная",
				config.classFor(config.danger_from - 1, 0) == AnomalyClass.SAFE);
		report(result, "с " + config.danger_from + " — опасная",
				config.classFor(config.danger_from + 1, 0) == AnomalyClass.DANGER);
		report(result, "с " + config.deadly_from + " — смертельная",
				config.classFor(config.deadly_from + 1, 0) == AnomalyClass.DEADLY);

		// размытие: у рубежа сдвиг в полосе ±class_blur переставляет класс
		int band = config.class_blur;
		double nearBorder = config.danger_from - band / 2.0;
		report(result, "размытие переставляет класс у рубежа",
				config.classFor(nearBorder, 0) == AnomalyClass.SAFE
						&& config.classFor(nearBorder, band) == AnomalyClass.DANGER);

		AnomalyFieldData field = new AnomalyFieldData();
		for (int i = 0; i < config.population; i++) {
			AnomalyField.spawnAuto(field, config, rnd, 0L);
		}
		boolean outside = true;
		boolean classes = true;
		int safe = 0;
		int danger = 0;
		int deadly = 0;
		for (Anomaly anomaly : field.anomalies) {
			double distance = Math.hypot(anomaly.x0, anomaly.z0);
			if (distance < config.spawn_min_radius - 1.0 || distance > config.world_radius) {
				outside = false;
			}
			switch (AnomalyClass.byId(anomaly.cls)) {
				case SAFE -> safe++;
				case DANGER -> danger++;
				case DEADLY -> deadly++;
			}
		}
		report(result, "все рождаются не ближе " + config.spawn_min_radius + " (" + field.anomalies.size() + " зон)",
				outside);
		classes = safe > 0 && danger > 0 && deadly > 0;
		report(result, "встречаются все три класса (" + safe + "/" + danger + "/" + deadly + ")", classes);
	}

	// --- бюджет разрушения ---

	private static void checkBudget(Result result) {
		check(result, "радиус 50 не рушит", 0.0, AnomalyClass.damageBudget(50, AnomalyClass.DEADLY), 1e-9);
		check(result, "безопасная не рушит даже гигантом", 0.0,
				AnomalyClass.damageBudget(3000, AnomalyClass.SAFE), 1e-9);
		report(result, "300 блоков — не больше 300",
				AnomalyClass.damageBudget(300, AnomalyClass.DANGER) <= 300.0);
		report(result, "1000 блоков — не больше 5 000",
				AnomalyClass.damageBudget(1000, AnomalyClass.DANGER) <= 5_000.0);
		report(result, "2000 блоков — не больше 60 000",
				AnomalyClass.damageBudget(2000, AnomalyClass.DANGER) <= 60_000.0);
		check(result, "3000 блоков — потолок 250 000", 250_000.0,
				AnomalyClass.damageBudget(3000, AnomalyClass.DEADLY), 1e-6);
		report(result, "бюджет растёт с радиусом",
				AnomalyClass.damageBudget(1500, AnomalyClass.DEADLY)
						> AnomalyClass.damageBudget(600, AnomalyClass.DEADLY));
	}

	// --- рассеивание у центра ---

	private static void checkSuppression(Result result, AnomalyConfig config) {
		AnomalyConfig local = new AnomalyConfig();
		local.population = 0; // без рождений: проверяем только таяние

		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly drifting = AnomalyField.create(field, config, new Random(11L), 0L, 4500, 0, 800.0,
				AnomalyClass.DANGER, false);
		drifting.life_hours = 100.0;
		drifting.born = -50L * 3_600_000L; // зрелость
		field.anomalies.add(drifting);
		double before = drifting.radius(50.0);

		// час в полосе подавления: 5 % в минуту → остаётся около 4.6 % радиуса
		AnomalyField.tickData(field, local, new Random(12L), 3_600_000L, 1.0);
		double after = drifting.radius(51.0);
		report(result, "за час в полосе радиус тает до " + String.format("%.0f", after) + " из "
						+ String.format("%.0f", before),
				after < before * 0.1 && after > 0.0);
		report(result, "сжатая но ещё не нулевая остаётся в поле", field.anomalies.contains(drifting));

		// ещё час — и она сжимается ниже порога и тихо уходит
		AnomalyField.tickData(field, local, new Random(13L), 7_200_000L, 1.0);
		report(result, "сжатая в точку аномалия удаляется без шрама", field.anomalies.isEmpty());

		// снаружи полосы ничего не тает
		AnomalyFieldData far = new AnomalyFieldData();
		Anomaly intact = AnomalyField.create(far, config, new Random(14L), 0L, 12_000, 0, 800.0,
				AnomalyClass.DANGER, false);
		intact.life_hours = 100.0;
		intact.born = -50L * 3_600_000L;
		far.anomalies.add(intact);
		AnomalyField.tickData(far, local, new Random(15L), 7_200_000L, 2.0);
		report(result, "снаружи полосы подавления не тает", intact.shrink == 1.0);

		// тестовая зона не тает даже в центре мира
		AnomalyFieldData pinnedField = new AnomalyFieldData();
		Anomaly pinned = AnomalyField.create(pinnedField, config, new Random(16L), 0L, 0, 0, 500.0,
				AnomalyClass.DANGER, true);
		pinnedField.anomalies.add(pinned);
		AnomalyField.tickData(pinnedField, local, new Random(17L), 7_200_000L, 5.0);
		report(result, "тестовая зона в центре не тает", pinned.shrink == 1.0 && !pinnedField.anomalies.isEmpty());
	}

	// --- население ---

	private static void checkPopulation(Result result, AnomalyConfig config, Random rnd) {
		AnomalyConfig local = new AnomalyConfig();
		local.population = 60;
		AnomalyFieldData field = new AnomalyFieldData();
		AnomalyField.seedPopulation(field, local, rnd, 0L);
		int seeded = field.anomalies.size();
		report(result, "посев даёт целевое население (" + seeded + " из 60)", seeded >= 45);

		int min = seeded;
		int max = seeded;
		boolean changed = false;
		long now = 0L;
		long before = 0L;
		Random stepRandom = new Random(424242L);
		for (int hour = 0; hour < 300; hour++) {
			now += 3_600_000L;
			int previous = field.anomalies.size();
			if (AnomalyField.tickData(field, local, stepRandom, now, 1.0)) {
				changed = true;
			}
			int current = field.anomalies.size();
			if (current != previous) {
				before++;
			}
			min = Math.min(min, current);
			max = Math.max(max, current);
		}
		report(result, "население держится у цели: " + min + ".." + max + " при цели 60",
				min >= 45 && max <= 60);
		report(result, "за 300 ч были рождения и смерти (" + before + " изменений)", changed && before > 20);
		report(result, "поле не выросло сверх цели", field.anomalies.size() <= 60);
	}

	// --- сохранение ---

	private static void checkPersistence(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		for (int i = 0; i < 8; i++) {
			AnomalyField.spawnAuto(field, config, rnd, 1_700_000_000_000L);
		}
		field.clock_ms = 1_700_123_456_789L;
		field.next_id = 500L;
		Anomaly original = field.anomalies.get(0);

		Path file = null;
		try {
			file = Files.createTempFile("anomaly-selftest", ".json");
			report(result, "поле записывается", AnomalyField.write(file, field));
			AnomalyFieldData loaded = AnomalyField.read(file);
			report(result, "поле читается обратно", loaded != null && loaded.anomalies != null);
			if (loaded != null && loaded.anomalies != null) {
				report(result, "число аномалий совпадает (" + loaded.anomalies.size() + ")",
						loaded.anomalies.size() == field.anomalies.size());
				report(result, "время и счётчик id сохраняются",
						loaded.clock_ms == field.clock_ms && loaded.next_id == field.next_id);
				Anomaly copy = loaded.anomalies.get(0);
				report(result, "параметры аномалии не потерялись",
						copy.id == original.id && copy.r0 == original.r0
								&& copy.life_hours == original.life_hours && copy.arc == original.arc
								&& copy.harmonics.size() == original.harmonics.size());
				double[] before = original.position(3.0);
				double[] after = copy.position(3.0);
				report(result, "после круга через файл траектория та же",
						Math.abs(before[0] - after[0]) < 1e-9 && Math.abs(before[1] - after[1]) < 1e-9);
			}
		} catch (IOException e) {
			report(result, "сохранение: " + e, false);
		} finally {
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				} catch (IOException ignored) {
					// временный файл не важнее теста
				}
			}
		}

		Path broken = null;
		try {
			broken = Files.createTempFile("anomaly-selftest-broken", ".json");
			Files.writeString(broken, "{ это не json", StandardCharsets.UTF_8);
			report(result, "битый файл не роняет загрузку", AnomalyField.read(broken) == null);
		} catch (IOException e) {
			report(result, "битый файл: " + e, false);
		} finally {
			if (broken != null) {
				try {
					Files.deleteIfExists(broken);
					Files.deleteIfExists(broken.resolveSibling(broken.getFileName() + ".bak"));
				} catch (IOException ignored) {
					// мусор в temp
				}
			}
		}
	}

	// --- крайние случаи ---

	private static void checkEdges(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData empty = new AnomalyFieldData();
		AnomalyConfig zero = new AnomalyConfig();
		zero.population = 0;
		report(result, "пустое поле тикает без ошибок",
				!AnomalyField.tickData(empty, zero, rnd, 0L, 1.0) && empty.anomalies.isEmpty());

		Anomaly tiny = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 12000, 0, 5.0,
				AnomalyClass.DANGER, false);
		report(result, "радиус меньше минимума поднимается до " + Math.round(config.radius_min),
				tiny.r0 >= config.radius_min - 1e-9);

		Anomaly huge = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 12000, 0, 99_999.0,
				AnomalyClass.DEADLY, false);
		report(result, "радиус больше максимума срезается до " + Math.round(config.radius_max),
				huge.r0 <= config.radius_max + 1e-9);

		Anomaly pinned = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 0, 0, 500.0,
				AnomalyClass.DANGER, true);
		report(result, "тестовая зона приколота: не дрейфует и не умирает",
				pinned.speed == 0.0 && pinned.life_hours > 24 * 300 && pinned.radius(1.0) > 0.0);

		AnomalyConfig broken = new AnomalyConfig();
		broken.population = -5;
		broken.radius_min = 5000;
		broken.radius_max = 10;
		broken.spawn_min_radius = 30_000;
		broken.world_radius = 1000;
		broken.sanitize();
		report(result, "кривой конфиг приводится в порядок",
				broken.population == 0 && broken.radius_max >= broken.radius_min
						&& broken.world_radius > broken.spawn_min_radius);
	}

	// --- отчёт ---

	private static void check(Result result, String name, double expected, double actual, double tolerance) {
		report(result, name + " (ожидалось " + expected + ", получилось " + actual + ")",
				Math.abs(expected - actual) <= tolerance);
	}

	private static void report(Result result, String line, boolean ok) {
		if (ok) {
			result.passed++;
		} else {
			result.failed++;
			result.failures.add(line);
		}
	}
}


