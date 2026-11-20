package anomaly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Все настройки в {@code config/anomaly.json}. Как у MobScaler: правка требует рестарта,
 * битый файл уходит в {@code .bak}, а не затирается молча.
 */
public class AnomalyConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("anomaly.json");

	/** Выключатель всего мода. */
	public boolean enabled = true;
	/** Только Верхний мир (как усиление мобов у MobScaler). */
	public boolean overworld_only = true;

	// --- где аномалии вообще бывают ---
	/**
	 * Ближе этой дистанции аномалий нет вовсе. Дальше работает {@code density_stages}.
	 */
	public int safe_radius = 2000;

	/**
	 * Плотность: {@code [блоки от центра, вероятность на чанк]}, по возрастанию.
	 * 0.008 на 3000 — аномалия примерно в каждом 125-м чанке (шаг ~180 блоков),
	 * 0.050 на 20000 — в каждом 20-м (шаг ~71 блок).
	 */
	public List<double[]> density_stages = defaultDensityStages();

	// --- размеры ---
	public double radius_min = 6.0;
	public double radius_max = 14.0;
	/** С этой дистанции аномалии могут накладываться друг на друга. */
	public int stack_from = 10000;
	/** Шанс, что в чанке окажется больше одной аномалии (считается первым). */
	public double stack_second_chance = 0.25;
	/** Шанс третьей аномалии в чанке (проверяется раньше второй, шансы не складываются). */
	public double stack_third_chance = 0.07;

	// --- доза ---
	/** Прирост дозы в секунду внутри аномалии силы 1.0 (0.05 — 20 с до полной). */
	public double dose_gain_per_second = 0.05;
	/** Спад дозы в секунду снаружи (0.02 — 50 с с полной до нуля). */
	public double dose_decay_per_second = 0.02;
	/** Ниже этой дозы «хвост» эффекта уже не держится. */
	public double lingering_floor = 0.1;

	// --- обратная связь ---
	/** С какого расстояния аномалия показывает частицы и звук. */
	public int view_range = 48;
	/** Раз во сколько тиков аномалия издаёт звук. */
	public int sound_period = 90;
	/** Период серверного тика мода (10 = 0.5 с). */
	public int tick_period = 10;

	/** Типы, которые не спавнятся: gravity, vortex, frost, shadow, static. */
	public List<String> disabled_types = new ArrayList<>();

	public static AnomalyConfig load() {
		AnomalyConfig config = new AnomalyConfig();
		if (Files.exists(PATH)) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				AnomalyConfig loaded = GSON.fromJson(json, AnomalyConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				AnomalyMod.LOGGER.error("Не смог прочитать {}, беру умолчания (старый файл уйдёт в .bak)", PATH, e);
				backupBroken();
			}
		}
		if (config.density_stages == null) {
			config.density_stages = defaultDensityStages();
		}
		if (config.disabled_types == null) {
			config.disabled_types = new ArrayList<>();
		}
		config.save();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Не смог записать {}", PATH, e);
		}
	}

	public static List<double[]> defaultDensityStages() {
		return new ArrayList<>(List.of(
				new double[]{3000, 0.008},
				new double[]{8000, 0.020},
				new double[]{14000, 0.035},
				new double[]{20000, 0.050}));
	}

	/** Вероятность аномалии на чанк по ступеням; ближе первой ступени — 0. */
	public double densityAt(double distance) {
		if (distance < safe_radius) {
			return 0.0;
		}
		double[] table = densityTable();
		if (table.length == 0) {
			return 0.0;
		}
		if (distance <= table[0]) {
			return clamp01(table[1]);
		}
		for (int i = 2; i < table.length; i += 2) {
			if (distance < table[i]) {
				double t = (distance - table[i - 2]) / (table[i] - table[i - 2]);
				return clamp01(table[i - 1] + (table[i + 1] - table[i - 1]) * t);
			}
		}
		return clamp01(table[table.length - 1]);
	}

	/** Сколько аномалий в чанке: 1, 2 или 3 по шансам и только дальше {@code stack_from}. */
	public int stacksAt(double distance, double roll) {
		if (distance < stack_from) {
			return 1;
		}
		if (roll < stack_third_chance) {
			return 3;
		}
		if (roll < stack_second_chance) {
			return 2;
		}
		return 1;
	}

	/** 0 у первой ступени плотности, 1 у последней и дальше — из этого считается сила аномалии. */
	public double depthFactor(double distance) {
		double[] table = densityTable();
		if (table.length == 0) {
			return 0.0;
		}
		double start = table[0];
		double end = table[table.length - 2];
		if (distance <= start || end <= start) {
			return 0.0;
		}
		return clamp01((distance - start) / (end - start));
	}

	public boolean isEnabled(AnomalyType type) {
		return disabled_types == null || !disabled_types.contains(type.id);
	}

	private double[] tableCache;
	private int cacheSize = -1;

	private double[] densityTable() {
		if (density_stages == null || density_stages.size() < 2) {
			tableCache = new double[0];
			cacheSize = 0;
			return tableCache;
		}
		if (tableCache != null && cacheSize == density_stages.size()) {
			return tableCache;
		}
		double[] out = new double[density_stages.size() * 2];
		double last = Double.NEGATIVE_INFINITY;
		int n = 0;
		for (double[] step : density_stages) {
			if (step == null || step.length < 2 || step[0] <= last) {
				continue;
			}
			out[n++] = step[0];
			out[n++] = step[1];
			last = step[0];
		}
		double[] trimmed = n < 4 ? new double[0] : new double[n];
		if (n >= 4) {
			System.arraycopy(out, 0, trimmed, 0, n);
		}
		tableCache = trimmed;
		cacheSize = density_stages.size();
		return trimmed;
	}

	private static double clamp01(double value) {
		return value < 0.0 ? 0.0 : Math.min(value, 1.0);
	}

	private static void backupBroken() {
		try {
			Path backup = PATH.resolveSibling(PATH.getFileName() + ".bak");
			Files.copy(PATH, backup, StandardCopyOption.REPLACE_EXISTING);
			AnomalyMod.LOGGER.warn("Битый конфиг сохранён в {}", backup);
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Не смог сохранить копию битого конфига {}", PATH, e);
		}
	}
}

