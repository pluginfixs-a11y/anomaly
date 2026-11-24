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

import java.util.ArrayList;
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
	/** Сервер, пока он работает: нужен шизе, чтобы убрать фигуру без игрока под рукой. */
	private static MinecraftServer server;

	public static MinecraftServer server() {
		return server;
	}

	@Override
	public void onInitialize() {
		config = AnomalyConfig.load();
		LOGGER.info("Anomaly: население {}, рождение с {} блоков, мир до {}, радиус {}-{}, жизнь до {} ч",
				config.population, config.spawn_min_radius, config.world_radius,
				config.radius_min, config.radius_max, config.lifetime_max_hours);

		ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
			server = srv;
			AnomalyField.load(srv);
			AnomalyResidue.load(srv);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
			AnomalyField.save();
			AnomalyResidue.save();
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(AnomalyMod::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			STATES.remove(handler.player.getUUID());
			AnomalyDebug.hide(handler.player.getUUID());
			AnomalyMadness.cleanup(handler.player.getUUID());
		});
		AnomalyResidue.register();
		AnomalyNodes.register();
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
			AnomalyResidue.tick();
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
		state.pulse(AnomalyState.stepTicks(seconds));

		// мёртвый на экране смерти не копит дозу и не получает эффектов; после респавна всё с нуля
		if (!player.isAlive()) {
			state.dose = 0.0;
			state.zoneId = 0L;
			state.ticksInside = 0;
			state.damageTicks = 0;
			state.hazardTicks = 0;
			state.coreTicks = 0;
			AnomalyMadness.cleanup(player.getUUID());
			return;
		}

		// Нижний мир — тот же мир в масштабе 1:8. Рейд в аду на 2 тыс. блоков — это 16 км
		// по Верхнему, и бесплатного обхода поля быть не должно. Проверка стоит ДО выхода по
		// overworld_only: иначе оброк был бы мёртвым кодом
		if (level.dimension() == Level.NETHER) {
			netherToll(player, state, config, seconds);
		}

		// вне Верхнего мира и у наблюдателей доза просто спадает, а не замирает;
		// остаток при этом продолжает таять — иначе Нижний стал бы бесплатной паузой
		if ((config.overworld_only && level.dimension() != Level.OVERWORLD) || player.isSpectator()) {
			state.leave(seconds, config);
			state.coreTicks = 0;
			state.hazardTicks = 0;
			AnomalyMadness.cleanup(player.getUUID());
			AnomalyResidue.update(player, seconds, null, state.dose);
			return;
		}

		// предвестник: за 10–30 минут до рождения зона уже шумит. Это не зона, дозы нет
		for (Anomaly herald : AnomalyField.heralds(level, player.getX(), player.getZ(), 192.0)) {
			AnomalyEffects.herald(level, player, herald, AnomalyClock.now());
		}

		List<Zone> near = AnomalyField.near(level, player.getX(), player.getZ(), config.view_range);

		// кемпинг: зона кормится, пока в ней стоят. Считаем здесь же — второй обход near()
		// на каждого игрока в секунду не нужен
		if (config.camp_feed > 0.0) {
			for (Zone zone : near) {
				if (!zone.remnant() && !zone.precursor() && zone.contains(player.getX(), player.getZ())) {
					AnomalyField.feedZone(zone.anomaly(), seconds, config.camp_feed);
					AnomalyField.markDirty();
				}
			}
		}

		Zone inside = null;
		for (Zone zone : near) {
			if (zone.contains(player.getX(), player.getZ())
					&& (inside == null || zone.strength() > inside.strength())) {
				inside = zone;
			}
		}
		// Компонентные пульсы (молния, гарь, левитация) идут у всех зон рядом: это механика.
		// Частицы края рисуем только у четырёх ближайших — в наложении это сотни пакетов на игрока,
		// а увидеть всё равно можно то, что ближе.
		List<Zone> drawOrder = new ArrayList<>(near);
		drawOrder.sort((a, b) -> Double.compare(
				Math.abs(a.signedDistance(player.getX(), player.getZ())),
				Math.abs(b.signedDistance(player.getX(), player.getZ()))));
		if (inside != null) {
			long insideId = inside.id();
			drawOrder.removeIf(zone -> zone.id() == insideId);
			drawOrder.add(0, inside);
		}
		java.util.Set<Long> drawnIds = new java.util.HashSet<>();
		for (int i = 0; i < Math.min(4, drawOrder.size()); i++) {
			drawnIds.add(drawOrder.get(i).id());
		}
		for (Zone zone : near) {
			AnomalyEffects.ambience(level, player, zone, config, state, drawnIds.contains(zone.id()));
		}

		AnomalyResidue.update(player, seconds, inside, state.dose);

		if (inside != null) {
			boolean fresh = state.zoneId != inside.id();
			// глубина нужна и для силы эффектов, и для достижений: считаем один раз
			double depth = Math.min(1.0, Math.max(0.0,
					-inside.signedDistance(player.getX(), player.getZ()) / Math.max(1.0, inside.radius())));
			state.lastDepth = depth;
			state.enter(inside, seconds, config);
			AnomalyEffects.apply(level, player, inside, state, config, seconds);
			if (fresh) {
				AnomalyEffects.entry(level, player, inside);
				// с остатком в крови за игроком идут: отсидеться у края не выйдет
				AnomalyResidue.raidOnEnter(level, player, inside);
			}
			// «в самом сердце»: глубина больше 85 % радиуса и не меньше пяти секунд
			if (depth > 0.85) {
				state.coreTicks += AnomalyState.stepTicks(seconds);
				if (state.coreTicks >= AnomalyProgress.CORE_TICKS) {
					AnomalyProgress.onCore(player);
				}
			} else {
				state.coreTicks = 0;
			}
		} else {
			// «вышел оттуда»: решаем по состоянию до сброса счётчиков в leave()
			if (AnomalyProgress.survives(state.ticksInside, state.dose, state.lastHazard, player.isAlive(),
					state.lastDepth)) {
				AnomalyProgress.onSurvived(player);
			}
			state.lastDepth = 0.0;
			state.coreTicks = 0;
			state.leave(seconds, config);
			// послевкусие только от опасной/смертельной зоны и только один раз за выход
			if (state.zoneId != 0L && state.lastHazard && !state.lingered
					&& state.dose >= Math.max(config.lingering_floor, 0.35)) {
				AnomalyEffects.lingering(level, player, state, config);
				state.lingered = true;
			}
		}
		// шиза: слои 1–2, работает от остатка и дозы, только в дальних землях
		AnomalyMadness.tick(player, state, config);
	}

	/** Для самопроверки и для будущих модов: доза игрока, если он уже попадал в аномалию. */
	static AnomalyState stateOf(UUID playerId) {
		return STATES.get(playerId);
	}

	/**
	 * Нижний мир: 1 блок = 8 блоков Верхнего. Дальний рейд в аду обходил бы поле целиком —
	 * ни дозы, ни остатка, ни времени. Поэтому глубина считается по координатам ×8: с 3.75 тыс.
	 * блоков ада начинается то же, что с 30 км в Верхнем, и цена растёт так же.
	 */
	static void netherToll(ServerPlayer player, AnomalyState state, AnomalyConfig config, double seconds) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		double scaled = Math.hypot(player.getX(), player.getZ()) * 8.0;
		if (scaled <= config.suppress_until) {
			return;
		}
		double depth = Math.min(1.0, (scaled - config.suppress_until) / 12000.0);
		state.dose = Math.min(1.0, state.dose + config.dose_gain_per_second * seconds * depth);
		state.lastHazard = true;
		AnomalyResidue.add(player.getUUID(), 3.0 * depth * seconds / 3600.0);
		// свой счётчик: ветка overworld_only зовёт leave(), а он гасит hazardTicks —
		// с ним урон в аду не срабатывал ни разу
		state.netherTicks += AnomalyState.stepTicks(seconds);
		if (state.netherTicks >= 120) {
			state.netherTicks = 0;
			player.hurt(player.level().damageSources().magic(), (float) (1.6 + 1.6 * depth));
		}
	}
}


