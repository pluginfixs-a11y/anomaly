package anomaly;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Блоки, которые ведут себя как листва: на них не спавнится ни один моб.
 * <p>
 * Грибные блоки (шляпки и ствол) и бамбук — это «растительность», но в ванили они считаются
 * полным твёрдым блоком, и ночью на шляпке выросшего гриба встаёт скелет. Листва не даёт
 * спавна из-за непрозрачной геометрии; здесь список задан явно, чтобы поведение не зависело
 * от того, как ваниль посчитает очередной блок.
 */
public final class AnomalySpawnBlocks {
	private static final Set<Block> NO_SPAWN = Set.of(
			Blocks.BROWN_MUSHROOM_BLOCK,
			Blocks.RED_MUSHROOM_BLOCK,
			Blocks.MUSHROOM_STEM,
			Blocks.BAMBOO,
			Blocks.BAMBOO_SAPLING,
			Blocks.BAMBOO_BLOCK);

	private AnomalySpawnBlocks() {
	}

	public static boolean blocksSpawn(Block block) {
		return NO_SPAWN.contains(block);
	}
}
