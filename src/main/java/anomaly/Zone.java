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
		// осколок — память, а не угроза: дозу он не набирает и чужие зоны не перебивает
		if (remnant() || precursor()) {
			return 0.0;
		}
		// «зона кормится»: чем дольше в ней стоят, тем сильнее она бьёт — до +60 %
		double fed = Math.min(2.0, Math.max(0.0, anomaly.fed_hours));
		return cls().strength * (1.0 + 0.3 * fed);
	}

	public boolean pinned() {
		return anomaly.pinned;
	}

	public boolean remnant() {
		return anomaly.remnant;
	}

	/** Предвестник: зона ещё не родилась, у неё нет ни радиуса, ни дозы. */
	public boolean precursor() {
		return anomaly.precursor;
	}

	/** Вложенная зона помнит большую, внутри которой родилась (0 — сама по себе). */
	public long parentId() {
		return anomaly.parent_id;
	}

	/** Отрицательное внутри, положительное снаружи. Через кэш на записи: за тик спрашивают 4–6 раз. */
	public double signedDistance(double px, double pz) {
		return anomaly.signedDistanceCached(px, pz, ageHours, radius);
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
		int base = cls().color;
		int mixed = base;
		int used = 0;
		for (AnomalyComponent component : components()) {
			// три оттенка по 0.30 уводили смертельную в жёлтый: на 40–200 блоках классы
			// переставали различаться. Подмешиваем мягко и не больше двух
			if (component.color != 0 && used < 2) {
				mixed = blend(mixed, component.color, 0.15);
				used++;
			}
		}
		return vividWithin(mixed, base);
	}

	/**
	 * Ваниль множит цвет пыли на 0.6–1.0, поэтому насыщенность и яркость нормализуются.
	 * Оттенок при этом держится в пределах 6° от цвета класса: оттенки компонентов — добавка
	 * к характеру, а не замена классу.
	 */
	private static int vividWithin(int color, int base) {
		float[] hsv = toHsv(color);
		float[] baseHsv = toHsv(base);
		float delta = hsv[0] - baseHsv[0];
		if (delta > 180f) {
			delta -= 360f;
		} else if (delta < -180f) {
			delta += 360f;
		}
		float hue = baseHsv[0] + Math.max(-6f, Math.min(6f, delta));
		if (hue < 0f) {
			hue += 360f;
		}
		return fromHsv(hue % 360f, Math.max(hsv[1], 0.5f), Math.max(hsv[2], 0.82f));
	}

	private static float[] toHsv(int color) {
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
		return new float[]{hue * 60f, sat, max};
	}

	private static int fromHsv(float h, float s, float v) {
		float c = v * s;
		float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
		float m = v - c;
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

