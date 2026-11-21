package anomaly;

/**
 * Доза игрока — то самое «чем дольше стоишь, тем дольше держится». Копится внутри аномалии,
 * спадает снаружи, а пока она выше {@code lingering_floor}, эффект продолжает висеть.
 * <p>
 * Числа в UI не выводятся: дозу читают по иконкам эффектов и по тому, насколько плохо.
 */
public final class AnomalyState {
	public static final double FULL = 1.0;
	/** Сколько дозы игрок может принести с собой в аномалию другого набора. */
	public static final double SWITCH_CARRY = 0.3;

	public double dose;
	/** id зоны, в которой игрок сейчас: по нему отличается «первый вход» от «перескока». */
	public long zoneId;
	/** Сколько тиков подряд игрок внутри — по нему ограничивается левитация (в тиках, не в вызовах). */
	public int ticksInside;
	/** Накопитель для урона давлением: бьёт раз в 40 тиков, а не раз в вызов. */
	public int damageTicks;

	/** Шаг внутрь аномалии: прирост пропорционален силе аномалии и времени. */
	public void enter(Zone zone, double seconds, AnomalyConfig config) {
		if (zoneId != zone.id() && ticksInside == 0) {
			// первый вход с улицы: чужую дозу с собой не тащим, иначе шаг из «холода»
			// в «давление» сразу бьёт на полную
			dose = Math.min(dose, SWITCH_CARRY);
		}
		// перескок между перекрывающимися зонами дозу не срезает
		dose = Math.min(FULL, dose + config.dose_gain_per_second * zone.strength() * seconds);
		zoneId = zone.id();
		ticksInside += stepTicks(seconds);
	}

	/** Сколько тиков длился один шаг мода: обычно период тика (0.5 с = 10 тиков). */
	public static int stepTicks(double seconds) {
		return Math.max(1, (int) Math.round(seconds * 20.0));
	}

	/** Шаг снаружи: доза спадает, на нуле зона забывается. */
	public void leave(double seconds, AnomalyConfig config) {
		ticksInside = 0;
		damageTicks = 0;
		dose = Math.max(0.0, dose - config.dose_decay_per_second * seconds);
		if (dose <= 0.0) {
			dose = 0.0;
			zoneId = 0L;
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
