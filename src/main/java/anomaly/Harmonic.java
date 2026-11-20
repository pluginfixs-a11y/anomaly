package anomaly;

/**
 * Одна гармоника формы: аномалия — не круг, а сумма синусов по углу.
 * <p>
 * {@code amp} — глубина лепестка, {@code phase} — где он стоит, {@code spin} — насколько фаза
 * уплывает за час (граница «идёт»), {@code wob} — на сколько процентов качается амплитуда,
 * {@code wob_speed}/{@code wob_phase} — период и смещение этого качания.
 * <p>
 * Поля публичные и с пустым конструктором: это же и формат сохранения в JSON.
 */
public final class Harmonic {
	public int k = 2;
	public double amp;
	public double phase;
	public double spin;
	public double wob;
	public double wob_speed;
	public double wob_phase;

	public Harmonic() {
	}

	public Harmonic(int k, double amp, double phase, double spin, double wob, double wobSpeed, double wobPhase) {
		this.k = k;
		this.amp = amp;
		this.phase = phase;
		this.spin = spin;
		this.wob = wob;
		this.wob_speed = wobSpeed;
		this.wob_phase = wobPhase;
	}

	/** Амплитуда в этот момент: качается вокруг своей средней на ±{@code wob}. */
	public double amplitude(double ageHours) {
		return amp * (1.0 + wob * Math.sin(ageHours * wob_speed + wob_phase));
	}

	/** Вклад гармоники в множитель радиуса на угле {@code theta}. */
	public double at(double theta, double ageHours) {
		return amplitude(ageHours) * Math.sin(k * theta + phase + spin * ageHours);
	}

	public boolean valid() {
		return k >= 2 && k <= 16
				&& Double.isFinite(amp) && Math.abs(amp) <= 1.0
				&& Double.isFinite(phase) && Double.isFinite(spin)
				&& Double.isFinite(wob) && Double.isFinite(wob_speed) && Double.isFinite(wob_phase);
	}
}
