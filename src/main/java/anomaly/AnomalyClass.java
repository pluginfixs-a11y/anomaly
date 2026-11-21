package anomaly;

/**
 * Класс опасности аномалии. Задаётся дальностью от центра мира (с размытой границей) и решает
 * три вещи: силу действия на игрока, бюджет разрушения мира и допустимые компоненты.
 */
public enum AnomalyClass {
	SAFE("safe", "безопасная", 0x6F8F6A, 0.6),
	DANGER("danger", "опасная", 0xC8863C, 0.8),
	DEADLY("deadly", "смертельная", 0xA33A2F, 1.0);

	/** Радиус → бюджет разрушения в блоках. Мелкие почти не рушат, гиганты — катастрофа. */
	private static final double[][] BUDGET = {
			{100, 0},
			{500, 300},
			{1000, 5_000},
			{2000, 60_000},
			{3000, 250_000}
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
		for (int i = 1; i < BUDGET.length; i++) {
			if (radius <= BUDGET[i][0]) {
				double r0 = BUDGET[i - 1][0];
				double r1 = BUDGET[i][0];
				double b0 = BUDGET[i - 1][1];
				double b1 = BUDGET[i][1];
				double k = (radius - r0) / (r1 - r0);
				return b0 + (b1 - b0) * k * k;
			}
		}
		return BUDGET[BUDGET.length - 1][1];
	}
}
