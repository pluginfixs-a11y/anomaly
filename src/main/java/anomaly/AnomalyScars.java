package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 */
public final class AnomalyScars {
	private static final double TAU = Math.PI * 2.0;
	private static final int MAX_HEIGHT = 150;
	/** Колонок за проход: ~2000 изменённых блоков и несколько тысяч пустых проверок. */
	private static final int COLUMNS_PER_PASS = 6000;
	/** Часы на проход: средний mspt помнит 100 тиков, этого мало для 2000 setBlock подряд. */
	private static final long NANOS_PER_PASS = 5_000_000L;
	private static final Block[] SPIKE_BLOCKS = {
			Blocks.STONE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DEEPSLATE
	};

	private AnomalyScars() {
	}

	// --- планирование ---

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
			ops.add(op);
			used += op.blocks;
		}
		return ops;
	}

	private static ScarOp pick(double cx, double cz, double r0, AnomalyClass cls, Random rnd, double remaining,
			List<String> components) {
		boolean deadly = cls == AnomalyClass.DEADLY;
		boolean dangerous = cls != AnomalyClass.SAFE;
		// Геология — от компонентов, а не только от класса: иначе все смертельные зоны
		// получают одни и те же пики и лаву, и пейзаж у них одинаковый.
		boolean storms = has(components, "storm_core");
		boolean pushes = storms || has(components, "pressure") || has(components, "night_toll");
		double roll = rnd.nextDouble();
		double[] spot = spot(cx, cz, r0, rnd);

		if (deadly && pushes && remaining > 6000.0 && roll < 0.40) {
			ScarOp op = ScarOp.spike(spot[0], spot[1], 4.0 + rnd.nextDouble() * 10.0,
					40 + rnd.nextInt(80), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		if (deadly && storms && remaining > 1200.0 && roll < 0.60) {
			ScarOp op = ScarOp.line("lava_fault", spot[0], spot[1], rnd.nextDouble() * TAU,
					40 + rnd.nextInt(100), 2, 4 + rnd.nextInt(3), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		if (dangerous && pushes && remaining > 900.0 && roll < 0.78) {
			ScarOp op = ScarOp.spike(spot[0], spot[1], 3.0 + rnd.nextDouble() * 4.0,
					15 + rnd.nextInt(30), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		// площадные следы: мёртвая земля или обломки — под остаток бюджета
		double soilBudget = Math.min(remaining * 0.7, deadly ? 3000.0 : 1500.0);
		double soilRadius = Math.max(3.0, Math.min(24.0, Math.sqrt(soilBudget / Math.PI)));
		ScarOp soil = rnd.nextDouble() < 0.65
				? ScarOp.disk("dead_soil", spot[0], spot[1], soilRadius, rnd.nextLong())
				: ScarOp.disk("rubble", spot[0], spot[1], soilRadius * 0.7, rnd.nextLong());
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

	/** Точка в ядре: 35 % радиуса от центра. Туда же попадает почти всё разрушение. */
	private static double[] spot(double cx, double cz, double r0, Random rnd) {
		double angle = rnd.nextDouble() * TAU;
		double distance = r0 * 0.35 * Math.sqrt(rnd.nextDouble());
		return new double[]{
				cx + Math.cos(angle) * distance,
				cz + Math.sin(angle) * distance
		};
	}

	// --- применение ---

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
		// средний mspt — грубый предохранитель с памятью в 100 тиков; точный — по часам внутри прохода
		if (server.getAverageTickTimeNanos() / 1_000_000.0 > config.mspt_limit) {
			return false;
		}
		long deadline = System.nanoTime() + NANOS_PER_PASS;
		int chunksThisTick = 0;
		int columnsThisTick = 0;
		int blocksThisTick = 0;
		boolean progressed = false;

		for (Anomaly anomaly : data.anomalies) {
			if (anomaly.scars == null || anomaly.scars.isEmpty() || anomaly.scar_next >= anomaly.scars.size()) {
				continue;
			}
			while (anomaly.scar_next < anomaly.scars.size()) {
				ScarOp op = anomaly.scars.get(anomaly.scar_next);
				int count = op.columnCount();
				boolean finished = true;
				while (anomaly.scar_cursor < count) {
					int[] column = op.column(anomaly.scar_cursor);
					if (column == null) {
						anomaly.scar_cursor++;
						continue;
					}
					int chunkX = column[0] >> 4;
					int chunkZ = column[1] >> 4;
					if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
						if (chunksThisTick >= config.chunks_per_tick) {
							finished = false;
							break;
						}
						level.getChunk(chunkX, chunkZ);
						chunksThisTick++;
					}
					// курсор двигаем только после проверок: иначе колонка на стыке бюджета чанков теряется
					anomaly.scar_cursor++;
					columnsThisTick++;
					// ближе полосы подавления мир не трогаем даже случайно задрейфовавшей зоной
					if (Math.hypot(column[0], column[1]) < config.suppress_until) {
						continue;
					}
					try {
						blocksThisTick += applyColumn(level, op, column[0], column[1]);
					} catch (RuntimeException e) {
						AnomalyMod.LOGGER.warn("Anomaly: след #{} в {}, {} не применён ({})",
								anomaly.id, column[0], column[1], e.toString());
					}
					progressed = true;
					if (blocksThisTick >= config.blocks_per_tick || columnsThisTick >= COLUMNS_PER_PASS
							|| System.nanoTime() > deadline) {
						finished = false;
						break;
					}
				}
				if (!finished) {
					return progressed;
				}
				anomaly.scar_next++;
				anomaly.scar_cursor = 0;
				if (blocksThisTick >= config.blocks_per_tick || chunksThisTick >= config.chunks_per_tick
						|| columnsThisTick >= COLUMNS_PER_PASS) {
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
		// а обломки заменили бы лёд булыжником: такие поверхности не трогаем вовсе.
		BlockState ground = level.getBlockState(new BlockPos(x, surface - 1, z));
		if (!ground.getFluidState().isEmpty() || isFragileSurface(ground) || builtBlock(ground)) {
			return 0;
		}
		int changed = switch (op.type) {
			case "dead_soil" -> deadSoil(level, x, surface, z);
			case "rubble" -> rubble(level, x, surface, z, op.seed);
			case "crack" -> trench(level, x, surface, z, op.depth, false);
			case "lava_fault" -> trench(level, x, surface, z, op.depth, true);
			case "spike" -> spike(level, op, x, surface, z);
			default -> 0;
		};
		// дерево сносим после следа и по исходной высоте: до правки ствол ещё на месте,
		// а обломки или траншея уже снесли бы дешёвую проверку
		if (tree) {
			changed += deforest(level, x, height, z);
		}
		return changed;
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

	private static int deadSoil(ServerLevel level, int x, int surface, int z) {
		int changed = 0;
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		if (!protectedBlock(state) && soil(state)) {
			level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed + clearPlant(level, x, surface, z);
	}

	/** Грунт, который выгорает: трава, земля, подзол, мицелий, мох, грязь. */
	private static boolean soil(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL)
				|| state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)
				|| state.is(Blocks.MUD) || state.is(Blocks.CLAY);
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
			if (protectedBlock(plant) || plant.blocksMotion() || !plant.getFluidState().isEmpty()) {
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
		int changed = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (!level.getChunkSource().hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					continue;
				}
				changed += clearTreeColumn(level, x + dx, height, z + dz);
			}
		}
		return changed;
	}

	/** Листва или грибные шляпы вокруг ствола: по ним дерево отличается от сруба из брёвен. */
	private static boolean hasFoliage(ServerLevel level, int x, int height, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bottom = Math.max(level.getMinY() + 1, height - 10);
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
		// сверху вниз: крона висит и ниже верхушки ствола, а земля — единственный стоп-блок
		int top = Math.min(level.getMaxY() - 2, height + 8);
		int bottom = Math.max(level.getMinY() + 1, height - 40);
		int changed = 0;
		for (int y = top; y >= bottom; y--) {
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

	private static int rubble(ServerLevel level, int x, int surface, int z, long seed) {
		int hash = (int) ((x * 73856093L) ^ (z * 19349663L) ^ seed);
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		if (protectedBlock(state) || state.isAir()) {
			return 0;
		}
		Block block = rubbleBlock(state, hash);
		clearPlant(level, x, surface, z);
		level.setBlock(ground, block.defaultBlockState(), Block.UPDATE_ALL);
		return 1;
	}

	/** Обломки из того же материала, что и грунт: булыжник посреди песка или терракоты — клякса. */
	private static Block rubbleBlock(BlockState ground, int hash) {
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
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < depth; i++) {
			int y = surface - 1 - i;
			if (y <= level.getMinY() + 1) {
				break;
			}
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (protectedBlock(state)) {
				return changed;
			}
			boolean bottom = i == depth - 1;
			BlockState target = bottom && lava ? Blocks.LAVA.defaultBlockState() : Blocks.AIR.defaultBlockState();
			level.setBlock(pos.set(x, y, z), target, Block.UPDATE_ALL);
			changed++;
		}
		// разлом посреди леса тоже оставляет мёртвую землю — дерево сносится в applyColumn
		return changed;
	}

	/** Вода или лава рядом с колонкой на уровне грунта. Незагруженный сосед считается водой. */
	private static boolean fluidNear(ServerLevel level, int x, int surface, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!level.getChunkSource().hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					return true;
				}
				if (!level.getBlockState(new BlockPos(x + dx, surface - 1, z + dz)).getFluidState().isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	private static int spike(ServerLevel level, ScarOp op, int x, int surface, int z) {
		double dx = x - op.x;
		double dz = z - op.z;
		double falloff = 1.0 - Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / Math.max(0.5, op.radius));
		int hash = (int) ((x * 341873128L) ^ (z * 132897987L) ^ op.seed);
		double jitter = 0.9 + Math.floorMod(hash, 100) / 500.0;
		int height = (int) Math.round(op.height * (0.3 + 0.7 * falloff) * jitter);
		height = Math.max(2, Math.min(MAX_HEIGHT, height));
		Block block = SPIKE_BLOCKS[Math.floorMod(hash, SPIKE_BLOCKS.length)];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		int top = Math.min(level.getMaxY() - 2, surface + height);
		for (int y = surface; y <= top; y++) {
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
				|| state.is(Blocks.BRICKS) || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
				|| state.is(Blocks.TINTED_GLASS) || state.is(Blocks.SANDSTONE) || state.is(Blocks.SMOOTH_SANDSTONE)
				|| state.is(Blocks.CUT_SANDSTONE) || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.MUD_BRICKS)
				|| state.is(Blocks.DEEPSLATE_BRICKS) || state.is(Blocks.DEEPSLATE_TILES)
				|| state.is(Blocks.POLISHED_DEEPSLATE);
	}

	static boolean protectedBlock(BlockState state) {
		if (state.hasBlockEntity()) {
			return true;
		}
		return state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
				|| state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.REINFORCED_DEEPSLATE)
				|| state.is(Blocks.BARRIER) || state.is(Blocks.END_PORTAL_FRAME)
				|| state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)
				|| state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.LODESTONE)
				|| state.is(Blocks.BUDDING_AMETHYST)
				|| state.is(Blocks.BEACON) || state.is(Blocks.SPAWNER);
	}

	// --- микрособытия: то, что делает мир живым между большими следами ---

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
				BlockState ground = level.getBlockState(pos.set(x0, surface - 1, z0));
				if (protectedBlock(ground) || builtBlock(ground) || isFragileSurface(ground)
						|| !ground.getFluidState().isEmpty()) {
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
				// дно воронки — под последним выкопанным блоком, а не «где-то по плану»:
				// если копание прервалось на сундуке, пол не должен висеть в пещере ниже
				if (dug < surface) {
					int floorY = Math.max(level.getMinY() + 1, dug - 1);
					BlockState floor = level.getBlockState(pos.set(x0, floorY, z0));
					if (floor.canBeReplaced() && !protectedBlock(floor) && !builtBlock(floor)) {
						level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
						changed++;
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
				changed += deadSoil(level, x0, surface, z0);
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
