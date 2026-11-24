package anomaly;

/**
 * Класс опасности аномалии. Задаётся дальностью от центра мира (с размытой границей) и решает
 * три вещи: силу действия на игрока, бюджет разрушения мира и допустимые компоненты.
 */
public enum AnomalyClass {
	SAFE("safe", "безопасная", 0x9BE07F, 0.6),
	DANGER("danger", "опасная", 0xFFB24A, 0.8),
	DEADLY("deadly", "смертельная", 0xFF5A43, 1.0);

	/** Радиус → бюджет разрушения в блоках. Мелкие почти не рушат, гиганты — катастрофа. */
	/**
	 * Бюджет — самый большой ограничитель: на 600 зон прежняя таблица давала ~400 тысяч блоков
	 * на весь мир (это четыре чанка материала), и «страшная» зона выглядела двумя шипами.
	 * Таблица поднята втрое: мир получает ~1,2 млн блоков, зона r=1500 — около 56 тысяч.
	 */
	private static final double[][] BUDGET = {
			{100, 0},
			{500, 900},
			{1000, 15_000},
			{2000, 180_000},
			{3000, 750_000}
	};

	public final String id;
	public final String title;
	/** Цвет для частиц отладки и для чата (0xRRGGBB). */
	public final int color;
	/** Базовая сила для дозы и эффектов: 0.6 / 0.8 / 1.0. */
	public final double strength;

	AnomalyClass(String id, String title, int color, double strength) {
		this.id = id;
		this.title = title;
		this.color = color;
		this.strength = strength;
	}

	public static AnomalyClass byId(String id) {
		for (AnomalyClass cls : values()) {
			if (cls.id.equals(id)) {
				return cls;
			}
		}
		return DANGER;
	}

	/** Безопасный класс не наносит урона и не рушит мир вообще. */
	public boolean damages() {
		return this != SAFE;
	}

	/**
	 * Сколько блоков мира аномалия вправе испортить. Ноль у безопасных и у всего мельче 100,
	 * дальше квадратичный рост между ступенями: радиус — это масса.
	 */
	public static double damageBudget(double radius, AnomalyClass cls) {
		if (cls == null || !cls.damages() || radius <= BUDGET[0][0]) {
			return 0.0;
		}
		// у опасной/смертельной зоны есть «подпись»: без пола бюджет в 3–20 блоков не оставлял
		// вообще ничего, и половина поля выглядела нетронутой. У мелких (100–149) — 40, от 150 — 120.
		double floor = radius >= 150.0 ? 120.0 : (radius >= 100.0 ? 40.0 : 0.0);
		for (int i = 1; i < BUDGET.length; i++) {
			if (radius <= BUDGET[i][0]) {
				double r0 = BUDGET[i - 1][0];
				double r1 = BUDGET[i][0];
				double b0 = BUDGET[i - 1][1];
				double b1 = BUDGET[i][1];
				double k = (radius - r0) / (r1 - r0);
				return Math.max(floor, b0 + (b1 - b0) * k * k);
			}
		}
		return Math.max(floor, BUDGET[BUDGET.length - 1][1]);
	}
}
