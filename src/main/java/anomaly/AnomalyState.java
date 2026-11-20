package anomaly;

/**
 * Доза игрока — то самое «чем дольше стоишь, тем дольше держится». Копится внутри аномалии,
 * спадает снаружи, а пока она выше {@code lingering_floor}, эффект продолжает висеть.
 */
public final class AnomalyState {
	public static final double FULL = 1.0;
	/** Сколько дозы игрок может принести с собой в аномалию другого типа. */
	public static final double SWITCH_CARRY = 0.3;

	public double dose;
	public AnomalyType type;
	/** Сколько тиков подряд игрок внутри — по нему ограничивается левитация вихря. */
	public int ticksInside;
	/** Последний процент, отправленный над хотбаром: строка не должна спамить каждые 0.5 с. */
	public int shownPercent = -100;

	/** Шаг внутрь аномалии: прирост пропорционален силе аномалии и времени. */
	public void enter(Anomaly anomaly, double seconds, AnomalyConfig config) {
		if (type != anomaly.type()) {
			// чужую дозу с собой не тащим: иначе шаг из «Тяжести» в «Мороз» сразу бьёт на полную
			dose = Math.min(dose, SWITCH_CARRY);
			ticksInside = 0;
			shownPercent = -100;
		}
		dose = Math.min(FULL, dose + config.dose_gain_per_second * anomaly.strength() * seconds);
		type = anomaly.type();
		ticksInside++;
	}

	/** Шаг снаружи: доза спадает, на нуле тип забывается. */
	public void leave(double seconds, AnomalyConfig config) {
		ticksInside = 0;
		dose = Math.max(0.0, dose - config.dose_decay_per_second * seconds);
		if (dose <= 0.0) {
			dose = 0.0;
			type = null;
			shownPercent = -100;
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
