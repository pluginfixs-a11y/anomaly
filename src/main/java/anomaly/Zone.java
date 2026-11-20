package anomaly;

/**
 * Аномалия в конкретный момент: где она, какая у неё граница, где ядро.
 * <p>
 * Всё, что нужно эффектам и отладке, считается один раз на тик и дальше читается как обычные
 * числа — без повторных вызовов синусов на каждый вопрос «я внутри?».
 */
public record Zone(Anomaly anomaly, long now, double x, double z, double radius, double coreX, double coreZ,
		double ageHours) {

	public long id() {
		return anomaly.id;
	}

	public AnomalyType type() {
		return AnomalyType.byId(anomaly.type);
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

	/** Полный поворот траектории за жизнь, градусы. По концепции не больше 10. */
	public double turnDegrees() {
		return Math.toDegrees(anomaly.arc * anomaly.life_hours);
	}
}
