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
 * Настройки в {@code config/anomaly.json}. Правка требует рестарта, битый файл уходит в {@code .bak}.
 * <p>
 * Версия в файле есть не просто так: Gson подставляет инициализаторы для отсутствующих ключей,
 * поэтому по одному «ключа нет» миграцию не отличить от нового поля. {@code config_version = 0}
 * означает «файл от 0.1.0», и тогда геометрия перезаписывается на концепцию 0.2 (столбы 6–14
 * блоков → области 100–3000), а пользовательские числа вроде дозы остаются.
 */
public class AnomalyConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("anomaly.json");
	/** Текущая версия формата конфига. */
	public static final int VERSION = 2;

	public int config_version = 0;

	/** Выключатель всего мода. */
	public boolean enabled = true;
	/** Только Верхний мир: поле одно на мир, остальные измерения не трогаем. */
	public boolean overworld_only = true;

	// --- где и сколько ---
	/** Сколько живых аномалий держать в мире (цель). Поле само добивает население. */
	public int population = 250;
	/** Ближе этого от центра аномалии не рождаются. */
	public int spawn_min_radius = 6000;
	/** Край мира: дальше аномалии не рождаются и не ходят. */
	public int world_radius = 20000;

	// --- размеры и жизнь ---
	public double radius_min = 100.0;
	public double radius_max = 3000.0;
	/** Потолок жизни: 7 реальных суток. */
	public double lifetime_max_hours = 168.0;
	/** Дрейф, блоков в минуту (мелкие — к верхней границе, гиганты — к нижней). */
	public double drift_min_per_minute = 0.5;
	public double drift_max_per_minute = 3.0;

	// --- классы ---
	/** Полоса размытия на рубежах классов, блоков в каждую сторону. */
	public int class_blur = 800;
	public int danger_from = 10000;
	public int deadly_from = 15000;

	// --- рассеивание у центра ---
	public int dissipate_radius = 3000;
	public int suppress_until = 6000;
	public double dissipate_rate_per_min = 5.0;
	/** Ниже этого радиуса аномалия в подавленной полосе просто удаляется. */
	public double dissipate_min_radius = 20.0;

	// --- доза и обратная связь ---
	public double dose_gain_per_second = 0.05;
	public double dose_decay_per_second = 0.02;
	public double lingering_floor = 0.1;

	public int view_range = 48;
	public int sound_period = 90;
	/** Период серверного тика эффектов (10 = 0.5 с). Поле тикает раз в секунду отдельно. */
	public int tick_period = 10;

	/** Типы, которые не используются при генерации: gravity, vortex, frost, shadow, static. */
	public List<String> disabled_types = new ArrayList<>();

	public static AnomalyConfig load() {
		AnomalyConfig config = new AnomalyConfig();
		boolean existed = Files.exists(PATH);
		if (existed) {
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
		config.sanitize();
		if (config.config_version < VERSION) {
			config.migrate(existed);
		}
		config.save();
		return config;
	}

	/** Приведение чисел в разумные рамки: конфиг правят руками, и там бывает что угодно. */
	public void sanitize() {
		if (disabled_types == null) {
			disabled_types = new ArrayList<>();
		}
		population = Math.max(0, Math.min(5000, population));
		if (radius_min <= 0) {
			radius_min = 1.0;
		}
		if (radius_max < radius_min) {
			radius_max = radius_min;
		}
		spawn_min_radius = Math.max(0, spawn_min_radius);
		world_radius = Math.max(spawn_min_radius + 1000, world_radius);
		if (lifetime_max_hours <= 0.01) {
			lifetime_max_hours = 168.0;
		}
		dissipate_radius = Math.max(0, Math.min(dissipate_radius, suppress_until));
		dissipate_min_radius = Math.max(1.0, dissipate_min_radius);
		// 100 % в минуту и больше дают отрицательное основание степени и NaN в радиусе
		dissipate_rate_per_min = Math.max(0.0, Math.min(99.0, dissipate_rate_per_min));
		if (tick_period < 1) {
			tick_period = 1;
		}
		if (view_range < 1) {
			view_range = 1;
		}
		if (danger_from < spawn_min_radius) {
			danger_from = spawn_min_radius;
		}
		if (deadly_from < danger_from) {
			deadly_from = danger_from;
		}
		config_version = Math.max(config_version, 0);
	}

	/** 0.1.0 → 0.2: столбы 6–14 блоков заменяются областями, плотность по чанкам — населением. */
	private void migrate(boolean existed) {
		radius_min = 100.0;
		radius_max = 3000.0;
		spawn_min_radius = 6000;
		world_radius = 20000;
		population = 250;
		lifetime_max_hours = 168.0;
		sanitize();
		config_version = VERSION;
		if (existed) {
			AnomalyMod.LOGGER.info("Anomaly: конфиг обновлён до версии {} (геометрия сброшена на концепцию 0.2)", VERSION);
		}
	}

	public void save() {
		config_version = VERSION;
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Не смог записать {}", PATH, e);
		}
	}

	// --- производные величины ---


	/** Класс по расстоянию с размытием: {@code jitter} в пределах ±class_blur сдвигает рубеж. */
	public AnomalyClass classFor(double distance, double jitter) {
		double d = distance + jitter;
		if (d < danger_from) {
			return AnomalyClass.SAFE;
		}
		return d < deadly_from ? AnomalyClass.DANGER : AnomalyClass.DEADLY;
	}

	/** Гиганты почти стоят, мелкие шустрые: 0.5–3 блока в минуту. */
	public double driftSpeedPerHour(double radius) {
		double min = Math.max(0.0, drift_min_per_minute * 60.0);
		double max = Math.max(min, drift_max_per_minute * 60.0);
		double speed = 24_000.0 / Math.max(1.0, radius);
		return Math.max(min, Math.min(max, speed));
	}

	public boolean isEnabled(AnomalyType type) {
		return disabled_types == null || !disabled_types.contains(type.id);
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
