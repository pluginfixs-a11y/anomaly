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
		double target = budget * (0.4 + rnd.nextDouble() * 0.5);
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
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// поваленный лес плашмя: самое читаемое издалека, а вблизи — стволы лежат, кроны снесены
		if (dangerous && remaining > 400.0 && roll < 0.88) {
			ScarOp op = ScarOp.forest(spot[0], spot[1], 10.0 + rnd.nextDouble() * 14.0, rnd.nextLong());
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
			List<ScarOp> planned = planAt(pos[0], pos[1], Math.min(anomaly.r0, 900.0), cls,
					new Random(anomaly.id * 31L + (long) (moved * 7.0)), false, anomaly.components);
			// остаток бюджета зоны, а не фиксированная доля: план при рождении уже взял 0.4–0.9,
			// и дрейфовые добавки утраивали обещанный damageBudget
			double budget = AnomalyClass.damageBudget(anomaly.r0, cls) - plannedBlocks(anomaly.scars);
			if (budget <= 0.0) {
				continue;
			}
			double used = 0.0;
			for (ScarOp op : planned) {
				if (used + op.blocks > budget || anomaly.scars.size() >= MAX_OPS) {
					break;
				}
				if (overlaps(anomaly.scars, op)) {
					continue;
				}
				anomaly.scars.add(op);
				used += op.blocks;
				changed = true;
			}
		}
		return changed;
	}

	// --- применение ---

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

	/** С какой записи начинать следующий проход: строгий FIFO морил голодом всё поле за первой зоной. */
	private static int rotate;
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
					// бюджет проверяем ДО загрузки чанка: getChunk — синхронная генерация
					if (blocksThisTick >= config.blocks_per_tick || columnsThisTick >= COLUMNS_PER_PASS
							|| System.nanoTime() > deadline) {
						finished = false;
						break;
					}
					int chunkX = column[0] >> 4;
					int chunkZ = column[1] >> 4;
					if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
						// суточный лимит: остаток плана ждёт следующего дня, а не съедает worldgen.
						// ВАЖНО: сдвигаем очередь — иначе следующая зона тоже упрётся в лимит
						// и всё поле замирает до смены полевых суток
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
						applied = applyColumn(level, op, column[0], column[1]);
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
				if (wetSkip) {
					// след целиком в воде: пропускаем op, а не режем берег половинками
					anomaly.scar_next++;
					anomaly.scar_cursor = 0;
					continue;
				}
				if (!finished) {
					// уперлись в лимит/бюджет чанков — идём к следующей зоне, иначе всё поле
					// ждёт смены суток; уперлись в блоки/время — продолжаем с этой же
					rotate = limitHit ? (index + 1) % size : index;
					return progressed;
				}				anomaly.scar_next++;
				anomaly.scar_cursor = 0;
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
		// игрок в объёме следа: откладываем op целиком, иначе в провале остаётся столб-остров
		if (opHasPlayer(level, op)) {
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
			case "crack" -> trench(level, x, surface, z, op.depth, false);
			case "lava_fault" -> trench(level, x, surface, z, op.depth, true);
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
			int hash = (int) ((x * 73856093L) ^ (z * 19349663L) ^ seed);
			// в пустыне, бэдлендах и на камне «мёртвая земля» из грубой земли — клякса:
			// выгоревший грунт берёт материал биома, как и обломки
			Block replacement = soil(state) ? Blocks.COARSE_DIRT : rubbleBlock(state, hash);
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
		return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.BAMBOO_BLOCK)
				|| state.is(Blocks.MANGROVE_ROOTS);
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
		int hash = (int) ((x * 73856093L) ^ (z * 19349663L) ^ op.seed);
		int changed = 0;
		double distance = Math.hypot(x - op.x, z - op.z);
		if (distance > op.radius) {
			return 0;
		}
		// пол внутри: обломки, а не дискотека из случайных столбиков
		if (Math.floorMod(hash, 3) != 0) {
			BlockPos ground = new BlockPos(x, surface - 1, z);
			BlockState state = level.getBlockState(ground);
			if (!protectedBlock(state) && !state.isAir() && state.getFluidState().isEmpty()
					&& !builtBlock(state)) {
				clearPlant(level, x, surface, z);
				level.setBlock(ground, pick(hash, RUIN_FLOOR).defaultBlockState(), Block.UPDATE_ALL);
				changed++;
			}
		}
		// стены: кольцо в один-полтора блока по периметру, 2–4 блока высотой, с проломами.
		// Раньше стены ставились по hash%3 по всей площади — получался гравий с частоколом
		double ring = Math.abs(distance - op.radius * 0.8);
		if (ring > 1.5) {
			return changed;
		}
		if (Math.floorMod(hash >> 7, 6) == 0) {
			// пролом: в стене должно быть видно, что она обрушена
			return changed;
		}
		int height = 2 + Math.floorMod(hash >> 3, 3);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < height; i++) {
			BlockState at = level.getBlockState(pos.set(x, surface + i, z));
			if (protectedBlock(at) || (!at.isAir() && at.blocksMotion())) {
				break;
			}
			level.setBlock(pos, pick(hash + i * 31, RUIN_WALLS).defaultBlockState(), Block.UPDATE_ALL);
			changed++;
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
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < height; i++) {
			// игрок на плите сверху — не повод ставить ему камень в ноги; колонку пропускаем
			// целиком, иначе останется шахта 1×1 на всю высоту
			if (playersNear(level, x, surface + i, z)) {
				return 0;
			}
			BlockState at = level.getBlockState(pos.set(x, surface + i, z));
			if (protectedBlock(at) || (!at.isAir() && at.blocksMotion() && !at.canBeReplaced())) {
				break;
			}
			// верх плиты — грунт, внутри камень: сверху это холм, а не каменный столб.
			// На песке и камне трава сверху выглядела бы приклеенной — берём материал биома
			Block filler;
			if (i == height - 1) {
				if (soil(ground)) {
					filler = pick(cell, Blocks.COARSE_DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK);
				} else if (ground.is(BlockTags.SAND)) {
					filler = pick(cell, Blocks.SAND, Blocks.SANDSTONE);
				} else if (ground.is(BlockTags.TERRACOTTA)) {
					filler = pick(cell, Blocks.TERRACOTTA, Blocks.STONE);
				} else {
					filler = pick(cell, Blocks.STONE, Blocks.GRAVEL, Blocks.TUFF);
				}
			} else {
				filler = pick(cell, Blocks.STONE, Blocks.TUFF, Blocks.DEEPSLATE);
			}
			level.setBlock(pos, filler.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
	}

	private static int rubble(ServerLevel level, int x, int surface, int z, long seed) {
		int hash = (int) ((x * 73856093L) ^ (z * 19349663L) ^ seed);
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		// грядки и тропинки — работа игрока: rubble не должен штукатурить ферму
		if (protectedBlock(state) || state.isAir() || state.is(Blocks.FARMLAND)
				|| state.is(Blocks.DIRT_PATH)) {
			return 0;
		}
		Block block = rubbleBlock(state, hash);
		clearPlant(level, x, surface, z);
		level.setBlock(ground, block.defaultBlockState(), Block.UPDATE_ALL);
		return 1;
	}

	/** Обломки из того же материала, что и грунт: булыжник посреди песка или терракоты — клякса. */
	private static Block rubbleBlock(BlockState ground, int hash) {
		// трава и земля — не камень: на лугу обломки это вывернутый грунт, а не булыжник,
		// иначе каждый хлопок у игрока красит луг серым пятном
		if (soil(ground)) {
			return pick(hash, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.GRAVEL);
		}
		if (ground.is(BlockTags.SAND)) {
			return pick(hash, Blocks.SANDSTONE, Blocks.SANDSTONE, Blocks.GRAVEL);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return pick(hash, Blocks.TERRACOTTA, Blocks.GRAVEL);
		}
		if (ground.is(Blocks.GRAVEL)) {
			return pick(hash, Blocks.GRAVEL, Blocks.COBBLESTONE);
		}
		if (ground.is(Blocks.CLAY) || ground.is(Blocks.MUD)) {
			return pick(hash, Blocks.CLAY, Blocks.GRAVEL);
		}
		if (ground.is(Blocks.MOSS_BLOCK)) {
			return pick(hash, Blocks.MOSS_BLOCK, Blocks.TUFF);
		}
		return pick(hash, Blocks.COBBLESTONE, Blocks.GRAVEL, Blocks.ANDESITE, Blocks.TUFF,
				Blocks.COBBLED_DEEPSLATE);
	}

	private static Block pick(int hash, Block... blocks) {
		return blocks[Math.floorMod(hash, blocks.length)];
	}

	private static int trench(ServerLevel level, int x, int surface, int z, int depth, boolean lava) {
		// у воды лава гаснет в камень, а траншея заливается сбоку: там нужна обычная трещина
		if (lava && fluidNear(level, x, surface, z)) {
			lava = false;
		}
		// трава и цветы в трещине сносятся ванилью с дропом — убираем сами
		clearPlant(level, x, surface, z);
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < depth; i++) {
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
			boolean bottom = i == depth - 1;
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
				// пропускались — проверка воды не работала вовсе
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
		// кромка провала: грунт вывернут и обломан. Ровная круглая яма с вертикальными
		// стенками с 20–200 блоков не читается вовсе
		double distance = Math.hypot(x - op.x, z - op.z);
		if (op.radius >= 3.0 && distance > op.radius - 1.2) {
			BlockState ground = level.getBlockState(new BlockPos(x, surface - 1, z));
			if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()
					|| isFragileSurface(ground)) {
				return 0;
			}
			int hash = (int) ((x * 73856093L) ^ (z * 19349663L) ^ op.seed);
			level.setBlock(new BlockPos(x, surface - 1, z),
					rubbleBlock(ground, hash).defaultBlockState(), Block.UPDATE_ALL);
			clearPlant(level, x, surface, z);
			return 1;
		}
		clearPlant(level, x, surface, z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		int depth = Math.max(1, Math.min(10, op.depth));
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
		return changed;
	}

	/** Материал шипа по биому: след из «местного» камня, а не серый булыжник везде. */
	private static Block spikeBlock(BlockState ground, int hash) {
		if (ground.is(BlockTags.SAND)) {
			return pick(hash, Blocks.SANDSTONE, Blocks.SANDSTONE, Blocks.STONE);
		}
		if (ground.is(BlockTags.TERRACOTTA)) {
			return pick(hash, Blocks.TERRACOTTA, Blocks.STONE);
		}
		if (ground.is(Blocks.GRAVEL)) {
			return pick(hash, Blocks.STONE, Blocks.GRAVEL);
		}
		if (ground.is(Blocks.MUD) || ground.is(Blocks.CLAY)) {
			return pick(hash, Blocks.PACKED_MUD, Blocks.STONE);
		}
		if (ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MYCELIUM)) {
			return pick(hash, Blocks.STONE, Blocks.TUFF);
		}
		if (ground.is(Blocks.DEEPSLATE)) {
			return pick(hash, Blocks.DEEPSLATE, Blocks.TUFF);
		}
		return SPIKE_BLOCKS[Math.floorMod(hash, SPIKE_BLOCKS.length)];
	}

	private static int spike(ServerLevel level, ScarOp op, int x, int surface, int z, BlockState ground) {
		double dx = x - op.x;
		double dz = z - op.z;
		double falloff = 1.0 - Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / Math.max(0.5, op.radius));
		int hash = (int) ((x * 341873128L) ^ (z * 132897987L) ^ op.seed);
		double jitter = 0.9 + Math.floorMod(hash, 100) / 500.0;
		int height = (int) Math.round(op.height * (0.3 + 0.7 * falloff) * jitter);
		height = Math.max(2, Math.min(MAX_HEIGHT, height));
		// кромка: вывернутый грунт и обломки вокруг основания. Без него конус растёт прямо
		// из целой травы, и с 20–200 блоков это читается как «камень из ничего»
		if (falloff < 0.18) {
			if (protectedBlock(ground) || builtBlock(ground) || !ground.getFluidState().isEmpty()) {
				return 0;
			}
			Block rim = rubbleBlock(ground, hash);
			level.setBlock(new BlockPos(x, surface - 1, z), rim.defaultBlockState(), Block.UPDATE_ALL);
			clearPlant(level, x, surface, z);
			return 1;
		}
		// камень из того же биома: песчаник в пустыне, терракота в бэдлендах, туф на камне.
		// Серый булыжник посреди песка читается как чужая постройка, а не как след аномалии
		Block block = spikeBlock(ground, hash);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		int top = Math.min(level.getMaxY() - 2, surface + height);
		for (int y = surface; y <= top; y++) {
			// игрок выше земли (на скале, в полёте) тоже не должен получить камень в голову;
			// колонку пропускаем целиком, иначе остаётся обрубок до высоты головы
			if (playersNear(level, x, y, z)) {
				return 0;
			}
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (protectedBlock(state)) {
				break;
			}
			if (!state.isAir() && state.blocksMotion()) {
				// столб растёт только по воздуху: раньше он перепрыгивал крону и торчал из неё
				break;
			}
			level.setBlock(pos.set(x, y, z), block.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
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
						level.setBlock(pos.set(x0, surface - 1, z0), rubbleBlock(ground,
								(int) ((x0 * 73856093L) ^ (z0 * 19349663L))).defaultBlockState(), Block.UPDATE_ALL);
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
				// и воронка углубляется без предела (у стоянки выгрызается терраса)
				if (dug < surface) {
					BlockPos floorPos = new BlockPos(x0, Math.max(level.getMinY() + 1, dug), z0);
					BlockState floor = level.getBlockState(floorPos);
					if (!protectedBlock(floor) && !builtBlock(floor) && !floor.isAir()) {
						level.setBlock(floorPos, seal(ground), Block.UPDATE_ALL);
					}
				}
			}
		}
		return changed;
	}

	/** Гарь от молнии: мёртвая земля и снесённые растения в маленьком пятне. */
	public static int scorch(ServerLevel level, double x, double z, double radius, double nearY) {
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














