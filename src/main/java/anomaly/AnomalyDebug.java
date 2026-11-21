package anomaly;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Отладочная визуализация без клиентского мода: контур аномалии, ядро и направление дрейфа
 * рисуются ванильными частицами пыли, цвет — по зоне.
 * <p>
 * Нужна, чтобы проверять форму в игре: край видно, он дышит и переливается лепестками.
 * Рисуем только в пределах {@link #VISIBLE} от игрока и на высоте рельефа: частицы на высоте
 * глаз плоским кольцом режут холмы, а дальше 96 блоков пыль уже не читается.
 */
public final class AnomalyDebug {
	private static final Set<UUID> SHOWING = new HashSet<>();
	private static final int TICK_PERIOD = 20;
	private static final int MAX_ZONES = 4;
	private static final double REACH = 200.0;
	/** Дальше этого пыль — меньше пикселя, а чанки там не загружены. */
	private static final double VISIBLE = 96.0;
	private static int ticker;

	private AnomalyDebug() {
	}

	/** Возвращает новое состояние: true — показ включён. */
	public static boolean toggle(UUID player) {
		if (SHOWING.remove(player)) {
			return false;
		}
		SHOWING.add(player);
		return true;
	}

	public static boolean isShowing(UUID player) {
		return SHOWING.contains(player);
	}

	/** Погасить контур при выходе: иначе множество UUID растёт без границы и показ сам включается после реконнекта. */
	public static void hide(UUID player) {
		SHOWING.remove(player);
	}

	public static void tick(MinecraftServer server) {
		if (SHOWING.isEmpty() || ++ticker % TICK_PERIOD != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!SHOWING.contains(player.getUUID())) {
				continue;
			}
			long now = AnomalyClock.now();
			List<Zone> zones = AnomalyField.near(player.level(), player.getX(), player.getZ(), REACH);
			int drawn = 0;
			for (Zone zone : zones) {
				if (drawn++ >= MAX_ZONES) {
					break;
				}
				draw(player, zone);
			}
			// тестовая зона может быть приколота прямо под ногами и не попасть в near() по границе
			if (drawn == 0) {
				Anomaly nearest = AnomalyField.nearest(player.getX(), player.getZ());
				if (nearest != null) {
					draw(player, nearest.zone(now));
				}
			}
		}
	}

	private static void draw(ServerPlayer player, Zone zone) {
		ServerLevel level = player.level();
		double radius = zone.radius();
		if (radius <= 0.5) {
			return;
		}
		DustParticleOptions edge = new DustParticleOptions(zone.edgeColor(), 1.6f);
		DustParticleOptions core = new DustParticleOptions(lighter(zone.edgeColor()), 2.0f);

		// контур: точки ставим по видимой дуге вокруг направления на игрока — иначе у большой зоны
		// шаг между точками в сотни блоков и в кадре оказывается ноль-девять штук
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double half = radius <= VISIBLE ? Math.PI : Math.asin(Math.min(1.0, VISIBLE / radius));
		double arc = 2.0 * half * radius;
		int points = Math.max(12, Math.min(48, (int) (arc / 8.0)));
		for (int i = 0; i <= points; i++) {
			double angle = toPlayer - half + (2.0 * half) * i / points;
			double[] point = zone.anomaly().boundaryPoint(angle, zone.ageHours(), radius);
			if (Math.hypot(point[0] - player.getX(), point[1] - player.getZ()) > VISIBLE) {
				continue;
			}
			double y = surfaceY(level, point[0], point[1]);
			if (Double.isNaN(y)) {
				continue;
			}
			level.sendParticles(player, edge, true, true, point[0], y + 0.6, point[1], 2, 0.0, 1.2, 0.0, 0.0);
			if ((i & 7) == 0) {
				// редкие точки выше — чтобы край читался как стена, а не как линия на земле
				level.sendParticles(player, edge, true, true, point[0], y + 3.0, point[1], 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		// ядро: смещено от центра и ползёт внутри области. Вблизи — столбик пыли, издалека — маяк
		// из end rod (fullbright, не тускнеет днём), иначе центра зоны R=3000 просто не видно
		double coreDistance = Math.hypot(zone.coreX() - player.getX(), zone.coreZ() - player.getZ());
		if (coreDistance <= 64.0) {
			double y = surfaceY(level, zone.coreX(), zone.coreZ());
			if (!Double.isNaN(y)) {
				for (int i = 0; i < 4; i++) {
					level.sendParticles(player, core, true, true, zone.coreX(), y + 0.6 + i, zone.coreZ(),
							1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		} else if (coreDistance <= VISIBLE * 4.0) {
			double y = surfaceY(level, zone.coreX(), zone.coreZ());
			if (!Double.isNaN(y)) {
				for (int i = 2; i <= 26; i += 3) {
					level.sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD, true, true,
							zone.coreX(), y + i, zone.coreZ(), 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}

		// направление дрейфа — стрелка вдоль heading; из центра она видна только вблизи,
		// издалека начинаем от ближайшей к игроку точки границы
		double heading = zone.anomaly().heading(zone.ageHours());
		double startX = zone.x();
		double startZ = zone.z();
		if (Math.hypot(startX - player.getX(), startZ - player.getZ()) > 64.0) {
			double[] near = zone.anomaly().boundaryPoint(toPlayer, zone.ageHours(), radius);
			startX = near[0];
			startZ = near[1];
		}
		for (int i = 1; i <= 8; i++) {
			double length = i * 4.0;
			double x = startX + Math.cos(heading) * length;
			double z = startZ + Math.sin(heading) * length;
			if (Math.hypot(x - player.getX(), z - player.getZ()) > VISIBLE) {
				break;
			}
			double y = surfaceY(level, x, z);
			if (Double.isNaN(y)) {
				continue;
			}
			level.sendParticles(player, edge, true, true, x, y + 1.2, z, 2, 0.0, 0.8, 0.0, 0.0);
			if (i == 8) {
				// перекладина у острия: без неё стрелка читается как ещё одна линия контура
				double leftX = x + Math.cos(heading + Math.PI * 0.75) * 4.0;
				double leftZ = z + Math.sin(heading + Math.PI * 0.75) * 4.0;
				double rightX = x + Math.cos(heading - Math.PI * 0.75) * 4.0;
				double rightZ = z + Math.sin(heading - Math.PI * 0.75) * 4.0;
				level.sendParticles(player, edge, true, true, leftX, y + 1.2, leftZ, 2, 0.0, 0.8, 0.0, 0.0);
				level.sendParticles(player, edge, true, true, rightX, y + 1.2, rightZ, 2, 0.0, 0.8, 0.0, 0.0);
			}
		}
	}

	/** Высота рельефа или NaN, если чанк не загружен (иначе getHeight отдаёт дно мира). */
	private static double surfaceY(ServerLevel level, double x, double z) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return Double.NaN;
		}
		return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
	}

	private static int lighter(int color) {
		int r = Math.min(255, ((color >> 16) & 0xFF) + 60);
		int g = Math.min(255, ((color >> 8) & 0xFF) + 60);
		int b = Math.min(255, (color & 0xFF) + 60);
		return (r << 16) | (g << 8) | b;
	}
}
