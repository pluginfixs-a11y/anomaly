package anomaly;

import net.fabricmc.api.ModInitializer;
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
 * Аномальные зоны: чем дальше от центра мира, тем чаще они встречаются и тем сильнее.
 * <p>
 * Мод серверный: клиенту ставить нечего, всё показывается ванильными каналами — эффекты,
 * частицы, звуки, строка над хотбаром, толчок скорости.
 * <p>
 * Механика: аномалия — вертикальный столб с радиусом 6–14 блоков. Пока игрок внутри, копится
 * доза (0..1); она задаёт силу эффекта и спадает не мгновенно, поэтому «хвост» держится ещё
 * несколько десятков секунд после выхода.
 */
public class AnomalyMod implements ModInitializer {
	public static final String MOD_ID = "anomaly";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static AnomalyConfig config;

	private static final Map<UUID, AnomalyState> STATES = new HashMap<>();

	@Override
	public void onInitialize() {
		config = AnomalyConfig.load();
		LOGGER.info("Anomaly: зон нет ближе {} блоков, дальше плотность {}..{} на чанк; доза +{}/с, спад {}/с; типы: {}",
				config.safe_radius,
				String.format("%.3f", config.densityAt(config.safe_radius)),
				String.format("%.3f", config.densityAt(20000)),
				config.dose_gain_per_second, config.dose_decay_per_second,
				AnomalyField.enabledTypes(config));

		ServerTickEvents.END_SERVER_TICK.register(AnomalyMod::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATES.remove(handler.player.getUUID()));
		AnomalyCommand.register();

		if (System.getProperty("anomaly.selftest") != null) {
			ServerTickEvents.START_SERVER_TICK.register(SelfTest::onTick);
			LOGGER.info("Anomaly: включена самопроверка (-Danomaly.selftest)");
		}
	}

	public static void tick(MinecraftServer server) {
		if (!config.enabled) {
			return;
		}
		int period = Math.max(1, config.tick_period);
		if (server.getTickCount() % period != 0) {
			return;
		}
		double seconds = period / 20.0;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			try {
				tickPlayer(player, seconds);
			} catch (RuntimeException e) {
				// одна кривая аномалия не должна ронять цикл остальным игрокам
				LOGGER.error("Anomaly: ошибка на игроке {}", player.getName().getString(), e);
			}
		}
	}

	private static void tickPlayer(ServerPlayer player, double seconds) {
		ServerLevel level = player.level();
		AnomalyState state = STATES.computeIfAbsent(player.getUUID(), key -> new AnomalyState());

		// вне Верхнего мира и у наблюдателей доза просто спадает, а не замирает
		if ((config.overworld_only && level.dimension() != Level.OVERWORLD) || player.isSpectator()) {
			state.leave(seconds, config);
			return;
		}

		List<Anomaly> near = AnomalyField.near(level, player.getX(), player.getZ(), config.view_range, config);

		Anomaly inside = null;
		for (Anomaly anomaly : near) {
			AnomalyEffects.ambience(level, player, anomaly, config);
			if (anomaly.contains(player.getX(), player.getZ())
					&& (inside == null || anomaly.strength() > inside.strength())) {
				inside = anomaly;
			}
		}

		if (inside != null) {
			boolean fresh = state.type != inside.type();
			state.enter(inside, seconds, config);
			AnomalyEffects.apply(level, player, inside, state, config, seconds);
			AnomalyEffects.overlay(player, state);
			if (fresh) {
				AnomalyEffects.entry(level, player, inside);
			}
		} else {
			state.leave(seconds, config);
			if (state.type != null && state.dose >= config.lingering_floor) {
				AnomalyEffects.lingering(level, player, state, config);
				AnomalyEffects.overlay(player, state);
			}
		}
	}

	/** Для самопроверки и для будущих модов: доза игрока, если он уже попадал в аномалию. */
	static AnomalyState stateOf(UUID playerId) {
		return STATES.get(playerId);
	}
}

