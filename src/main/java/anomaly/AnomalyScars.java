package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Разрушения и геологические следы: планирование и применение.
 * <p>
 * След не хранится списком блоков — хранится правило ({@link ScarOp}) и курсор: сколько колонок
 * уже обработано. Поэтому план переживает рестарт, ничего не раздувает и не зависит от того,
 * на какой высоте оказался рельеф.
 * <p>
 * Применение ленивое: 1–2 чанка и до {@code blocks_per_tick} блоков за секунду, только когда
 * сервер не задыхается (mspt ниже порога). Чанк сперва проверяется на загруженность и только
 * потом грузится — иначе {@code getHeight} вернёт дно мира и мы выроем яму под бедроком.
 * <p>
 * Три вещи из 0.3.0: реестр следов (перекрывающиеся объёмы отбрасываются), следы у зрелой зоны
 * (при дрейфе план достраивается на новом месте) и суточный лимит FULL-генераций чанков.
 */
public final class AnomalyScars {
	private static final double TAU = Math.PI * 2.0;
	private static final int MAX_HEIGHT = 150;
	/** Больше этого числа следов в одной зоне не копим: файл поля и очередь должны быть конечны. */
	private static final int MAX_OPS = 64;
	/** Колонок за проход: ~2000 изменённых блоков и несколько тысяч пустых проверок. */
	private static final int COLUMNS_PER_PASS = 6000;
	/** Часы на проход: средний mspt помнит 100 тиков, этого мало для 2000 setBlock подряд. */
	private static final long NANOS_PER_PASS = 5_000_000L;
	/** Пауза между сигналом перед следом и первыми блоками: игрок успевает обернуться. */
	private static final long TELEGRAPH_HOLD_MS = 2_000L;

	/**
	 * Микрособытия (хлопок, гарь) идут из компонентов, а не из прохода следов: в грозу
	 * у ядра их десятки за тик на каждого игрока. Потолок на тик — общий, а не на игрока.
	 */
	private static int microThisTick;
	private static long microTickStamp;

	/**
	 * Есть ли игроки ХОТЬ ГДЕ на сервере. Проверка по одному измерению врала: все ушли в ад
	 * или в край — Верхний мир «пуст», и следы снова генерировали чанки синхронно.
	 */
	static boolean serverHasPlayers(ServerLevel level) {
		return level.getServer() != null && !level.getServer().getPlayerList().getPlayers().isEmpty();
	}

	private static boolean microBudget(ServerLevel level) {
		long tick = level.getServer().overworld().getGameTime();
		if (tick != microTickStamp) {
			microTickStamp = tick;
			microThisTick = 0;
		}
		return ++microThisTick <= 8;
	}
	private static final Block[] SPIKE_BLOCKS = {
			Blocks.STONE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DEEPSLATE
	};
	private static final Block[] RUIN_WALLS = {
			Blocks.COBBLESTONE, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS
	};
	private static final Block[] RUIN_FLOOR = {
			Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.COBBLESTONE, Blocks.ANDESITE
	};

	private AnomalyScars() {
	}

	// --- планирование ---

	/**
	 * Опоясывающая рана по настоящему контуру зоны: пятна мёртвой земли на {@code boundaryPoint},
	 * а не по кругу. Круг у вытянутой зоны уходит вдвое внутрь или наружу, и граница класса
	 * остаётся нематериальной. Планируется один раз при рождении, стоит 1–3 % бюджета.
	 */
	public static List<ScarOp> woundRing(Anomaly anomaly, double ageHours, Random rnd) {
		List<ScarOp> ops = new ArrayList<>();
		double r0 = anomaly.r0;
		if (r0 < 300.0) {
			return ops;
		}
		int count = Math.max(10, Math.min(24, (int) Math.round(2.0 * Math.PI * r0 / 260.0)));
		double budget = AnomalyClass.damageBudget(r0, AnomalyClass.byId(anomaly.cls)) * 0.03;
		double used = 0.0;
		for (int i = 0; i < count; i++) {
			double angle = i * Math.PI * 2.0 / count + rnd.nextDouble() * 0.12;
			double[] point = anomaly.boundaryPoint(angle, ageHours, r0);
			if (!Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
				continue;
			}
			ScarOp spot = ScarOp.disk("dead_soil", point[0], point[1], 2.5 + rnd.nextDouble() * 1.5, rnd.nextLong());
			if (used + spot.blocks > budget) {
				break;
			}
			ops.add(spot);
			used += spot.blocks;
		}
		return ops;
	}

	public static List<ScarOp> plan(Anomaly anomaly, AnomalyClass cls, Random rnd) {
		return planAt(anomaly.x0, anomaly.z0, anomaly.r0, cls, rnd, anomaly.pinned, anomaly.components);
	}

	/** План вокруг точки: при миграции старой зоны это её нынешнее место, а не точка рождения. */
	public static List<ScarOp> planAt(double x, double z, double r0, AnomalyClass cls, Random rnd,
			boolean pinned, List<String> components) {
		List<ScarOp> ops = new ArrayList<>();
		double budget = AnomalyClass.damageBudget(r0, cls);
		if (budget < 20.0 || pinned) {
			return ops;
		}
		// план рождения берёт не больше 70 % бюджета: остальное — тихим следам (drip) и
		// дрейфовым добавкам. Раньше он забирал до 90 %, и зона не могла больше сорить
		double target = budget * (0.3 + rnd.nextDouble() * 0.4);
		double used = 0.0;
		int guard = 0;
		while (used < target && guard++ < 40) {
			ScarOp op = pick(x, z, r0, cls, rnd, target - used, components);
			if (op == null) {
				break;
			}
			// реестр следов: два пятна в одной точке нарастают друг на друга и читаются как грязь
			if (overlaps(ops, op)) {
				continue;
			}
			ops.add(op);
			used += op.blocks;
		}
		return ops;
	}

	/**
	 * Перекрываются ли два объёмных следа. Линии (трещины, разломы) пересекаться вправе — они
	 * читаются как сеть; пятна, пики и провалы — нет.
	 */
	static boolean overlaps(List<ScarOp> ops, ScarOp op) {
		if (op == null || op.isLine()) {
			return false;
		}
		for (ScarOp other : ops) {
			if (other == null || other.isLine()) {
				continue;
			}
			double need = (Math.max(2.0, op.radius) + Math.max(2.0, other.radius)) * 0.7;
			if (Math.hypot(op.x - other.x, op.z - other.z) < need) {
				return true;
			}
		}
		return false;
	}

	private static ScarOp pick(double cx, double cz, double r0, AnomalyClass cls, Random rnd, double remaining,
			List<String> components) {
		boolean deadly = cls == AnomalyClass.DEADLY;
		boolean dangerous = cls != AnomalyClass.SAFE;
		// Геология — от компонентов, а не только от класса: иначе все смертельные зоны
		// получают одни и те же пики и лаву, и пейзаж у них одинаковый.
		boolean storms = has(components, "storm_core");
		boolean pushes = storms || has(components, "pressure") || has(components, "night_toll");
		boolean rot = has(components, "husk_bloom") || has(components, "dark_sucking");
		double roll = rnd.nextDouble();
		double[] spot = spot(cx, cz, r0, rnd);

		if (deadly && pushes && remaining > 6000.0 && roll < 0.35) {
			ScarOp op = ScarOp.spike(spot[0], spot[1], 4.0 + rnd.nextDouble() * 10.0,
					40 + rnd.nextInt(80), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		if (deadly && storms && remaining > 1200.0 && roll < 0.50) {
			ScarOp op = ScarOp.line("lava_fault", spot[0], spot[1], rnd.nextDouble() * TAU,
					40 + rnd.nextInt(100), 2, 4 + rnd.nextInt(3), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// поднятая плита: то, что видно за километры через voxy — не пик, а пласт земли
		if (deadly && remaining > 5000.0 && roll < 0.62) {
			ScarOp op = ScarOp.uplift(spot[0], spot[1], 8.0 + rnd.nextDouble() * 10.0,
					6 + rnd.nextInt(10), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		if (dangerous && pushes && remaining > 900.0 && roll < 0.72) {
			ScarOp op = ScarOp.spike(spot[0], spot[1], 3.0 + rnd.nextDouble() * 4.0,
					15 + rnd.nextInt(30), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// обрушенные постройки: стены без крыш и пол из обломков — «здесь жили»
		if (dangerous && remaining > 1800.0 && roll < 0.80) {
			ScarOp op = ScarOp.ruin(spot[0], spot[1], 5.0 + rnd.nextDouble() * 8.0, rnd.nextLong());
			// поворот постройки: без него все руины стояли по осям координат
			op.angle = rnd.nextDouble() * TAU;
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// поваленный лес плашмя: самое читаемое издалека, а вблизи — стволы лежат, кроны снесены
		if (dangerous && remaining > 400.0 && roll < 0.88) {
			ScarOp op = ScarOp.forest(spot[0], spot[1], 10.0 + rnd.nextDouble() * 14.0, rnd.nextLong());
			// направление падения своё у каждого опа: иначе во всём мире один и тот же ветер
			op.angle = rnd.nextDouble() * TAU;
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// провал — отдельная геология: не пик и не пятно, а дыра в рельефе
		if (dangerous && remaining > 2500.0 && roll < 0.94) {
			ScarOp op = ScarOp.sinkhole(spot[0], spot[1], 3.0 + rnd.nextDouble() * 5.0,
					3 + rnd.nextInt(6), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// площадные следы: мёртвая земля или обломки — под остаток бюджета
		double soilBudget = Math.min(remaining * 0.7, deadly ? 3000.0 : 1500.0);
		double soilRadius = Math.max(3.0, Math.min(24.0, Math.sqrt(soilBudget / Math.PI)));
		String soilType = rot ? "dead_soil" : (rnd.nextDouble() < 0.65 ? "dead_soil" : "rubble");
		ScarOp soil = ScarOp.disk(soilType, spot[0], spot[1],
				soilType.equals("rubble") ? soilRadius * 0.7 : soilRadius, rnd.nextLong());
		if (soil.blocks <= remaining) {
			return soil;
		}
		// трещина — самое дешёвое: 10–160 блоков в длину
		int maxLength = (int) Math.max(10.0, Math.min(160.0, remaining / 6.0));
		if (maxLength < 12) {
			return null;
		}
		ScarOp crack = ScarOp.line("crack", spot[0], spot[1], rnd.nextDouble() * TAU,
				12 + rnd.nextInt(maxLength - 11), 1 + rnd.nextInt(2), 2 + rnd.nextInt(2), rnd.nextLong());
		return crack.blocks <= remaining ? crack : null;
	}

	/** Есть ли такой компонент в наборе. */
	private static boolean has(List<String> components, String id) {
		return components != null && components.contains(id);
	}

	/**
	 * Точка в зоне, где ставится след. Не только ядро и не только край: ядро должно быть
	 * изуродовано, иначе игрок идёт по чистому диску к маяку, а полоса 45–85 % оставляла
	 * 1.35 км стерильного центра у зоны на 3000.
	 */
	private static double[] spot(double cx, double cz, double r0, Random rnd) {
		double angle = rnd.nextDouble() * TAU;
		double distance = r0 * (0.08 + 0.78 * Math.sqrt(rnd.nextDouble()));
		return new double[]{
				cx + Math.cos(angle) * distance,
				cz + Math.sin(angle) * distance
		};
	}

	/**
	 * Следы у зрелой зоны: план считается один раз в точке рождения, а зона уезжает на 0.5–3
	 * блока в минуту — до 10 км за жизнь. Без пересчёта у старой зоны следы остаются там, где
	 * она родилась. Раз в порог смещения достраиваем план на новом месте, с реестром против
	 * наложения и потолком числа следов.
	 */
	static boolean trail(AnomalyFieldData data, AnomalyConfig config, long now) {
		boolean changed = false;
		int worldOps = totalOps(data);
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.pinned || anomaly.remnant || anomaly.precursor || anomaly.scars == null) {
				continue;
			}
			double[] pos = anomaly.position(anomaly.ageHours(now));
			if (!Double.isFinite(pos[0]) || !Double.isFinite(pos[1])) {
				continue;
			}
			if (anomaly.scar_cx == 0.0 && anomaly.scar_cz == 0.0) {
				// файлы 0.2.x не знали точки привязки: принимаем текущую, ничего не генерируя
				anomaly.scar_cx = pos[0];
				anomaly.scar_cz = pos[1];
				changed = true;
				continue;
			}
			if (anomaly.scars.size() >= MAX_OPS) {
				continue;
			}
			double moved = Math.hypot(pos[0] - anomaly.scar_cx, pos[1] - anomaly.scar_cz);
			double threshold = Math.max(40.0, Math.min(400.0, anomaly.r0 * 0.2));
			if (moved < threshold) {
				continue;
			}
			AnomalyClass cls = AnomalyClass.byId(anomaly.cls);
			// план строится по зрелому радиусу: на новорождённой зоне (радиус ~0) следы легли бы
			// в 1–2 км от неё, где аномалии ещё нет. Ждём, пока зона дорастёт до ~85 % r0 —
			// это первые часы жизни, а не вся она
			if (anomaly.radius(anomaly.ageHours(now)) < anomaly.r0 * 0.85) {
				continue;
			}
			anomaly.scar_cx = pos[0];
			anomaly.scar_cz = pos[1];
			// точка привязки уехала — это изменение данных, его надо записать, иначе после
			// рестарта trail() решит, что зона сместилась на всё это расстояние сразу
			changed = true;
			if (!cls.damages() || config == null || !config.scar_enabled) {
				continue;
			}
			boolean firstPlan = anomaly.scars.isEmpty() && anomaly.scar_spent <= 0.0;
			// потолок плана на весь мир: иначе файл и снимок поля растут без предела
			if (worldOps >= MAX_OPS_WORLD) {
				continue;
			}
			List<ScarOp> planned = planAt(pos[0], pos[1], Math.min(anomaly.r0, 900.0), cls,
					new Random(anomaly.id * 31L + (long) (moved * 7.0)), false, anomaly.components);
			// остаток бюджета зоны, а не фиксированная доля: план при рождении уже взял 0.4–0.9,
			// и дрейфовые добавки утраивали обещанный damageBudget
			double budget = AnomalyClass.damageBudget(anomaly.r0, cls)
					- anomaly.scar_spent - plannedBlocks(anomaly.scars);
			if (budget <= 0.0) {
				continue;
			}
			double used = 0.0;
			for (ScarOp op : planned) {
				if (used + op.blocks > budget || anomaly.scars.size() >= MAX_OPS
						|| worldOps >= MAX_OPS_WORLD) {
					break;
				}
				if (overlaps(anomaly.scars, op)) {
					continue;
				}
				anomaly.scars.add(op);
				worldOps++;
				used += op.blocks;
				changed = true;
			}
			// кольцо раны — один раз, вместе с первым планом: у настоящей зоны план рождается
			// здесь (в born() радиус ещё нулевой, и её следы планирует trail)
			if (firstPlan) {
				for (ScarOp ring : woundRing(anomaly, anomaly.ageHours(now), new Random(anomaly.id * 0x5DEECE66DL))) {
					if (anomaly.scars.size() >= MAX_OPS || worldOps >= MAX_OPS_WORLD) {
						break;
					}
					if (overlaps(anomaly.scars, ring)) {
						continue;
					}
					anomaly.scars.add(ring);
					worldOps++;
					changed = true;
				}
			}
		}
		return changed;
	}

	// --- применение ---

	/**
	 * Потолок плана на весь мир: 600 зон × 64 op — это 38 тыс. следов ≈ 8 МБ файла и ~90 мс
	 * Gson на тик-потоке. Пять тысяч — уже вдесятеро больше, чем поле успевает применить.
	 */
	static final int MAX_OPS_WORLD = 5000;

	/** Сколько следов уже запланировано И ещё не применено: только эта очередь и стоит в файле. */
	static int totalOps(AnomalyFieldData data) {
		int total = 0;
		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.scars != null) {
				// применённые op остаются в списке навсегда: считать их — значит упереться
				// в потолок и молча лишить следа все зоны, чья очередь позже
				total += Math.max(0, anomaly.scars.size() - Math.max(0, anomaly.scar_next));
			}
		}
		return total;
	}

	/** Сколько блоков уже обещано планом зоны: по нему считается остаток бюджета. */
	static double plannedBlocks(List<ScarOp> ops) {
		double sum = 0.0;
		for (ScarOp op : ops) {
			if (op != null) {
				sum += op.blocks;
			}
		}
		return sum;
	}

	/**
	 * Один op отработан: его блоки уходят в счётчик зоны, сам op выбрасывается. Общая книга
	 * для обычного применения и для мокрого пропуска — иначе счётчик расходится с планом.
	 */
	private static void finishOp(Anomaly anomaly, ScarOp op) {
		anomaly.scar_spent = Math.max(0.0, anomaly.scar_spent) + Math.max(0, op.blocks);
		anomaly.scar_next++;
		anomaly.scar_cursor = 0;
		// довели очередь до конца — чистим план зоны: применённое больше не нужно, и файл
		// поля не растёт до 7 МБ (42 мс Gson на тик-потоке)
		if (anomaly.scar_next >= anomaly.scars.size()) {
			anomaly.scars.clear();
			anomaly.scar_next = 0;
			anomaly.scar_telegraph_for = -1;
			anomaly.scar_hold_for = -1;
			anomaly.scar_wet_for = -1;
			AnomalyField.markDirty();
		}
	}

	/** С какой записи начинать следующий проход: строгий FIFO морил голодом всё поле за первой зоной. */
	private static int rotate;

	/**
	 * Раз в столько тиков поля миру достаётся один тихий след. Поле тикает раз в секунду, так что
	 * 6 — это шесть секунд; не путать с тиками сервера (20/с). Счёт на вызовах, а не на полевых
	 * часах: {@code /anomaly timescale 3600} не должен превращать дрип в поток.
	 */
	private static final int DRIP_PERIOD_TICKS = 6;
	private static final Random DRIP_RANDOM = new Random(0x5EED5CA7L);
	private static int dripTicks;
	private static boolean dripLogged;
	private static int dripDebug;

	/**
	 * Тихий след: зона должна сорить и без игрока.
	 * <p>
	 * Пока в зоне никого нет, единственное, что там появляется, — это план, поставленный при
	 * рождении и дрейфе. Игрок возвращается через сутки и находит два шипа и пустоту: «страшная
	 * аномалия» не выглядит страшной. Здесь раз в несколько секунд одна повреждённая зона
	 * получает маленький op — воронку, мёртвую землю, трещину, осыпь.
	 * <p>
	 * Op идёт в обычную очередь зоны, поэтому его применяет тот же проход: чанк загружен — след
	 * лёг, не загружен — ждёт. Синхронной генерации чанков это не добавляет: она по-прежнему
	 * разрешена только на пустом сервере и под суточным лимитом.
	 */
	static boolean drip(AnomalyFieldData data, AnomalyConfig config, long now, Random rnd) {
		if (config == null || !config.scar_enabled) {
			return false;
		}
		if (++dripTicks < DRIP_PERIOD_TICKS) {
			return false;
		}
		dripTicks = 0;
		return dripOnce(data, config, now, rnd);
	}

	/** Один тихий след в случайную повреждённую зону. Вынесено от таймера ради самопроверки. */
	static boolean dripOnce(AnomalyFieldData data, AnomalyConfig config, long now, Random rnd) {
		if (data == null || data.anomalies.isEmpty() || totalOps(data) >= MAX_OPS_WORLD) {
			return false;
		}
		int size = data.anomalies.size();
		int start = rnd.nextInt(size);
		int roomy = 0;
		String reason = "все зоны отсеяны до бюджета";
		for (int step = 0; step < size; step++) {
			Anomaly anomaly = data.anomalies.get((start + step) % size);
			if (anomaly.pinned || anomaly.remnant || anomaly.precursor || anomaly.scars == null) {
				continue;
			}
			AnomalyClass cls = AnomalyClass.byId(anomaly.cls);
			// безопасная зона не рушит мир: у неё и бюджет нулевой
			if (!cls.damages() || anomaly.scars.size() >= MAX_OPS) {
				continue;
			}
			double age = anomaly.ageHours(now);
			double[] pos = anomaly.position(age);
			if (!Double.isFinite(pos[0]) || !Double.isFinite(pos[1])) {
				continue;
			}
			// та же касса, что у плана: бюджет зоны один на всех, и дрип не должен
			// съедать больше, чем осталось после уже обещанного
			double budget = AnomalyClass.damageBudget(anomaly.r0, cls);
			double used = anomaly.scar_spent + plannedBlocks(anomaly.scars);
			ScarOp op = dripOp(pos[0], pos[1], Math.max(12.0, anomaly.radius(age)), rnd);
			roomy++;
			if (used + op.blocks > budget) {
				reason = "зона #" + anomaly.id + ": " + Math.round(used) + " занято из "
						+ Math.round(budget) + ", op просит " + op.blocks;
				continue;
			}
			// уже применённое не мешает: сравниваем только с тем, что ещё в очереди
			List<ScarOp> queue = anomaly.scars.subList(Math.max(0, anomaly.scar_next), anomaly.scars.size());
			if (overlaps(queue, op)) {
				continue;
			}
			anomaly.scars.add(op);
			AnomalyField.markDirty();
			// одна строка за сессию: по ней в логе видно, что механизм живой, без спама
			if (!dripLogged) {
				dripLogged = true;
				AnomalyMod.LOGGER.info("Anomaly: тихий след в зоне #{} — {}", anomaly.id, op.describe());
			}
			return true;
		}
		if (dripDebug++ % 30 == 0) {
			AnomalyMod.LOGGER.info("Anomaly: тихий след не нашёл места (зон {}, дошли до бюджета {}, причина: {})",
					size, roomy, reason);
		}
		return false;
	}

	/** Мелкий след для дрипа: тот же язык, что у плана, но самая мелкая его часть. */
	private static ScarOp dripOp(double cx, double cz, double radius, Random rnd) {
		double angle = rnd.nextDouble() * Math.PI * 2.0;
		double dist = radius * (0.1 + 0.75 * rnd.nextDouble());
		double x = cx + Math.cos(angle) * dist;
		double z = cz + Math.sin(angle) * dist;
		long seed = rnd.nextLong();
		return switch (rnd.nextInt(4)) {
			case 0 -> ScarOp.disk("dead_soil", x, z, 4 + rnd.nextInt(6), seed);
			case 1 -> ScarOp.sinkhole(x, z, 2 + rnd.nextInt(3), 2, seed);
			case 2 -> ScarOp.line("crack", x, z, rnd.nextDouble() * Math.PI * 2.0, 12 + rnd.nextInt(20), 1, 3, seed);
			default -> ScarOp.disk("rubble", x, z, 3 + rnd.nextInt(5), seed);
		};
	}
	/** Дедлайн текущего прохода: деревья внутри одной колонки умеют съесть его целиком. */
	private static long passDeadline;

	/**
	 * Суточный лимит FULL-генераций чанков под следы. Без него первое поле на 600 зон
	 * растягивает worldgen на 10–30 тыс. генераций и часы работы сервера.
	 */
	static boolean chunkBudgetAllows(AnomalyFieldData data, AnomalyConfig config, long now) {
		int day = (int) (now / AnomalyField.DAY_MS);
		if (data.gen_day != day) {
			data.gen_day = day;
			data.gen_count = 0;
		}
		return config == null || config.chunk_gen_per_day <= 0 || data.gen_count < config.chunk_gen_per_day;
	}

	/**
	 * Возвращает true, если что-то продвинулось: по этому полю решается, пора ли сохранять файл.
	 * <p>
	 * Бюджет тройной: чанки, колонки и блоки. Колонки считаются отдельно, потому что на песке,
	 * воде и камне {@code applyColumn} возвращает 0 — по одним изменённым блокам проход не
	 * остановился бы никогда и за тик выгреб бы весь план зоны.
	 */
	public static boolean tick(MinecraftServer server, ServerLevel level, AnomalyFieldData data,
			AnomalyConfig config) {
		try {
			boolean dripped = drip(data, config, AnomalyClock.now(), DRIP_RANDOM);
			return pass(server, level, data, config) || dripped;
		} finally {
			// дедлайн держим только внутри тика. Раньше он оставался в прошлом, и любой
			// вызов вне прохода (/anomaly demo, /anomaly event) получал «время вышло»
			// и обрезал каждую колонку на восьми блоках: шип вырастал плоской нашлёпкой
			passDeadline = 0L;
		}
	}

	private static boolean pass(MinecraftServer server, ServerLevel level, AnomalyFieldData data,
			AnomalyConfig config) {
		if (!config.scar_enabled) {
			return false;
		}
		boolean changed = trail(data, config, AnomalyClock.now());
		// средний mspt — грубый предохранитель с памятью в 100 тиков; точный — по часам внутри прохода
		if (server.getAverageTickTimeNanos() / 1_000_000.0 > Math.min(config.mspt_limit, 30.0)) {
			return changed;
		}
		long deadline = System.nanoTime() + NANOS_PER_PASS;
		passDeadline = deadline;
		int chunksThisTick = 0;
		int columnsThisTick = 0;
		int blocksThisTick = 0;
		long failedAt = 0L;
		boolean progressed = changed;

		int size = data.anomalies.size();
		if (size == 0) {
			return progressed;
		}
		if (rotate < 0 || rotate >= size) {
			rotate = 0;
		}
		for (int step = 0; step < size; step++) {
			int index = (rotate + step) % size;
			Anomaly anomaly = data.anomalies.get(index);
			if (anomaly.scars == null || anomaly.scars.isEmpty() || anomaly.scar_next >= anomaly.scars.size()) {
				continue;
			}
			while (anomaly.scar_next < anomaly.scars.size()) {
				ScarOp op = anomaly.scars.get(anomaly.scar_next);
				int count = op.columnCount();
				boolean finished = true;
				boolean limitHit = false;
				boolean wetSkip = false;
				boolean skipped = false;
				// игрок в объёме опа — проверяем ОДИН раз на op, а не на каждую колонку
				boolean opPlayerFree = anomaly.scar_retry_tree || !opHasPlayer(level, op);
				if (!opPlayerFree) {
					finished = false;
					limitHit = true;
					break;
				}
				while (anomaly.scar_cursor < count) {
					int[] column = op.column(anomaly.scar_cursor);
					if (column == null) {
						anomaly.scar_cursor++;
						continue;
					}
					// ближе полосы подавления мир не трогаем даже случайно задрейфовавшей зоной.
					// Проверка до загрузки чанка: иначе подавленный план всё равно генерирует мир.
					if (Math.hypot(column[0], column[1]) < config.suppress_until) {
						anomaly.scar_cursor++;
						continue;
					}
					// Пауза между сигналом и блоками: игрок должен успеть обернуться на звук
					// и пыль, а не обнаружить гору через тик. Ждём до 2 с, op не теряется
					if (anomaly.scar_hold_for == anomaly.scar_next
							&& AnomalyClock.now() < anomaly.scar_hold_until) {
						finished = false;
						limitHit = true;
						break;
					}
					// бюджет проверяем ДО загрузки чанка: getChunk — синхронная генерация
					if (blocksThisTick >= config.blocks_per_tick || columnsThisTick >= COLUMNS_PER_PASS
							|| System.nanoTime() > deadline) {
						finished = false;
						break;
					}
					int chunkX = column[0] >> 4;
					int chunkZ = column[1] >> 4;
					if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
						// Чанк не загружен. Синхронная генерация — это 10–50 мс прямо в тик-потоке,
						// поэтому при живых игроках след ждёт: чанк загрузится сам, когда игрок
						// подойдёт. И это НЕ повод обрывать весь проход: с «break» поле обслуживало
						// ровно одну зону в секунду, и 610 зон ждали своей очереди десять минут
						if (serverHasPlayers(level)) {
							skipped = true;
							break;
						}
						if (chunksThisTick >= config.chunks_per_tick
								|| !chunkBudgetAllows(data, config, AnomalyClock.now())) {
							finished = false;
							limitHit = true;
							break;
						}
						level.getChunk(chunkX, chunkZ);
						data.gen_count++;
						AnomalyField.markDirty();
						chunksThisTick++;
						// дедлайн проверяем сразу после генерации: getChunk — это 10–50 мс,
						// и проверка «до» его не ограничивает
						if (System.nanoTime() > deadline) {
							finished = false;
							break;
						}
					}
				// вода проверяется один раз на op: column(0) у диска — угол квадрата вне круга
				// и возвращает null, поэтому привязка к scar_cursor == 0 не срабатывала никогда
				if (anomaly.scar_wet_for != anomaly.scar_next && !anomaly.scar_retry_tree) {
					int wet = wetOpState(level, op, data, config);
					if (wet < 0) {
						// исчерпан суточный лимит генераций: след ждёт следующего дня, op не трогаем
						finished = false;
						limitHit = true;
						break;
					}
					if (wet == 0) {
						// бюджет/дедлайн не дали даже одного чанка: это не «сухо», а «не знаю» —
						// не помечаем op проверенным и возвращаемся к нему в следующем проходе
						if (++anomaly.scar_wet_tries < 8) {
							finished = false;
							limitHit = true;
							break;
						}
						// восемь раз не смогли: считаем МОКРЫМ. «Сухо» здесь означало бы воронку
						// в берег — ровно то, от чего защищались; пропуск безопаснее
						anomaly.scar_wet_tries = 0;
						anomaly.scar_wet_for = anomaly.scar_next;
						wetSkip = true;
						break;
					}
					anomaly.scar_wet_tries = 0;
					anomaly.scar_wet_for = anomaly.scar_next;
					if (wet == 2) {
						wetSkip = true;
						break;
					}
				}
				if (!anomaly.scar_retry_tree && anomaly.scar_telegraph_for != anomaly.scar_next) {
					// сигнал от первой реальной колонки, а не от центра опа: у линии работа
					// уходит на сотню блоков, и центр мог оказаться в стороне (или в незагруженном чанке)
					AnomalyEffects.scarTelegraph(level, column[0], column[1],
							AnomalyClass.byId(anomaly.cls).color);
					anomaly.scar_telegraph_for = anomaly.scar_next;
					// и сразу откладываем блоки: 2 с между пылью и камнем
					anomaly.scar_hold_for = anomaly.scar_next;
					anomaly.scar_hold_until = AnomalyClock.now() + TELEGRAPH_HOLD_MS;
					finished = false;
					limitHit = true;
					break;
				}
					// курсор двигаем только после проверок: иначе колонка на стыке бюджета чанков теряется
					anomaly.scar_cursor++;
					columnsThisTick++;
					int applied = 0;
					try {
						// повтор после обрыва по дереву: op уже применён, доделываем только дерево,
						// иначе воронка копается второй раз уже по дну ямы
						// повтор колонки: дерево сносится ДО следа, значит op ещё не применён
						// и колонку надо обработать целиком (а не только доделать дерево).
						// Если прошлая попытка оборвалась на кроне — досносим её: deforestForce
						// идемпотентен, а fallenForest на повторе уже не видит ствола
						if (anomaly.scar_retry_tree) {
							int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column[0], column[1]);
							if (h > level.getMinY() + 3 && h < level.getMaxY() - 2) {
								deforestForce(level, column[0], h, column[1]);
							}
						}
						applied = applyColumn(level, op, column[0], column[1], opPlayerFree);
						anomaly.scar_retry_tree = false;
					} catch (RuntimeException e) {
						// одна строка на проход, а не на колонку: устойчивое исключение иначе
						// давало до 6000 строк со стеком в секунду
						if (failedAt == 0L) {
							failedAt = System.nanoTime();
							AnomalyMod.LOGGER.warn("Anomaly: след #{} в {}, {} не применён ({})",
									anomaly.id, column[0], column[1], e.toString());
						}
					}
					if (applied == -2) {
						// игрок стоит в объёме следа: откладываем op, а не колонку
						anomaly.scar_cursor--;
						finished = false;
						limitHit = true;
						break;
					}
					if (applied < 0) {
						// дерево не доделано: возвращаем курсор, иначе остаток кроны останется
						// висеть навсегда — колонка не считается обработанной
						anomaly.scar_cursor--;
						anomaly.scar_retry_tree = true;
						finished = false;
						break;
					}
					blocksThisTick += applied;
					progressed = true;
					if (blocksThisTick >= config.blocks_per_tick || columnsThisTick >= COLUMNS_PER_PASS
							|| System.nanoTime() > deadline) {
						finished = false;
						break;
					}
				}
				if (skipped) {
					// чанк этой зоны не загружен: идём к следующей, а не тратим проход впустую
					break;
				}
				if (wetSkip) {
					// след целиком в воде: пропускаем op, а не режем берег половинками.
					// Через ту же книгу, что и обычный op: иначе мокрый последний op не чистит
					// список, его блоки считаются дважды (scar_spent + план) и зона голодает
					finishOp(anomaly, op);
					continue;
				}
				if (!finished) {
					// уперлись в лимит/бюджет чанков — идём к следующей зоне, иначе всё поле
					// ждёт смены суток; уперлись в блоки/время — продолжаем с этой же
					rotate = limitHit ? (index + 1) % size : index;
					return progressed;
				}
				finishOp(anomaly, op);
				if (blocksThisTick >= config.blocks_per_tick || chunksThisTick >= config.chunks_per_tick
						|| columnsThisTick >= COLUMNS_PER_PASS) {
					rotate = (index + 1) % size;
					return progressed;
				}
			}
		}
		return progressed;
	}

	static int applyColumn(ServerLevel level, ScarOp op, int x, int z) {
		return applyColumn(level, op, x, z, false);
	}

	/**
	 * @param playerFree тик уже проверил «игрок вне опа» один раз на весь op: внутри
	 *                   колонок проверку не повторяем (getHeight на каждого игрока съедал
	 *                   5-мс бюджет). Команды и тесты зовут версию без флага.
	 */
	static int applyColumn(ServerLevel level, ScarOp op, int x, int z, boolean playerFree) {
		// чанк уже загружен вызывающим: иначе getHeight отдаёт минимум мира
		int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (height <= level.getMinY() + 3 || height >= level.getMaxY() - 2) {
			return 0;
		}
		// heightmap считает верхушку ствола и снежный слой поверхностью: след должен лечь на землю,
		// иначе трещина копает ствол сверху, а мёртвая земля не выжигает грунт под деревом
		boolean tree = isTreePart(level.getBlockState(new BlockPos(x, height - 1, z)));
		int surface = groundY(level, x, height, z);
		// heightmap считает воду и лёд грунтом. В холодном океане на льдине вырос бы каменный шип,
		// а обломки заменили бы лёд булыжником: на льду и снежном блоке разрешаем только то,
		// что вынимает блок (трещина, провал) — иначе ледник и ледяные пики остаются без следов
		BlockState ground = level.getBlockState(new BlockPos(x, surface - 1, z));
		if (!ground.getFluidState().isEmpty() || builtBlock(ground)) {
			return 0;
		}
		// на льду и снежном блоке — только трещина: шип и плита выглядят мусором,
		// а провал всё равно упирается в воду подо льдом
		if (isFragileSurface(ground) && !op.type.equals("crack")) {
			return 0;
		}
		// игрок в объёме следа: откладываем op целиком, иначе в провале остаётся столб-остров.
		// игрок в объёме следа: откладываем op целиком, иначе в провале остаётся столб-остров
		if (!playerFree && opHasPlayer(level, op)) {
			return -2;
		}
		// игрок вплотную: камень в ступнях — это удушье, а вынутый блок — падение
		if (playersNear(level, x, surface, z)) {
			return 0;
		}
		// дерево сносим ДО следа: иначе шип, упёршись в ствол, оставлял шахту 1×1 на всю высоту,
		// ровным рядом по местам деревьев, а deforest потом убирал ствол — сквозь конус видно небо.
		// fallen_forest сносит своё дерево сам
		int treeFallen = 0;
		if (tree && !op.type.equals("fallen_forest")) {
			treeFallen = deforest(level, x, height, z);
			if (treeFallen < 0) {
				return -1;
			}
			height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			surface = groundY(level, x, height, z);
			ground = level.getBlockState(new BlockPos(x, surface - 1, z));
			if (!ground.getFluidState().isEmpty() || builtBlock(ground)) {
				return treeFallen;
			}
		}
		int changed = switch (op.type) {
			case "dead_soil" -> deadSoil(level, x, surface, z, op.seed);
			case "rubble" -> rubble(level, x, surface, z, op.seed);
			case "crack" -> trench(level, op, x, surface, z, op.depth, false);
			case "lava_fault" -> trench(level, op, x, surface, z, op.depth, true);
			case "spike" -> spike(level, op, x, surface, z, ground);
			case "sinkhole" -> sinkhole(level, op, x, surface, z);
			case "fallen_forest" -> fallenForest(level, op, x, surface, z, height);
			case "ruin" -> ruin(level, op, x, surface, z);
			case "uplift" -> uplift(level, op, x, surface, z, ground);
			default -> 0;
		};
		if (changed < 0) {
			// дерево не доделано (дедлайн/незагруженный чанк): колонка не считается обработанной
			return -1;
		}
		return changed + treeFallen;
	}

	/**
	 * Настоящая земля колонки. `heightmap` отдаёт верх ствола (листву он не считает) или снежный
	 * слой; спускаемся вниз, пока стоят дерево или снег.
	 */
	private static int groundY(ServerLevel level, int x, int height, int z) {
		int y = height - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (y > level.getMinY() + 1) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.is(Blocks.SNOW) || isTreePart(state) || state.is(BlockTags.LEAVES)) {
				y--;
				continue;
			}
			break;
		}
		return y + 1;
	}

	private static int deadSoil(ServerLevel level, int x, int surface, int z, long seed) {
		int changed = 0;
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		if (!protectedBlock(state) && !state.isAir() && state.getFluidState().isEmpty()
				// грядки и тропинки — работа игрока, а не рельеф: их не штукатурим
				&& !state.is(Blocks.FARMLAND) && !state.is(Blocks.DIRT_PATH)) {
			// Материал — по ячейке 3×3: поблочный хеш давал «перец с солью», а один сплошной
			// COARSE_DIRT — ровный коричневый диск, который читается как /fill.
			// Пропорции как у выгоревшей земли: грубая земля, гравий, корни, подзол
			// выгоревшая земля: разливы грубой земли с вкраплениями гравия и корней.
			// Материал — пятнами по 13 блоков, иначе диск выглядит как посыпка
			Block burned = blotch(x, z, seed + 11, Blocks.COARSE_DIRT, Blocks.GRAVEL,
					Blocks.ROOTED_DIRT, Blocks.PODZOL);
			// в пустыне, бэдлендах и на камне «мёртвая земля» из грубой земли — клякса:
			// выгоревший грунт берёт материал биома, как и обломки
			Block replacement = soil(state) ? burned : rubbleBlock(state, x, z, seed);
			level.setBlock(ground, replacement.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed + clearPlant(level, x, surface, z);
	}

	/** Грунт, который выгорает: трава, земля, подзол, мицелий, мох, грязь. */
	private static boolean soil(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL)
				|| state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)
				|| state.is(Blocks.MUD) || state.is(Blocks.CLAY) || state.is(Blocks.COARSE_DIRT);
	}

	/**
	 * Сносит растения в колонке и всё, что растёт над ними: бамбук и тростник стоят стеной,
	 * и оставленная верхушка падает дропом на мёртвую землю.
	 */
	private static int clearPlant(ServerLevel level, int x, int surface, int z) {
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int top = Math.min(level.getMaxY() - 2, surface + 12);
		for (int y = surface; y <= top; y++) {
			BlockState plant = level.getBlockState(pos.set(x, y, z));
			if (plant.isAir()) {
				if (changed > 0) {
					break;
				}
				continue;
			}
			if (protectedBlock(plant) || plant.blocksMotion() || !plant.getFluidState().isEmpty()
					|| plant.is(BlockTags.RAILS) || plant.is(BlockTags.BUTTONS)
					|| plant.is(BlockTags.PRESSURE_PLATES) || plant.is(BlockTags.ALL_SIGNS)
					|| plant.is(Blocks.TORCH) || plant.is(Blocks.WALL_TORCH)
					|| plant.is(Blocks.SOUL_TORCH) || plant.is(Blocks.SOUL_WALL_TORCH)
					// рукотворная мелочь — не растение: фонарь, цепь, рычаг, редстоун, рамка
					|| plant.is(Blocks.LANTERN) || plant.is(Blocks.SOUL_LANTERN)
					|| plant.is(Blocks.IRON_CHAIN) || plant.is(Blocks.LEVER)
					|| plant.is(Blocks.REDSTONE_TORCH) || plant.is(Blocks.REDSTONE_WALL_TORCH)
					|| plant.is(Blocks.REDSTONE_WIRE) || plant.is(Blocks.TRIPWIRE)
					|| plant.is(Blocks.TRIPWIRE_HOOK) || plant.is(Blocks.FLOWER_POT)
					|| plant.is(Blocks.END_ROD) || plant.is(Blocks.FIRE) || plant.is(Blocks.SOUL_FIRE)) {
				break;
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
	}

	/**
	 * Сносит дерево в радиусе двух блоков: ствол, крону, азалии, грибные шляпы.
	 * Без листвы рядом бревно — это не дерево, а сруб: постройки не трогаем.
	 * Соседние незагруженные чанки не читаем и не пишем — иначе синхронная генерация в обход бюджета.
	 */
	private static int deforest(ServerLevel level, int x, int height, int z) {
		// heightmap не считает листву, поэтому верх ствола — это height-1, а не height
		if (!isTreePart(level.getBlockState(new BlockPos(x, height - 1, z)))) {
			return 0;
		}
		if (!hasFoliage(level, x, height, z)) {
			return 0;
		}
		return deforestForce(level, x, height, z);
	}

	/**
	 * Снос без проверки «это дерево»: нужен на повторе, когда ствол уже снесён прошлым проходом,
	 * а крона осталась. Границы держим те же 9×9.
	 */
	private static int deforestForce(ServerLevel level, int x, int height, int z) {
		int changed = 0;
		// крона дуба/тёмного дуба шире 5×5: с радиусом 2 оставались висящие кольца листвы
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				// дедлайн проверяем внутри: одна колонка с деревом — это 81 колонка и до 48 блоков
				// на каждую, и проход перебирал 5 мс в разы. -1: колонка не доделана, её нельзя
				// считать обработанной, иначе в лесу остаются недоснесённые кроны
				if (passDeadline != 0L && System.nanoTime() > passDeadline) {
					return -1;
				}
				if (!level.getChunkSource().hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					continue;
				}
				int part = clearTreeColumn(level, x + dx, height, z + dz);
				if (part < 0) {
					return -1;
				}
				changed += part;
			}
		}
		return changed;
	}

	/** Листва или грибные шляпы вокруг ствола: по ним дерево отличается от сруба из брёвен. */
	private static boolean hasFoliage(ServerLevel level, int x, int height, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// листва только на уровне верха ствола и выше: иначе стена из брёвен у леса
		// считалась кроной, и clearTreeColumn выносил всю вертикаль
		int bottom = Math.max(level.getMinY() + 1, height - 1);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (!level.getChunkSource().hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					continue;
				}
				for (int y = bottom; y <= height + 2; y++) {
					BlockState state = level.getBlockState(pos.set(x + dx, y, z + dz));
					if (state.is(BlockTags.LEAVES) || state.is(Blocks.RED_MUSHROOM_BLOCK)
							|| state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.MUSHROOM_STEM)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Ствол и всё, что считается деревом: грибная ножка, бамбук, корни мангров не входят в #logs. */
	private static boolean isTreePart(BlockState state) {
		// шляпки грибных деревьев — тоже «дерево»: без них пик и плита врастали в
		// красный блок, и след читался как ошибка генерации на грибном острове
		return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM)
				|| state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
				|| state.is(Blocks.BAMBOO_BLOCK) || state.is(Blocks.MANGROVE_ROOTS);
	}

	private static int clearTreeColumn(ServerLevel level, int x, int height, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// сверху вниз: крона висит и ниже верхушки ствола, а земля — единственный стоп-блок.
		// Высоту берём свою: у соседнего дерева крона выше, и top от чужого ствола её не видел
		int own = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		int top = Math.min(level.getMaxY() - 2, Math.max(height + 8, own + 2));
		int bottom = Math.max(level.getMinY() + 1, height - 40);
		int changed = 0;
		for (int y = top; y >= bottom; y--) {
			// дерево — это до 48 setBlock в одной колонке: проверяем бюджет внутри, иначе
			// проход перелетает 5 мс и лимит блоков. -1: не доделали, колонка не считается
			if (passDeadline != 0L && System.nanoTime() > passDeadline) {
				return -1;
			}
			BlockState state = level.getBlockState(pos.set(x, y, z));
			// азалия — не листва: после сноса ствола куст остаётся висеть в воздухе
			if (isTreePart(state) || state.is(BlockTags.LEAVES) || state.is(Blocks.AZALEA)
					|| state.is(Blocks.FLOWERING_AZALEA)) {
				if (protectedBlock(state)) {
					break;
				}
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				changed++;
				continue;
			}
			// дошли до твёрдой земли — ниже дерева нет
			if (state.blocksMotion()) {
				break;
			}
		}
		return changed;
	}

	/**
	 * Поваленный лес плашмя: ствол сносится, но не исчезает — он ложится на землю в сторону
	 * {@code op.angle}. Это единственный след, который видно за километры через voxy, и при этом
	 * вблизи он читается как «лес положило».
	 */
	private static int fallenForest(ServerLevel level, ScarOp op, int x, int surface, int z, int height) {
		BlockState top = level.getBlockState(new BlockPos(x, height - 1, z));
		if (!isTreePart(top) || !hasFoliage(level, x, height, z)) {
			return 0;
		}
		// бревно — только настоящее бревно: шляпка грибного дерева, уложенная плашмя,
		// давала красную плиту на земле, а не поваленный ствол. Размен: бамбук, корни
		// мангров и грибные стволы поваленный лес тоже не трогает — у них нет бревна
		if (!top.is(BlockTags.LOGS)) {
			return 0;
		}
		Block log = top.getBlock();
		// крона снимается целиком: одна колонка ствола оставляла висеть 8/9 листвы
		int changed = deforestForce(level, x, height, z);
		if (changed < 0) {
			// ствол не доснесён по дедлайну: колонку нельзя считать обработанной, иначе рядом
			// с лежащим бревном останется обрубок
			return -1;
		}
		int hash = (int) ((x * 31L) ^ (z * 17L) ^ op.seed);
		int length = 4 + Math.floorMod(hash, 4);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// укладка начинается со второго блока: на месте ствола остаётся пень, и первое бревно
		// в той же колонке не давало уложить ни одного (place упирался в пень)
		for (int i = 1; i < length; i++) {
			int bx = (int) Math.floor(x + Math.cos(op.angle) * i);
			int bz = (int) Math.floor(z + Math.sin(op.angle) * i);
			if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
				break;
			}
			int columnHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			if (columnHeight <= level.getMinY() + 2 || columnHeight >= level.getMaxY() - 2) {
				break;
			}
			int y = groundY(level, bx, columnHeight, bz);
			BlockState ground = level.getBlockState(pos.set(bx, y - 1, bz));
			if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()
					|| !ground.blocksMotion()) {
				break;
			}
			clearPlant(level, bx, y, bz);
			BlockState place = level.getBlockState(pos.set(bx, y, bz));
			if (!place.isAir() && !place.canBeReplaced()) {
				break;
			}
			BlockState lay = log.defaultBlockState();
			if (log instanceof RotatedPillarBlock pillar) {
				// лежащий ствол: ось вдоль направления падения, а не вертикально
				double ax = Math.abs(Math.cos(op.angle));
				double az = Math.abs(Math.sin(op.angle));
				lay = pillar.defaultBlockState().setValue(RotatedPillarBlock.AXIS,
						ax >= az ? Direction.Axis.X : Direction.Axis.Z);
			}
			level.setBlock(pos, lay, Block.UPDATE_ALL);
			changed++;
		}
		// пень: 1–2 блока ствола у земли, ставится после укладки, иначе первое бревно
		// упирается в него и лес остаётся стоять пнём
		BlockPos.MutableBlockPos stump = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 2; i++) {
			BlockState at = level.getBlockState(stump.set(x, surface + i, z));
			if (at.blocksMotion() || protectedBlock(at)) {
				break;
			}
			level.setBlock(stump, log.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
	}

	/** Обрушенная постройка: стены по периметру с проломами и пол из обломков внутри. */
	private static int ruin(ServerLevel level, ScarOp op, int x, int surface, int z) {
		int changed = 0;
		double dx = x - op.x;
		double dz = z - op.z;
		double distance = Math.hypot(dx, dz);
		if (distance > op.radius) {
			return 0;
		}
		// Прямоугольный контур, а не кольцо: круг из булыжника с прорехами читается как загон,
		// а не как жильё. Полуоси 0.85 и 0.7 радиуса, поворот — угол опа
		double cos = Math.cos(op.angle);
		double sin = Math.sin(op.angle);
		double lx = Math.abs(dx * cos + dz * sin);
		double lz = Math.abs(-dx * sin + dz * cos);
		double halfX = op.radius * 0.85;
		double halfZ = op.radius * 0.70;
		double ex = lx - halfX;
		double ez = lz - halfZ;
		double edge = (ex > 0 || ez > 0)
				? Math.hypot(Math.max(ex, 0.0), Math.max(ez, 0.0))
				: Math.max(ex, ez);
		BlockState ground = level.getBlockState(new BlockPos(x, surface - 1, z));
		// Пол — не ковёр на всю площадь: у брошенного дома остаются полосы у стен и
		// отдельные уцелевшие плиты, середина зарастает. Прежний пол красил 75 %
		// прямоугольника клетками 3×3 и читался как клетчатое одеяло, а не как руина
		int patch = (int) (((x / 5) * 37L) ^ ((z / 5) * 19L) ^ op.seed);
		boolean apron = edge > -Math.min(3.5, op.radius * 0.4);
		boolean slab = Math.floorMod(patch, 9) == 0;
		if (edge < -1.0 && (apron || slab)) {
			if (!protectedBlock(ground) && !ground.isAir() && ground.getFluidState().isEmpty()
					&& !builtBlock(ground)) {
				clearPlant(level, x, surface, z);
				level.setBlock(new BlockPos(x, surface - 1, z),
						ruinFloor(ground, x, z, op.seed).defaultBlockState(), Block.UPDATE_ALL);
				changed++;
			}
		}
		// стены: полоса 1.2 блока по периметру, 3–6 блоков высотой, обвалы кусками по
		// 3 блока вдоль стены — угол и обрыв читаются как «здесь жили и это упало»
		if (Math.abs(edge) > 1.2) {
			return changed;
		}
		double along = lx >= lz ? lz : lx;
		int segment = (int) Math.floor(along / 3.0);
		int roll = Math.floorMod(segment * 31 + (int) (op.seed & 0x7F), 12);
		if (roll < 4) {
			// треть сегментов обвалилась целиком: столбик в один блок среди стен 3–6
			// читался тем же «зубом», который убрали с кромок пика и провала
			return changed;
		}
		if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()) {
			return changed;
		}
		// Материал и высота стены — по сегменту (3 блока вдоль стены), а не по блоку:
		// булыжник, треснувший кирпич, мшистый булыжник и каменный кирпич вперемешку
		// давали шахматку — ту же «странность генерации», от которой ушли у плиты и шипа
		int wallCell = segment * 53 + (int) (op.seed & 0xFF);
		int height = 3 + Math.floorMod(wallCell >> 3, 4);
		Block wall = ruinBlock(ground, x, z, wallCell);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < height; i++) {
			BlockState at = level.getBlockState(pos.set(x, surface + i, z));
			if (protectedBlock(at) || (!at.isAir() && at.blocksMotion())) {
				break;
			}
			level.setBlock(pos, wall.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		// под стеной всегда лежит осыпь: кирпич не стоит на траве без мусора вокруг
		if (Math.floorMod(wallCell, 3) == 0) {
			BlockState below = level.getBlockState(new BlockPos(x, surface - 1, z));
			if (!protectedBlock(below) && below.getFluidState().isEmpty() && !below.isAir()) {
				level.setBlock(new BlockPos(x, surface - 1, z),
						rubbleBlock(below, x, z, op.seed).defaultBlockState(), Block.UPDATE_ALL);
				changed++;
			}
		}
		return changed;
	}

	/** Поднятая плита: пласт земли, вывернутый вверх. Плоский верх — то, что видно за километры. */
	private static int uplift(ServerLevel level, ScarOp op, int x, int surface, int z, BlockState ground) {
		double distance = Math.hypot(x - op.x, z - op.z);
		double falloff = 1.0 - Math.min(1.0, distance / Math.max(0.5, op.radius));
		if (falloff <= 0.0) {
			return 0;
		}
		int hash = (int) ((x * 341873128L) ^ (z * 132897987L) ^ op.seed);
		// палитра и высота — по ячейке 4×4, а не по колонке: «перец с солью» из туфа в траве
		// и ступеньки по 1 блоку читались как ошибка генерации, а не как выдавленная земля
		int cell = ((x >> 2) * 31) ^ ((z >> 2) * 17) ^ (int) op.seed;
		int height = (int) Math.round(op.height * (0.25 + 0.75 * falloff)
				* (0.8 + Math.floorMod(cell, 100) / 250.0));
		height = Math.max(2, Math.min(40, height));
		height -= height % 2;
		if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()
				|| !ground.blocksMotion()) {
			return 0;
		}
		clearPlant(level, x, surface, z);
		// игрок на плите сверху — не повод ставить ему камень в ноги; проверяем весь объём
		// до первого блока, иначе колонка обрывается и остаётся шахта 1×1 на всю высоту
		for (int i = 0; i < height; i++) {
			if (playersNear(level, x, surface + i, z)) {
				return 0;
			}
		}
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < height; i++) {
			BlockState at = level.getBlockState(pos.set(x, surface + i, z));
			if (protectedBlock(at) || (!at.isAir() && at.blocksMotion() && !at.canBeReplaced())) {
				break;
			}
			// верх плиты — грунт, внутри камень: сверху это холм, а не каменный столб.
			// На песке и камне трава сверху выглядела бы приклеенной — берём материал биома
			Block filler;
			if (i == height - 1) {
				if (soil(ground)) {
					filler = blotch(x, z, op.seed, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK);
				} else if (ground.is(BlockTags.SAND)) {
					filler = blotch(x, z, op.seed, Blocks.SAND, Blocks.SANDSTONE);
				} else if (ground.is(BlockTags.TERRACOTTA)) {
					filler = blotch(x, z, op.seed, Blocks.TERRACOTTA, Blocks.STONE);
				} else {
					filler = blotch(x, z, op.seed, Blocks.STONE, Blocks.GRAVEL, Blocks.TUFF);
				}
			} else {
				filler = blotch(x, z, op.seed + 3, Blocks.STONE, Blocks.TUFF, Blocks.DEEPSLATE);
			}
			level.setBlock(pos, filler.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
	}

	private static int rubble(ServerLevel level, int x, int surface, int z, long seed) {
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		// грядки и тропинки — работа игрока: rubble не должен штукатурить ферму
		if (protectedBlock(state) || state.isAir() || state.is(Blocks.FARMLAND)
				|| state.is(Blocks.DIRT_PATH)) {
			return 0;
		}
		Block block = rubbleBlock(state, x, z, seed);
		clearPlant(level, x, surface, z);
		level.setBlock(ground, block.defaultBlockState(), Block.UPDATE_ALL);
		return 1;
	}

	/**
	 * Материал следа пятнами по 10–14 блоков: ведущая порода держит ~70 % площади, остальное
	 * идёт вкраплениями. Хеш по клеткам 2×2 и 3×3 давал «перец с солью» — кашу из кусочков
	 * разного цвета, которая на скриншоте читается как ошибка генерации, а не как порода.
	 */
	private static Block blotch(int x, int z, long seed, Block... blocks) {
		double n = smoothNoise(x, z, 13, seed * 341873128712L + 132897987541L);
		if (blocks.length == 1 || n < 0.7) {
			return blocks[0];
		}
		int rest = blocks.length - 1;
		return blocks[1 + Math.min(rest - 1, (int) ((n - 0.7) / 0.3 * rest))];
	}

	/** Обломки из того же материала, что и грунт: булыжник посреди песка или терракоты — клякса. */
	private static Block rubbleBlock(BlockState ground, int x, int z, long seed) {
		// трава и земля — не камень: на лугу обломки это вывернутый грунт, а не булыжник,
		// иначе каждый хлопок у игрока красит луг серым пятном
		if (soil(ground)) {
			return blotch(x, z, seed, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.GRAVEL);
		}
		if (ground.is(BlockTags.SAND)) {
			return blotch(x, z, seed, Blocks.SANDSTONE, Blocks.SAND, Blocks.GRAVEL);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return blotch(x, z, seed, Blocks.TERRACOTTA, Blocks.GRAVEL);
		}
		if (ground.is(Blocks.GRAVEL)) {
			return blotch(x, z, seed, Blocks.GRAVEL, Blocks.COBBLESTONE);
		}
		if (ground.is(Blocks.CLAY) || ground.is(Blocks.MUD)) {
			return blotch(x, z, seed, Blocks.CLAY, Blocks.GRAVEL);
		}
		if (ground.is(Blocks.MOSS_BLOCK)) {
			return blotch(x, z, seed, Blocks.MOSS_BLOCK, Blocks.TUFF);
		}
		return blotch(x, z, seed, Blocks.COBBLESTONE, Blocks.GRAVEL, Blocks.ANDESITE,
				Blocks.TUFF, Blocks.COBBLED_DEEPSLATE);
	}

	private static int trench(ServerLevel level, ScarOp op, int x, int surface, int z, int depth, boolean lava) {
		// у воды лава гаснет в камень, а траншея заливается сбоку: там нужна обычная трещина
		if (lava && fluidNear(level, x, surface, z)) {
			lava = false;
		}
		// трещина сужается к концам: щель одной глубины обрывается, как /fill. Мелкие
		// трещины (2–3 блока) не трогаем — там сужение съедает всю глубину
		double cos = Math.cos(op.angle);
		double sin = Math.sin(op.angle);
		double along = (x - Math.floor(op.x)) * cos + (z - Math.floor(op.z)) * sin;
		double span = Math.max(1.0, op.length - 1);
		double t = Math.min(1.0, Math.abs(along - span * 0.5) / (span * 0.5));
		int here = depth >= 4 ? Math.max(2, (int) Math.round(depth * (1.0 - 0.5 * t * t))) : depth;
		// трава и цветы в трещине сносятся ванилью с дропом — убираем сами
		clearPlant(level, x, surface, z);
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < here; i++) {
			int y = surface - 1 - i;
			if (y <= level.getMinY() + 1) {
				break;
			}
			BlockState state = level.getBlockState(pos.set(x, y, z));
			// трещина не режет полы и стены: тёсаный камень и доски останавливают её так же, как контейнеры.
			// Жидкость в вынимаемой колонке — стоп: иначе трещина вскрывает водоносный слой и вода
			// встаёт стенкой в щели
			if (protectedBlock(state) || builtBlock(state) || !state.getFluidState().isEmpty()) {
				return changed;
			}
			boolean bottom = i == here - 1;
			BlockState target = bottom && lava ? Blocks.LAVA.defaultBlockState() : Blocks.AIR.defaultBlockState();
			level.setBlock(pos.set(x, y, z), target, Block.UPDATE_ALL);
			changed++;
		}
		if (lava && changed > 0) {
			// остывший шов по кромке: магма и базальт через колонку — светящаяся линия видна
			// с 20–30 блоков и не поджигает лес, в отличие от лавы на поверхности
			int hash = (int) ((x * 73856093L) ^ (z * 19349663L));
			int roll = Math.floorMod(hash, 5);
			if (roll == 0 || roll == 1) {
				BlockPos rim = new BlockPos(x, surface - 1, z);
				BlockState at = level.getBlockState(rim);
				if (!protectedBlock(at) && !builtBlock(at)) {
					// на кромке — базальт (магма жгла бы стоящего рядом), а светящийся шов —
					// магмой на блок ниже: её видно из щели, но ногой не заденешь
					level.setBlock(rim, Blocks.BASALT.defaultBlockState(), Block.UPDATE_ALL);
					changed++;
					if (roll == 0 && depth >= 3) {
						BlockPos glow = new BlockPos(x, surface - 2, z);
						BlockState deep = level.getBlockState(glow);
						if (!protectedBlock(deep) && !builtBlock(deep) && deep.isAir()) {
							level.setBlock(glow, Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
							changed++;
						}
					}
				}
			}
		}
		// разлом посреди леса тоже оставляет мёртвую землю — дерево сносится в applyColumn
		return changed;
	}

	/** Вода или лава рядом с колонкой на уровне грунта. Незагруженный сосед водой не считается. */
	private static boolean fluidNear(ServerLevel level, int x, int surface, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!level.getChunkSource().hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					continue;
				}
				if (!level.getBlockState(new BlockPos(x + dx, surface - 1, z + dz)).getFluidState().isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * След целиком в воде (озеро, река, океан): колонки такого op обрывались по урезу и давали
	 * половину воронки. Планирование идёт без уровня, поэтому проверяем при первом заходе в op.
	 */
	/**
	 * Состояние воды под следом: 2 — вода есть (op пропускаем), 1 — сухо, 0 — не знаю
	 * (чанк не загружен, а бюджета или дедлайна на загрузку нет — повторим позже).
	 * Планирование идёт без уровня, поэтому проверяем при первом заходе в op.
	 */
	private static int wetOpState(ServerLevel level, ScarOp op, AnomalyFieldData data, AnomalyConfig config) {
		double radius = Math.max(1.0, op.radius);
		// у линии точек вдоль длины, а не кольцо вокруг начала: трещина 40–80 блоков
		// переходит ручей в середине, и кольцо у старта этого не видит
		int samples = op.isLine() ? Math.max(5, op.length / 8 + 1) : 13;
		int loads = 0;
		int wet = 0;
		boolean budgetStop = false;
		for (int i = 0; i < samples; i++) {
			double x;
			double z;
			if (op.isLine()) {
				double t = samples <= 1 ? 0.0 : i / (double) (samples - 1);
				x = op.x + Math.cos(op.angle) * op.length * t;
				z = op.z + Math.sin(op.angle) * op.length * t;
			} else {
				// центр, кольца 0.5, 0.75 и 1.0: воронка на берегу касается воды краем,
				// а ручей в середине радиуса не видело ни одно из внутренних колец
				double angle = (i - 1) * Math.PI * 2.0 / 12.0;
				double r = i == 0 ? 0.0 : radius * (i % 3 == 1 ? 0.5 : i % 3 == 2 ? 0.75 : 1.0);
				x = op.x + Math.cos(angle) * r;
				z = op.z + Math.sin(angle) * r;
			}
			int bx = (int) Math.floor(x);
			int bz = (int) Math.floor(z);
			int chunkX = bx >> 4;
			int chunkZ = bz >> 4;
			if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
				// в свежей местности загружен только чанк первой колонки, и все сэмплы молча
				// пропускались — проверка воды не работала вовсе. Генерировать в тик-потоке
				// при живых игроках нельзя: след ждёт, пока чанк загрузится сам
				if (serverHasPlayers(level)) {
					budgetStop = true;
					break;
				}
				if (!chunkBudgetAllows(data, config, AnomalyClock.now())) {
					// суточный лимит генераций: след ждёт следующего дня, а не выбрасывается
					budgetStop = true;
					break;
				}
				if (loads >= Math.max(1, config.chunks_per_tick)
						|| (passDeadline != 0L && System.nanoTime() > passDeadline)) {
					// не хватило тика: «не знаю», повторим в следующем проходе
					return 0;
				}
				level.getChunk(chunkX, chunkZ);
				data.gen_count++;
				loads++;
			}
			int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			if (h <= level.getMinY() + 2) {
				continue;
			}
			if (!level.getBlockState(new BlockPos(bx, h - 1, bz)).getFluidState().isEmpty()) {
				wet++;
				if (wet >= 2) {
					// уже мокро: остальные сэмплы ничего не изменят
					break;
				}
			}
		}
		if (loads > 0) {
			AnomalyField.markDirty();
		}
		if (budgetStop) {
			return -1;
		}
		// одна лужа у края не повод выбрасывать весь след: у прибрежной зоны иначе не остаётся
		// ни одного шрама. Мокрым считаем op, если вода в двух и более точках — там воронка
		// действительно режется по урезу
		return wet >= 2 ? 2 : 1;
	}

	/** Провал: вынимаем колонку на depth блоков, стенки остаются как есть. */
	private static int sinkhole(ServerLevel level, ScarOp op, int x, int surface, int z) {
		double distance = Math.hypot(x - op.x, z - op.z);
		int cell = ((x >> 1) * 31) ^ ((z >> 1) * 17) ^ (int) op.seed;
		// кромка провала: грунт вывернут и обломан. Ровная круглая яма с вертикальными
		// стенками с 20–200 блоков не читается вовсе. Губа ровно в один блок и только
		// у настоящих провалов: прежние 1.2 радиуса съедали почти весь диск 3×3, и провал
		// оставался шахтой 1×1 с обломками вокруг
		// кромка провала: грунт вывернут и обломан. Ровная круглая яма с вертикальными
		// стенками с 20–200 блоков не читается вовсе. Губа нужна при любом радиусе:
		// у провала r=3 без неё оставалась шахта 6×6 с ровными стенками, выглядящая как /fill
		double lip = op.radius >= 4.0 ? 1.2 : 0.9;
		if (op.radius >= 2.0 && distance > op.radius - lip) {
			BlockState ground = level.getBlockState(new BlockPos(x, surface - 1, z));
			if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()
					|| isFragileSurface(ground)) {
				return 0;
			}
			Block rim = rubbleBlock(ground, x, z, op.seed);
			level.setBlock(new BlockPos(x, surface - 1, z), rim.defaultBlockState(), Block.UPDATE_ALL);
			// валунов сверху нет: одиночный блок на траве читался как зуб, а не как
			// обвал. Кромку держит сама вывернутая земля (surface - 1), и её достаточно
			clearPlant(level, x, surface, z);
			return 1;
		}
		clearPlant(level, x, surface, z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// глубина гуляет по клеткам 2×2 и мельчает к краю: ровное дно читалось как /fill
		double taper = 0.7 + 0.3 * (1.0 - distance / Math.max(0.5, op.radius));
		// глубина — из ScarOp.sinkholeDepth (не меньше 0.9 радиуса): по ней же
		// считается бюджет, иначе зона пишет в мир больше, чем планировала
		int depth = Math.max(1,
				(int) Math.round(op.sinkholeDepth() * taper * (0.8 + Math.floorMod(cell, 100) / 250.0)));
		// у самой стенки колонна копается на блок меньше, а у дна кладётся обломок: осыпь
		// со стены, а не подрезанный край
		boolean scree = op.radius >= 3.0 && distance > op.radius - lip - 1.0;
		if (scree) {
			depth = Math.max(1, depth - 1);
		}
		int changed = 0;
		for (int i = 0; i < depth; i++) {
			int y = surface - 1 - i;
			if (y <= level.getMinY()) {
				break;
			}
			BlockState state = level.getBlockState(pos.set(x, y, z));
			// провал на леднике — законный след (шапка из снежного блока и пакета льда), но
			// ICE трогать нельзя: под ним вода, и дыра в припае заливается сбоку
			if (protectedBlock(state) || builtBlock(state)
					|| (isFragileSurface(state)
					&& (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE) || state.is(Blocks.POWDER_SNOW)))
					|| !state.getFluidState().isEmpty()) {
				break;
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		// осыпь у стенки: обломки лежат на дне, стенка не обрывается в пустоту
		if (scree && changed > 0) {
			BlockPos floor = new BlockPos(x, surface - 1 - depth, z);
			BlockState at = level.getBlockState(floor);
			if (at.isAir() && !protectedBlock(at)) {
				level.setBlock(floor, rubbleBlock(level.getBlockState(new BlockPos(x, surface - 2, z)), x, z, op.seed)
						.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		return changed;
	}

	/** Пол руины по грунту: в пустыне песчаник, в бэдлендах терракота, в болоте грязевой кирпич. */
	private static Block ruinFloor(BlockState ground, int x, int z, long seed) {
		if (ground.is(BlockTags.SAND)) {
			return blotch(x, z, seed, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return blotch(x, z, seed, Blocks.TERRACOTTA, Blocks.PACKED_MUD);
		}
		if (ground.is(Blocks.MUD) || ground.is(Blocks.CLAY)) {
			return blotch(x, z, seed, Blocks.PACKED_MUD, Blocks.MUD_BRICKS);
		}
		if (ground.is(Blocks.DEEPSLATE)) {
			return blotch(x, z, seed, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_TILES);
		}
		return blotch(x, z, seed, RUIN_FLOOR);
	}

	/** Материал стен руины по грунту: в пустыне песчаник, в болоте грязевой кирпич. */
	private static Block ruinBlock(BlockState ground, int x, int z, int salt) {
		if (ground.is(BlockTags.SAND)) {
			return blotch(x, z, salt, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return blotch(x, z, salt, Blocks.TERRACOTTA, Blocks.BRICKS);
		}
		if (ground.is(Blocks.DEEPSLATE)) {
			return blotch(x, z, salt, Blocks.DEEPSLATE_BRICKS, Blocks.COBBLED_DEEPSLATE);
		}
		if (ground.is(Blocks.MUD) || ground.is(Blocks.CLAY)) {
			return blotch(x, z, salt, Blocks.MUD_BRICKS, Blocks.BRICKS);
		}
		if (ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MYCELIUM)) {
			return blotch(x, z, salt, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE);
		}
		return blotch(x, z, salt, RUIN_WALLS);
	}

	/** Материал шипа по биому: след из «местного» камня, а не серый булыжник везде. */
	private static Block spikeBlock(BlockState ground, int x, int z, long seed) {
		if (ground.is(BlockTags.SAND)) {
			return blotch(x, z, seed, Blocks.SANDSTONE, Blocks.STONE);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return blotch(x, z, seed, Blocks.TERRACOTTA, Blocks.STONE);
		}
		if (ground.is(Blocks.GRAVEL)) {
			return blotch(x, z, seed, Blocks.STONE, Blocks.GRAVEL);
		}
		if (ground.is(Blocks.MUD) || ground.is(Blocks.CLAY)) {
			return blotch(x, z, seed, Blocks.PACKED_MUD, Blocks.STONE);
		}
		if (ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MYCELIUM)) {
			return blotch(x, z, seed, Blocks.STONE, Blocks.TUFF);
		}
		if (ground.is(Blocks.DEEPSLATE)) {
			return blotch(x, z, seed, Blocks.DEEPSLATE, Blocks.TUFF);
		}
		return blotch(x, z, seed, SPIKE_BLOCKS);
	}

	private static int spike(ServerLevel level, ScarOp op, int x, int surface, int z, BlockState ground) {
		double dx = x - op.x;
		double dz = z - op.z;
		// контур пика гуляет по шуму 8 блоков: ровный конус с боку читается как
		// поставленный модом обелиск, а у вырванной скалы рёбра и наплывы
		double wobble = 1.0 + 0.4 * (smoothNoise(x, z, 8, op.seed + 5) - 0.5);
		double falloff = 1.0 - Math.min(1.0,
				Math.sqrt(dx * dx + dz * dz) * wobble / Math.max(0.5, op.radius));
		// высота и камень — по ячейке 2×2, а не по колонке: «перец с солью» на грани пика
		// читался как ошибка генерации (то же уже сделано для плиты)
		int cell = ((x >> 1) * 31) ^ ((z >> 1) * 17) ^ (int) op.seed;
		// Профиль шипа ведём в ноль на границе обломочной юбки (falloff = 0.28), иначе у
		// подошвы получалась вертикальная стенка в 0.26·h — при h=120 это 31 блок камня
		double base = Math.max(0.0, (falloff - 0.28) / 0.72);
		double profile = 0.95 * Math.pow(base, 1.2);
		// шум высоты билинейный по сетке 5 блоков: ступени, привязанные к сетке 2 блока,
		// читались как набранная из кубиков крышка
		double jitter = 0.88 + 0.24 * smoothNoise(x, z, 5, op.seed);
		// высота (с кэпом 2.5 радиуса) — из ScarOp.spikeHeight: по ней же считается бюджет
		int height = (int) Math.round(op.spikeHeight() * profile * jitter);
		// у самой юбки колонна не встаёт вовсе: камни вырастают из обломков, а не из травы.
		// Проверка после ветки юбки — иначе обломки оставались бы только в кольце 0.24..0.28
		if (height < 1) {
			height = falloff > 0.24 ? 1 : 0;
		}
		height = Math.max(0, Math.min(MAX_HEIGHT, height));
		if (height <= 0 && falloff >= 0.28) {
			return 0;
		}
		// Кромка: вывернутый грунт и обломки вокруг основания, 2–3 клетки в ширину и с
		// просветами. Без него конус растёт прямо из целой травы, сплошной плитой — насыпь
		if (falloff < 0.28) {
			if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()) {
				return 0;
			}
			int skip = falloff > 0.16 ? 2 : 3;
			if (Math.floorMod(cell, skip) == 0) {
				return 0;
			}
			Block rim = rubbleBlock(ground, x, z, op.seed);
			level.setBlock(new BlockPos(x, surface - 1, z), rim.defaultBlockState(), Block.UPDATE_ALL);
			// над кромкой камней не ставим: одиночный блок на траве читается как зуб
			clearPlant(level, x, surface, z);
			return 1;
		}
		// камень из того же биома: песчаник в пустыне, терракота в бэдлендах, туф на камне.
		// Серый булыжник посреди песка читается как чужая постройка, а не как след аномалии
		Block block = spikeBlock(ground, x, z, op.seed);
		// нижняя треть — тот же камень, но темнее: у настоящей скалы основание всегда в тени
		Block core = spikeCore(ground, x, z, op.seed);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		int top = Math.min(level.getMaxY() - 2, surface + height);
		// игрок выше земли (на скале, в полёте) тоже не должен получить камень в голову.
		// Проверяем весь объём ДО первого блока: иначе колонка обрывается на высоте головы
		// и остаётся обрубок, а колонка считается обработанной
		for (int y = surface; y <= top; y++) {
			if (playersNear(level, x, y, z)) {
				return 0;
			}
		}
		int shadow = surface + Math.max(1, (int) Math.round((top - surface) * 0.3));
		for (int y = surface; y <= top; y++) {
			// масса камня не может съесть весь тик: у пика до 150 блоков в одной колонке
			if (passDeadline != 0L && (changed & 7) == 7 && System.nanoTime() > passDeadline) {
				return changed > 0 ? changed : -1;
			}
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (protectedBlock(state)) {
				break;
			}
			if (!state.isAir() && state.blocksMotion()) {
				// столб растёт только по воздуху: раньше он перепрыгивал крону и торчал из неё
				break;
			}
			// внутренним блокам не нужны обновления соседей и пересвет: их ставят тысячи
			int flags = y < top ? Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE : Block.UPDATE_ALL;
			level.setBlock(pos.set(x, y, z), (y < shadow ? core : block).defaultBlockState(), flags);
			changed++;
		}
		return changed;
	}

	/**
	 * Билинейный шум по сетке {@code scale} блоков: 0..1, гладкий. Ячеечный хеш давал
	 * ступени, привязанные к сетке, — на пике это читалось как набранная из кубиков крышка.
	 */
	private static double smoothNoise(int x, int z, int scale, long seed) {
		double fx = x / (double) scale;
		double fz = z / (double) scale;
		int x0 = (int) Math.floor(fx);
		int z0 = (int) Math.floor(fz);
		double tx = fx - x0;
		double tz = fz - z0;
		tx = tx * tx * (3.0 - 2.0 * tx);
		tz = tz * tz * (3.0 - 2.0 * tz);
		double a = lattice(x0, z0, seed);
		double b = lattice(x0 + 1, z0, seed);
		double c = lattice(x0, z0 + 1, seed);
		double d = lattice(x0 + 1, z0 + 1, seed);
		return (a * (1.0 - tx) + b * tx) * (1.0 - tz) + (c * (1.0 - tx) + d * tx) * tz;
	}

	private static double lattice(int x, int z, long seed) {
		long h = x * 374761393L + z * 668265263L + seed * 1442695040888963407L;
		h = (h ^ (h >>> 13)) * 1274126177L;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
	}

	/** Нижний пояс пика: тот же камень биома, но тёмный — тень у основания. */
	private static Block spikeCore(BlockState ground, int x, int z, long seed) {
		if (ground.is(BlockTags.SAND)) {
			return blotch(x, z, seed, Blocks.STONE, Blocks.SANDSTONE);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return blotch(x, z, seed, Blocks.TERRACOTTA, Blocks.STONE);
		}
		if (ground.is(Blocks.DEEPSLATE)) {
			return Blocks.DEEPSLATE;
		}
		return blotch(x, z, seed, Blocks.TUFF, Blocks.STONE);
	}

	/**
	 * Поверхность, на которой след выглядит мусором: лёд (льдины холодного океана, ледяные пики),
	 * снежный блок, powder snow. Снежный слой ({@code SNOW}) сюда не входит: он лежит на обычной
	 * земле, и тайга/снежная равнина без следов — это не «красиво», а пусто.
	 */
	private static boolean isFragileSurface(BlockState state) {
		return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
				|| state.is(Blocks.FROSTED_ICE) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW);
	}

	/** Чужие постройки — не рельеф: доски, кирпич, стекло, бетон, шерсть, медь и тёсаный камень. */
	static boolean builtBlock(BlockState state) {
		return state.is(BlockTags.PLANKS) || state.is(BlockTags.STONE_BRICKS) || state.is(BlockTags.WOOL)
				|| state.is(BlockTags.CONCRETE) || state.is(BlockTags.CONCRETE_POWDERS) || state.is(BlockTags.COPPER)
				// стены, заборы, двери, люки: без них следы сносили забор вокруг базы
				|| state.is(BlockTags.WALLS) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
				|| state.is(Blocks.BRICKS) || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
				|| state.is(Blocks.TINTED_GLASS) || state.is(Blocks.SANDSTONE) || state.is(Blocks.SMOOTH_SANDSTONE)
				|| state.is(Blocks.CUT_SANDSTONE) || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.MUD_BRICKS)
				|| state.is(Blocks.DEEPSLATE_BRICKS) || state.is(Blocks.DEEPSLATE_TILES)
				|| state.is(Blocks.POLISHED_DEEPSLATE) || state.is(Blocks.COBBLESTONE)
				|| state.is(Blocks.MOSSY_COBBLESTONE) || state.is(BlockTags.SLABS)
				|| state.is(BlockTags.STAIRS)
				// камень, который в природе не встречается: кварц, пурпур, призмарин, чернокамень
				|| state.is(Blocks.QUARTZ_BLOCK) || state.is(Blocks.SMOOTH_QUARTZ) || state.is(Blocks.PURPUR_BLOCK)
				|| state.is(Blocks.PRISMARINE) || state.is(Blocks.PRISMARINE_BRICKS) || state.is(Blocks.DARK_PRISMARINE)
				|| state.is(Blocks.BLACKSTONE) || state.is(Blocks.POLISHED_BLACKSTONE)
				|| state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.RED_NETHER_BRICKS)
				|| state.is(Blocks.IRON_BARS) || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.BOOKSHELF)
				|| state.is(Blocks.SCAFFOLDING) || state.is(Blocks.BEEHIVE);
	}

	static boolean protectedBlock(BlockState state) {
		if (state.hasBlockEntity()) {
			return true;
		}
		return state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
				|| state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.REINFORCED_DEEPSLATE)
				|| state.is(Blocks.BARRIER) || state.is(Blocks.END_PORTAL_FRAME)
				|| state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)
				// lodestone — алтарь рейда: следы не должны съедать его, иначе рейд невозможен
				|| state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.LODESTONE)
				|| state.is(Blocks.BUDDING_AMETHYST)
				|| state.is(Blocks.BEACON) || state.is(Blocks.SPAWNER);
	}

	// --- микрособытия: то, что делает мир живым между большими следами ---

	/**
	 * Игрок внутри объёма следа: мир не должен меняться под ногами, и колонку нельзя просто
	 * пропустить — в воронке остался бы столб-остров с вертикальными стенками. Такой op
	 * откладывается целиком, пока игрок не выйдет.
	 */
	static boolean opHasPlayer(ServerLevel level, ScarOp op) {
		for (ServerPlayer player : level.players()) {
			// окно по Y: игрок в шахте под следом не должен замораживать опа наверху
			int bx = (int) Math.floor(op.x);
			int bz = (int) Math.floor(op.z);
			if (level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
				double oy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
				if (Math.abs(player.getY() - oy) > 24.0) {
					continue;
				}
			}
			double dx = player.getX() - op.x;
			double dz = player.getZ() - op.z;
			if (op.isLine()) {
				// расстояние до отрезка вдоль angle длиной length
				double cos = Math.cos(op.angle);
				double sin = Math.sin(op.angle);
				double along = Math.max(0.0, Math.min(op.length, dx * cos + dz * sin));
				dx -= cos * along;
				dz -= sin * along;
				if (Math.hypot(dx, dz) <= 4.0) {
					return true;
				}
				continue;
			}
			if (Math.hypot(dx, dz) <= Math.max(0.5, op.radius) + 3.0) {
				return true;
			}
		}
		return false;
	}

	/** Дно уже выбитой воронки: повторный хлопок в ту же точку просаживал землю без предела. */
	static boolean isCraterFloor(BlockState state) {
		// только булыжник: туф, андезит и песчаник встречаются в природе, и по ним нельзя
		// отличить выбитое дно от обычного грунта
		return state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
				|| state.is(Blocks.MOSSY_COBBLESTONE);
	}

	/** Материал пломбы дна воронки: узнаваемый «уже копано», чтобы хлопок не углублял яму. */
	private static BlockState seal(BlockState ground) {
		return (ground.is(Blocks.DEEPSLATE) ? Blocks.COBBLED_DEEPSLATE : Blocks.COBBLESTONE)
				.defaultBlockState();
	}

	/** Близко ли игрок к клетке: след не должен ставить камень в игрока (удушье) или вынимать
	 * блок у него из-под ног. Радиус в 3 блока — это «вплотную», дальше игрок видит и успевает.
	 */
	static boolean playersNear(ServerLevel level, int x, int y, int z) {
		for (ServerPlayer player : level.players()) {
			if (Math.abs(player.getX() - (x + 0.5)) <= 3.0 && Math.abs(player.getZ() - (z + 0.5)) <= 3.0
					&& Math.abs(player.getY() - y) <= 6.0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Маленький кратер от хлопка или молнии: 1–2 блока в глубину, обломки на дне.
	 * Живёт только возле игрока, где чанк уже загружен, и подчиняется тем же запретам, что следы.
	 * {@code nearY} — высота игрока: если рельеф на 4+ блока выше, игрок в шахте и событие
	 * вырезало бы крышу у него над головой (или правило бы невидимый пол в 50 блоках наверху).
	 */
	public static int microCrater(ServerLevel level, double x, double z, double radius, int depth, double nearY) {
		if (!microBudget(level)) {
			return 0;
		}
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return 0;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int side = (int) Math.ceil(radius) + 1;
		int changed = 0;
		for (int dx = -side; dx <= side; dx++) {
			for (int dz = -side; dz <= side; dz++) {
				double distance = Math.sqrt(dx * dx + dz * dz);
				// внешний блок — кромка: грунт вывернут и обломан, иначе дыра 3×3 в траве
				// читается как выкопанная лопатой
				if (distance > radius + 1.0) {
					continue;
				}
				int x0 = bx + dx;
				int z0 = bz + dz;
				if (!level.getChunkSource().hasChunk(x0 >> 4, z0 >> 4)) {
					continue;
				}
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0);
				if (surface <= level.getMinY() + 1 || surface >= level.getMaxY() - 2
						|| surface > nearY + 4.0) {
					continue;
				}
				BlockState ground = level.getBlockState(pos.set(x0, surface - 1, z0));
				if (protectedBlock(ground) || builtBlock(ground) || isFragileSurface(ground)
						|| !ground.getFluidState().isEmpty() || isCraterFloor(ground)) {
					continue;
				}
				// кромка: грунт вывернут и обломан
				if (distance > radius - 0.4) {
					if (!playersNear(level, x0, surface, z0)) {
						level.setBlock(pos.set(x0, surface - 1, z0),
								rubbleBlock(ground, x0, z0, 20260101L).defaultBlockState(), Block.UPDATE_ALL);
						clearPlant(level, x0, surface, z0);
						changed++;
					}
					continue;
				}
				if (playersNear(level, x0, surface, z0)) {
					continue;
				}
				clearPlant(level, x0, surface, z0);
				int dug = surface;
				for (int i = 0; i < depth; i++) {
					int y = surface - 1 - i;
					if (y <= level.getMinY()) {
						break;
					}
					BlockState state = level.getBlockState(pos.set(x0, y, z0));
					// под верхним грунтом может стоять сундук, доски или лёд — это не выкапываем
					if (protectedBlock(state) || builtBlock(state) || isFragileSurface(state)
							|| !state.getFluidState().isEmpty()) {
						break;
					}
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					changed++;
					dug = y;
				}
				// дно воронки пломбируем всегда: иначе следующий хлопок видит новый грунт ниже
				// и воронка углубляется без предела (у стоянки выгрызается терраса). Пломба —
				// первый НЕвыкопанный блок под последней вынутой клеткой
				if (dug < surface) {
					BlockPos floorPos = new BlockPos(x0, Math.max(level.getMinY() + 1, dug - 1), z0);
					BlockState floor = level.getBlockState(floorPos);
					if (!protectedBlock(floor) && !builtBlock(floor) && !floor.isAir()) {
						level.setBlock(floorPos, seal(floor), Block.UPDATE_ALL);
					}
				}
			}
		}
		return changed;
	}

	/** Гарь от молнии: мёртвая земля и снесённые растения в маленьком пятне. */
	public static int scorch(ServerLevel level, double x, double z, double radius, double nearY) {
		if (!microBudget(level)) {
			return 0;
		}
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return 0;
		}
		int side = (int) Math.ceil(radius);
		int changed = 0;
		for (int dx = -side; dx <= side; dx++) {
			for (int dz = -side; dz <= side; dz++) {
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				int x0 = bx + dx;
				int z0 = bz + dz;
				if (!level.getChunkSource().hasChunk(x0 >> 4, z0 >> 4)) {
					continue;
				}
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0);
				if (surface <= level.getMinY() + 1 || surface >= level.getMaxY() - 2
						|| surface > nearY + 4.0) {
					continue;
				}
				BlockState ground = level.getBlockState(new BlockPos(x0, surface - 1, z0));
				if (protectedBlock(ground) || builtBlock(ground) || isFragileSurface(ground)
						|| !ground.getFluidState().isEmpty()) {
					continue;
				}
				changed += deadSoil(level, x0, surface, z0, 0L);
			}
		}
		return changed;
	}

	/** Наледь от холода: слой снега на твёрдой земле, не на листве и не на кактусах. */
	public static int frost(ServerLevel level, double x, double z, double radius, double nearY) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return 0;
		}
		int side = (int) Math.ceil(radius);
		int changed = 0;
		for (int dx = -side; dx <= side; dx++) {
			for (int dz = -side; dz <= side; dz++) {
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				int x0 = bx + dx;
				int z0 = bz + dz;
				if (!level.getChunkSource().hasChunk(x0 >> 4, z0 >> 4)) {
					continue;
				}
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0);
				if (surface <= level.getMinY() + 1 || surface >= level.getMaxY() - 2
						|| surface > nearY + 4.0) {
					continue;
				}
				BlockState ground = level.getBlockState(new BlockPos(x0, surface - 1, z0));
				if (protectedBlock(ground) || builtBlock(ground) || isFragileSurface(ground)
						|| !ground.getFluidState().isEmpty() || !ground.blocksMotion()
						|| ground.is(BlockTags.LEAVES) || ground.is(Blocks.CACTUS)) {
					continue;
				}
				// наледь ложится на голую землю: трава и цветы убираются, иначе снег лежит под ними
				clearPlant(level, x0, surface, z0);
				if (level.getBlockState(new BlockPos(x0, surface, z0)).isAir()) {
					level.setBlock(new BlockPos(x0, surface, z0), Blocks.SNOW.defaultBlockState(),
							Block.UPDATE_ALL);
					changed++;
				}
			}
		}
		return changed;
	}

	/** Сколько блоков ещё в очереди: для команды и для тестов. */
	public static int pendingBlocks(Anomaly anomaly) {
		if (anomaly.scars == null || anomaly.scar_next >= anomaly.scars.size()) {
			return 0;
		}
		int sum = 0;
		for (int i = anomaly.scar_next; i < anomaly.scars.size(); i++) {
			ScarOp op = anomaly.scars.get(i);
			if (i == anomaly.scar_next) {
				// текущий след уже частично пройден — не показываем его целиком второй раз
				int count = op.columnCount();
				int left = Math.max(0, count - anomaly.scar_cursor);
				sum += count > 0 ? (int) ((long) op.blocks * left / count) : 0;
				continue;
			}
			sum += op.blocks;
		}
		return sum;
	}
}















