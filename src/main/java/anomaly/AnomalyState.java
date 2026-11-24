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
	/** Сколько тиков подряд игрок внутри. */
	public int ticksInside;
	/** Счётчик эффектных тиков: по нему идут «раз в 2 с» и «раз в 20 с», а не по мировому времени. */
	public int pulseTicks;
	/** Длина последнего шага в тиках: по ней период срабатывает при любом tick_period. */
	public int lastStep = 10;
	/** Накопитель для урона давлением: бьёт раз в 20 с, а не раз в вызов. */
	public int damageTicks;
	/** Накопитель урона класса: у смертельной зоны своя цена за время внутри. */
	public int hazardTicks;
	/** Тиков в ядре зоны (глубина > 85 %): по нему выдаётся достижение, а не по времени в мире. */
	public int coreTicks;
	/** Глубина последнего шага внутри зоны (0 — на границе, 1 — в ядре): для достижений на выходе. */
	public double lastDepth;
	/** Последняя зона была опасной: «послевкусие» после безопасной не включаем. */
	public boolean lastHazard;
	/** Послевкусие уже выдано за этот выход: иначе тьма обновлялась каждые 0.5 с. */
	public boolean lingered;
	/** Последняя позиция и время: по ним восстанавливается реальная скорость игрока перед толчком. */
	public double lastX;
	public double lastY;
	public double lastZ;
	public long lastMoveMs;
	/** Измеренная скорость с прошлого замера: два толчка в одном тике не должны обнулять её. */
	public double velX;
	public double velY;
	public double velZ;
	public boolean hasLast;

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
		lastHazard = zone.cls().damages();
		lingered = false;
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
		hazardTicks = 0;
		dose = Math.max(0.0, dose - config.dose_decay_per_second * seconds);
		if (dose <= 0.0) {
			dose = 0.0;
			zoneId = 0L;
		}
	}

	/** Счётчик эффектных тиков: по нему идут периодические эффекты, а не по мировому времени. */
	public void pulse(int ticks) {
		pulseTicks += ticks;
		lastStep = ticks;
	}

	public int percent() {
		return (int) Math.round(dose * 100.0);
	}
}
