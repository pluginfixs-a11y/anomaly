package anomaly;

import java.util.ArrayList;
import java.util.List;

/**
 * Всё поле целиком в том виде, в каком оно лежит в {@code world/data/anomaly_field.json}.
 * Поле — данные, а не процесс: аномалии не «живут в памяти», их параметры читаются из файла.
 */
public final class AnomalyFieldData {
	/** Версия формата файла: по ней делается миграция при будущих переименованиях. */
	public int version = 1;

	/** Счётчик полевого времени на момент сохранения, мс. */
	public long clock_ms;

	public long next_id = 1;

	public List<Anomaly> anomalies = new ArrayList<>();
}
