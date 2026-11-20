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
		DustParticleOptions edge = new DustParticleOptions(zone.cls().color, 1.3f);
		DustParticleOptions core = new DustParticleOptions(lighter(zone.cls().color), 1.8f);
		double y = player.getY() + 0.6;

		// контур: плотность точек не зависит от размера — на гиганте они просто реже
		int points = (int) Math.max(24, Math.min(120, Math.round(radius / 4.0) + 24));
		for (int i = 0; i < points; i++) {
			double angle = i * (Math.PI * 2.0) / points;
			double[] point = zone.anomaly().boundaryPoint(angle, zone.ageHours(), radius);
			level.sendParticles(edge, true, true, point[0], y, point[1], 1, 0.0, 0.0, 0.0, 0.0);
			if ((i & 7) == 0) {
				// редкие точки выше — чтобы край читался как стена, а не как линия на земле
				level.sendParticles(edge, true, true, point[0], y + 3.0, point[1], 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		// ядро: смещено от центра и ползёт внутри области
		for (int i = 0; i < 4; i++) {
			level.sendParticles(core, true, true, zone.coreX(), y + i, zone.coreZ(), 1, 0.0, 0.0, 0.0, 0.0);
		}

		// направление дрейфа — короткая стрелка из центра
		double heading = zone.anomaly().heading(zone.ageHours());
		for (int i = 1; i <= 4; i++) {
			double length = i * 5.0;
			level.sendParticles(edge, true, true,
					zone.x() + Math.cos(heading) * length, y, zone.z() + Math.sin(heading) * length,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static int lighter(int color) {
		int r = Math.min(255, ((color >> 16) & 0xFF) + 60);
		int g = Math.min(255, ((color >> 8) & 0xFF) + 60);
		int b = Math.min(255, (color & 0xFF) + 60);
		return (r << 16) | (g << 8) | b;
	}
}
