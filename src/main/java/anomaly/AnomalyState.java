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
	/** Сколько тиков подряд игрок внутри — по нему ограничивается левитация вихря (в тиках, не в вызовах). */
	public int ticksInside;
	/** Накопитель для урона холодом: бьёт раз в 20 тиков, а не раз в вызов. */
	public int damageTicks;
	/** Последний процент, отправленный над хотбаром: строка не должна спамить каждые 0.5 с. */
	public int shownPercent = -100;

	/** Шаг внутрь аномалии: прирост пропорционален силе аномалии и времени. */
	public void enter(Anomaly anomaly, double seconds, AnomalyConfig config) {
		if (type != anomaly.type()) {
			if (ticksInside == 0) {
				// первый вход с улицы: чужую дозу с собой не тащим, иначе шаг из «Тяжести»
				// в «Мороз» сразу бьёт на полную
				dose = Math.min(dose, SWITCH_CARRY);
				shownPercent = -100;
			}
			// перескок между перекрывающимися столбами (штатно дальше stack_from) дозу не срезает
		}
		dose = Math.min(FULL, dose + config.dose_gain_per_second * anomaly.strength() * seconds);
		type = anomaly.type();
		ticksInside += stepTicks(seconds);
	}

	/** Сколько тиков длился один шаг мода: обычно период тика (0.5 с = 10 тиков). */
	public static int stepTicks(double seconds) {
		return Math.max(1, (int) Math.round(seconds * 20.0));
	}

	/** Шаг снаружи: доза спадает, на нуле тип забывается. */
	public void leave(double seconds, AnomalyConfig config) {
		ticksInside = 0;
		damageTicks = 0;
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
