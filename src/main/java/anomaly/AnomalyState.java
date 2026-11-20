package anomaly;

/**
 * Доза игрока — то самое «чем дольше стоишь, тем дольше держится». Копится внутри аномалии,
 * спадает снаружи, а пока она выше {@code lingering_floor}, эффект продолжает висеть.
 */
public final class AnomalyState {
	public static final double FULL = 1.0;

	public double dose;
	public AnomalyType type;
	public Anomaly last;

	/** Шаг внутрь аномалии: прирост пропорционален силе аномалии и времени. */
	public void enter(Anomaly anomaly, double seconds, AnomalyConfig config) {
		dose = Math.min(FULL, dose + config.dose_gain_per_second * anomaly.strength() * seconds);
		type = anomaly.type();
		last = anomaly;
	}

	/** Шаг снаружи: доза спадает, на нуле тип забывается. */
	public void leave(double seconds, AnomalyConfig config) {
		dose = Math.max(0.0, dose - config.dose_decay_per_second * seconds);
		if (dose <= 0.0) {
			dose = 0.0;
			type = null;
			last = null;
		}
	}

	/** 0 — еле заметно, 1 — средне, 2 — совсем плохо. */
	public int amplifier() {
		if (dose >= 0.8) {
			return 2;
		}
		return dose >= 0.45 ? 1 : 0;
	}

	public int percent() {
		return (int) Math.round(dose * 100.0);
	}
}
