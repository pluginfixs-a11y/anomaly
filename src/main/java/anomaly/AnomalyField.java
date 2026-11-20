package anomaly;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Детерминированное размещение аномалий: никаких файлов и SavedData, только хэш от (seed, chunkX, chunkZ).
 * Один и тот же чанк в одном мире всегда даёт те же аномалии, а новый мир — другие.
 * <p>
 * Считается только вокруг игроков, поэтому и стоит дешево: 9×9 чанков на игрока раз в полсекунды.
 */
public final class AnomalyField {
	private AnomalyField() {
	}

	/** Аномалии одного чанка (0..3 штуки). */
	public static List<Anomaly> inChunk(ServerLevel level, int chunkX, int chunkZ, AnomalyConfig config) {
		double centreX = chunkX * 16.0 + 8.0;
		double centreZ = chunkZ * 16.0 + 8.0;
		double distance = Math.hypot(centreX, centreZ);

		long hash = hash(level.getSeed(), chunkX, chunkZ);
		if (random01(hash) >= config.densityAt(distance)) {
			return List.of();
		}
		List<AnomalyType> types = enabledTypes(config);
		if (types.isEmpty()) {
			return List.of();
		}

		int stacks = config.stacksAt(distance, random01(mix(hash + 1L)));
		double depth = config.depthFactor(distance);
		List<Anomaly> out = new ArrayList<>(stacks);
		for (int i = 0; i < stacks; i++) {
			long seed = mix(hash + 31L * (i + 2));
			double x = chunkX * 16.0 + 1.0 + random01(seed) * 14.0;
			double z = chunkZ * 16.0 + 1.0 + random01(mix(seed + 1L)) * 14.0;
			double radius = config.radius_min + random01(mix(seed + 2L)) * Math.max(0.0, config.radius_max - config.radius_min);
			int pick = (int) Math.min(types.size() - 1, (long) (random01(mix(seed + 3L)) * types.size()));
			out.add(new Anomaly(types.get(pick), seed, x, z, radius, 0.6 + 0.4 * depth));
		}
		return out;
	}

	/** Все аномалии в {@code range} блоков от точки — для эффектов и для частиц. */
	public static List<Anomaly> near(ServerLevel level, double px, double pz, double range, AnomalyConfig config) {
		// радиус берём по большему из двух: при radius_min > radius_max столб может оказаться
		// шире, чем область поиска, и игрок внутри него не получил бы эффектов
		double widest = Math.max(0.0, Math.max(config.radius_min, config.radius_max));
		double reach = range + widest;
		int minChunkX = Mth.floor((px - reach) / 16.0);
		int maxChunkX = Mth.floor((px + reach) / 16.0);
		int minChunkZ = Mth.floor((pz - reach) / 16.0);
		int maxChunkZ = Mth.floor((pz + reach) / 16.0);

		List<Anomaly> out = new ArrayList<>();
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				for (Anomaly anomaly : inChunk(level, chunkX, chunkZ, config)) {
					if (anomaly.distanceTo(px, pz) <= range + anomaly.radius()) {
						out.add(anomaly);
					}
				}
			}
		}
		return out;
	}

	/** Включённые в конфиге типы; порядок фиксированный, чтобы выбор из хэша был стабильным. */
	public static List<AnomalyType> enabledTypes(AnomalyConfig config) {
		List<AnomalyType> out = new ArrayList<>(AnomalyType.values().length);
		for (AnomalyType type : AnomalyType.values()) {
			if (config.isEnabled(type)) {
				out.add(type);
			}
		}
		return out;
	}

	// --- хэш: splitmix64, чтобы соседние чанки не давали похожих чисел ---

	static long mix(long value) {
		long z = value;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static long hash(long seed, int chunkX, int chunkZ) {
		long h = mix(seed ^ 0x9E3779B97F4A7C15L);
		h = mix(h ^ (chunkX * 0xC2B2AE3D27D4EB4FL));
		return mix(h ^ (chunkZ * 0x165667B19E3779F9L));
	}

	/** Равномерное [0, 1) из 53 старших бит. */
	static double random01(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}
}
