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
 * рисуются ванильными частицами пыли, цвет — по классу опасности.
 * <p>
 * Нужна, чтобы проверять форму в игре: край видно, он дышит и переливается лепестками.
 * Частицы отправляются только вокруг игрока (в пределах ~200 блоков от границы) — дальний
 * контур клиент всё равно не отрисует, поэтому для осмотра есть {@code /anomaly edge}.
 */
public final class AnomalyDebug {
	private static final Set<UUID> SHOWING = new HashSet<>();
	private static final int TICK_PERIOD = 20;
	private static final int MAX_ZONES = 4;
	private static final double REACH = 200.0;
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
		DustParticleOptions edge = new DustParticleOptions(zone.edgeColor(), 1.3f);
		DustParticleOptions core = new DustParticleOptions(lighter(zone.edgeColor()), 1.8f);
		double y = player.getY() + 0.6;
		// клиент видит частицы максимум в ~512 блоках: точки дальше — впустую посчитанные пакеты
		double visible = 480.0;

		// контур: точки ставим по видимой дуге вокруг направления на игрока — иначе у большой зоны
		// шаг между точками в сотни блоков и в кадре оказывается ноль-девять штук
		double toPlayer = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double half = radius <= visible ? Math.PI : Math.asin(Math.min(1.0, visible / radius));
		int points = 64;
		for (int i = 0; i <= points; i++) {
			double angle = toPlayer - half + (2.0 * half) * i / points;
			double[] point = zone.anomaly().boundaryPoint(angle, zone.ageHours(), radius);
			if (Math.hypot(point[0] - player.getX(), point[1] - player.getZ()) > visible) {
				continue;
			}
			level.sendParticles(edge, true, true, point[0], y, point[1], 1, 0.0, 0.0, 0.0, 0.0);
			if ((i & 7) == 0) {
				// редкие точки выше — чтобы край читался как стена, а не как линия на земле
				level.sendParticles(edge, true, true, point[0], y + 3.0, point[1], 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		// ядро: смещено от центра и ползёт внутри области; рисуем, только если оно рядом
		if (Math.hypot(zone.coreX() - player.getX(), zone.coreZ() - player.getZ()) <= visible) {
			for (int i = 0; i < 4; i++) {
				level.sendParticles(core, true, true, zone.coreX(), y + i, zone.coreZ(), 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		// направление дрейфа — короткая стрелка из центра
		if (Math.hypot(zone.x() - player.getX(), zone.z() - player.getZ()) <= visible) {
			double heading = zone.anomaly().heading(zone.ageHours());
			for (int i = 1; i <= 4; i++) {
				double length = i * 5.0;
				level.sendParticles(edge, true, true,
						zone.x() + Math.cos(heading) * length, y, zone.z() + Math.sin(heading) * length,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	private static int lighter(int color) {
		int r = Math.min(255, ((color >> 16) & 0xFF) + 60);
		int g = Math.min(255, ((color >> 8) & 0xFF) + 60);
		int b = Math.min(255, (color & 0xFF) + 60);
		return (r << 16) | (g << 8) | b;
	}
}
