package anomaly;

/**
 * Часы поля. Это не системное время, а счётчик, который двигает сам мод, и он лежит в файле мира.
 * <p>
 * Почему так, а не {@code System.currentTimeMillis()}:
 * <ul>
 *   <li>аномалии не умирают и не рождаются, пока сервер выключен — поле не пустеет за неделю простоя;</li>
 *   <li>отладка может ускорить время ({@code /anomaly timescale 3600} — час в секунду), и тогда
 *       жизненный цикл видно за минуты, а не за неделю;</li>
 *   <li>при рестарте время продолжается с сохранённой точки, а не прыгает вперёд.</li>
 * </ul>
 * «7 реальных суток» при сервере, работающем круглосуточно, — это и есть 7 суток.
 */
public final class AnomalyClock {
	/** Множитель времени: 1 — как в жизни, 0 — стоит, 3600 — час в секунду. */
	private static double scale = 1.0;
	private static long fieldMs;
	private static long lastRealMs;

	private AnomalyClock() {
	}

	/** Время поля на сейчас, без фиксации (монотонно, пока не позовут {@link #commit()}). */
	public static long now() {
		long real = System.currentTimeMillis();
		if (lastRealMs == 0L) {
			lastRealMs = real;
			return fieldMs;
		}
		return fieldMs + (long) (Math.max(0L, real - lastRealMs) * scale);
	}

	/** Зафиксировать прошедшее реальное время в полевом счётчике. */
	public static void commit() {
		long real = System.currentTimeMillis();
		if (lastRealMs == 0L) {
			lastRealMs = real;
			return;
		}
		fieldMs += (long) (Math.max(0L, real - lastRealMs) * scale);
		lastRealMs = real;
	}

	public static long fieldMs() {
		return fieldMs;
	}

	/** Загрузка из файла мира: счётчик продолжается, реальное время считается от текущего. */
	public static void setFieldMs(long value) {
		fieldMs = value;
		lastRealMs = System.currentTimeMillis();
	}

	public static double scale() {
		return scale;
	}

	public static void setScale(double value) {
		commit();
		scale = Math.max(0.0, Math.min(100_000.0, value));
	}

	/** Сброс на реальное время: новое поле не должно наследовать отладку другого мира. */
	public static void resetScale() {
		commit();
		scale = 1.0;
	}

	/** Сдвинуть поле во времени вручную (отладка). */
	public static void advance(long deltaMs) {
		commit();
		fieldMs += deltaMs;
	}
}
