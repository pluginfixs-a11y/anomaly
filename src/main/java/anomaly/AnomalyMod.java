package anomaly;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Аномальные поля: чем дальше от центра мира, тем крупнее и злее области.
 * <p>
 * Мод серверный: клиенту ставить нечего, всё показывается ванильными каналами — эффекты,
 * частицы, звуки, толчок скорости.
 * <p>
 * Поле живёт в {@code world/data/anomaly_field.json} и обновляется раз в секунду: рождения,
 * смерти, сжатие у центра. Форма и позиция каждой аномалии — замкнутые функции возраста,
 * поэтому тик не интегрирует движение и не копит ошибку.
 */
public class AnomalyMod implements ModInitializer {
	public static final String MOD_ID = "anomaly";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static AnomalyConfig config;

	private static final Map<UUID, AnomalyState> STATES = new HashMap<>();

	@Override
	public void onInitialize() {
		config = AnomalyConfig.load();
		LOGGER.info("Anomaly: население {}, рождение с {} блоков, мир до {}, радиус {}-{}, жизнь до {} ч",
				config.population, config.spawn_min_radius, config.world_radius,
				config.radius_min, config.radius_max, config.lifetime_max_hours);

		ServerLifecycleEvents.SERVER_STARTED.register(AnomalyField::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AnomalyField.save());
		ServerTickEvents.END_SERVER_TICK.register(AnomalyMod::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			STATES.remove(handler.player.getUUID());
			AnomalyDebug.hide(handler.player.getUUID());
		});
		AnomalyCommand.register();

		if (System.getProperty("anomaly.selftest") != null || System.getenv("ANOMALY_SELFTEST") != null) {
			ServerTickEvents.START_SERVER_TICK.register(SelfTest::onTick);
			LOGGER.info("Anomaly: включена самопроверка (ANOMALY_SELFTEST)");
		}
	}

	public static void tick(MinecraftServer server) {
		if (config == null || !config.enabled) {
			return;
		}
		int tick = server.getTickCount();
		// поле — раз в секунду: этого хватает для дрейфа в единицы блоков в минуту
		if (tick % 20 == 0) {
			AnomalyField.tick(server);
		}
		if (tick % Math.max(1, config.tick_period) == 0) {
			double seconds = Math.max(1, config.tick_period) / 20.0;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				try {
					tickPlayer(player, seconds);
				} catch (RuntimeException e) {
					// одна кривая аномалия не должна ронять цикл остальным игрокам
					LOGGER.error("Anomaly: ошибка на игроке {}", player.getName().getString(), e);
				}
			}
		}
		AnomalyDebug.tick(server);
	}

	private static void tickPlayer(ServerPlayer player, double seconds) {
		ServerLevel level = player.level();
		AnomalyState state = STATES.computeIfAbsent(player.getUUID(), key -> new AnomalyState());

		// вне Верхнего мира и у наблюдателей доза просто спадает, а не замирает
		if ((config.overworld_only && level.dimension() != Level.OVERWORLD) || player.isSpectator()) {
			state.leave(seconds, config);
			return;
		}

		List<Zone> near = AnomalyField.near(level, player.getX(), player.getZ(), config.view_range);

		Zone inside = null;
		for (Zone zone : near) {
			AnomalyEffects.ambience(level, player, zone, config);
			if (zone.contains(player.getX(), player.getZ())
					&& (inside == null || zone.strength() > inside.strength())) {
				inside = zone;
			}
		}

		if (inside != null) {
			boolean fresh = state.zoneId != inside.id();
			state.enter(inside, seconds, config);
			AnomalyEffects.apply(level, player, inside, state, config, seconds);
			if (fresh) {
				AnomalyEffects.entry(level, player, inside);
			}
		} else {
			state.leave(seconds, config);
			if (state.zoneId != 0L && state.dose >= config.lingering_floor) {
				AnomalyEffects.lingering(level, player, state, config);
			}
		}
	}

	/** Для самопроверки и для будущих модов: доза игрока, если он уже попадал в аномалию. */
	static AnomalyState stateOf(UUID playerId) {
		return STATES.get(playerId);
	}
}
