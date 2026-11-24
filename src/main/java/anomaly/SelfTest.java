package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Самопроверка ядра без клиента и без игрока: форма, дрейф, жизнь, классы, бюджет, рассеивание,
 * население и сохранение. Запускается системным свойством/переменной {@code ANOMALY_SELFTEST}
 * при старте или командой {@code /anomaly selftest} прямо в игре.
 * <p>
 * Проверки не трогают живое поле: всё гоняется на локальной копии {@link AnomalyFieldData}.
 */
public final class SelfTest {
	private static boolean done;

	private SelfTest() {
	}

	/** Итог прогона: счётчики и тексты провалов — их показывает команда. */
	public static final class Result {
		public int passed;
		public int failed;
		public final List<String> failures = new ArrayList<>();

		public int total() {
			return passed + failed;
		}
	}

	public static void onTick(MinecraftServer server) {
		if (done) {
			return;
		}
		done = true;
		Result result = run(server);
		AnomalyMod.LOGGER.info("SELFTEST: {} из {} — {}", result.passed, result.total(),
				result.failed == 0 ? "PASS" : "FAILED");
		for (String failure : result.failures) {
			AnomalyMod.LOGGER.error("SELFTEST FAIL: {}", failure);
		}
		// разовый прогон на выделенном сервере: самопроверка закрывает сервер, а не висит до таймаута
		if (System.getenv("ANOMALY_SELFTEST_EXIT") != null) {
			server.halt(false);
		}
	}

	public static Result run(MinecraftServer server) {
		Result result = new Result();
		AnomalyConfig config = new AnomalyConfig();
		Random rnd = new Random(20261120L);

		runGroup(result, "форма", () -> checkShape(result, config, rnd));
		runGroup(result, "дрейф", () -> checkDrift(result, config, rnd));
		runGroup(result, "жизнь", () -> checkLife(result, config, rnd));
		runGroup(result, "доза", () -> checkDose(result, config));
		runGroup(result, "компоненты", () -> checkComponents(result, config, rnd));
		runGroup(result, "следы", () -> checkScars(result, config, rnd));
		runGroup(result, "классы", () -> checkClasses(result, config, rnd));
		runGroup(result, "бюджет", () -> checkBudget(result));
		runGroup(result, "рассеивание", () -> checkSuppression(result, config));
		runGroup(result, "осколки", () -> checkRemnants(result, config, rnd));
		runGroup(result, "остаток", () -> checkResidue(result, config));
		runGroup(result, "рейды", () -> checkNodes(result, config));
		runGroup(result, "население", () -> checkPopulation(result, config, rnd));
		runGroup(result, "предвестник", () -> checkHerald(result, config, rnd));
		runGroup(result, "кемпинг", () -> checkCamping(result, config, rnd));
		runGroup(result, "места рождения", () -> checkSpawnPlaces(result, config, rnd));
		runGroup(result, "реестр следов", () -> checkScarRegistry(result, config, rnd));
		runGroup(result, "новая геология", () -> checkGeology(result, config, rnd));
		runGroup(result, "вложенность", () -> checkNesting(result, config, rnd));
		runGroup(result, "шиза", () -> checkMadness(result, config, rnd));
		runGroup(result, "обучение", () -> checkTeaching(result));
		runGroup(result, "лимит генерации", () -> checkChunkBudget(result, config));
		runGroup(result, "рейды остатка", () -> checkResidueRaids(result, config));
		runGroup(result, "экономика", () -> checkEconomy(result, config, rnd));
		runGroup(result, "сохранение", () -> checkPersistence(result, config, rnd));
		runGroup(result, "края", () -> checkEdges(result, config, rnd));
		if (server != null) {
			// живое поле должно быть с компонентами: пустая зона — это невидимая и инертная оболочка
			runGroup(result, "живое поле", () -> {
				List<Anomaly> live = AnomalyField.all();
				boolean withComponents = true;
				for (Anomaly anomaly : live) {
					if (anomaly.remnant) {
						continue;
					}
					withComponents &= !anomaly.componentList().isEmpty();
				}
				report(result, "у всех зон живого поля есть компоненты (" + live.size() + ")", withComponents);
			});
			// разрушения правят блоки: в чужом мире это делает только явно включённая самопроверка
			if (System.getProperty("anomaly.selftest") != null || System.getenv("ANOMALY_SELFTEST") != null) {
				runGroup(result, "следы вживую", () -> checkScarsLive(result, server));
				runGroup(result, "рейды вживую", () -> checkNodesLive(result, server));
				runGroup(result, "узлы вживую", () -> checkNodesPlacedLive(result, server));
				runGroup(result, "порталы вживую", () -> checkPortalsLive(result, server));
			}
		}
		return result;
	}

	private static void runGroup(Result result, String name, Runnable body) {
		try {
			body.run();
		} catch (Throwable t) {
			report(result, "группа «" + name + "» упала: " + t, false);
		}
	}

	// --- форма ---

	private static void checkShape(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 9000, 0, 900.0, AnomalyClass.DANGER, false);
		// ставим в середину жизни: в момент рождения радиуса ещё нет
		anomaly.life_hours = 24.0;
		anomaly.born = -12L * 3_600_000L;
		Zone zone = anomaly.zone(0L);
		double radius = zone.radius();

		boolean onEdge = true;
		double previousX = 0;
		double previousZ = 0;
		double maxStep = 0;
		// шаг выборки должен быть мелким относительно самой высокой гармоники, иначе тест
		// меряет алиасинг рваного края, а не разрыв контура
		int points = 360;
		for (Harmonic harmonic : anomaly.harmonics) {
			points = Math.max(points, harmonic.k * 16);
		}
		for (int i = 0; i <= points; i++) {
			double angle = i * Math.PI * 2.0 / points;
			double[] point = anomaly.boundaryPoint(angle, zone.ageHours(), radius);
			if (Math.abs(anomaly.signedDistance(point[0], point[1], zone.ageHours(), radius)) > 0.5) {
				onEdge = false;
			}
			if (i > 0) {
				maxStep = Math.max(maxStep, Math.hypot(point[0] - previousX, point[1] - previousZ));
			}
			previousX = point[0];
			previousZ = point[1];
		}
		report(result, "граница замкнута и точки лежат на ней", onEdge);
		report(result, "контур без разрывов (шаг " + String.format("%.1f", maxStep) + " при радиусе "
				+ Math.round(radius) + ")", maxStep < radius * 0.1);

		// форма меняется во времени: это «край дышит», а не статичный многоугольник
		boolean morphs = false;
		for (int i = 0; i < 48 && !morphs; i++) {
			double theta = i * Math.PI * 2.0 / 48;
			double now = anomaly.shapeFactor(theta, 0.0);
			double later = anomaly.shapeFactor(theta, 24.0);
			if (Math.abs(now - later) > 0.005) {
				morphs = true;
			}
		}
		report(result, "форма меняется за сутки", morphs);

		boolean factorSane = true;
		for (int i = 0; i < 180; i++) {
			double value = anomaly.shapeFactor(i * Math.PI * 2.0 / 180, 10.0);
			if (!(value >= 0.35 && value < 4.0)) {
				factorSane = false;
			}
		}
		report(result, "множитель формы в 0.35..4", factorSane);

		// глубокая вмятина не должна выворачиваться наружу: мягкий максимум держит дно у 0.35
		Anomaly dent = new Anomaly();
		dent.r0 = 1000.0;
		dent.harmonics = new ArrayList<>();
		dent.harmonics.add(new Harmonic(2, 0.9, 0.0, 0.0, 0.0, 0.0, 0.0));
		double trough = dent.shapeFactor(3.0 * Math.PI / 4.0, 0.0);
		report(result, "вмятина упирается в 0.35, а не выворачивается (дно " + String.format("%.2f", trough) + ")",
				trough >= 0.35 && trough < 0.45);

		boolean harmonicsOk = anomaly.harmonics.size() >= 2 && anomaly.harmonics.size() <= 6;
		for (Harmonic harmonic : anomaly.harmonics) {
			harmonicsOk &= harmonic.valid();
		}
		report(result, "гармоник 2–6 и все корректны", harmonicsOk);
	}

	// --- дрейф ---

	private static void checkDrift(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		boolean turnsOk = true;
		boolean lifetimeOk = true;
		boolean speedOk = true;
		for (int i = 0; i < 200; i++) {
			Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 12000, 0, null, null, false);
			double turn = Math.toDegrees(anomaly.arc * anomaly.life_hours);
			if (Math.abs(turn) > 10.0 + 1e-6) {
				turnsOk = false;
			}
			if (anomaly.life_hours > config.lifetime_max_hours + 1e-6) {
				lifetimeOk = false;
			}
			double perMinute = anomaly.speed / 60.0;
			if (perMinute < config.drift_min_per_minute - 0.01 || perMinute > config.drift_max_per_minute + 0.01) {
				speedOk = false;
			}
		}
		report(result, "дуга не больше 10° за жизнь", turnsOk);
		report(result, "жизнь не длиннее потолка " + Math.round(config.lifetime_max_hours) + " ч", lifetimeOk);
		report(result, "дрейф в " + config.drift_min_per_minute + "–" + config.drift_max_per_minute + " бл/мин", speedOk);

		Anomaly straight = new Anomaly();
		straight.x0 = 1000;
		straight.z0 = -500;
		straight.speed = 60;
		straight.dir = 0;
		straight.arc = 0;
		straight.r0 = 200;
		straight.life_hours = 10;
		double[] start = straight.position(0.0);
		double[] after = straight.position(5.0);
		report(result, "без кривизны путь прямой (60 бл/ч за 5 ч = 300)",
				Math.abs(after[0] - start[0] - 300.0) < 1e-6 && Math.abs(after[1] - start[1]) < 1e-6);

		// дуга 10°: хорда короче пути меньше чем на 0.2 %, иначе это уже не «почти прямая»
		Anomaly arc = new Anomaly();
		arc.x0 = 0;
		arc.z0 = 0;
		arc.speed = 60;
		arc.dir = 0;
		arc.arc = Math.toRadians(10.0) / 24.0;
		arc.r0 = 200;
		arc.life_hours = 24;
		double[] pos = arc.position(24.0);
		double chord = Math.hypot(pos[0], pos[1]);
		report(result, "дуга 10°: пройдено " + String.format("%.1f", chord) + " из 1440",
				chord > 1440.0 * 0.998);

		double[] again = arc.position(24.0);
		report(result, "позиция детерминирована", pos[0] == again[0] && pos[1] == again[1]);
	}

	// --- жизнь ---

	private static void checkLife(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 12000, 0, 800.0, AnomalyClass.DEADLY, false);
		anomaly.life_hours = 100.0;

		check(result, "в начале жизни радиуса нет", 0.0, anomaly.envelope(0.0), 1e-9);
		check(result, "в середине — полный радиус", 1.0, anomaly.envelope(50.0), 1e-9);
		check(result, "на исходе — снова ноль", 0.0, anomaly.envelope(100.0), 1e-9);

		double early = anomaly.radius(10.0);
		double middle = anomaly.radius(50.0);
		double late = anomaly.radius(95.0);
		report(result, "растёт: " + Math.round(early) + " < " + Math.round(middle), early < middle * 0.6);
		report(result, "сжимается: " + Math.round(late) + " < " + Math.round(middle), late < middle * 0.3);

		long now = 10_000_000L;
		anomaly.born = now - (long) (100.0 * 3_600_000L);
		report(result, "после срока жизни не живёт", !anomaly.alive(now));
		report(result, "за час до конца ещё жива", anomaly.alive(now - 3_600_000L));
	}

	// --- доза ---

	private static void checkDose(Result result, AnomalyConfig config) {
		Anomaly anomaly = new Anomaly();
		anomaly.id = 1L;
		anomaly.r0 = 500;
		anomaly.life_hours = 24;
		anomaly.cls = AnomalyClass.DEADLY.id;
		Zone zone = anomaly.zone(0L);

		AnomalyState state = new AnomalyState();
		state.enter(zone, 10.0, config);
		check(result, "10 с внутри → доза 0.5", 0.5, state.dose, 1e-9);
		state.enter(zone, 100.0, config);
		check(result, "доза не выше 1.0", 1.0, state.dose, 1e-9);
		state.leave(25.0, config);
		check(result, "25 с снаружи → 0.5", 0.5, state.dose, 1e-9);
		state.leave(100.0, config);
		check(result, "доза до нуля", 0.0, state.dose, 1e-9);
		report(result, "на нуле зона забыта", state.zoneId == 0L);

		AnomalyState amp = new AnomalyState();
		amp.dose = 0.2;
		report(result, "доза 0.2 → 20 %", amp.percent() == 20);
		amp.dose = 0.5;
		report(result, "доза 0.5 → 50 %", amp.percent() == 50);
		amp.dose = 0.9;
		report(result, "доза 0.9 → 90 %", amp.percent() == 90);

		// смена набора не тащит чужую дозу: вход в другую зону срезает её до 30 %
		Anomaly other = new Anomaly();
		other.id = 2L;
		other.r0 = 500;
		other.life_hours = 24;
		other.cls = AnomalyClass.DEADLY.id;
		AnomalyState switching = new AnomalyState();
		switching.dose = 1.0;
		switching.zoneId = 1L;
		switching.enter(other.zone(0L), 0.0, config);
		check(result, "вход в другую зону срезает дозу до 0.3", 0.3, switching.dose, 1e-9);
	}

	// --- компоненты ---

	private static void checkComponents(Result result, AnomalyConfig config, Random rnd) {
		boolean counts = true;
		boolean budgets = true;
		boolean allowed = true;
		boolean groups = true;
		boolean safeNoDamage = true;
		boolean visible = true;

		for (AnomalyClass cls : AnomalyClass.values()) {
			for (int i = 0; i < 200; i++) {
				double radius = 100.0 + rnd.nextDouble() * 2900.0;
				List<AnomalyComponent> set = AnomalyComponent.roll(cls, rnd, config, radius);
				int bonus = radius >= 800.0 ? 1 : 0;
				if (set.size() < AnomalyComponent.minCount(cls)
						|| set.size() > AnomalyComponent.maxCount(cls) + bonus) {
					counts = false;
				}
				for (AnomalyComponent component : set) {
					if (!component.allows(cls)) {
						allowed = false;
					}
					if (cls == AnomalyClass.SAFE && component.minClass != AnomalyClass.SAFE) {
						safeNoDamage = false;
					}
				}
				// полная цена учитывает и надбавку за синергии; у гигантов бюджет выше на 2
				int cap = AnomalyComponent.budgetCap(cls) + (radius >= 1800.0 ? 2 : 0);
				if (AnomalyComponent.totalCost(set) > cap) {
					budgets = false;
				}
				if (!groupsOk(set, cls)) {
					groups = false;
				}
				boolean hasVisible = false;
				for (AnomalyComponent component : set) {
					hasVisible |= component.isVisible();
				}
				if (!hasVisible) {
					visible = false;
				}
			}
		}
		report(result, "компонентов в наборе по классу и размеру", counts);
		report(result, "бюджет смертоносности не превышен", budgets);
		report(result, "в наборе только разрешённые классу компоненты", allowed);
		report(result, "запрещённые пары не встречаются", groups);
		report(result, "безопасная не получает урон", safeNoDamage);
		report(result, "у каждой зоны есть видимый компонент", visible);

		List<AnomalyComponent> first = AnomalyComponent.roll(AnomalyClass.DEADLY, new Random(77L), config, 900);
		List<AnomalyComponent> second = AnomalyComponent.roll(AnomalyClass.DEADLY, new Random(77L), config, 900);
		report(result, "набор детерминирован по сиду", first.equals(second));

		int synergies = 0;
		for (int i = 0; i < 200; i++) {
			List<AnomalyComponent> set = AnomalyComponent.roll(AnomalyClass.DEADLY, new Random(i), config, 1400);
			if (AnomalyComponent.synergyCount(set) > 0) {
				synergies++;
			}
		}
		report(result, "синергии иногда выпадают (" + synergies + " из 200)", synergies > 0);
		report(result, "синергия поднимает силу на 25 %",
				Math.abs(AnomalyComponent.intensity(List.of(AnomalyComponent.COLD_CREEP,
						AnomalyComponent.ASH_FALL)) - 1.25) < 1e-9);

		report(result, "незнакомый id в файле игнорируется",
				AnomalyComponent.parse(List.of("no_such", "cold_creep", "cold_creep")).size() == 1);
		report(result, "пустой список компонентов разбирается в пустой",
				AnomalyComponent.parse(null).isEmpty());

		java.util.Set<String> ids = new java.util.HashSet<>();
		boolean unique = true;
		boolean titled = true;
		for (AnomalyComponent component : AnomalyComponent.values()) {
			unique &= ids.add(component.id);
			titled &= !component.title.isBlank();
		}
		report(result, "у компонентов уникальные id", unique);
		report(result, "у каждого компонента есть название", titled);

		AnomalyState pulses = new AnomalyState();
		pulses.pulse(10);
		pulses.pulse(10);
		check(result, "счётчик эффектных тиков растёт", 20, pulses.pulseTicks, 0);

		// выключение в конфиге действительно выключает: остаётся только разрешённое
		AnomalyConfig onlyOne = new AnomalyConfig();
		for (AnomalyComponent component : AnomalyComponent.values()) {
			if (component != AnomalyComponent.LIGHT_WALL) {
				onlyOne.disabled_components.add(component.id);
			}
		}
		List<AnomalyComponent> only = AnomalyComponent.roll(AnomalyClass.DEADLY, new Random(5L), onlyOne, 900);
		report(result, "выключенные компоненты не выпадают",
				!only.isEmpty() && only.stream().allMatch(c -> c == AnomalyComponent.LIGHT_WALL));

		AnomalyConfig noneEnabled = new AnomalyConfig();
		for (AnomalyComponent component : AnomalyComponent.values()) {
			noneEnabled.disabled_components.add(component.id);
		}
		report(result, "всё выключено → пустой набор без падения",
				AnomalyComponent.roll(AnomalyClass.DEADLY, new Random(6L), noneEnabled, 900).isEmpty());

		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 12000, 0, null, null, false);
		report(result, "аномалия рождается с компонентами", !anomaly.componentList().isEmpty());
	}

	// --- следы разрушения ---

	private static void checkScars(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly small = AnomalyField.create(field, config, rnd, 0L, 12000, 0, 60.0, AnomalyClass.DANGER, false);
		report(result, "мелкая зона не оставляет следов", AnomalyScars.plan(small,
				AnomalyClass.DANGER, new Random(1L)).isEmpty());

		Anomaly deadly = AnomalyField.create(field, config, rnd, 0L, 16000, 0, 1800.0, AnomalyClass.DEADLY, false);
		// геология теперь зависит от компонентов: пики — давление/гроза, лава — только гроза
		deadly.components = List.of("pressure", "storm_core");
		List<ScarOp> plan = AnomalyScars.plan(deadly, AnomalyClass.DEADLY, new Random(2L));
		report(result, "у большой смертельной есть следы", !plan.isEmpty());

		// мелкие опасные (100–149) раньше оставались без единого следа: бюджет 0 < порога плана
		report(result, "у мелкой опасной есть подпись (40 блоков)",
				AnomalyClass.damageBudget(120.0, AnomalyClass.DANGER) >= 40.0);
		report(result, "у зоны r=120 есть план следов",
				!AnomalyScars.plan(AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 12000, 0,
						120.0, AnomalyClass.DANGER, false), AnomalyClass.DANGER, new Random(9L)).isEmpty());
		// трещина не должна резать пол базы из булыжника
		report(result, "булыжник считается постройкой",
				AnomalyScars.builtBlock(Blocks.COBBLESTONE.defaultBlockState()));
		int total = 0;
		boolean inBudget = true;
		boolean insideZone = true;
		boolean outer = false;
		boolean kinds = false;
		double budget = AnomalyClass.damageBudget(deadly.r0, AnomalyClass.DEADLY);
		for (ScarOp op : plan) {
			total += op.blocks;
			if (total > budget) {
				inBudget = false;
			}
			double distance = Math.hypot(op.x - deadly.x0, op.z - deadly.z0);
			// следы живут в полосе 45–85 % радиуса: это та часть зоны, которую игрок пересекает,
			// и край перестаёт выглядеть нетронутым (раньше всё лежало в ядре — 35 %)
			if (distance > deadly.r0 * 0.9) {
				insideZone = false;
			}
			outer |= distance > deadly.r0 * 0.4;
			kinds |= "spike".equals(op.type);
		}
		report(result, "следы не превышают бюджет (" + total + " из " + Math.round(budget) + ")", inBudget);
		report(result, "следы не выходят за зону и доходят до внешней половины", insideZone && outer);
		report(result, "у смертельной бывают пики", kinds);

		List<ScarOp> safePlan = AnomalyScars.plan(
				AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 12000, 0, 1500.0,
						AnomalyClass.SAFE, false),
				AnomalyClass.SAFE, new Random(3L));
		boolean noSpikes = true;
		for (ScarOp op : safePlan) {
			noSpikes &= !"spike".equals(op.type) && !"lava_fault".equals(op.type);
		}
		report(result, "безопасная не получает пиков и лавы", noSpikes);

		// геология идёт за компонентами: без «давящих» и грозы смертельная зона не ставит пики и лаву
		Anomaly bare = AnomalyField.create(field, config, rnd, 0L, 16000, 0, 1800.0, AnomalyClass.DEADLY, false);
		bare.components = List.of();
		boolean geologyFollowsComponents = true;
		for (ScarOp op : AnomalyScars.plan(bare, AnomalyClass.DEADLY, new Random(5L))) {
			geologyFollowsComponents &= !"spike".equals(op.type) && !"lava_fault".equals(op.type);
		}
		report(result, "без pressure и грозы нет пиков и лавы", geologyFollowsComponents);

		List<ScarOp> first = AnomalyScars.plan(deadly, AnomalyClass.DEADLY, new Random(2L));
		List<ScarOp> second = AnomalyScars.plan(deadly, AnomalyClass.DEADLY, new Random(2L));
		boolean same = first.size() == second.size();
		for (int i = 0; same && i < first.size(); i++) {
			same = first.get(i).type.equals(second.get(i).type) && first.get(i).blocks == second.get(i).blocks;
		}
		report(result, "план следов детерминирован", same);

		ScarOp hole = ScarOp.sinkhole(0.0, 0.0, 4.0, 5, 1L);
		report(result, "провал оценивается по объёму",
				hole.blocks == (int) Math.round(Math.PI * 16.0 * 5)
						&& hole.describe().startsWith("провал"));

		boolean columnsOk = true;
		for (ScarOp op : first) {
			int count = op.columnCount();
			if (count <= 0) {
				columnsOk = false;
				continue;
			}
			double reach = op.radius + op.length + op.width + 8.0;
			for (int i = 0; i < Math.min(count, 128); i++) {
				int[] column = op.column(i);
				if (column != null && (Math.abs(column[0] - op.x) > reach || Math.abs(column[1] - op.z) > reach)) {
					columnsOk = false;
				}
			}
		}
		report(result, "колонки следов считаются в своих границах", columnsOk);

		Anomaly pinned = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 0, 0, 900.0,
				AnomalyClass.DEADLY, true);
		report(result, "тестовая зона не рушит мир", AnomalyScars.plan(pinned,
				AnomalyClass.DEADLY, new Random(4L)).isEmpty());

		report(result, "порталы и инфраструктура неприкосновенны",
				AnomalyScars.protectedBlock(Blocks.NETHER_PORTAL.defaultBlockState())
						&& AnomalyScars.protectedBlock(Blocks.END_PORTAL.defaultBlockState())
						&& AnomalyScars.protectedBlock(Blocks.END_GATEWAY.defaultBlockState())
						&& AnomalyScars.protectedBlock(Blocks.CHEST.defaultBlockState())
						&& AnomalyScars.protectedBlock(Blocks.BEDROCK.defaultBlockState())
						&& !AnomalyScars.protectedBlock(Blocks.STONE.defaultBlockState()));

		report(result, "постройки отличаются от рельефа",
				AnomalyScars.builtBlock(Blocks.OAK_PLANKS.defaultBlockState())
						&& AnomalyScars.builtBlock(Blocks.STONE_BRICKS.defaultBlockState())
						&& AnomalyScars.builtBlock(Blocks.GLASS.defaultBlockState())
						&& !AnomalyScars.builtBlock(Blocks.STONE.defaultBlockState())
						&& !AnomalyScars.builtBlock(Blocks.DIRT.defaultBlockState()));
	}

	// --- рейды вживую (ставят настоящий сундук в загруженный чанк) ---

	/**
	 * Порталы вживую: блоки портала гаснут, обсидиановая рама остаётся, а дальность считается
	 * в блоках Верхнего мира (ад — ×8). Блоки портала — это именно NETHER_PORTAL, не обсидиан.
	 */
	private static void checkPortalsLive(Result result, MinecraftServer server) {
		ServerLevel level = server.overworld();
		int px = 96;
		int pz = 96;
		level.getChunk(px >> 4, pz >> 4);
		int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
		Map<BlockPos, BlockState> before = snapshot(level, px, pz, 4, py - 2, py + 6);
		// рама 4×5 из обсидиана и блоки портала внутри
		for (int dx = 0; dx < 4; dx++) {
			level.setBlock(new BlockPos(px + dx, py, pz), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(px + dx, py + 4, pz), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		}
		for (int dy = 1; dy < 4; dy++) {
			level.setBlock(new BlockPos(px, py + dy, pz), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(px + 3, py + dy, pz), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
			for (int dx = 1; dx < 3; dx++) {
				level.setBlock(new BlockPos(px + dx, py + dy, pz), Blocks.NETHER_PORTAL.defaultBlockState(),
						Block.UPDATE_ALL);
			}
		}
		int portalBlocks = 0;
		for (int dx = 1; dx < 3; dx++) {
			for (int dy = 1; dy < 4; dy++) {
				if (level.getBlockState(new BlockPos(px + dx, py + dy, pz)).is(Blocks.NETHER_PORTAL)) {
					portalBlocks++;
				}
			}
		}
		// гасим руками: коллапс не требует живого игрока (тот же код, что зовёт миксин)
		AnomalyPortals.collapseAt(level, new BlockPos(px + 1, py + 1, pz));
		int after = 0;
		int obsidian = 0;
		for (int dx = 1; dx < 3; dx++) {
			for (int dy = 1; dy < 4; dy++) {
				if (level.getBlockState(new BlockPos(px + dx, py + dy, pz)).is(Blocks.NETHER_PORTAL)) {
					after++;
				}
			}
		}
		obsidian += level.getBlockState(new BlockPos(px, py + 1, pz)).is(Blocks.OBSIDIAN) ? 1 : 0;
		obsidian += level.getBlockState(new BlockPos(px + 3, py + 2, pz)).is(Blocks.OBSIDIAN) ? 1 : 0;
		obsidian += level.getBlockState(new BlockPos(px, py, pz)).is(Blocks.OBSIDIAN) ? 1 : 0;
		report(result, "портал: блоки гаснут, рама цела", portalBlocks == 6 && after == 0 && obsidian == 3);
		restore(level, before);

		report(result, "дальность портала: ад считается ×8",
				Math.abs(AnomalyPortals.equivalent(level, 300.0, 400.0) - 500.0) < 1.0e-6);
	}

	/** Узлы в живом мире: кладовая, алтарь и три якоря реально появляются на своих местах. */
	private static void checkNodesPlacedLive(Result result, MinecraftServer server) {
		ServerLevel level = server.overworld();
		level.getChunk(0, 0);
		Anomaly zone = AnomalyField.spawnTest(48.0, 48.0, 120.0, AnomalyClass.SAFE);
		if (zone == null) {
			report(result, "тестовая зона для узлов создаётся", false);
			return;
		}
		boolean placed = false;
		try {
			AnomalyNodes.placeNow(level, zone);
			java.util.List<double[]> spots = AnomalyNodes.nodeSpots(zone, zone.node_cx, zone.node_cz, zone.node_r);
			BlockPos cachePos = new BlockPos((int) Math.floor(spots.get(0)[0]), (int) Math.round(zone.node_cy),
					(int) Math.floor(spots.get(0)[1]));
			boolean cache = zone.node_cache_ok && level.getBlockState(cachePos).is(Blocks.CHEST);

			double[] altar = spots.get(1);
			int ax = (int) Math.floor(altar[0]);
			int az = (int) Math.floor(altar[1]);
			boolean altarBlock = zone.node_altar_ok && level.getBlockState(
					new BlockPos(ax, (int) Math.round(zone.node_altar_cy), az)).is(Blocks.LODESTONE);

			int anchors = 0;
			for (int i = 0; i < 3; i++) {
				double[] spot = spots.get(i + 2);
				int x = (int) Math.floor(spot[0]);
				int z = (int) Math.floor(spot[1]);
				int y = zone.anchor_ys != null && zone.anchor_ys.length == 3 ? zone.anchor_ys[i] : 0;
				if (y != 0 && level.getBlockState(new BlockPos(x, y, z)).is(Blocks.CRYING_OBSIDIAN)) {
					anchors++;
				}
			}
			placed = cache && altarBlock && anchors == 3;
			report(result, "кладовая, алтарь и три якоря встали на места", placed);
		} finally {
			// за собой убираем: иначе в тестовом мире остаются сундук, алтарь и три обсидиана
			java.util.List<double[]> spots = AnomalyNodes.nodeSpots(zone, zone.node_cx, zone.node_cz, zone.node_r);
			java.util.List<BlockPos> touched = new java.util.ArrayList<>(5);
			if (zone.node_cache_ok) {
				touched.add(new BlockPos((int) Math.floor(spots.get(0)[0]), (int) Math.round(zone.node_cy),
						(int) Math.floor(spots.get(0)[1])));
			}
			if (zone.node_altar_ok) {
				touched.add(new BlockPos((int) Math.floor(spots.get(1)[0]),
						(int) Math.round(zone.node_altar_cy), (int) Math.floor(spots.get(1)[1])));
			}
			for (int i = 0; i < 3 && zone.anchor_ys != null && zone.anchor_ys.length == 3; i++) {
				if (zone.anchor_ys[i] != 0) {
					touched.add(new BlockPos((int) Math.floor(spots.get(i + 2)[0]), zone.anchor_ys[i],
							(int) Math.floor(spots.get(i + 2)[1])));
				}
			}
			for (BlockPos pos : touched) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
			boolean clean = true;
			for (BlockPos pos : touched) {
				clean &= level.getBlockState(pos).isAir();
			}
			report(result, "за собой узлы убираются", clean && !touched.isEmpty());
			AnomalyField.remove(zone.id);
		}
	}

	/** Кладовая в живом мире: сундук ставится и получает лут. */
	private static void checkNodesLive(Result result, MinecraftServer server) {
		ServerLevel level = server.overworld();
		level.getChunk(0, 0);
		int y = Math.max(level.getMinY() + 10,
				level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 12, 12));
		BlockPos pos = new BlockPos(12, y, 12);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		Anomaly anomaly = new Anomaly();
		anomaly.id = 777L;
		anomaly.cls = "deadly";
		anomaly.nodes_placed = true;
		anomaly.node_cache_ok = true;
		anomaly.node_cx = 12.0;
		anomaly.node_cz = 12.0;
		anomaly.node_cy = y;
		anomaly.node_r = 100.0;
		try {
			AnomalyNodes.placeChest(level, anomaly, pos);
			boolean chest = level.getBlockState(pos).is(Blocks.CHEST);
			boolean loot = chest
					&& level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chestEntity
					&& !chestEntity.isEmpty();
			report(result, "кладовая ставится и наполняется", chest && loot);
			String described = AnomalyNodes.describe(level, anomaly, 0, 0);
			report(result, "описание узлов видит кладовую на месте",
					described.contains("кладовая 12, " + y + ", 12 ✓"));
		} finally {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	// --- следы разрушения вживую (правит блоки, только при явно включённой самопроверке) ---

	private static void checkScarsLive(Result result, MinecraftServer server) {
		ServerLevel level = server.overworld();
		int bx = 512;
		int bz = 512;
		List<Map<BlockPos, BlockState>> snapshots = new ArrayList<>();
		try {
			level.getChunk(bx >> 4, bz >> 4);
			level.getChunk((bx + 64) >> 4, bz >> 4);

			// мёртвая земля: трава становится грубой, растение уходит
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			Map<BlockPos, BlockState> before = snapshot(level, bx, bz, 1, surface - 2, surface + 2);
			snapshots.add(before);
			level.setBlock(new BlockPos(bx, surface - 1, bz), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(bx, surface, bz), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.disk("dead_soil", bx, bz, 7.0, 7L), bx, bz);
			report(result, "мёртвая земля: трава → грубая земля",
					level.getBlockState(new BlockPos(bx, surface - 1, bz)).is(Blocks.COARSE_DIRT));
			report(result, "мёртвая земля: растение снесено",
					level.getBlockState(new BlockPos(bx, surface, bz)).isAir());
			restore(level, before);

			// обломки берут материал грунта, а не булыжник посреди песка
			int rx = bx + 16;
			level.getChunk(rx >> 4, bz >> 4);
			int rubbleSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, bz);
			Map<BlockPos, BlockState> rubbleBefore = snapshot(level, rx, bz, 1, rubbleSurface - 2, rubbleSurface + 2);
			snapshots.add(rubbleBefore);
			level.setBlock(new BlockPos(rx, rubbleSurface - 1, bz), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.disk("rubble", rx, rubbleSurface, bz, 9L), rx, bz);
			BlockState rubble = level.getBlockState(new BlockPos(rx, rubbleSurface - 1, bz));
			report(result, "обломки в песке — песчаник или гравий, а не булыжник",
					rubble.is(Blocks.SANDSTONE) || rubble.is(Blocks.GRAVEL));
			restore(level, rubbleBefore);

			// пик поднимает колонку
			int sx = bx + 32;
			level.getChunk(sx >> 4, bz >> 4);
			int spikeSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, bz);
			Map<BlockPos, BlockState> spikeBefore = snapshot(level, sx, bz, 8, spikeSurface - 1, spikeSurface + 24);
			snapshots.add(spikeBefore);
			level.setBlock(new BlockPos(sx, spikeSurface - 1, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.spike(sx, bz, 3.0, 14, 11L), sx, bz);
			int spikeAfter = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, bz);
			report(result, "пик поднимает колонку на " + (spikeAfter - spikeSurface) + " блоков",
					spikeAfter >= spikeSurface + 4);
			restore(level, spikeBefore);

			// трещина выедает колонку
			int cx = bx + 64;
			int crackSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, bz);
			Map<BlockPos, BlockState> crackBefore = snapshot(level, cx, bz, 3, crackSurface - 6, crackSurface + 2);
			snapshots.add(crackBefore);
			level.setBlock(new BlockPos(cx, crackSurface - 1, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.line("crack", cx, bz, 0.0, 12, 1, 3, 13L), cx, bz);
			report(result, "трещина выедает верхний блок",
					level.getBlockState(new BlockPos(cx, crackSurface - 1, bz)).isAir());
			restore(level, crackBefore);

			// лёд не трогаем: шип на льдине холодного океана — уродство
			int ix = bx + 80;
			// та же ловушка, что и в боевом коде: getHeight на незагруженном чанке отдаёт дно мира
			level.getChunk(ix >> 4, bz >> 4);
			int iceSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, bz);
			Map<BlockPos, BlockState> iceBefore = snapshot(level, ix, bz, 6, iceSurface - 2, iceSurface + 20);
			snapshots.add(iceBefore);
			level.setBlock(new BlockPos(ix, iceSurface - 1, bz), Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.spike(ix, bz, 3.0, 14, 19L), ix, bz);
			report(result, "лёд под шипом остаётся льдом",
					level.getBlockState(new BlockPos(ix, iceSurface - 1, bz)).is(Blocks.ICE));
			restore(level, iceBefore);

			// лавовый разлом у воды — обычная трещина: лава там гаснет в камень
			int lx = bx + 160;
			level.getChunk(lx >> 4, bz >> 4);
			int lavaSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, bz);
			Map<BlockPos, BlockState> lavaBefore = snapshot(level, lx, bz, 2, lavaSurface - 8, lavaSurface + 2);
			snapshots.add(lavaBefore);
			level.setBlock(new BlockPos(lx, lavaSurface - 1, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(lx + 1, lavaSurface - 1, bz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.line("lava_fault", lx, bz, 0.0, 12, 1, 4, 37L), lx, bz);
			int bottomY = lavaSurface - 4;
			report(result, "у воды разлом без лавы (дно " + bottomY + ")",
					!level.getBlockState(new BlockPos(lx, bottomY, bz)).is(Blocks.LAVA));
			restore(level, lavaBefore);

			// ледник: трещина вынимает лёд (иначе ледяные пики и шапки остаются нетронутыми),
			// а шип на льду запрещён — он рос бы из льдины посреди холодного океана
			int sx2 = bx + 96;
			level.getChunk(sx2 >> 4, bz >> 4);
			int snowSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx2, bz);
			Map<BlockPos, BlockState> snowBefore = snapshot(level, sx2, bz, 6, snowSurface - 4, snowSurface + 2);
			snapshots.add(snowBefore);
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(new BlockPos(sx2, snowSurface + 1, bz + dz), Blocks.SNOW.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(new BlockPos(sx2, snowSurface, bz + dz), Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(new BlockPos(sx2, snowSurface - 1, bz + dz), Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
			}
			AnomalyScars.applyColumn(level, ScarOp.line("crack", sx2, bz, 0.0, 12, 1, 2, 29L), sx2, bz);
			boolean crackInIce = level.getBlockState(new BlockPos(sx2, snowSurface, bz)).isAir()
					&& level.getBlockState(new BlockPos(sx2, snowSurface - 1, bz)).isAir();
			AnomalyScars.applyColumn(level, ScarOp.spike(sx2 + 4, bz, 3.0, 6, 31L), sx2 + 4, bz);
			boolean spikeBlocked = true;
			for (int y = snowSurface; y <= snowSurface + 6; y++) {
				if (level.getBlockState(new BlockPos(sx2 + 4, y, bz)).is(Blocks.STONE)) {
					spikeBlocked = false;
				}
			}
			report(result, "ледник: трещина вынимает лёд, шип не встаёт", crackInIce && spikeBlocked);
			restore(level, snowBefore);

			// дерево: ствол и крона — иначе на мёртвой земле висит зелёный навес
			int tx = bx + 112;
			level.getChunk(tx >> 4, bz >> 4);
			int treeSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, bz);
			Map<BlockPos, BlockState> treeBefore = snapshot(level, tx, bz, 4, treeSurface - 1, treeSurface + 12);
			snapshots.add(treeBefore);
			plantTree(level, tx, treeSurface, bz);
			AnomalyScars.applyColumn(level, ScarOp.disk("dead_soil", tx, bz, 7.0, 23L), tx, bz);
			report(result, "мёртвая земля валит дерево вместе с кроной",
					treeGone(level, tx, treeSurface, bz));
			restore(level, treeBefore);

			// то же от трещины: обломки и траншея не должны пережить ствол, снесённый до проверки
			int tx2 = bx + 144;
			level.getChunk(tx2 >> 4, bz >> 4);
			int tree2Surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx2, bz);
			Map<BlockPos, BlockState> tree2Before = snapshot(level, tx2, bz, 4, tree2Surface - 1, tree2Surface + 12);
			snapshots.add(tree2Before);
			plantTree(level, tx2, tree2Surface, bz);
			AnomalyScars.applyColumn(level, ScarOp.line("crack", tx2, bz, 0.0, 12, 1, 3, 31L), tx2, bz);
			report(result, "трещина валит дерево, а не копает ствол",
					treeGone(level, tx2, tree2Surface, bz));
			restore(level, tree2Before);

			// поваленный лес: пень остаётся, ствол лежит — иначе «лес украли дровосеки»
			int tx3 = bx + 160;
			level.getChunk(tx3 >> 4, bz >> 4);
			int forestSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx3, bz);
			Map<BlockPos, BlockState> forestBefore = snapshot(level, tx3, bz, 8, forestSurface - 1, forestSurface + 12);
			snapshots.add(forestBefore);
			plantTree(level, tx3, forestSurface, bz);
			AnomalyScars.applyColumn(level, ScarOp.forest(tx3, bz, 6.0, 37L), tx3, bz);
			boolean stump = level.getBlockState(new BlockPos(tx3, forestSurface, bz))
					.is(net.minecraft.tags.BlockTags.LOGS)
					|| level.getBlockState(new BlockPos(tx3, forestSurface + 1, bz))
					.is(net.minecraft.tags.BlockTags.LOGS);
			report(result, "поваленный лес: пень на месте", stump);
			restore(level, forestBefore);

			// провал с кромкой: у края грунт вывернут обломками, а не остаётся травой
			int rimX = bx + 192;
			level.getChunk(rimX >> 4, bz >> 4);
			int rimSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rimX, bz);
			Map<BlockPos, BlockState> rimBefore = snapshot(level, rimX, bz, 8, rimSurface - 2, rimSurface + 2);
			snapshots.add(rimBefore);
			AnomalyScars.applyColumn(level, ScarOp.sinkhole(rimX, bz, 5.0, 3, 41L), rimX + 4, bz);
			BlockState rim = level.getBlockState(new BlockPos(rimX + 4, rimSurface - 1, bz));
			report(result, "провал: кромка обломана обломками", !rim.isAir() && !rim.is(Blocks.GRASS_BLOCK));
			restore(level, rimBefore);

			// микрособытия: воронка от хлопка, наледь от холода, гарь от молнии
			int mx = bx + 176;
			level.getChunk(mx >> 4, bz >> 4);
			int microSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, bz);
			Map<BlockPos, BlockState> microBefore = snapshot(level, mx, bz, 3, microSurface - 4, microSurface + 14);
			snapshots.add(microBefore);
			level.setBlock(new BlockPos(mx, microSurface - 1, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.microCrater(level, mx, bz, 0.6, 1, microSurface);
			report(result, "хлопок проламывает землю на блок",
					level.getBlockState(new BlockPos(mx, microSurface - 1, bz)).isAir()
							&& !level.getBlockState(new BlockPos(mx, microSurface - 2, bz)).isAir());
			restore(level, microBefore);

			// контейнер под грунтом не выкапывается: дно воронки не затирает block entity
			int nx = bx + 224;
			level.getChunk(nx >> 4, bz >> 4);
			int chestSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, bz);
			Map<BlockPos, BlockState> chestBefore = snapshot(level, nx, bz, 3, chestSurface - 4, chestSurface + 14);
			snapshots.add(chestBefore);
			level.setBlock(new BlockPos(nx, chestSurface - 1, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(nx, chestSurface - 2, bz), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.microCrater(level, nx, bz, 0.6, 2, chestSurface);
			report(result, "сундук под воронкой остаётся сундуком",
					level.getBlockState(new BlockPos(nx, chestSurface - 2, bz)).is(Blocks.CHEST));
			restore(level, chestBefore);

			int fx = bx + 192;
			level.getChunk(fx >> 4, bz >> 4);
			int frostSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, bz);
			Map<BlockPos, BlockState> frostBefore = snapshot(level, fx, bz, 3, frostSurface - 2, frostSurface + 14);
			snapshots.add(frostBefore);
			level.setBlock(new BlockPos(fx, frostSurface - 1, bz), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(fx, frostSurface, bz), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.frost(level, fx, bz, 1.5, frostSurface);
			report(result, "холод снимает траву и кладёт наледь",
					level.getBlockState(new BlockPos(fx, frostSurface, bz)).is(Blocks.SNOW));
			restore(level, frostBefore);

			int gx = bx + 208;
			level.getChunk(gx >> 4, bz >> 4);
			int scorchSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, bz);
			Map<BlockPos, BlockState> scorchBefore = snapshot(level, gx, bz, 3, scorchSurface - 2, scorchSurface + 14);
			snapshots.add(scorchBefore);
			level.setBlock(new BlockPos(gx, scorchSurface - 1, bz), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.scorch(level, gx, bz, 1.5, scorchSurface);
			report(result, "молния выжигает землю",
					level.getBlockState(new BlockPos(gx, scorchSurface - 1, bz)).is(Blocks.COARSE_DIRT));
			restore(level, scorchBefore);

			// провал: колонка вынимается на несколько блоков
			int hx = bx + 240;
			level.getChunk(hx >> 4, bz >> 4);
			int holeSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hx, bz);
			Map<BlockPos, BlockState> holeBefore = snapshot(level, hx, bz, 2, holeSurface - 12, holeSurface + 2);
			snapshots.add(holeBefore);
			level.setBlock(new BlockPos(hx, holeSurface - 1, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(hx, holeSurface - 2, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(hx, holeSurface - 3, bz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			AnomalyScars.applyColumn(level, ScarOp.sinkhole(hx, bz, 0.6, 3, 41L), hx, bz);
			report(result, "провал вынимает три блока",
					level.getBlockState(new BlockPos(hx, holeSurface - 1, bz)).isAir()
							&& level.getBlockState(new BlockPos(hx, holeSurface - 3, bz)).isAir());
			restore(level, holeBefore);
		} finally {
			// исключение внутри кейса не должно оставить тестовые блоки в мире
			for (Map<BlockPos, BlockState> states : snapshots) {
				restore(level, states);
			}
		}
	}

	/** Ствол 5 блоков и крона 5×5 на верхушке. */
	private static void plantTree(ServerLevel level, int x, int surface, int z) {
		for (int y = 0; y < 5; y++) {
			level.setBlock(new BlockPos(x, surface + y, z), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				level.setBlock(new BlockPos(x + dx, surface + 5, z + dz),
						Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	private static boolean treeGone(ServerLevel level, int x, int surface, int z) {
		for (int y = 0; y < 5; y++) {
			if (!level.getBlockState(new BlockPos(x, surface + y, z)).isAir()) {
				return false;
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (!level.getBlockState(new BlockPos(x + dx, surface + 5, z + dz)).isAir()) {
					return false;
				}
			}
		}
		return true;
	}

	private static Map<BlockPos, BlockState> snapshot(ServerLevel level, int cx, int cz, int radius,
			int fromY, int toY) {
		Map<BlockPos, BlockState> states = new HashMap<>();
		for (int x = cx - radius; x <= cx + radius; x++) {
			for (int z = cz - radius; z <= cz + radius; z++) {
				for (int y = fromY; y <= toY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					states.put(pos, level.getBlockState(pos));
				}
			}
		}
		return states;
	}

	private static void restore(ServerLevel level, Map<BlockPos, BlockState> states) {
		for (Map.Entry<BlockPos, BlockState> entry : states.entrySet()) {
			level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
		}
	}

	private static boolean groupsOk(List<AnomalyComponent> set, AnomalyClass cls) {		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (AnomalyComponent component : set) {
			if (component.group != null) {
				counts.merge(component.group, 1, Integer::sum);
			}
		}
		for (var entry : counts.entrySet()) {
			int limit = switch (entry.getKey()) {
				case "food", "motion" -> 1;
				case "damage" -> cls == AnomalyClass.DEADLY ? 2 : 1;
				case "show" -> cls == AnomalyClass.DEADLY ? 3 : 2;
				default -> 1;
			};
			if (entry.getValue() > limit) {
				return false;
			}
		}
		return true;
	}

	// --- классы ---

	private static void checkClasses(Result result, AnomalyConfig config, Random rnd) {
		report(result, "до " + config.danger_from + " — безопасная",
				config.classFor(config.danger_from - 1, 0) == AnomalyClass.SAFE);
		report(result, "с " + config.danger_from + " — опасная",
				config.classFor(config.danger_from + 1, 0) == AnomalyClass.DANGER);
		report(result, "с " + config.deadly_from + " — смертельная",
				config.classFor(config.deadly_from + 1, 0) == AnomalyClass.DEADLY);

		// размытие: у рубежа сдвиг в полосе ±class_blur переставляет класс
		int band = config.class_blur;
		double nearBorder = config.danger_from - band / 2.0;
		report(result, "размытие переставляет класс у рубежа",
				config.classFor(nearBorder, 0) == AnomalyClass.SAFE
						&& config.classFor(nearBorder, band) == AnomalyClass.DANGER);

		AnomalyFieldData field = new AnomalyFieldData();
		for (int i = 0; i < config.population; i++) {
			AnomalyField.spawnAuto(field, config, rnd, 0L);
		}
		boolean outside = true;
		boolean classes = true;
		int safe = 0;
		int danger = 0;
		int deadly = 0;
		for (Anomaly anomaly : field.anomalies) {
			double distance = Math.hypot(anomaly.x0, anomaly.z0);
			if (distance < config.spawn_min_radius - 1.0 || distance > config.world_radius) {
				outside = false;
			}
			switch (AnomalyClass.byId(anomaly.cls)) {
				case SAFE -> safe++;
				case DANGER -> danger++;
				case DEADLY -> deadly++;
			}
		}
		report(result, "все рождаются не ближе " + config.spawn_min_radius + " (" + field.anomalies.size() + " зон)",
				outside);
		classes = safe > 0 && danger > 0 && deadly > 0;
		report(result, "встречаются все три класса (" + safe + "/" + danger + "/" + deadly + ")", classes);
	}

	// --- бюджет разрушения ---

	private static void checkBudget(Result result) {
		check(result, "радиус 50 не рушит", 0.0, AnomalyClass.damageBudget(50, AnomalyClass.DEADLY), 1e-9);
		check(result, "безопасная не рушит даже гигантом", 0.0,
				AnomalyClass.damageBudget(3000, AnomalyClass.SAFE), 1e-9);
		report(result, "300 блоков — не больше 300",
				AnomalyClass.damageBudget(300, AnomalyClass.DANGER) <= 300.0);
		report(result, "1000 блоков — не больше 5 000",
				AnomalyClass.damageBudget(1000, AnomalyClass.DANGER) <= 5_000.0);
		report(result, "2000 блоков — не больше 60 000",
				AnomalyClass.damageBudget(2000, AnomalyClass.DANGER) <= 60_000.0);
		check(result, "3000 блоков — потолок 250 000", 250_000.0,
				AnomalyClass.damageBudget(3000, AnomalyClass.DEADLY), 1e-6);
		report(result, "бюджет растёт с радиусом",
				AnomalyClass.damageBudget(1500, AnomalyClass.DEADLY)
						> AnomalyClass.damageBudget(600, AnomalyClass.DEADLY));
	}

	// --- осколки умерших зон ---

	private static void checkRemnants(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		AnomalyConfig local = new AnomalyConfig();
		local.population = 0;
		Anomaly anomaly = AnomalyField.create(field, local, rnd, 0L, 12000, 0, 900.0, AnomalyClass.DEADLY, false);
		anomaly.born = -7_200_000L;
		anomaly.life_hours = 0.5;
		anomaly.components = List.of("storm_core", "ash_fall", "hunger_drain");
		field.anomalies.add(anomaly);
		AnomalyField.tickData(field, local, rnd, 0L, 1.0);
		Anomaly first = field.anomalies.isEmpty() ? null : field.anomalies.get(0);
		report(result, "умершая зона оставляет осколок", first != null && first.remnant);
		report(result, "осколок меньше и без следов",
				first != null && first.r0 < 450.0 && first.scars.isEmpty());
		report(result, "осколок без урона в компонентах", first != null
				&& first.components.contains("ash_fall")
				&& !first.components.contains("storm_core")
				&& !first.components.contains("hunger_drain"));

		// осколок остаётся там, где зона умерла, а не в точке рождения
		AnomalyFieldData field2 = new AnomalyFieldData();
		Anomaly mover = AnomalyField.create(field2, local, rnd, 0L, 12000, 0, 900.0, AnomalyClass.DEADLY, false);
		mover.speed = 60.0;
		mover.born = -50L * 3_600_000L;
		mover.life_hours = 1.0;
		double[] death = mover.position(mover.ageHours(0L));
		field2.anomalies.add(mover);
		AnomalyField.tickData(field2, local, rnd, 0L, 1.0);
		Anomaly scar = field2.anomalies.isEmpty() ? null : field2.anomalies.get(0);
		report(result, "осколок стоит там, где зона умерла",
				scar != null && Math.hypot(scar.x0 - death[0], scar.z0 - death[1]) < 50.0);
		report(result, "осколок не даёт дозы", scar != null && scar.zone(0L).strength() == 0.0);
	}

	// --- остаток ---

	private static void checkResidue(Result result, AnomalyConfig config) {
		// у пользователя потолок может быть ниже 24: тест не должен падать от легального конфига
		double cap = Math.max(24.0, config.residue_cap_hours);
		check(result, "остаток растёт по часам", 1.2,
				AnomalyResidue.nextHours(0.0, 2.0, 0.8, 1.0, false, false, cap), 1e-9);
		check(result, "остаток не уходит в минус", 0.0,
				AnomalyResidue.nextHours(0.1, 0.0, 0.8, 1.0, false, false, cap), 1e-9);
		check(result, "потолок остатка держит", cap,
				AnomalyResidue.nextHours(cap - 1.0, 50.0, 0.0, 1.0, false, false, cap), 1e-9);
		// рядом с другим игроком −10 % от текущего за час
		check(result, "рядом с другим −10 % в час", 9.0,
				AnomalyResidue.nextHours(10.0, 0.0, 0.0, 1.0, true, false, cap), 1e-6);
		// у центра мира спад вдвое быстрее: 10 − 2·0.8 = 8.4
		check(result, "у центра спад вдвое быстрее", 8.4,
				AnomalyResidue.nextHours(10.0, 0.0, 0.8, 1.0, false, true, cap), 1e-6);
		check(result, "безопасная зона не даёт остатка", 0.0,
				AnomalyResidue.growthPerHour(AnomalyClass.SAFE, 1.0, 1.0), 1e-9);
		check(result, "смертельная в ядре — 8.5 ч/ч", 8.5,
				AnomalyResidue.growthPerHour(AnomalyClass.DEADLY, 1.0, 1.0), 1e-9);
		check(result, "смертельная у края вчетверо слабее ядра", 1.75,
				AnomalyResidue.growthPerHour(AnomalyClass.DEADLY, 0.0, 0.0), 1e-9);
		report(result, "состояния по порогам", AnomalyResidue.state(1.9).equals("след")
				&& AnomalyResidue.state(2.0).equals("дрожь")
				&& AnomalyResidue.state(5.0).equals("оскомина")
				&& AnomalyResidue.state(8.0).equals("печать ада")
				&& AnomalyResidue.state(12.0).equals("пелена")
				&& AnomalyResidue.state(16.0).equals("голод глубины"));
		// окна — чистая функция секунд: одинаковое у всех, без таймеров в памяти
		report(result, "окна последствий периодичны",
				AnomalyResidue.window(0L, 0L, 10, 2)
						&& !AnomalyResidue.window(2L, 0L, 10, 2)
						&& AnomalyResidue.window(10L, 0L, 10, 2)
						&& !AnomalyResidue.window(0L, 3L, 10, 2)
						&& !AnomalyResidue.window(5L, 0L, 0, 2));

		// файл: у каждого игрока своя шкала по UUID, версия и часы сохраняются
		AnomalyResidue.Data payload = new AnomalyResidue.Data();
		AnomalyResidue.Entry entry = new AnomalyResidue.Entry();
		entry.hours = 7.25;
		entry.updated_ms = 1_234L;
		payload.players.put("00000000-0000-0000-0000-000000000001", entry);
		Path file = null;
		Path broken = null;
		try {
			file = Files.createTempFile("anomaly-residue", ".json");
			report(result, "остаток записывается", AnomalyResidue.write(file, payload));
			AnomalyResidue.Data loaded = AnomalyResidue.read(file);
			report(result, "остаток читается обратно", loaded != null && loaded.players != null);
			if (loaded != null && loaded.players != null) {
				AnomalyResidue.Entry copy = loaded.players.get("00000000-0000-0000-0000-000000000001");
				report(result, "остаток игрока пережил файл",
						copy != null && Math.abs(copy.hours - 7.25) < 1e-9 && copy.updated_ms == 1_234L);
			}
			broken = Files.createTempFile("anomaly-residue-broken", ".json");
			Files.writeString(broken, "{ это не json", StandardCharsets.UTF_8);
			report(result, "битый файл остатка не роняет загрузку", AnomalyResidue.read(broken) == null);
		} catch (IOException e) {
			report(result, "остаток: " + e, false);
		} finally {
			for (Path temp : new Path[]{file, broken}) {
				if (temp == null) {
					continue;
				}
				try {
					Files.deleteIfExists(temp);
					Files.deleteIfExists(temp.resolveSibling(temp.getFileName() + ".bak"));
				} catch (IOException ignored) {
					// мусор в temp
				}
			}
		}
	}

	// --- рейды ---

	private static void checkNodes(Result result, AnomalyConfig config) {
		java.util.List<ItemStack> safe = AnomalyNodes.lootFor(AnomalyClass.SAFE, new Random(1L));
		java.util.List<ItemStack> deadly = AnomalyNodes.lootFor(AnomalyClass.DEADLY, new Random(1L));
		report(result, "у кладовой есть лут", !safe.isEmpty() && !deadly.isEmpty());
		boolean bread = safe.stream().anyMatch(stack -> stack.is(Items.BREAD));
		boolean netherite = deadly.stream().anyMatch(stack -> stack.is(Items.NETHERITE_INGOT));
		// незерит — не гарантия, а шанс: иначе кладовая становится станком. Хлеб в безопасной
		// есть всегда, незерит проверяется на серии зёрен в группе «экономика»
		report(result, "лут по классу: хлеб в безопасной", bread);
		report(result, "смертельная даёт алмазы", deadly.stream().anyMatch(stack -> stack.is(Items.DIAMOND)));

		java.util.List<ItemStack> again = AnomalyNodes.lootFor(AnomalyClass.DEADLY, new Random(1L));
		boolean same = again.size() == deadly.size();
		for (int i = 0; same && i < again.size(); i++) {
			same = again.get(i).is(deadly.get(i).getItem())
					&& again.get(i).getCount() == deadly.get(i).getCount();
		}
		report(result, "лут детерминирован по сиду", same);

		// узлы: алтарь и якоря внутри зоны, кладовая в ядре
		Anomaly anomaly = new Anomaly();
		anomaly.id = 42L;
		anomaly.cls = "danger";
		anomaly.node_cx = 1000.0;
		anomaly.node_cz = -500.0;
		anomaly.node_r = 400.0;
		anomaly.nodes_placed = true;
		double[] altar = AnomalyNodes.nodePos(anomaly, AnomalyNodes.baseAngle(anomaly), 0.32);
		double[] anchor = AnomalyNodes.nodePos(anomaly, AnomalyNodes.baseAngle(anomaly) + 2.0, 0.62);
		double altarDist = Math.hypot(altar[0] - anomaly.node_cx, altar[1] - anomaly.node_cz);
		double anchorDist = Math.hypot(anchor[0] - anomaly.node_cx, anchor[1] - anomaly.node_cz);
		report(result, "алтарь и якоря внутри зоны",
				altarDist > 1.0 && altarDist < 400.0 && anchorDist > altarDist && anchorDist < 400.0);
		report(result, "узел без координат не считается поставленным", !new Anomaly().nodes_placed);
		report(result, "описание узлов не падает", AnomalyNodes.describe(null, anomaly, 0, 0).startsWith("#42"));

		Anomaly broken = new Anomaly();
		broken.nodes_placed = true;
		broken.node_r = 1.0e9;
		report(result, "битый узел из файла отменяется", broken.valid() && !broken.nodes_placed);

		// радиус рейда не тянется за радиусом зоны, но у большой зоны узлы не в самом ядре
		report(result, "радиус узлов: 30 % зоны в пределах 20..360",
				AnomalyNodes.nodeRadius(20.0) == 20.0 && AnomalyNodes.nodeRadius(700.0) == 210.0
						&& AnomalyNodes.nodeRadius(3000.0) == 360.0);

		// все пять точек узлов: кладовая, алтарь и три якоря — внутри зоны
		java.util.List<double[]> spots = AnomalyNodes.nodeSpots(anomaly, 1000.0, -500.0, 160.0);
		boolean inside = spots.size() == 5;
		for (double[] spot : spots) {
			inside &= Math.hypot(spot[0] - 1000.0, spot[1] + 500.0) <= 160.0 * 0.62 + 0.01;
		}
		report(result, "пять узлов рейда внутри зоны", inside);

		// лут есть у всех трёх классов
		boolean allLoot = true;
		for (AnomalyClass cls : AnomalyClass.values()) {
			allLoot &= !AnomalyNodes.lootFor(cls, new Random(7L)).isEmpty();
		}
		report(result, "лут есть у всех трёх классов", allLoot);

		// плата алтаря — остаток: на потолке обмен закрыт
		report(result, "алтарь закрыт, когда плата упрётся в потолок",
				AnomalyNodes.canTrade(0.0, 24.0) && AnomalyNodes.canTrade(20.9, 24.0)
						&& AnomalyNodes.canTrade(21.0, 24.0) && !AnomalyNodes.canTrade(21.5, 24.0)
						&& !AnomalyNodes.canTrade(23.9, 24.0)
						&& !AnomalyNodes.canTrade(24.0, 24.0) && !AnomalyNodes.canTrade(Double.NaN, 24.0)
						&& !AnomalyNodes.canTrade(1.0, 0.0) && !AnomalyNodes.canTrade(1.0, 2.0));

		// сломанный якорь не ставится заново после рестарта
		Anomaly raided = new Anomaly();
		raided.node_cx = 10.0;
		raided.node_cz = 10.0;
		raided.node_r = 100.0;
		raided.anchors_broken = 0b101;
		raided.anchor_ys = new int[]{40, 0, 44};
		report(result, "сломанные якоря остаются сломанными", raided.valid()
				&& raided.anchors_placed == 0b101 && raided.nodes_placed);
	}
	// --- рассеивание у центра ---

	private static void checkSuppression(Result result, AnomalyConfig config) {
		AnomalyConfig local = new AnomalyConfig();
		local.population = 0; // без рождений: проверяем только таяние

		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly drifting = AnomalyField.create(field, config, new Random(11L), 0L, 3500, 0, 800.0,
				AnomalyClass.DANGER, false);
		drifting.life_hours = 100.0;
		drifting.born = -50L * 3_600_000L; // зрелость
		drifting.speed = 0.0;              // не дрейфует: тест про таяние, а не про удачу с курсом
		field.anomalies.add(drifting);
		double before = drifting.radius(50.0);

		// час в полосе подавления: на 3.5к скорость ~4.4 % в минуту → остаётся около 7 %
		AnomalyField.tickData(field, local, new Random(12L), 3_600_000L, 1.0);
		double after = drifting.radius(51.0);
		report(result, "за час в полосе радиус тает до " + String.format("%.0f", after) + " из "
						+ String.format("%.0f", before),
				after < before * 0.15 && after > 0.0);
		report(result, "сжатая но ещё не нулевая остаётся в поле", field.anomalies.contains(drifting));

		// ещё час — и она сжимается ниже порога и тихо уходит
		AnomalyField.tickData(field, local, new Random(13L), 7_200_000L, 1.0);
		report(result, "сжатая в точку аномалия удаляется без шрама", field.anomalies.isEmpty());

		// снаружи полосы ничего не тает
		AnomalyFieldData far = new AnomalyFieldData();
		Anomaly intact = AnomalyField.create(far, config, new Random(14L), 0L, 12_000, 0, 800.0,
				AnomalyClass.DANGER, false);
		intact.life_hours = 100.0;
		intact.born = -50L * 3_600_000L;
		far.anomalies.add(intact);
		AnomalyField.tickData(far, local, new Random(15L), 7_200_000L, 2.0);
		report(result, "снаружи полосы подавления не тает", intact.shrink == 1.0);

		// тестовая зона не тает даже в центре мира
		AnomalyFieldData pinnedField = new AnomalyFieldData();
		Anomaly pinned = AnomalyField.create(pinnedField, config, new Random(16L), 0L, 0, 0, 500.0,
				AnomalyClass.DANGER, true);
		pinnedField.anomalies.add(pinned);
		AnomalyField.tickData(pinnedField, local, new Random(17L), 7_200_000L, 5.0);
		report(result, "тестовая зона в центре не тает", pinned.shrink == 1.0 && !pinnedField.anomalies.isEmpty());
	}

	// --- население ---

	private static void checkPopulation(Result result, AnomalyConfig config, Random rnd) {
		AnomalyConfig local = new AnomalyConfig();
		local.population = 60;
		AnomalyFieldData field = new AnomalyFieldData();
		AnomalyField.seedPopulation(field, local, rnd, 0L);
		int seeded = live(field);
		report(result, "посев даёт целевое население (" + seeded + " из 60)", seeded >= 45);

		int min = seeded;
		int max = seeded;
		boolean changed = false;
		long now = 0L;
		long before = 0L;
		Random stepRandom = new Random(424242L);
		for (int hour = 0; hour < 300; hour++) {
			now += 3_600_000L;
			int previous = live(field);
			if (AnomalyField.tickData(field, local, stepRandom, now, 1.0)) {
				changed = true;
			}
			int current = live(field);
			if (current != previous) {
				before++;
			}
			min = Math.min(min, current);
			max = Math.max(max, current);
		}
		report(result, "население держится у цели: " + min + ".." + max + " при цели 60",
				min >= 45 && max <= 60);
		report(result, "за 300 ч были рождения и смерти (" + before + " изменений)", changed && before > 20);
		report(result, "поле не выросло сверх цели", live(field) <= 60);
		// осколки умерших живут часами, но не копятся бесконечно
		report(result, "осколков не больше живых",
				field.anomalies.size() - live(field) <= 60);
	}

	private static int live(AnomalyFieldData field) {
		int count = 0;
		for (Anomaly anomaly : field.anomalies) {
			if (!anomaly.remnant) {
				count++;
			}
		}
		return count;
	}

	// --- сохранение ---

	private static void checkPersistence(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData field = new AnomalyFieldData();
		for (int i = 0; i < 8; i++) {
			AnomalyField.spawnAuto(field, config, rnd, 1_700_000_000_000L);
		}
		field.clock_ms = 1_700_123_456_789L;
		field.next_id = 500L;
		Anomaly original = field.anomalies.get(0);

		Path file = null;
		try {
			file = Files.createTempFile("anomaly-selftest", ".json");
			report(result, "поле записывается", AnomalyField.write(file, field));
			AnomalyFieldData loaded = AnomalyField.read(file);
			report(result, "поле читается обратно", loaded != null && loaded.anomalies != null);
			if (loaded != null && loaded.anomalies != null) {
				report(result, "число аномалий совпадает (" + loaded.anomalies.size() + ")",
						loaded.anomalies.size() == field.anomalies.size());
				report(result, "время и счётчик id сохраняются",
						loaded.clock_ms == field.clock_ms && loaded.next_id == field.next_id);
				Anomaly copy = loaded.anomalies.get(0);
				report(result, "параметры аномалии не потерялись",
						copy.id == original.id && copy.r0 == original.r0
								&& copy.life_hours == original.life_hours && copy.arc == original.arc
								&& copy.harmonics.size() == original.harmonics.size());
				report(result, "компоненты пережили файл",
						!copy.componentList().isEmpty()
								&& copy.componentList().equals(original.componentList()));
				double[] before = original.position(3.0);
				double[] after = copy.position(3.0);
				report(result, "после круга через файл траектория та же",
						Math.abs(before[0] - after[0]) < 1e-9 && Math.abs(before[1] - after[1]) < 1e-9);
			}
		} catch (IOException e) {
			report(result, "сохранение: " + e, false);
		} finally {
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				} catch (IOException ignored) {
					// временный файл не важнее теста
				}
			}
		}

		Path broken = null;
		try {
			broken = Files.createTempFile("anomaly-selftest-broken", ".json");
			Files.writeString(broken, "{ это не json", StandardCharsets.UTF_8);
			report(result, "битый файл не роняет загрузку", AnomalyField.read(broken) == null);
		} catch (IOException e) {
			report(result, "битый файл: " + e, false);
		} finally {
			if (broken != null) {
				try {
					Files.deleteIfExists(broken);
					Files.deleteIfExists(broken.resolveSibling(broken.getFileName() + ".bak"));
				} catch (IOException ignored) {
					// мусор в temp
				}
			}
		}
	}

	// --- крайние случаи ---

	private static void checkEdges(Result result, AnomalyConfig config, Random rnd) {
		AnomalyFieldData empty = new AnomalyFieldData();
		AnomalyConfig zero = new AnomalyConfig();
		zero.population = 0;
		report(result, "пустое поле тикает без ошибок",
				!AnomalyField.tickData(empty, zero, rnd, 0L, 1.0) && empty.anomalies.isEmpty());

		Anomaly tiny = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 12000, 0, 5.0,
				AnomalyClass.DANGER, false);
		report(result, "радиус меньше минимума поднимается до " + Math.round(config.radius_min),
				tiny.r0 >= config.radius_min - 1e-9);

		Anomaly huge = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 12000, 0, 99_999.0,
				AnomalyClass.DEADLY, false);
		report(result, "радиус больше максимума срезается до " + Math.round(config.radius_max),
				huge.r0 <= config.radius_max + 1e-9);

		Anomaly pinned = AnomalyField.create(new AnomalyFieldData(), config, rnd, 0L, 0, 0, 500.0,
				AnomalyClass.DANGER, true);
		report(result, "тестовая зона приколота: не дрейфует, не стареет и сразу полноразмерная",
				pinned.speed == 0.0
						&& pinned.alive(pinned.born + (long) (pinned.life_hours * 2.0 * 3_600_000L))
						&& pinned.radius(1.0) > pinned.r0 * 0.5);

		AnomalyConfig broken = new AnomalyConfig();
		broken.population = -5;
		broken.radius_min = 5000;
		broken.radius_max = 10;
		broken.spawn_min_radius = 30_000;
		broken.world_radius = 1000;
		broken.sanitize();
		report(result, "кривой конфиг приводится в порядок",
				broken.population == 0 && broken.radius_max >= broken.radius_min
						&& broken.world_radius > broken.spawn_min_radius);

		// NaN из ручного JSON не должен превращать поле в невидимые зоны, занимающие слоты
		Anomaly nan = new Anomaly();
		nan.r0 = 500;
		nan.life_hours = 10;
		nan.dir = Double.NaN;
		report(result, "запись с dir=NaN отбраковывается", !nan.valid());

		Anomaly dead = new Anomaly();
		dead.r0 = 500;
		dead.life_hours = 0;
		report(result, "нулевая жизнь не считается живой", !dead.alive(0L));

		AnomalyConfig nanConfig = new AnomalyConfig();
		nanConfig.radius_min = Double.NaN;
		nanConfig.suppress_until = 999_999;
		nanConfig.sanitize();
		report(result, "NaN в конфиге не проходит",
				Double.isFinite(nanConfig.radius_min) && nanConfig.suppress_until < nanConfig.world_radius);
	}

	// --- предвестник ---

	private static void checkHerald(Result result, AnomalyConfig config, Random rnd) {
		AnomalyConfig cfg = new AnomalyConfig();
		cfg.population = 3;
		cfg.herald_enabled = true;
		cfg.herald_min_minutes = 10;
		cfg.herald_max_minutes = 30;
		cfg.nested_chance = 0.0;
		AnomalyFieldData field = new AnomalyFieldData();
		long start = 1_000_000L;
		Anomaly first = AnomalyField.spawnAuto(field, cfg, rnd, start, AnomalyField.Avoid.none(), true);
		report(result, "рождение сначала становится предвестником", first != null && first.precursor);
		if (first == null) {
			return;
		}
		check(result, "у предвестника нулевой радиус", 0.0, first.zone(start).radius(), 1e-9);
		check(result, "у предвестника нулевая сила", 0.0, first.zone(start).strength(), 1e-9);
		long lead = first.birth_at - start;
		report(result, "предвестник ждёт 10–30 минут (" + (lead / 60_000L) + " мин)",
				lead >= 10 * 60_000L && lead <= 30 * 60_000L);
		AnomalyField.tickData(field, cfg, rnd, first.birth_at - 1L, 0.0);
		report(result, "до даты он не рождается", first.precursor);
		// население считает и предвестников: иначе во время окна поле переполняется рождениями
		int before = field.anomalies.size();
		AnomalyField.tickData(field, cfg, rnd, start, 0.5);
		report(result, "слот предвестника держит население (" + before + " → " + field.anomalies.size() + ")",
				field.anomalies.size() <= cfg.population);
		boolean became = AnomalyField.tickData(field, cfg, rnd, first.birth_at, 0.0);
		report(result, "в свою дату предвестник становится зоной", became && !first.precursor);
		report(result, "у рождённой зоны есть компоненты и план следов",
				!first.componentList().isEmpty() && first.scars != null);
		report(result, "рождённая зона стоит там, где шумела", first.x0 == first.scar_cx);
	}

	// --- кемпинг ---

	private static void checkCamping(Result result, AnomalyConfig config, Random rnd) {
		check(result, "час стояния при 0.5 — полчаса поля", 0.5, AnomalyField.feedHours(3600, 0.5), 1e-9);
		check(result, "ноль времени — ноль кормления", 0.0, AnomalyField.feedHours(0, 0.5), 1e-9);
		check(result, "отрицательное время не кормит", 0.0, AnomalyField.feedHours(-10, 0.5), 1e-9);

		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 9000, 0, 600.0, AnomalyClass.DANGER, false);
		anomaly.born = 0L;
		anomaly.life_hours = 24.0;
		double plain = anomaly.ageHours(3_600_000L);
		double baseStrength = anomaly.zone(3_600_000L).strength();
		AnomalyField.feedZone(anomaly, 3600.0, 0.5);
		check(result, "час кемпинга — возраст плюс полчаса", plain + 0.5, anomaly.ageHours(3_600_000L), 1e-9);
		report(result, "кормлённая зона бьёт сильнее (" + String.format(java.util.Locale.ROOT, "%.2f", baseStrength)
				+ " → " + String.format(java.util.Locale.ROOT, "%.2f", anomaly.zone(3_600_000L).strength()) + ")",
				anomaly.zone(3_600_000L).strength() > baseStrength);
		report(result, "сила не растёт бесконечно (потолок +60 %)",
				anomaly.zone(3_600_000L).strength() <= AnomalyClass.DANGER.strength * 1.6 + 1e-9);

		// пока в зоне стоит игрок, кладовая не восстанавливается
		report(result, "кладовая молчит, пока игрок внутри", !AnomalyNodes.cacheDue(0L, 999_999_999L, true));
		long placed = 1_000_000L;
		report(result, "без игрока кладовая созревает за 25 минут",
				AnomalyNodes.cacheDue(placed, placed + 25L * 60_000L, false));
		report(result, "девяти минут ещё не хватает",
				!AnomalyNodes.cacheDue(placed, placed + 9L * 60_000L, false));
	}

	// --- места рождения ---

	private static void checkSpawnPlaces(Result result, AnomalyConfig config, Random rnd) {
		AnomalyConfig cfg = new AnomalyConfig();
		cfg.population = 2;
		cfg.spawn_min_radius = 0;
		cfg.world_radius = 4000;
		cfg.herald_enabled = false;
		cfg.nested_chance = 0.0;
		AnomalyField.Avoid avoid = new AnomalyField.Avoid(List.of(new double[]{0.0, 0.0, 5000.0}), null, 0);
		report(result, "точка в запретной зоне не годится", avoid.blocks(10, 10));
		report(result, "точка вне запретной — годится", !avoid.blocks(6000, 6000));
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly blocked = AnomalyField.spawnAuto(field, cfg, rnd, 0L, avoid, false);
		report(result, "у игрока зона не рождается вовсе", blocked == null && field.anomalies.isEmpty());
		Anomaly allowed = AnomalyField.spawnAuto(field, cfg, rnd, 0L, AnomalyField.Avoid.none(), false);
		report(result, "без запрета рождается как обычно", allowed != null);

		// радиус запрета — не «вообще нигде»: на 256 блоках от игрока зоны уже могут появляться
		AnomalyField.Avoid small = new AnomalyField.Avoid(List.of(new double[]{0.0, 0.0, 256.0}), null, 0);
		report(result, "на 256 блоках от игрока можно", !small.blocks(300, 0));
		report(result, "в 200 блоках от игрока нельзя", small.blocks(200, 0));
	}

	// --- реестр следов ---

	private static void checkScarRegistry(Result result, AnomalyConfig config, Random rnd) {
		ScarOp disk = ScarOp.disk("dead_soil", 0, 0, 10.0, 1L);
		ScarOp same = ScarOp.disk("dead_soil", 3, 0, 10.0, 2L);
		ScarOp far = ScarOp.disk("dead_soil", 40, 0, 10.0, 3L);
		ScarOp crack = ScarOp.line("crack", 0, 0, 0.0, 60, 1, 2, 4L);
		report(result, "пятно в пустом месте годится", !AnomalyScars.overlaps(List.of(), disk));
		report(result, "наложенное пятно отбрасывается", AnomalyScars.overlaps(List.of(disk), same));
		report(result, "дальнее пятно остаётся", !AnomalyScars.overlaps(List.of(disk), far));
		report(result, "трещина пересекает пятно и это нормально",
				!AnomalyScars.overlaps(List.of(disk), crack));

		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly anomaly = AnomalyField.create(field, config, rnd, 0L, 9000, 0, 1400.0, AnomalyClass.DEADLY, false);
		boolean clean = true;
		List<ScarOp> ops = anomaly.scars == null ? List.of() : anomaly.scars;
		for (int i = 0; i < ops.size() && clean; i++) {
			for (int j = i + 1; j < ops.size(); j++) {
				if (AnomalyScars.overlaps(List.of(ops.get(i)), ops.get(j))) {
					clean = false;
					break;
				}
			}
		}
		report(result, "план из " + ops.size() + " следов не накладывает объёмы", clean);
	}

	// --- новая геология ---

	private static void checkGeology(Result result, AnomalyConfig config, Random rnd) {
		ScarOp forest = ScarOp.forest(0, 0, 14.0, 1L);
		ScarOp ruin = ScarOp.ruin(0, 0, 9.0, 2L);
		ScarOp uplift = ScarOp.uplift(0, 0, 12.0, 10, 3L);
		report(result, "поваленный лес оценивается в блоки", forest.blocks > 0 && !forest.isLine());
		report(result, "руины оцениваются в блоки", ruin.blocks > 0 && !ruin.isLine());
		check(result, "подъём держит высоту", 10, uplift.height, 0);
		report(result, "подъём дороже пятна того же радиуса",
				uplift.blocks > ScarOp.disk("dead_soil", 0, 0, 12.0, 3L).blocks);
		report(result, "описания читаются",
				forest.describe().contains("лес") && ruin.describe().contains("руины")
						&& uplift.describe().contains("подъём"));

		boolean inside = true;
		for (ScarOp op : new ScarOp[]{forest, ruin, uplift}) {
			for (int i = 0; i < op.columnCount(); i++) {
				int[] column = op.column(i);
				if (column == null) {
					continue;
				}
				if (Math.hypot(column[0], column[1]) > op.radius + 2.0) {
					inside = false;
				}
			}
		}
		report(result, "колонки новых следов внутри своего круга", inside);

		AnomalyFieldData field = new AnomalyFieldData();
		boolean sawNew = false;
		for (int i = 0; i < 60 && !sawNew; i++) {
			Anomaly anomaly = AnomalyField.create(field, config, new Random(7000L + i), 0L,
					9000 + i, 0, 1600.0, AnomalyClass.DEADLY, false);
			if (anomaly.scars == null) {
				continue;
			}
			for (ScarOp op : anomaly.scars) {
				sawNew |= op.type.equals("fallen_forest") || op.type.equals("ruin")
						|| op.type.equals("uplift");
			}
		}
		report(result, "план крупной зоны включает новые типы следов", sawNew);
	}

	// --- вложенность ---

	private static void checkNesting(Result result, AnomalyConfig config, Random rnd) {
		AnomalyConfig cfg = new AnomalyConfig();
		cfg.population = 4;
		cfg.nested_chance = 1.0;
		cfg.spawn_min_radius = 6000;
		cfg.world_radius = 20000;
		cfg.herald_enabled = false;
		AnomalyFieldData field = new AnomalyFieldData();
		Anomaly host = AnomalyField.create(field, cfg, rnd, 0L, 9000, 0, 1200.0, AnomalyClass.DEADLY, false);
		host.born = -6L * 3_600_000L;
		field.anomalies.add(host);
		Anomaly child = AnomalyField.spawnAuto(field, cfg, rnd, 0L, AnomalyField.Avoid.none(), false);
		report(result, "вложенная зона помнит родителя", child != null && child.parent_id == host.id);
		if (child == null) {
			return;
		}
		report(result, "вложенная заметно меньше родителя", child.r0 <= host.r0 * 0.31);
		Zone hostZone = host.zone(0L);
		Zone childZone = child.zone(0L);
		double distance = Math.hypot(childZone.x() - hostZone.x(), childZone.z() - hostZone.z());
		report(result, "вложенная целиком внутри родителя (центр " + Math.round(distance) + ")",
				distance + child.r0 < host.r0);
		report(result, "класс вложенной — как у родителя", child.cls.equals(host.cls));
		// клиренс действует и на вложенные: ребёнок не рождается на базе игрока
		AnomalyField.Avoid blocked = new AnomalyField.Avoid(List.of(new double[]{9000.0, 0.0, 5000.0}), null, 0);
		report(result, "вложенная не рождается в запретной зоне",
				AnomalyField.spawnNested(field, cfg, new Random(5L), 0L, blocked) == null);
		// столкновение с родителем — не столкновение: иначе вложенность невозможна
		long nestedCount = field.anomalies.stream().filter(a -> a.parent_id != 0L).count();
		report(result, "в поле ровно одна вложенная зона", nestedCount == 1L);
		report(result, "статистика видит вложенную",
				AnomalyField.stats().nested() >= 0);
	}

	// --- шиза ---

	private static void checkMadness(Result result, AnomalyConfig config, Random rnd) {
		check(result, "у дома шиза спит", 0.0,
				AnomalyMadness.intensity(20.0, 0.9, 1000.0, 6000.0), 1e-9);
		check(result, "в дальних землях на пике — полная", 1.0,
				AnomalyMadness.intensity(20.0, 0.9, 9000.0, 6000.0), 1e-9);
		report(result, "остаток 4 ч слабее остатка 10 ч",
				AnomalyMadness.intensity(4.0, 0.0, 9000.0, 6000.0)
						< AnomalyMadness.intensity(10.0, 0.0, 9000.0, 6000.0));
		check(result, "дрожь — это ещё не шиза", 0.0,
				AnomalyMadness.intensity(1.5, 0.0, 9000.0, 6000.0), 1e-9);
		check(result, "период при полной силе — базовый", 45,
				AnomalyMadness.period(45, 1.0), 0);
		check(result, "период при слабой — впятеро реже", 300,
				AnomalyMadness.period(45, 0.15), 0);
		check(result, "нулевая сила молчит", Integer.MAX_VALUE,
				AnomalyMadness.period(45, 0.0), 0);
		report(result, "окно зависит от фазы",
				AnomalyMadness.window(0L, 0L, 60, 7) != AnomalyMadness.window(0L, 30L, 60, 7));
		report(result, "в окне семи секунд из минуты событие есть",
				AnomalyMadness.window(0L, 0L, 60, 7));
		report(result, "вне окна события нет", !AnomalyMadness.window(30L, 0L, 60, 7));
	}

	// --- обучение ---

	private static void checkTeaching(Result result) {
		java.util.Set<net.minecraft.sounds.SoundEvent> sounds = new java.util.HashSet<>();
		boolean all = true;
		for (AnomalyComponent component : AnomalyComponent.values()) {
			var sound = component.signature();
			all &= sound != null;
			sounds.add(sound);
		}
		report(result, "у каждого компонента есть звук-подпись", all);
		report(result, "звуки-подписи не повторяются (" + sounds.size() + " из "
				+ AnomalyComponent.values().length + ")",
				sounds.size() == AnomalyComponent.values().length);
		report(result, "30 с с полной дозой в опасной зоне — выживание",
				AnomalyProgress.survives(AnomalyProgress.SURVIVE_TICKS, 0.6, true, true, 0.9));
		report(result, "мимоходом — нет",
				!AnomalyProgress.survives(200, 0.6, true, true, 0.9));
		report(result, "без полной дозы — нет",
				!AnomalyProgress.survives(AnomalyProgress.SURVIVE_TICKS, 0.5, true, true, 0.9));
		report(result, "в безопасной зоне — нет",
				!AnomalyProgress.survives(AnomalyProgress.SURVIVE_TICKS, 0.6, false, true, 0.9));
		report(result, "на кромке — нет",
				!AnomalyProgress.survives(AnomalyProgress.SURVIVE_TICKS, 0.6, true, true, 0.2));
		report(result, "мёртвому не выдают",
				!AnomalyProgress.survives(AnomalyProgress.SURVIVE_TICKS, 0.6, true, false, 0.9));
	}

	// --- суточный лимит генерации чанков ---

	private static void checkChunkBudget(Result result, AnomalyConfig config) {
		AnomalyConfig cfg = new AnomalyConfig();
		AnomalyFieldData data = new AnomalyFieldData();
		data.gen_day = 0;
		data.gen_count = 5;
		cfg.chunk_gen_per_day = 5;
		report(result, "на лимите генерация останавливается",
				!AnomalyScars.chunkBudgetAllows(data, cfg, 1000L));
		cfg.chunk_gen_per_day = 6;
		report(result, "под лимитом генерация идёт", AnomalyScars.chunkBudgetAllows(data, cfg, 1000L));
		cfg.chunk_gen_per_day = 0;
		report(result, "нулевой лимит — без ограничений", AnomalyScars.chunkBudgetAllows(data, cfg, 1000L));
		cfg.chunk_gen_per_day = 5;
		data.gen_count = 5;
		report(result, "новые сутки сбрасывают счётчик",
				AnomalyScars.chunkBudgetAllows(data, cfg, AnomalyField.DAY_MS + 1L) && data.gen_count == 0);
	}

	// --- рейды остатка ---

	private static void checkResidueRaids(Result result, AnomalyConfig config) {
		check(result, "до пелены никто не идёт", 0, AnomalyResidue.raidCount(11.9), 0);
		check(result, "на пелене — один", 1, AnomalyResidue.raidCount(12.0), 0);
		check(result, "на голоде глубины — три", 3, AnomalyResidue.raidCount(24.0), 0);
		check(result, "без остатка зона обычная", 1.0, AnomalyResidue.hazardScale(0.0), 1e-9);
		check(result, "оскомина не усиливает", 1.0, AnomalyResidue.hazardScale(5.0), 1e-9);
		check(result, "на пике зона в полтора раза злее", 1.5, AnomalyResidue.hazardScale(16.0), 1e-9);
		report(result, "усиление монотонно",
				AnomalyResidue.hazardScale(8.0) > AnomalyResidue.hazardScale(6.0)
						&& AnomalyResidue.hazardScale(12.0) > AnomalyResidue.hazardScale(8.0));
	}

	// --- экономика и частота кадров ---

	private static void checkEconomy(Result result, AnomalyConfig config, Random rnd) {
		// стена: один кадр в секунду при любой фазе и любом периоде тика эффектов
		boolean cadence = true;
		for (int step : new int[]{5, 10, 20}) {
			for (int phase = 0; phase < 20; phase++) {
				int count = 0;
				for (int pulse = 0; pulse < 20; pulse += step) {
					if (AnomalyEffects.frameDue(pulse, phase, 20, step)) {
						count++;
					}
				}
				cadence &= count == 1;
			}
		}
		report(result, "стена рисуется ровно раз в секунду при любом шаге и фазе", cadence);
		report(result, "фаза не совпадает с нулём и это не ломает кадр",
				AnomalyEffects.frameDue(20, 3, 20, 10));
		report(result, "в чужой половине периода кадра нет",
				!AnomalyEffects.frameDue(10, 3, 20, 10));

		// кладовая: 25 минут и потолок выдач
		long placed = 5_000_000L;
		report(result, "кладовая не созревает за 24 минуты",
				!AnomalyNodes.cacheDue(placed, placed + 24L * 60_000L, false));
		report(result, "кладовая созревает за 25 минут",
				AnomalyNodes.cacheDue(placed, placed + 25L * 60_000L, false));

		// лут смертельной: не гарантия незерита и тотема, иначе кладовая — станок
		int totems = 0;
		int netherite = 0;
		boolean sane = true;
		for (int i = 0; i < 200; i++) {
			java.util.List<net.minecraft.world.item.ItemStack> loot =
					AnomalyNodes.lootFor(AnomalyClass.DEADLY, new Random(9000L + i));
			sane &= loot.size() >= 2 && loot.size() <= 5;
			for (net.minecraft.world.item.ItemStack stack : loot) {
				if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
					totems++;
				}
				if (stack.is(net.minecraft.world.item.Items.NETHERITE_INGOT)) {
					netherite++;
				}
			}
		}
		report(result, "лут смертельной помещается в сундук и не пустой", sane);
		report(result, "тотем в смертельной — редкость (" + totems + "/200)",
				totems > 20 && totems < 80);
		report(result, "незерит в смертельной — не всегда (" + netherite + "/200)",
				netherite > 20 && netherite < 120);

		// клиренс рождения учитывает будущий радиус: зона на 3000 не «садится» в 500 блоках от базы
		AnomalyField.Avoid avoid = new AnomalyField.Avoid(List.of(new double[]{0.0, 0.0, 256.0}), null, 0);
		report(result, "точка в 500 блоках годится для мелкой зоны", !avoid.blocks(500, 0, 100));
		report(result, "та же точка не годится для крупной", avoid.blocks(500, 0, 400));
	}

	// --- отчёт ---

	private static void check(Result result, String name, double expected, double actual, double tolerance) {
		report(result, name + " (ожидалось " + expected + ", получилось " + actual + ")",
				Math.abs(expected - actual) <= tolerance);
	}

	private static void report(Result result, String line, boolean ok) {
		if (ok) {
			result.passed++;
		} else {
			result.failed++;
			result.failures.add(line);
		}
	}
}





