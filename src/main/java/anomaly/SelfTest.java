package anomaly;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Самопроверка без клиента и без игрока: размещение, плотность, стопки, доза и крайние конфиги.
 * Включается только системным свойством {@code -Danomaly.selftest=1}.
 */
public final class SelfTest {
	private static boolean done;
	private static int passed;
	private static int failed;

	private SelfTest() {
	}

	public static void onTick(MinecraftServer server) {
		if (done) {
			return;
		}
		done = true;
		run(server);
	}

	public static void run(MinecraftServer server) {
		AnomalyConfig config = AnomalyMod.config;
		ServerLevel level = server.overworld();

		// каждая группа отдельно: падение одной не должно скрывать остальные проверки
		runGroup("плотность", () -> checkDensity(config));
		runGroup("размещение", () -> checkPlacement(level, config));
		runGroup("распределение", () -> checkDensityByDistance(level, config));
		runGroup("стопки", () -> checkStacks(config));
		runGroup("доза", () -> checkDose(config));
		runGroup("живые вызовы", () -> checkWorldCalls(server, level, config));
		runGroup("крайние конфиги", SelfTest::checkEdgeConfig);

		AnomalyMod.LOGGER.info("SELFTEST: {} из {} — {}", passed, passed + failed, failed == 0 ? "PASS" : "FAILED");
	}

	private static void runGroup(String name, Runnable body) {
		try {
			body.run();
		} catch (Throwable t) {
			failed++;
			AnomalyMod.LOGGER.error("SELFTEST FAIL: группа «{}» упала", name, t);
		}
	}

	private static void checkDensity(AnomalyConfig config) {
		if (config.density_stages == null || config.density_stages.isEmpty()) {
			report("стадии плотности заданы", false);
			return;
		}
		checkRate("ближе safe_radius плотность 0", 0.0, config.densityAt(config.safe_radius - 1));
		checkRate("на первой ступени плотность из конфига", config.density_stages.get(0)[1],
				config.densityAt(config.density_stages.get(0)[0]));

		double previous = 0.0;
		boolean grows = true;
		boolean bounded = true;
		for (double distance = 0; distance <= 24000; distance += 250) {
			double density = config.densityAt(distance);
			if (density < previous - 1e-9) {
				grows = false;
			}
			if (density < 0.0 || density > 1.0) {
				bounded = false;
			}
			previous = density;
		}
		report("плотность не убывает с расстоянием", grows);
		report("плотность всегда в 0..1", bounded);
	}

	private static void checkPlacement(ServerLevel level, AnomalyConfig config) {
		int found = 0;
		boolean same = true;
		boolean radiusOk = true;
		for (int chunkX = 200; chunkX < 700 && found < 12; chunkX++) {
			List<Anomaly> first = AnomalyField.inChunk(level, chunkX, 7, config);
			if (first.isEmpty()) {
				continue;
			}
			found++;
			List<Anomaly> second = AnomalyField.inChunk(level, chunkX, 7, config);
			if (second.size() != first.size()) {
				same = false;
				continue;
			}
			for (int i = 0; i < first.size(); i++) {
				Anomaly a = first.get(i);
				Anomaly b = second.get(i);
				if (a.type() != b.type() || a.x() != b.x() || a.z() != b.z() || a.id() != b.id()) {
					same = false;
				}
				if (a.radius() < config.radius_min - 1e-9 || a.radius() > config.radius_max + 1e-9) {
					radiusOk = false;
				}
				if (a.strength() < 0.6 - 1e-9 || a.strength() > 1.0 + 1e-9) {
					radiusOk = false;
				}
			}
		}
		report("аномалии вообще находятся в дальних чанках (" + found + ")", found > 0);
		report("один и тот же чанк даёт те же аномалии", same);
		report("радиус и сила в границах конфига", radiusOk);

		// ближе safe_radius зон нет ни в одном чанке
		int nearby = 0;
		for (int chunkX = -6; chunkX <= 6; chunkX++) {
			for (int chunkZ = -6; chunkZ <= 6; chunkZ++) {
				nearby += AnomalyField.inChunk(level, chunkX, chunkZ, config).size();
			}
		}
		check("около центра мира аномалий нет", 0, nearby);
	}

	private static void checkDensityByDistance(ServerLevel level, AnomalyConfig config) {
		int side = 40;
		int samples = side * side;
		int nearCount = countChunks(level, config, 250, side);
		int farCount = countChunks(level, config, 1125, side);

		// сравниваем не «втрое», а с ожиданием из самой кривой плотности: полоса 4–4.6к
		// даёт уже ~1 % на чанк, и жёсткий порог «×3» ловил не баг, а шум выборки
		double nearExpected = config.densityAt(4250) * samples;
		double farExpected = config.densityAt(18300) * samples;
		report("на 18к аномалий больше, чем на 4к (" + farCount + " против " + nearCount + ")",
				farCount >= nearCount * 2);
		report("ближняя полоса близка к своей плотности (" + nearCount + " при ожидании "
						+ String.format("%.1f", nearExpected) + ")",
				nearCount >= nearExpected * 0.5 && nearCount <= nearExpected * 1.8);
		report("дальняя полоса близка к своей плотности (" + farCount + " при ожидании "
						+ String.format("%.1f", farExpected) + ")",
				farCount >= farExpected * 0.5 && farCount <= farExpected * 1.8);
	}

	/** Сколько чанков с аномалиями в квадрате {@code side}×{@code side} вокруг чанка {@code originChunk}. */
	private static int countChunks(ServerLevel level, AnomalyConfig config, int originChunk, int side) {
		int count = 0;
		for (int chunkX = originChunk; chunkX < originChunk + side; chunkX++) {
			for (int chunkZ = -side / 2; chunkZ < side / 2; chunkZ++) {
				if (!AnomalyField.inChunk(level, chunkX, chunkZ, config).isEmpty()) {
					count++;
				}
			}
		}
		return count;
	}

	private static void checkStacks(AnomalyConfig config) {
		check("до stack_from всегда одна", 1, config.stacksAt(config.stack_from - 1, 0.0));
		check("дальше stack_from: третья", 3, config.stacksAt(config.stack_from + 1000, 0.0));
		check("дальше stack_from: вторая", 2, config.stacksAt(config.stack_from + 1000, config.stack_third_chance + 0.01));
		check("дальше stack_from: одна", 1, config.stacksAt(config.stack_from + 1000, 0.99));
	}

	private static void checkDose(AnomalyConfig config) {
		Anomaly anomaly = new Anomaly(AnomalyType.SHADOW, 1L, 0, 0, 10, 1.0);
		AnomalyState state = new AnomalyState();

		state.enter(anomaly, 10.0, config);
		checkRate("10 с внутри → доза 0.5", 0.5, state.dose);
		state.enter(anomaly, 100.0, config);
		checkRate("доза не выше 1.0", 1.0, state.dose);
		state.leave(5.0, config);
		checkRate("5 с снаружи → 0.9", 0.9, state.dose);
		state.leave(100.0, config);
		checkRate("доза до нуля", 0.0, state.dose);
		report("на нуле тип забыт", state.type == null);

		AnomalyState amp = new AnomalyState();
		amp.dose = 0.2;
		check("доза 0.2 → усилитель 0", 0, amp.amplifier());
		amp.dose = 0.5;
		check("доза 0.5 → усилитель 1", 1, amp.amplifier());
		amp.dose = 0.9;
		check("доза 0.9 → усилитель 2", 2, amp.amplifier());

		// смена типа не тащит чужую дозу: из «Тяжести» на 100 % в «Мороз» приходит не больше 30 %
		AnomalyState switching = new AnomalyState();
		switching.dose = 1.0;
		switching.type = AnomalyType.GRAVITY;
		switching.enter(new Anomaly(AnomalyType.FROST, 2L, 0, 0, 10, 1.0), 0.0, config);
		checkRate("смена типа срезает дозу до 0.3", 0.3, switching.dose);

		// счётчик тиков внутри растёт и сбрасывается снаружи (по нему ограничена левитация)
		check("тиков внутри после входа", 1, switching.ticksInside);
		switching.enter(new Anomaly(AnomalyType.FROST, 2L, 0, 0, 10, 1.0), 0.0, config);
		check("тиков внутри после двух входов", 2, switching.ticksInside);
		switching.leave(0.0, config);
		check("тиков внутри после выхода", 0, switching.ticksInside);

		// счётчик считается в тиках, а не в вызовах: 0.5 с = 10 тиков, значит кап левитации
		// в 60 тиков — это честные 3 секунды, а не 30
		AnomalyState exposure = new AnomalyState();
		exposure.enter(new Anomaly(AnomalyType.VORTEX, 3L, 0, 0, 10, 1.0), 0.5, config);
		check("0.5 с внутри = 10 тиков", 10, exposure.ticksInside);

		// перекрывающиеся столбы: тип скачет каждые полсекунды, но игрок не выходил,
		// поэтому дозу срезать нельзя — иначе в двойной аномалии она не наберётся вообще
		AnomalyState overlap = new AnomalyState();
		overlap.enter(new Anomaly(AnomalyType.GRAVITY, 4L, 0, 0, 10, 1.0), 10.0, config);
		overlap.enter(new Anomaly(AnomalyType.FROST, 5L, 0, 0, 10, 1.0), 0.0, config);
		checkRate("перескок между столбами не срезает дозу", 0.5, overlap.dose);
	}

	/**
	 * Проверка ванильных вызовов, которые не требуют сетевого коннекта: толчок, заморозка,
	 * частицы, звук, молния. {@code addEffect}/{@code hurt} на «пустом» игроке падают по NPE
	 * (нет {@code connection}), поэтому эти ветки проверяются только в игре.
	 */
	private static void checkWorldCalls(MinecraftServer server, ServerLevel level, AnomalyConfig config) {
		ServerPlayer player;
		try {
			player = new ServerPlayer(server, level,
					new GameProfile(UUID.nameUUIDFromBytes("anomaly-selftest".getBytes(StandardCharsets.UTF_8)), "AnomalyTest"),
					ClientInformation.createDefault());
		} catch (Throwable t) {
			failed++;
			AnomalyMod.LOGGER.error("SELFTEST FAIL: не смог создать тестового игрока", t);
			return;
		}

		try {
			player.push(0.1, 0.0, 0.1);
			player.setTicksFrozen(100);
			report("толчок и заморозка игрока без исключений", player.getTicksFrozen() == 100);
		} catch (Throwable t) {
			failed++;
			AnomalyMod.LOGGER.error("SELFTEST FAIL: толчок/заморозка", t);
		}

		// аномалия в 50 блоках: разряд не достанет до игрока, значит hurt не полезет в коннект
		Anomaly anomaly = new Anomaly(AnomalyType.STATIC, 42L, player.getX() + 50, player.getZ(), 10.0, 1.0);
		try {
			AnomalyEffects.ambience(level, player, anomaly, config);
			report("частицы и звук без исключений", true);
		} catch (Throwable t) {
			failed++;
			AnomalyMod.LOGGER.error("SELFTEST FAIL: частицы/звук", t);
		}

		boolean struck = false;
		for (int attempt = 0; attempt < 40 && !struck; attempt++) {
			struck = AnomalyEffects.strike(level, player, anomaly, 0.9);
		}
		report("молния разряда ставится в мир за 40 попыток", struck);
		player.discard();
	}

	private static void checkEdgeConfig() {
		AnomalyConfig config = new AnomalyConfig();
		config.density_stages = null;
		checkRate("ступени плотности null → 0 без NPE", 0.0, config.densityAt(15000));

		config.density_stages = new ArrayList<>(List.of(new double[]{3000, 0.01}));
		checkRate("одна ступень плотности → 0 без NPE", 0.0, config.densityAt(15000));

		config.density_stages = new ArrayList<>(List.of(
				new double[]{3000, 0.01}, new double[]{20000, 0.0}));
		double falling = 0.01 * (1.0 - (10000 - 3000) / 17000.0);
		checkRate("плотность падает → интерполяция в середине", falling, config.densityAt(10000));

		config.disabled_types = null;
		report("disabled_types null → все типы включены", config.isEnabled(AnomalyType.GRAVITY));

		config.disabled_types = new ArrayList<>(List.of("gravity"));
		report("выключенный тип выключен", !config.isEnabled(AnomalyType.GRAVITY));
		report("остальные типы живы", config.isEnabled(AnomalyType.FROST));
	}

	private static void check(String name, int expected, int actual) {
		report(name + " (ожидалось " + expected + ", получилось " + actual + ")", expected == actual);
	}

	private static void checkRate(String name, double expected, double actual) {
		report(name + " (ожидалось " + expected + ", получилось " + actual + ")",
				Math.abs(expected - actual) < 1e-6);
	}

	private static void report(String line, boolean ok) {
		if (ok) {
			passed++;
			AnomalyMod.LOGGER.info("SELFTEST ok: {}", line);
		} else {
			failed++;
			AnomalyMod.LOGGER.error("SELFTEST FAIL: {}", line);
		}
	}
}



