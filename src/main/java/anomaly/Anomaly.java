package anomaly;

import java.util.ArrayList;
import java.util.List;

/**
 * Аномалия как данные: параметры, из которых её состояние считается в любой момент времени.
 * <p>
 * Двигатель намеренно не «тикает» каждую аномалию по шагам. Позиция, радиус и форма —
 * замкнутые функции возраста: {@code age = (now - born) / 3600000}. Поэтому:
 * <ul>
 *   <li>рестарт ничего не сбрасывает и не пересчитывает — параметры лежат в файле мира;</li>
 *   <li>перемотка времени (отладочный {@code timescale}) не ломает траекторию;</li>
 *   <li>стоимость тика — один обход списка, без интеграторов и накопления ошибки.</li>
 * </ul>
 * Поля публичные: это заодно и JSON-формат {@code world/data/anomaly_field.json}.
 */
public final class Anomaly {
	/** Доля жизни на рост (0..0.22) и на распад (0.80..1). Между — зрелость. */
	public static final double GROW_UNTIL = 0.22;
	public static final double DECAY_FROM = 0.80;
	private static final double HOUR_MS = 3_600_000.0;

	public long id;
	/** Момент рождения в миллисекундах полевого времени (см. {@link AnomalyClock}). */
	public long born;
	public double life_hours = 24.0;

	/** Точка рождения. Текущая позиция = рождение + дуга дрейфа. */
	public double x0;
	public double z0;
	public double speed;
	public double dir;
	/** Кривизна дуги, рад/ч. За всю жизнь даёт не больше ±10°. */
	public double arc;

	/** Радиус зрелой аномалии до сжатия у центра. */
	public double r0 = 200.0;
	/** Вытянутость по оси X в локальных координатах: 0.55 — «сосиска» вдоль Z, 2.25 — вдоль X. */
	public double stretch = 1.0;
	/** Поворот вытянутости в мире, рад. */
	public double rotation;
	public List<Harmonic> harmonics = new ArrayList<>();
	public double breath;
	public double breath_speed;

	public double core_offset = 0.2;
	public double core_angle;
	public double core_spin;

	/**
	 * Множитель сжатия у центра: 1 — целая, меньше — уже рассасывается. Уменьшается только
	 * внутри полосы подавления (≈5 % в минуту) и обратно не растёт: урон необратим.
	 */
	public double shrink = 1.0;

	/** Набор компонентов: id из {@link AnomalyComponent}. Пустой — только у старых файлов до 0.2.0. */
	public List<String> components = new ArrayList<>();
	/** Класс: safe, danger, deadly. */
	public String cls = "safe";

	/**
	 * Тестовая аномалия из команды: не дрейфует, не гаснет у центра и не умирает по возрасту.
	 * Нужна, чтобы смотреть форму и границу там, где стоишь.
	 */
	public boolean pinned;

	/**
	 * Осколок умершей зоны: то же место и форма, слабые частицы, без урона и без новых следов.
	 * Живёт 1–6 часов — по нему видно, что здесь было.
	 */
	public boolean remnant;

	/** План следов разрушения. Хранится правилами, а не списком блоков. */
	public List<ScarOp> scars = new ArrayList<>();

	/** Рейдовые узлы: кладовая, алтарь и три якоря. Появляются при первом подходе к ядру. */
	public boolean nodes_placed;
	public double node_cx;
	public double node_cz;
	public double node_cy;
	public double node_r;
	/** Кладовая поставлена: если её разобрали, раз в 10 минут она возвращается. */
	public boolean node_cache_ok;
	/** Алтарь поставлен. */
	public boolean node_altar_ok;
	/** Биты поставленных якорей: каждый появляется сам, когда загружен его чанк. */
	public int anchors_placed;
	/** Когда кладовая была поставлена: раз в 10 минут её можно восстановить, если зона жива. */
	public long node_cache_ms;
	/** Биты сломанных якорей: три из трёх — зона схлопывается. */
	public int anchors_broken;
	/** Номер следующего следа и колонка внутри него: по ним видно, сколько уже сделано. */
	public int scar_next;
	public int scar_cursor;

	public Anomaly() {
	}

	// --- время ---

	public double ageHours(long now) {
		return (now - born) / HOUR_MS;
	}

	public boolean alive(long now) {
		// тестовая зона не стареет: она живёт, пока её не уберут руками
		return pinned || ageHours(now) < life_hours;
	}

	public double leftHours(long now) {
		return life_hours - ageHours(now);
	}

	/** 0 → только родилась, 1 → зрелость, к концу жизни снова 0. */
	public double envelope(double ageHours) {
		if (life_hours <= 0) {
			return 0.0;
		}
		double frac = ageHours / life_hours;
		if (frac <= 0.0) {
			return 0.0;
		}
		if (frac < GROW_UNTIL) {
			return frac / GROW_UNTIL;
		}
		if (frac > DECAY_FROM) {
			return Math.max(0.0, (1.0 - frac) / (1.0 - DECAY_FROM));
		}
		return 1.0;
	}

	// --- форма ---

	/** Множитель радиуса на угле {@code theta}: «дыхание» плюс плывущие гармоники. */
	public double shapeFactor(double theta, double ageHours) {
		double r = 1.0 + breath * Math.sin(ageHours * breath_speed);
		if (harmonics != null) {
			for (Harmonic h : harmonics) {
				r += h.at(theta, ageHours);
			}
		}
		// мягкий максимум от 0.35: жёсткий зажим превращал глубокую вмятину в хорду с изломом,
		// а наивная гипербола при r < 0.29 выворачивала её наружу лопастью
		return 0.5 * (0.35 + r + Math.hypot(0.35 - r, 0.06));
	}

	/** Верхняя граница радиуса по всем углам: для отсева и для поиска края делением пополам. */
	public double maxRadius() {
		double sum = 0.0;
		if (harmonics != null) {
			for (Harmonic h : harmonics) {
				sum += Math.abs(h.amp) * (1.0 + Math.abs(h.wob));
			}
		}
		return r0 * (1.0 + Math.abs(breath) + sum) * Math.max(1.0, stretch);
	}

	// --- дрейф ---

	/** Текущая позиция: хорда дуги постоянной кривизны, а не шаги по прямой. */
	public double[] position(double ageHours) {
		return position(x0, z0, speed, dir, arc, ageHours);
	}

	/**
	 * Позиция через {@code ageHours} после рождения. При малой кривизне — прямая, при большой —
	 * точная дуга (иначе деление на почти ноль даёт скачки).
	 */
	public static double[] position(double x0, double z0, double speed, double dir0, double arc, double ageHours) {
		double theta = arc * ageHours;
		double chord;
		if (Math.abs(theta) < 1.0e-4) {
			chord = speed * ageHours * (1.0 - theta * theta / 24.0);
		} else {
			chord = speed * ageHours * Math.sin(theta / 2.0) / (theta / 2.0);
		}
		double mid = dir0 + theta / 2.0;
		return new double[]{x0 + Math.cos(mid) * chord, z0 + Math.sin(mid) * chord};
	}

	/** Направление движения сейчас (рад) — для стрелки в отладке. */
	public double heading(double ageHours) {
		return dir + arc * ageHours;
	}

	// --- состояние в момент времени ---

	public Zone zone(long now) {
		double ageHours = ageHours(now);
		double[] pos = position(ageHours);
		double radius = radius(ageHours);
		double coreAngle = core_angle + core_spin * ageHours;
		double coreX = pos[0] + Math.cos(coreAngle) * radius * core_offset;
		double coreZ = pos[1] + Math.sin(coreAngle) * radius * core_offset;
		return new Zone(this, now, pos[0], pos[1], radius, coreX, coreZ, ageHours, componentList());
	}

	/** Радиус с учётом фазы жизни и уже случившегося сжатия у центра. */
	public double radius(double ageHours) {
		// тестовая зона сразу полноразмерная: иначе её не видно первые часы после /anomaly spawn
		double full = r0 * (pinned ? 1.0 : envelope(ageHours));
		return Math.max(0.0, full * shrink);
	}

	/**
	 * Расстояние до границы со знаком: отрицательное внутри, положительное снаружи.
	 * Форма не круг, поэтому радиус на угле считается через {@link #shapeFactor}.
	 */
	public double signedDistance(double px, double pz, double ageHours, double radius) {
		double[] pos = position(ageHours);
		double dx = px - pos[0];
		double dz = pz - pos[1];
		double cos = Math.cos(rotation);
		double sin = Math.sin(rotation);
		double lx = (dx * cos + dz * sin) / Math.max(0.2, stretch);
		double lz = -dx * sin + dz * cos;
		double dist = Math.hypot(lx, lz);
		if (dist < 1.0e-6) {
			return -radius;
		}
		double theta = Math.atan2(lz, lx);
		return dist - radius * shapeFactor(theta, ageHours);
	}

	/**
	 * Точка на границе в направлении {@code worldAngle} от центра.
	 * <p>
	 * Не итерации: в локальных координатах луч под углом φ превращается в θ по
	 * {@code tan θ = tan φ · stretch} (вытянутость меняет угол), а множитель формы считается
	 * один раз. Иначе контур из сотни точек стоил бы сотни делений пополам.
	 */
	public double[] boundaryPoint(double worldAngle, double ageHours, double radius) {
		double[] pos = position(ageHours);
		double local = worldAngle - rotation;
		// то же ограничение вытянутости, что и в signedDistance: иначе точка уезжает с границы
		double stretchValue = Math.max(0.2, stretch);
		double theta = Math.atan2(Math.sin(local) * stretchValue, Math.cos(local));
		double edge = shapeFactor(theta, ageHours) * radius;
		double lx = Math.cos(theta) * edge * stretchValue;
		double lz = Math.sin(theta) * edge;
		double cos = Math.cos(rotation);
		double sin = Math.sin(rotation);
		return new double[]{pos[0] + lx * cos - lz * sin, pos[1] + lx * sin + lz * cos};
	}

	/** Разобранный набор компонентов; кэш на время жизни записи (Gson его не пишет). */
	private transient java.util.List<AnomalyComponent> parsedComponents;

	/** Разобранный набор компонентов; пустой у старых файлов до миграции. */
	public java.util.List<AnomalyComponent> componentList() {
		if (parsedComponents == null) {
			parsedComponents = AnomalyComponent.parse(components);
		}
		return parsedComponents;
	}

	/** Сбросить кэш разобранного набора: звать после замены {@code components}. */
	public void invalidateComponents() {
		parsedComponents = null;
	}

	/** Пригодность записи из файла: битые строки выбрасываются, а не роняют поле. */
	public boolean valid() {
		if (!(Double.isFinite(x0) && Double.isFinite(z0) && Double.isFinite(r0) && r0 > 0.0)) {
			return false;
		}
		if (!(Double.isFinite(life_hours) && life_hours > 0.0)) {
			return false;
		}
		if (!(Double.isFinite(speed) && speed >= 0.0)) {
			return false;
		}
		if (!(Double.isFinite(arc) && Double.isFinite(stretch) && stretch > 0.01)) {
			return false;
		}
		// stretch из ручного JSON в 1e6 делал зону невидимым диском на весь мир
		stretch = Math.min(stretch, 10.0);
		if (!(Double.isFinite(dir) && Double.isFinite(rotation) && Double.isFinite(breath)
				&& Double.isFinite(breath_speed))) {
			return false;
		}
		if (!(Double.isFinite(core_offset) && Double.isFinite(core_angle) && Double.isFinite(core_spin))) {
			return false;
		}
		if (harmonics == null) {
			harmonics = new ArrayList<>();
		}
		harmonics.removeIf(h -> h == null || !h.valid());
		if (components == null) {
			components = new ArrayList<>();
		}
		if (scars == null) {
			scars = new ArrayList<>();
		}
		scars.removeIf(op -> op == null || op.blocks < 0 || !Double.isFinite(op.x) || !Double.isFinite(op.z)
				|| !Double.isFinite(op.radius) || op.radius < 1.0 || op.length < 0 || op.width < 1
				|| op.height < 0 || op.height > 400 || op.length > 512 || op.width > 8 || op.radius > 256);
		scar_next = Math.max(0, Math.min(scar_next, scars.size()));
		scar_cursor = Math.max(0, scar_cursor);
		// битый курсор молча съедал бы след: подрезаем к длине текущего опа
		if (scar_next < scars.size()) {
			scar_cursor = Math.min(scar_cursor, scars.get(scar_next).columnCount());
		} else {
			scar_cursor = 0;
		}
		if (!(Double.isFinite(shrink) && shrink > 0.0 && shrink <= 4.0)) {
			shrink = 1.0;
		}
		// узел из файла: битые координаты просто отменяют постановку, а не роняют зону
		if (!(Double.isFinite(node_cx) && Double.isFinite(node_cz) && Double.isFinite(node_cy)
				&& Double.isFinite(node_r) && node_r > 0.0 && node_r <= 10_000.0)) {
			nodes_placed = false;
			node_cache_ok = false;
			node_altar_ok = false;
			anchors_placed = 0;
		}
		anchors_broken &= 0x7;
		// сломанный якорь обратно не ставим, даже если файл писали до появления anchors_placed
		anchors_placed |= anchors_broken;
		if (anchors_placed != 0) {
			nodes_placed = true;
		}
		return true;
	}
}

