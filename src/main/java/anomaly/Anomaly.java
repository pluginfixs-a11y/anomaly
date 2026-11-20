package anomaly;

/**
 * Аномалия — вертикальный столб: радиус считается по X/Z, по высоте он бесконечен.
 * Так не нужно искать поверхность и не бывает аномалий, «висящих» в воздухе над пещерой.
 *
 * @param id       устойчивый идентификатор из хэша чанка и номера в стопке
 * @param x        центр столба по X
 * @param z        центр столба по Z
 * @param radius   радиус действия (X/Z)
 * @param strength 0.6..1.0, растёт с удалением от центра мира
 */
public record Anomaly(AnomalyType type, long id, double x, double z, double radius, double strength) {

	public double distanceTo(double px, double pz) {
		return Math.hypot(px - x, pz - z);
	}

	public boolean contains(double px, double pz) {
		return distanceTo(px, pz) <= radius;
	}
}
