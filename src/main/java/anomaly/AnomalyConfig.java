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
	public static final int VERSION = 7;

	public int config_version = 0;

	/** Выключатель всего мода. */
	public boolean enabled = true;
	/** Только Верхний мир: поле одно на мир, остальные измерения не трогаем. */
	public boolean overworld_only = true;

	// --- где и сколько ---
	/** Сколько живых аномалий держать в мире (цель). Поле само добивает население. */
	public int population = 600;
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

	// --- разрушения ---
	public boolean scar_enabled = true;
	/** Чанков за проход: 2 — это 2 в секунду, проход один и только когда mspt ниже порога. */
	public int chunks_per_tick = 2;
	/** Блоков за тик: потолок работы разрушений, чтобы не вешать сервер. */
	public int blocks_per_tick = 2000;
	/** Порог mspt: выше него разрушения ждут следующего тика. */
	public double mspt_limit = 45.0;
	/**
	 * Сколько FULL-генераций чанков под следы разрешено за сутки поля. На новом поле 600 зон
	 * набирают 10–30 тыс. генераций; без лимита первый запуск растягивает worldgen на часы.
	 */
	public int chunk_gen_per_day = 6000;

	// --- предвестник и место рождения ---
	/** Шум и знаки за 10–30 минут до рождения зоны: зона не должна появляться молча. */
	public boolean herald_enabled = true;
	public int herald_min_minutes = 10;
	public int herald_max_minutes = 30;
	/** Не ближе этого к игроку рождается зона: дом не должен сносить без предупреждения. */
	public int spawn_clear_players = 256;
	/** То же для точек возрождения (кровать/якорь): это и есть «обжитое место». */
	public int spawn_clear_beds = 256;
	/** Столько блоков от найденной постройки (по загруженному чанку) зона не рождается. */
	public int spawn_clear_build = 96;

	/** Сколько часов поля даёт час стояния внутри зоны: кемпинг старит зону и убивает лут. */
	public double camp_feed = 2.0;

	/** Шанс, что новая зона родится внутри большой (вложенность): 0 — никогда. */
	public double nested_chance = 0.12;

	// --- шиза ---
	/** Слой звуков и фигур: включается остатком или дозой, только в дальних землях. */
	public boolean madness_enabled = true;
	/** Ближе этого от центра мира шиза не включается: у дома должны быть тишина и покой. */
	public int madness_min_distance = 6000;

	// --- доза и обратная связь ---
	public double dose_gain_per_second = 0.05;
	public double dose_decay_per_second = 0.02;
	public double lingering_floor = 0.1;

	/** Рейдовые узлы (кладовая, алтарь, якоря). Выключенные узлы не появляются, старые остаются в мире. */
	public boolean nodes_enabled = true;

	// --- остаток ---
	/** «Остаток» — долгие последствия на игрока, отдельная шкала в файле мира по UUID. */
	public boolean residue_enabled = true;
	/** Потолок остатка: наказание не бывает вечным. */
	public double residue_cap_hours = 24.0;
	/** Базовый спад, часов остатка за реальный час. Сон и компания ускоряют его отдельно. */
	public double residue_decay_per_hour = 0.8;

	public int view_range = 128;
	/** Период серверного тика эффектов (10 = 0.5 с). Поле тикает раз в секунду отдельно. */
	public int tick_period = 10;

	/** Компоненты, которые не используются при генерации: id из {@link AnomalyComponent}. */
	public List<String> disabled_components = new ArrayList<>();

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
		if (disabled_components == null) {
			disabled_components = new ArrayList<>();
		}
		// NaN и бесконечность проходят мимо сравнений вида «x <= 0», поэтому сначала чиним их
		radius_min = finite(radius_min, 100.0);
		radius_max = finite(radius_max, 3000.0);
		lifetime_max_hours = finite(lifetime_max_hours, 168.0);
		drift_min_per_minute = finite(drift_min_per_minute, 0.5);
		drift_max_per_minute = finite(drift_max_per_minute, 3.0);
		dissipate_rate_per_min = finite(dissipate_rate_per_min, 5.0);
		dissipate_min_radius = finite(dissipate_min_radius, 20.0);
		dose_gain_per_second = finite(dose_gain_per_second, 0.05);
		dose_decay_per_second = finite(dose_decay_per_second, 0.02);
		lingering_floor = finite(lingering_floor, 0.1);
		residue_cap_hours = finite(residue_cap_hours, 24.0);
		residue_decay_per_hour = finite(residue_decay_per_hour, 0.8);
		if (residue_cap_hours < 1.0) {
			residue_cap_hours = 1.0;
		}
		residue_cap_hours = Math.min(168.0, residue_cap_hours);
		residue_decay_per_hour = Math.max(0.0, Math.min(24.0, residue_decay_per_hour));
		if (!Double.isFinite(radius_max) || radius_max < radius_min) {
			radius_max = radius_min;
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
		suppress_until = Math.max(0, Math.min(suppress_until, world_radius - 1000));
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
		chunks_per_tick = Math.max(1, Math.min(16, chunks_per_tick));
		blocks_per_tick = Math.max(100, Math.min(20_000, blocks_per_tick));
		mspt_limit = Math.max(1.0, Math.min(1000.0, mspt_limit));
		if (view_range < 64) {
			// 0.2.0-alpha2 писал сюда 48: с такой дальностью зону не видно вообще
			view_range = 128;
			AnomalyMod.LOGGER.info("Anomaly: view_range < 64, поднят до 128 — иначе край зоны не виден");
		}
		if (danger_from < spawn_min_radius) {
			danger_from = spawn_min_radius;
		}
		if (deadly_from < danger_from) {
			deadly_from = danger_from;
		}
		// v6 → v7: предвестник, кемпинг, вложенность, лимит генерации, шиза
		herald_min_minutes = (int) Math.max(1, Math.min(240, herald_min_minutes));
		herald_max_minutes = (int) Math.max(herald_min_minutes, Math.min(600, herald_max_minutes));
		spawn_clear_players = Math.max(0, Math.min(5000, spawn_clear_players));
		spawn_clear_beds = Math.max(0, Math.min(5000, spawn_clear_beds));
		spawn_clear_build = Math.max(0, Math.min(512, spawn_clear_build));
		camp_feed = Math.max(0.0, Math.min(5.0, finite(camp_feed, 0.5)));
		nested_chance = Math.max(0.0, Math.min(0.5, finite(nested_chance, 0.12)));
		chunk_gen_per_day = Math.max(0, Math.min(200_000, chunk_gen_per_day));
		madness_min_distance = Math.max(0, Math.min(world_radius, madness_min_distance));
		config_version = Math.max(config_version, 0);
	}

	/** 0.1.0 → 0.2: столбы 6–14 блоков заменяются областями, плотность по чанкам — населением. */
	private void migrate(boolean existed) {
		if (config_version < 2) {
			radius_min = 100.0;
			radius_max = 3000.0;
			spawn_min_radius = 6000;
			world_radius = 20000;
			population = 250;
			lifetime_max_hours = 168.0;
		}
		// v2 → v3: появились компоненты, старый список выключенных «типов» больше не значит ничего.
		// Геометрию при этом не трогаем: пользователь мог её уже настроить.
		disabled_components = new ArrayList<>();
		// v3 → v4: 250 зон на мир 40 000 блоков — это одна на 2 500 блоков, в поле зрения пусто.
		// Поднимаем только старое дефолтное значение, выставленное руками не трогаем.
		if (config_version < 4 && population == 250) {
			population = 800;
		}
		// v4 → v5: 800 зон с крупными давали 38 % покрытия — перебор; 600 даёт ~32 %
		if (config_version < 5 && population == 800) {
			population = 600;
		}
		// v5 → v6: появился «остаток»; у старых файлов его ключей нет, Gson ставит умолчания,
		// и это ровно то, что нужно. Пользовательские числа не трогаем.
		sanitize();
		config_version = VERSION;
		if (existed) {
			AnomalyMod.LOGGER.info("Anomaly: конфиг обновлён до версии {}", VERSION);
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

	public boolean isEnabled(AnomalyComponent component) {
		return disabled_components == null || !disabled_components.contains(component.id);
	}

	private static double finite(double value, double fallback) {
		return Double.isFinite(value) ? value : fallback;
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
