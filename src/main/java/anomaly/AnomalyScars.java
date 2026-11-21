package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
	private static final Block[] SPIKE_BLOCKS = {
			Blocks.STONE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DEEPSLATE
	};

	private AnomalyScars() {
	}

	// --- планирование ---

	public static List<ScarOp> plan(Anomaly anomaly, AnomalyClass cls, Random rnd) {
		List<ScarOp> ops = new ArrayList<>();
		double budget = AnomalyClass.damageBudget(anomaly.r0, cls);
		if (budget < 20.0 || anomaly.pinned) {
			return ops;
		}
		double target = budget * (0.4 + rnd.nextDouble() * 0.5);
		double used = 0.0;
		int guard = 0;
		while (used < target && guard++ < 40) {
			ScarOp op = pick(anomaly, cls, rnd, target - used);
			if (op == null) {
				break;
			}
			ops.add(op);
			used += op.blocks;
		}
		return ops;
	}

	private static ScarOp pick(Anomaly anomaly, AnomalyClass cls, Random rnd, double remaining) {
		boolean deadly = cls == AnomalyClass.DEADLY;
		boolean dangerous = cls != AnomalyClass.SAFE;
		double roll = rnd.nextDouble();
		double[] spot = spot(anomaly, rnd);

		if (deadly && remaining > 6000.0 && roll < 0.40) {
			ScarOp op = ScarOp.spike(spot[0], spot[1], 4.0 + rnd.nextDouble() * 10.0,
					40 + rnd.nextInt(80), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		if (deadly && remaining > 1200.0 && roll < 0.60) {
			ScarOp op = ScarOp.line("lava_fault", spot[0], spot[1], rnd.nextDouble() * TAU,
					40 + rnd.nextInt(100), 2, 4 + rnd.nextInt(3), rnd.nextLong());
			if (op.blocks <= remaining) {
				return op;
			}
		}
		if (dangerous && remaining > 900.0 && roll < 0.78) {
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

	/** Точка в ядре: 35 % радиуса от центра рождения. Туда же попадает почти всё разрушение. */
	private static double[] spot(Anomaly anomaly, Random rnd) {
		double angle = rnd.nextDouble() * TAU;
		double distance = anomaly.r0 * 0.35 * Math.sqrt(rnd.nextDouble());
		return new double[]{
				anomaly.x0 + Math.cos(angle) * distance,
				anomaly.z0 + Math.sin(angle) * distance
		};
	}

	// --- применение ---

	/** Возвращает true, если что-то продвинулось: по этому полю решается, пора ли сохранять файл. */
	public static boolean tick(MinecraftServer server, ServerLevel level, AnomalyFieldData data,
			AnomalyConfig config) {
		if (!config.scar_enabled) {
			return false;
		}
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		if (mspt > config.mspt_limit) {
			return false;
		}
		int chunksThisTick = 0;
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
					anomaly.scar_cursor++;
					if (column == null) {
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
					blocksThisTick += applyColumn(level, op, column[0], column[1]);
					progressed = true;
					if (blocksThisTick >= config.blocks_per_tick) {
						finished = false;
						break;
					}
				}
				if (!finished) {
					return progressed;
				}
				anomaly.scar_next++;
				anomaly.scar_cursor = 0;
				if (blocksThisTick >= config.blocks_per_tick || chunksThisTick >= config.chunks_per_tick) {
					return progressed;
				}
			}
		}
		return progressed;
	}

	private static int applyColumn(ServerLevel level, ScarOp op, int x, int z) {
		// чанк уже загружен вызывающим: иначе getHeight отдаёт минимум мира
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (surface <= level.getMinY() + 3 || surface >= level.getMaxY() - 2) {
			return 0;
		}
		return switch (op.type) {
			case "dead_soil" -> deadSoil(level, x, surface, z);
			case "rubble" -> rubble(level, x, surface, z, op.seed);
			case "crack" -> trench(level, x, surface, z, op.depth, false);
			case "lava_fault" -> trench(level, x, surface, z, op.depth, true);
			case "spike" -> spike(level, op, x, surface, z);
			default -> 0;
		};
	}

	private static int deadSoil(ServerLevel level, int x, int surface, int z) {
		int changed = 0;
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		if (!protectedBlock(state) && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
				|| state.is(Blocks.PODZOL) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.SNOW_BLOCK))) {
			level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		BlockPos above = new BlockPos(x, surface, z);
		BlockState plant = level.getBlockState(above);
		if (!plant.isAir() && !protectedBlock(plant) && !plant.blocksMotion()) {
			level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
	}

	private static int rubble(ServerLevel level, int x, int surface, int z, long seed) {
		Block[] palette = {Blocks.COBBLESTONE, Blocks.GRAVEL, Blocks.ANDESITE, Blocks.COARSE_DIRT,
				Blocks.TUFF, Blocks.COBBLED_DEEPSLATE};
		int hash = (int) ((x * 73856093L) ^ (z * 19349663L) ^ seed);
		Block block = palette[Math.floorMod(hash, palette.length)];
		BlockPos ground = new BlockPos(x, surface - 1, z);
		BlockState state = level.getBlockState(ground);
		if (protectedBlock(state) || state.isAir()) {
			return 0;
		}
		level.setBlock(ground, block.defaultBlockState(), Block.UPDATE_ALL);
		return 1;
	}

	private static int trench(ServerLevel level, int x, int surface, int z, int depth, boolean lava) {
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
		return changed;
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
				continue;
			}
			level.setBlock(pos.set(x, y, z), block.defaultBlockState(), Block.UPDATE_ALL);
			changed++;
		}
		return changed;
	}

	private static boolean protectedBlock(BlockState state) {
		if (state.hasBlockEntity()) {
			return true;
		}
		return state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
				|| state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.REINFORCED_DEEPSLATE)
				|| state.is(Blocks.BARRIER) || state.is(Blocks.END_PORTAL_FRAME)
				|| state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.LODESTONE)
				|| state.is(Blocks.BEACON) || state.is(Blocks.SPAWNER);
	}

	/** Сколько блоков ещё в очереди: для команды и для тестов. */
	public static int pendingBlocks(Anomaly anomaly) {
		if (anomaly.scars == null || anomaly.scar_next >= anomaly.scars.size()) {
			return 0;
		}
		int sum = 0;
		for (int i = anomaly.scar_next; i < anomaly.scars.size(); i++) {
			sum += anomaly.scars.get(i).blocks;
		}
		return sum;
	}
}
