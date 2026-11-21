package anomaly;

import java.util.List;

/**
 * Аномалия в конкретный момент: где она, какая у неё граница, где ядро.
 * <p>
 * Всё, что нужно эффектам и отладке, считается один раз на тик и дальше читается как обычные
 * числа — без повторных вызовов синусов на каждый вопрос «я внутри?».
 */
public record Zone(Anomaly anomaly, long now, double x, double z, double radius, double coreX, double coreZ,
		double ageHours, List<AnomalyComponent> components) {

	public long id() {
		return anomaly.id;
	}

	public AnomalyClass cls() {
		return AnomalyClass.byId(anomaly.cls);
	}

	public double strength() {
		return cls().strength;
	}

	public boolean pinned() {
		return anomaly.pinned;
	}

	/** Отрицательное внутри, положительное снаружи. */
	public double signedDistance(double px, double pz) {
		return anomaly.signedDistance(px, pz, ageHours, radius);
	}

	public boolean contains(double px, double pz) {
		return signedDistance(px, pz) <= 0.0;
	}

	/** Расстояние до центра (не до края). */
	public double distanceTo(double px, double pz) {
		return Math.hypot(px - x, pz - z);
	}

	public double budget() {
		return AnomalyClass.damageBudget(anomaly.r0, cls());
	}

	/** Цвет края: класс задаёт базу, компоненты подмешивают свой оттенок — зоны перестают быть на одно лицо. */
	public int edgeColor() {
		int color = cls().color;
		int mixed = 0;
		for (AnomalyComponent component : components()) {
			if (component.color != 0 && mixed < 3) {
				color = blend(color, component.color, 0.30);
				mixed++;
			}
		}
		// ваниль множит цвет пыли на 0.6–1.0, а после подмешивания классы сближаются по яркости.
		// Нормализуем в HSV: насыщенность не ниже 0.5, значение не ниже 0.82, оттенок остаётся.
		return vivid(color);
	}

	private static int vivid(int color) {
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float hue;
		if (max == min) {
			hue = 0f;
		} else if (max == r) {
			hue = ((g - b) / (max - min) + 6f) % 6f;
		} else if (max == g) {
			hue = (b - r) / (max - min) + 2f;
		} else {
			hue = (r - g) / (max - min) + 4f;
		}
		float sat = max <= 0f ? 0f : (max - min) / max;
		float val = Math.max(max, 0.82f);
		float s = Math.max(sat, 0.5f);
		float h = hue * 60f;
		float c = val * s;
		float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
		float m = val - c;
		float rr;
		float gg;
		float bb;
		if (h < 60f) {
			rr = c; gg = x; bb = 0f;
		} else if (h < 120f) {
			rr = x; gg = c; bb = 0f;
		} else if (h < 180f) {
			rr = 0f; gg = c; bb = x;
		} else if (h < 240f) {
			rr = 0f; gg = x; bb = c;
		} else if (h < 300f) {
			rr = x; gg = 0f; bb = c;
		} else {
			rr = c; gg = 0f; bb = x;
		}
		int red = Math.round((rr + m) * 255f);
		int green = Math.round((gg + m) * 255f);
		int blue = Math.round((bb + m) * 255f);
		return (red << 16) | (green << 8) | blue;
	}

	private static int blend(int base, int tint, double k) {
		int r = (int) Math.round(((base >> 16) & 0xFF) * (1 - k) + ((tint >> 16) & 0xFF) * k);
		int g = (int) Math.round(((base >> 8) & 0xFF) * (1 - k) + ((tint >> 8) & 0xFF) * k);
		int b = (int) Math.round((base & 0xFF) * (1 - k) + (tint & 0xFF) * k);
		return (r << 16) | (g << 8) | b;
	}

	/** Разобранный набор компонентов: считается один раз при создании зоны. */
	public List<AnomalyComponent> components() {
		return components;
	}

	/** Полный поворот траектории за жизнь, градусы. По концепции не больше 10. */
	public double turnDegrees() {
		return Math.toDegrees(anomaly.arc * anomaly.life_hours);
	}
}
