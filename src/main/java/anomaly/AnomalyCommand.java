package anomaly;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * {@code /anomaly} — инструменты проверки прямо в игре, без клиентских модов и без полёта наугад.
 * <p>
 * Всё, что нужно, чтобы увидеть поле своими глазами: список живых зон, тестовая зона под ногами,
 * телепорт на край, светящийся контур из частиц и ускорение времени, чтобы жизненный цикл
 * прошёл за минуты, а не за неделю.
 */
public final class AnomalyCommand {
	private AnomalyCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("anomaly")
						.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
						.executes(context -> status(context.getSource()))
						.then(Commands.literal("field")
								.executes(context -> field(context.getSource(), 6))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 40))
										.executes(context -> field(context.getSource(),
												IntegerArgumentType.getInteger(context, "count")))))
						.then(Commands.literal("here").executes(context -> here(context.getSource())))
						.then(Commands.literal("stats").executes(context -> stats(context.getSource())))
						.then(Commands.literal("spawn")
								.executes(context -> spawn(context.getSource(), null, null))
								.then(Commands.argument("radius", DoubleArgumentType.doubleArg(10.0, 5000.0))
										.executes(context -> spawn(context.getSource(),
												DoubleArgumentType.getDouble(context, "radius"), null))
										.then(Commands.argument("class", StringArgumentType.word())
												.executes(context -> spawn(context.getSource(),
														DoubleArgumentType.getDouble(context, "radius"),
														StringArgumentType.getString(context, "class"))))))
						.then(Commands.literal("kill")
								.then(Commands.argument("target", StringArgumentType.word())
										.executes(context -> kill(context.getSource(),
												StringArgumentType.getString(context, "target")))))
						.then(Commands.literal("tp")
								.then(Commands.argument("target", StringArgumentType.word())
										.executes(context -> teleport(context.getSource(),
												StringArgumentType.getString(context, "target"), false))))
						.then(Commands.literal("edge")
								.then(Commands.argument("target", StringArgumentType.word())
										.executes(context -> teleport(context.getSource(),
												StringArgumentType.getString(context, "target"), true))))
						.then(Commands.literal("show").executes(context -> show(context.getSource())))
						.then(Commands.literal("timescale")
								.executes(context -> timescale(context.getSource(), null))
								.then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.0, 100000.0))
										.executes(context -> timescale(context.getSource(),
												DoubleArgumentType.getDouble(context, "scale")))))
						.then(Commands.literal("selftest").executes(context -> selftest(context.getSource())))
						.then(Commands.literal("save").executes(context -> save(context.getSource())))));
	}

	// --- сводка ---

	private static int status(CommandSourceStack source) {
		AnomalyConfig config = AnomalyMod.config;
		AnomalyField.Stats stats = AnomalyField.stats();
		reply(source, "Аномалии: " + (config.enabled ? "включены" : "выключены")
				+ ", зон " + stats.total() + " (цель " + config.population + ")");
		reply(source, "Рождение с " + config.spawn_min_radius + " блоков, мир до " + config.world_radius
				+ ", радиус " + Math.round(config.radius_min) + "–" + Math.round(config.radius_max)
				+ ", жизнь до " + Math.round(config.lifetime_max_hours) + " ч");
		reply(source, "Классы: до " + config.danger_from + " безопасные, до " + config.deadly_from
				+ " опасные, дальше смертельные (размытие ±" + config.class_blur + ")");
		reply(source, "Рассеивание: внутри " + config.dissipate_radius + ", подавление до " + config.suppress_until
				+ "; время поля ×" + trim(AnomalyClock.scale()) + " (" + timeHint() + ")");
		reply(source, "Проверка: /anomaly field · here · spawn · edge · show · timescale · selftest");
		return 1;
	}

	private static int stats(CommandSourceStack source) {
		AnomalyField.Stats stats = AnomalyField.stats();
		reply(source, "Зон " + stats.total() + ": безопасных " + stats.safe()
				+ ", опасных " + stats.danger() + ", смертельных " + stats.deadly()
				+ (stats.pinned() > 0 ? ", тестовых " + stats.pinned() : ""));
		reply(source, String.format("Покрыто %.1f %% площади мира, суммарный бюджет разрушения %s блоков",
				stats.coveragePercent(), thousands(stats.budget())));
		reply(source, "Время поля " + String.format("%.1f", AnomalyClock.fieldMs() / 3_600_000.0)
				+ " ч, множитель ×" + trim(AnomalyClock.scale()));
		return 1;
	}

	private static int field(CommandSourceStack source, int count) {
		ServerPlayer player = source.getPlayer();
		double px = player != null ? player.getX() : source.getPosition().x;
		double pz = player != null ? player.getZ() : source.getPosition().z;
		List<Anomaly> list = AnomalyField.sortedByDistance(px, pz);
		if (list.isEmpty()) {
			reply(source, "Поле пустое: ни одной аномалии. /anomaly spawn — сделать тестовую");
			return 1;
		}
		long now = AnomalyClock.now();
		AnomalyConfig config = AnomalyMod.config;
		int shown = 0;
		for (Anomaly anomaly : list) {
			if (shown++ >= count) {
				break;
			}
			Zone zone = anomaly.zone(now);
			double distance = Math.hypot(zone.x() - px, zone.z() - pz);
			reply(source, "#" + anomaly.id
					+ (anomaly.pinned ? " [тест]" : "")
					+ " " + classTag(zone.cls()) + " r " + Math.round(anomaly.r0)
					+ " · " + String.format("%.1f", zone.ageHours()) + "/" + String.format("%.1f", anomaly.life_hours) + " ч"
					+ " · дрейф " + Math.round(anomaly.speed) + " бл/ч"
					+ " · дуга " + String.format("%.1f", zone.turnDegrees()) + "°"
					+ " · " + blocks(distance) + " от точки");
		}
		return 1;
	}

	private static int here(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		double px = player != null ? player.getX() : source.getPosition().x;
		double pz = player != null ? player.getZ() : source.getPosition().z;
		Anomaly anomaly = AnomalyField.nearest(px, pz);
		if (anomaly == null) {
			reply(source, "Аномалий нет вообще");
			return 1;
		}
		Zone zone = anomaly.zone(AnomalyClock.now());
		double signed = zone.signedDistance(px, pz);
		double drift = Math.hypot(zone.x() - anomaly.x0, zone.z() - anomaly.z0);

		reply(source, "#" + anomaly.id + (anomaly.pinned ? " [тест]" : "") + " " + classTag(zone.cls())
				+ " · " + (signed <= 0 ? "точка ВНУТРИ" : "снаружи") + " · до края " + blocks(Math.abs(signed)));
		reply(source, "Радиус " + Math.round(zone.radius()) + " из " + Math.round(anomaly.r0)
				+ " (жизнь " + String.format("%.1f", zone.ageHours()) + "/" + String.format("%.1f", anomaly.life_hours)
				+ " ч, фаза " + phase(anomaly, zone.ageHours()) + ")");
		reply(source, "Центр " + coord(zone.x(), zone.z()) + " · от центра мира " + blocks(Math.hypot(zone.x(), zone.z()))
				+ " · ядро " + blocks(Math.hypot(zone.coreX() - zone.x(), zone.coreZ() - zone.z())) + " от центра");
		reply(source, "Дрейф " + Math.round(anomaly.speed) + " бл/ч, дуга " + String.format("%.1f", zone.turnDegrees())
				+ "°, прошла " + blocks(drift) + " от рождения; тип " + zone.type().title.toLowerCase());
		reply(source, "Бюджет разрушения " + thousands(zone.budget()) + " блоков"
				+ (zone.cls().damages() ? "" : " (безопасная не рушит)"));
		return 1;
	}

	// --- создание и удаление ---

	private static int spawn(CommandSourceStack source, Double radius, String classArg)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		AnomalyClass cls = null;
		if (classArg != null) {
			cls = parseClass(classArg);
			if (cls == null) {
				reply(source, "Класс бывает safe, danger или deadly", ChatFormatting.RED);
				return 0;
			}
		}
		ServerPlayer player = source.getPlayerOrException();
		Anomaly anomaly = AnomalyField.spawnTest(player.getX(), player.getZ(), radius, cls);
		Zone zone = anomaly.zone(AnomalyClock.now());
		reply(source, "Тестовая аномалия #" + anomaly.id + " " + classTag(zone.cls())
				+ " r " + Math.round(anomaly.r0) + " под ногами. Приколота: не дрейфует, не гаснет, не умирает.");
		reply(source, "/anomaly show — контур частицами, /anomaly edge #" + anomaly.id
				+ " — встать у края, /anomaly timescale 600 — ускорить форму");
		return 1;
	}

	private static int kill(CommandSourceStack source, String target) {
		ServerPlayer player = source.getPlayer();
		double px = player != null ? player.getX() : source.getPosition().x;
		double pz = player != null ? player.getZ() : source.getPosition().z;
		Anomaly anomaly = AnomalyField.find(target, px, pz);
		if (anomaly == null) {
			reply(source, "Не нашёл аномалию «" + target + "»", ChatFormatting.RED);
			return 0;
		}
		AnomalyField.remove(anomaly.id);
		reply(source, "Аномалия #" + anomaly.id + " убрана из поля (население доберётся само)");
		return 1;
	}

	// --- перемещение ---

	private static int teleport(CommandSourceStack source, String target, boolean edge)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Anomaly anomaly = AnomalyField.find(target, player.getX(), player.getZ());
		if (anomaly == null) {
			reply(source, "Не нашёл аномалию «" + target + "»", ChatFormatting.RED);
			return 0;
		}
		ServerLevel level = player.level();
		if (level.dimension() != Level.OVERWORLD) {
			reply(source, "Аномалии живут в Верхнем мире — из этого измерения телепорт не работает",
					ChatFormatting.RED);
			return 0;
		}
		Zone zone = anomaly.zone(AnomalyClock.now());
		double x;
		double z;
		if (edge) {
			double angle = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
			double[] point = anomaly.boundaryPoint(angle, zone.ageHours(), zone.radius());
			// на два блока наружу: стоим у края и смотрим внутрь
			x = point[0] + Math.cos(angle) * 2.0;
			z = point[1] + Math.sin(angle) * 2.0;
		} else {
			x = zone.coreX();
			z = zone.coreZ();
		}
		int blockX = Mth.floor(x);
		int blockZ = Mth.floor(z);
		int y = safeY(level, blockX, blockZ);
		if (lavaBelow(level, blockX, y, blockZ)) {
			reply(source, "Под точкой лава — не ставлю. Повторите /anomaly edge: угол зависит от того, где вы стоите",
					ChatFormatting.RED);
			return 0;
		}
		player.teleportTo(blockX + 0.5, y + 0.1, blockZ + 0.5);
		reply(source, (edge ? "Вы у края " : "Вы в ядре ") + "#" + anomaly.id + " " + classTag(zone.cls())
				+ ", радиус " + Math.round(zone.radius()) + ". /anomaly show — включить контур");
		return 1;
	}

	/**
	 * Высота, на которую можно ставить игрока. {@code Level.getHeight} на незагруженном чанке отдаёт
	 * минимум мира (там игрок окажется под бедроком), поэтому чанк сначала грузим. И поднимаемся,
	 * пока под ногами жидкость: садиться в лаву или океан «по карте высот» — плохая идея.
	 */
	private static int safeY(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int limit = level.getMaxY() - 2;
		for (int i = 0; i < 24 && y < limit; i++) {
			boolean dryFloor = level.getBlockState(pos.set(x, y - 1, z)).getFluidState().isEmpty();
			boolean dryFeet = level.getBlockState(pos.set(x, y, z)).getFluidState().isEmpty();
			if (dryFloor && dryFeet) {
				break;
			}
			y++;
		}
		return y;
	}

	/** Лава под точкой: падение в неё — это смерть, а не отладка. */
	private static boolean lavaBelow(ServerLevel level, int x, int y, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy >= -3; dy--) {
			if (level.getBlockState(pos.set(x, y + dy, z)).getFluidState().is(FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	// --- отладка ---

	private static int show(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean on = AnomalyDebug.toggle(player.getUUID());
		if (!on) {
			reply(source, "Контур выключен");
			return 1;
		}
		Anomaly nearest = AnomalyField.nearest(player.getX(), player.getZ());
		if (nearest == null) {
			reply(source, "Контур включён, но аномалий нет: /anomaly spawn", ChatFormatting.YELLOW);
			return 1;
		}
		Zone zone = nearest.zone(AnomalyClock.now());
		double signed = zone.signedDistance(player.getX(), player.getZ());
		reply(source, "Контур включён: ближайшая #" + nearest.id + " " + classTag(zone.cls())
				+ ", до края " + blocks(Math.abs(signed)));
		if (Math.abs(signed) > 400) {
			reply(source, "Край дальше 400 блоков — частицы не долетят. /anomaly edge #" + nearest.id,
					ChatFormatting.YELLOW);
		}
		return 1;
	}

	private static int timescale(CommandSourceStack source, Double scale) {
		if (scale == null) {
			reply(source, "Время поля ×" + trim(AnomalyClock.scale()) + " (" + timeHint() + ")");
			return 1;
		}
		AnomalyClock.setScale(scale);
		reply(source, "Время поля ×" + trim(scale) + " (" + timeHint() + ")");
		if (scale > 60) {
			reply(source, "Аномалии будут рождаться и умирать на глазах; вернуть — /anomaly timescale 1",
					ChatFormatting.YELLOW);
		}
		return 1;
	}

	private static int selftest(CommandSourceStack source) {
		SelfTest.Result result = SelfTest.run(source.getServer());
		reply(source, "Самопроверка: " + result.passed + " из " + (result.passed + result.failed)
				+ (result.failed == 0 ? " — всё в порядке" : " — есть ошибки"),
				result.failed == 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
		int shown = 0;
		for (String failure : result.failures) {
			if (shown++ >= 8) {
				reply(source, "…и ещё " + (result.failures.size() - 8) + " (полностью — в логе)");
				break;
			}
			reply(source, "FAIL: " + failure, ChatFormatting.RED);
		}
		return result.failed == 0 ? 1 : 0;
	}

	private static int save(CommandSourceStack source) {
		boolean saved = AnomalyField.save();
		if (saved) {
			reply(source, "Поле записано: " + AnomalyField.path(), ChatFormatting.GREEN);
		} else {
			reply(source, "Не смог записать поле в " + AnomalyField.path() + " — смотрите лог сервера",
					ChatFormatting.RED);
		}
		return saved ? 1 : 0;
	}

	// --- текст ---

	private static AnomalyClass parseClass(String value) {
		for (AnomalyClass cls : AnomalyClass.values()) {
			if (cls.id.equalsIgnoreCase(value)) {
				return cls;
			}
		}
		return null;
	}

	private static String classTag(AnomalyClass cls) {
		return switch (cls) {
			case SAFE -> "безопасная";
			case DANGER -> "опасная";
			case DEADLY -> "смертельная";
		};
	}

	private static String phase(Anomaly anomaly, double ageHours) {
		double frac = ageHours / Math.max(0.001, anomaly.life_hours);
		if (frac < Anomaly.GROW_UNTIL) {
			return "рост";
		}
		return frac > Anomaly.DECAY_FROM ? "распад" : "зрелость";
	}

	private static String coord(double x, double z) {
		return Math.round(x) + ", " + Math.round(z);
	}

	private static String blocks(double value) {
		if (value >= 1000.0) {
			return String.format("%.1fк", value / 1000.0);
		}
		return String.valueOf(Math.round(value));
	}

	private static String thousands(double value) {
		return String.format(java.util.Locale.ROOT, "%,.0f", value).replace(',', ' ');
	}

	private static String trim(double value) {
		if (value == Math.rint(value)) {
			return String.valueOf((long) value);
		}
		return String.format("%.1f", value);
	}

	private static String timeHint() {
		double scale = AnomalyClock.scale();
		if (scale <= 0) {
			return "время стоит";
		}
		if (scale == 1) {
			return "как в жизни";
		}
		double realSecondsPerHour = 3600.0 / scale;
		if (realSecondsPerHour >= 60) {
			return "час поля за " + trim(realSecondsPerHour / 60.0) + " мин";
		}
		return "час поля за " + trim(realSecondsPerHour) + " с";
	}

	private static void reply(CommandSourceStack source, String text) {
		reply(source, text, ChatFormatting.AQUA);
	}

	private static void reply(CommandSourceStack source, String text, ChatFormatting format) {
		source.sendSuccess(() -> Component.literal(text).withStyle(format), false);
	}
}
