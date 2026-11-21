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
			if (component.color != 0 && mixed < 2) {
				color = blend(color, component.color, 0.25);
				mixed++;
			}
		}
		// ваниль сама множит цвет пыли на 0.6–1.0: тёмный край на светлом небе просто исчезает,
		// поэтому после подмешивания поднимаем самый яркий канал хотя бы до 190
		return brighten(color, 190);
	}

	private static int brighten(int color, int floor) {
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		if (max >= floor || max == 0) {
			return color;
		}
		double k = floor / (double) max;
		r = Math.min(255, (int) Math.round(r * k));
		g = Math.min(255, (int) Math.round(g * k));
		b = Math.min(255, (int) Math.round(b * k));
		return (r << 16) | (g << 8) | b;
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
