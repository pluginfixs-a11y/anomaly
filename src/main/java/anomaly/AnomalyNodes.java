package anomaly;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Рейды в аномалии: то, ради чего в неё вообще идут.
 * <p>
 * Три узла, которые появляются лениво — когда игрок впервые подошёл к ядру зоны на 96 блоков
 * (никакой генерации чанков ради этого):
 * <ul>
 *   <li><b>кладовая</b> — сундук с лутом по классу зоны; восстанавливается, пока зона жива;</li>
 *   <li><b>алтарь</b> — обмен 5 уровней опыта на редкое, но остаток растёт на 1.5–3 ч;</li>
 *   <li><b>три якоря</b> — снёс все три, зона схлопывается: волна мобов, взрыв и награда из кладовой.</li>
 * </ul>
 * Позиции узлов фиксируются при постановке и живут в файле поля: зона может уехать, узлы остаются
 * там, где игрок их нашёл. Текст в UI не выводится, только звук, частицы и предметы.
 */
public final class AnomalyNodes {
	/** Насколько близко к ядру должен подойти игрок, чтобы узлы появились. */
	private static final double REACH = 96.0;
	/** Запас вокруг узлов: они стоят до 100 блоков от ядра, игрок не обязан идти в центр. */
	private static final double NODE_SPREAD = 160.0;
	/** Кладовая восстанавливается через 25 минут, и только если рядом никого не было. */
	private static final long CACHE_RESTORE_MS = 25L * 60_000L;
	/** Радиус «рядом»: у сундука не отсидеться за границей мелкой зоны (r=100 — это 10 секунд ходьбы). */
	private static final double CACHE_CLEAR = 512.0;
	/** Сколько раз кладовая наполняется за жизнь зоны: ферма у сундука не должна кормить вечно. */
	private static final int CACHE_MAX_RESTORES = 3;
	/** Сколько уровней стоит один обмен на алтаре. */
	private static final int ALTAR_LEVELS = 5;
	private static final long ALTAR_COOLDOWN_MS = 1500L;
	/** Максимальная плата за обмен, часы остатка: под потолком должно оставаться место на неё. */
	private static final double PAY_MAX = 3.0;

	/** Последний обмен на алтаре: только чтобы двойной клик не съел уровни дважды. Карта не растёт бесконечно. */
	private static final java.util.Map<java.util.UUID, Long> ALTAR_LAST = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<java.util.UUID, Long> eldest) {
			return size() > 512;
		}
	};

	private AnomalyNodes() {
	}

	public static void register() {
		// Алтарь: правый клик по якорю-алтарю — обмен. Ваниль этот блок так не использует
		// (заряженный якорь взрывается), поэтому отменять ничего не ломаем.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!AnomalyMod.config.enabled || !AnomalyMod.config.nodes_enabled) {
				return InteractionResult.PASS;
			}
			if (!(player instanceof ServerPlayer serverPlayer) || hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			if (!(level instanceof ServerLevel serverLevel)
					|| !serverLevel.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			if (!level.getBlockState(pos).is(Blocks.LODESTONE)) {
				return InteractionResult.PASS;
			}
			Anomaly anomaly = altarAt(serverLevel, pos);
			if (anomaly == null) {
				return InteractionResult.PASS;
			}
			useAltar(serverLevel, serverPlayer, anomaly);
			return InteractionResult.SUCCESS;
		});

		// Якоря: сломал третий — зона схлопывается.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!AnomalyMod.config.enabled || !AnomalyMod.config.nodes_enabled) {
				return;
			}
			if (!(level instanceof ServerLevel serverLevel)
					|| !serverLevel.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
				return;
			}
			if (!state.is(Blocks.CRYING_OBSIDIAN)) {
				return;
			}
			Anomaly anomaly = anchorAt(serverLevel, pos);
			if (anomaly == null) {
				return;
			}
			int bit = anchorIndex(anomaly, pos);
			if (bit < 0) {
				return;
			}
			anomaly.anchors_broken |= 1 << bit;
			serverLevel.playSound(null, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
					SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.0f, 0.6f);
			if (Integer.bitCount(anomaly.anchors_broken) >= 3) {
				// третий якорь обрушивает зону только если игрок внутри: иначе это просто
				// сломанный блок в чистом поле, а не рейд
				ServerPlayer actor = player instanceof ServerPlayer sp ? sp : null;
				Zone zone = anomaly.zone(AnomalyClock.now());
				if (actor != null && zone.contains(actor.getX(), actor.getZ())) {
					collapse(serverLevel, anomaly, actor);
				} else {
					serverLevel.playSound(null, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
							SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 0.8f, 0.4f);
				}
			}
		});
	}

	/**
	 * Раз в секунду: доставить узлы зоне рядом с игроком и восстановить кладовую.
	 * <p>
	 * Узлы ставятся по одному, каждый — когда загружен его чанк: у зоны радиусом 3к алтарь и якоря
	 * далеко от ядра, и ждать, пока игрок дойдёт до ядра, значило бы оставить рейд без якорей.
	 */
	public static void tick(MinecraftServer server, ServerLevel level, AnomalyConfig config) {
		if (!config.nodes_enabled) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != level) {
				continue;
			}
			for (Zone zone : AnomalyField.near(level, player.getX(), player.getZ(), REACH + NODE_SPREAD)) {
				if (zone.remnant() || zone.precursor()) {
					continue;
				}
				if (Math.hypot(zone.coreX() - player.getX(), zone.coreZ() - player.getZ()) > REACH + NODE_SPREAD) {
					continue;
				}
				Anomaly anomaly = zone.anomaly();
				placeNodes(level, anomaly, zone);
				restoreCache(level, anomaly, occupied(server, level, zone));
			}
		}
	}

	// --- постановка узлов ---

	/**
	 * Доставить недостающие узлы. Возвращает true, если что-то появилось.
	 * Координаты узлов фиксируются при первой попытке и потом не меняются: зона дрейфует,
	 * а узлы остаются там, где игрок их нашёл.
	 */
	private static boolean placeNodes(ServerLevel level, Anomaly anomaly, Zone zone) {
		if (!Double.isFinite(anomaly.node_r) || anomaly.node_r <= 0.0) {
			anomaly.node_cx = zone.coreX();
			anomaly.node_cz = zone.coreZ();
			anomaly.node_r = nodeRadius(zone.radius());
			anomaly.anchors_broken = 0;
			anomaly.anchors_placed = 0;
			anomaly.node_cache_ok = false;
			anomaly.node_altar_ok = false;
			anomaly.node_cache_ms = 0L;
		}
		boolean changed = false;
		if (!anomaly.node_cache_ok) {
			BlockPos cache = surface(level, anomaly.node_cx, anomaly.node_cz);
			if (cache != null) {
				placeChest(level, anomaly, cache);
				anomaly.node_cy = cache.getY();
				anomaly.node_cache_ms = System.currentTimeMillis();
				anomaly.node_cache_ok = true;
				changed = true;
			}
		}
		if (!anomaly.node_altar_ok) {
			double[] altar = nodePos(anomaly, baseAngle(anomaly), 0.32);
			BlockPos altarPos = adoptOrSpot(level, altar[0], altar[1], Blocks.LODESTONE);
			if (altarPos != null) {
				if (!level.getBlockState(altarPos).is(Blocks.LODESTONE)) {
					level.setBlock(altarPos, Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_ALL);
				}
				anomaly.node_altar_ok = true;
				anomaly.node_altar_cy = altarPos.getY();
				changed = true;
			}
		}
		for (int i = 0; i < 3; i++) {
			int bit = 1 << i;
			if ((anomaly.anchors_placed & bit) != 0) {
				continue;
			}
			double[] anchor = nodePos(anomaly, baseAngle(anomaly) + i * Math.PI * 2.0 / 3.0, 0.62);
			BlockPos anchorPos = adoptOrSpot(level, anchor[0], anchor[1], Blocks.CRYING_OBSIDIAN);
			if (anchorPos != null) {
				if (!level.getBlockState(anchorPos).is(Blocks.CRYING_OBSIDIAN)) {
					level.setBlock(anchorPos, Blocks.CRYING_OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
				}
				anomaly.anchors_placed |= bit;
				if (anomaly.anchor_ys == null || anomaly.anchor_ys.length != 3) {
					anomaly.anchor_ys = new int[3];
				}
				anomaly.anchor_ys[i] = anchorPos.getY();
				changed = true;
			}
		}
		if (!changed) {
			return false;
		}
		// узел есть, даже если кладовая не встала (лёд, лава, чужая постройка):
		// алтарь и якоря обязаны работать сами по себе
		anomaly.nodes_placed = true;
		int coreY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				(int) Math.floor(zone.coreX()), (int) Math.floor(zone.coreZ()));
		level.playSound(null, zone.coreX(), coreY, zone.coreZ(),
				SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 0.8f, 0.7f);
		AnomalyField.save();
		return true;
	}

	/**
	 * Кладовая: если её разобрали, а зона жива, раз в 10 минут сундук появляется снова.
	 * Пока в зоне стоит игрок, кладовая не восстанавливается: иначе кемпинг у сундука —
	 * бесконечный лут, а «кемпинг невыгоден» — правило мода, а не пожелание.
	 */
	private static void restoreCache(ServerLevel level, Anomaly anomaly, boolean occupied) {
		if (!anomaly.node_cache_ok || anomaly.remnant || anomaly.cache_restores >= CACHE_MAX_RESTORES) {
			return;
		}
		long now = System.currentTimeMillis();
		if (occupied) {
			// отсчёт начинается с момента, когда рядом никого не осталось, а не с прошлой выдачи
			anomaly.node_cache_ms = now;
			return;
		}
		if (!cacheDue(anomaly.node_cache_ms, now, false)) {
			return;
		}
		BlockPos pos = blockPos(anomaly.node_cx, anomaly.node_cy, anomaly.node_cz);
		if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
			return;
		}
		if (level.getBlockState(pos).is(Blocks.CHEST)) {
			anomaly.node_cache_ms = now;
			return;
		}
		if (!level.getBlockState(pos).canBeReplaced()) {
			// место заняли (постройка, обвал): ждём следующего окна, а не проверяем каждый тик
			anomaly.node_cache_ms = now;
			return;
		}
		placeChest(level, anomaly, pos);
		anomaly.node_cache_ms = now;
		anomaly.cache_restores++;
		AnomalyField.save();
	}

	/** Пора ли восстанавливать кладовую: чистая функция, по ней же гоняется самопроверка. */
	static boolean cacheDue(long lastMs, long now, boolean occupied) {
		if (occupied) {
			return false;
		}
		return lastMs <= 0L || now - lastMs >= CACHE_RESTORE_MS;
	}

	/** Стоит ли кто-то в зоне или у кладовой: пока стоит, зона «кормится», а кладовая молчит. */
	private static boolean occupied(MinecraftServer server, ServerLevel level, Zone zone) {
		double cx = zone.anomaly().node_cx;
		double cz = zone.anomaly().node_cz;
		boolean placed = zone.anomaly().node_cache_ok;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.level() != level) {
				continue;
			}
			if (zone.contains(other.getX(), other.getZ())) {
				return true;
			}
			// шаг за границу мелкой зоны не должен снимать «занято»
			if (placed && Math.hypot(other.getX() - cx, other.getZ() - cz) < CACHE_CLEAR) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Поверхность для узла. В океане `MOTION_BLOCKING` даёт воду, поэтому там спускаемся
	 * по `OCEAN_FLOOR` до дна: кладовая на дне — лучше, чем зона вообще без рейда.
	 */
	private static BlockPos surface(ServerLevel level, double x, double z) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return null;
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		BlockState ground = level.getBlockState(new BlockPos(bx, Math.max(level.getMinY(), y - 1), bz));
		if (!ground.getFluidState().isEmpty() || fragile(ground)) {
			y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, bx, bz);
			ground = level.getBlockState(new BlockPos(bx, Math.max(level.getMinY(), y - 1), bz));
		}
		if (y <= level.getMinY() + 2 || y >= level.getMaxY() - 2 || fragile(ground)) {
			return null;
		}
		BlockPos pos = new BlockPos(bx, y, bz);
		BlockState above = level.getBlockState(pos);
		// под лавой узлы не ставим: сундук, который нельзя открыть, — не награда;
		// вода — можно: в океане узлы стоят на дне, и это лучше, чем зона без рейда
		boolean water = above.is(Blocks.WATER);
		if (above.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
				|| !(above.canBeReplaced() || water)) {
			return null;
		}
		return pos;
	}

	/**
	 * Место для узла: если блок этого типа уже стоит в колонке (±2 по высоте), усыновляем его —
	 * так узлы, поставленные до появления записи высот, не задваиваются. Иначе берём поверхность.
	 */
	private static BlockPos adoptOrSpot(ServerLevel level, double x, double z, Block block) {
		BlockPos pos = surface(level, x, z);
		if (pos == null) {
			return null;
		}
		for (int dy = -2; dy <= 2; dy++) {
			BlockPos near = pos.offset(0, dy, 0);
			if (level.getBlockState(near).is(block)) {
				return near;
			}
		}
		return pos;
	}

	/** Свободное место в кольце 3..6 блоков от ядра: там, где кладовая уже стоит, награду не поставить. */
	private static BlockPos ringSpot(ServerLevel level, double cx, double cz) {
		for (double radius = 3.0; radius <= 6.0; radius += 1.5) {
			for (int i = 0; i < 8; i++) {
				double angle = i * Math.PI / 4.0;
				BlockPos pos = surface(level, cx + Math.cos(angle) * radius, cz + Math.sin(angle) * radius);
				if (pos != null) {
					return pos;
				}
			}
		}
		return null;
	}

	private static boolean fragile(BlockState state) {
		return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
				|| state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW);
	}

	// --- лут ---

	static void placeChest(ServerLevel level, Anomaly anomaly, BlockPos pos) {
		level.setBlock(pos, Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
		fillChest(level, anomaly, pos, 1.0);
	}

	private static void fillChest(ServerLevel level, Anomaly anomaly, BlockPos pos, double multiplier) {
		if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
			return;
		}
		chest.clearContent();
		Random rnd = new Random(anomaly.id * 0x2545F4914F6CDD1DL ^ (long) (multiplier * 1000));
		int slot = 0;
		for (ItemStack stack : lootFor(AnomalyClass.byId(anomaly.cls), rnd)) {
			if (slot >= chest.getContainerSize()) {
				break;
			}
			int count = Math.max(1, (int) Math.round(stack.getCount() * multiplier));
			chest.setItem(slot++, new ItemStack(stack.getItem(), count));
		}
	}

	/** Лут по классу: безопасная кормит, смертельная даёт то, за чем идут. Детерминирован по сиду. */
	static List<ItemStack> lootFor(AnomalyClass cls, Random rnd) {
		List<ItemStack> loot = new ArrayList<>();
		switch (cls) {
			case SAFE -> {
				loot.add(new ItemStack(Items.BREAD, 2 + rnd.nextInt(3)));
				loot.add(new ItemStack(Items.IRON_INGOT, 1 + rnd.nextInt(2)));
				loot.add(new ItemStack(Items.TORCH, 4 + rnd.nextInt(5)));
				if (rnd.nextBoolean()) {
					loot.add(new ItemStack(Items.APPLE, 1 + rnd.nextInt(2)));
				}
			}
			case DANGER -> {
				loot.add(new ItemStack(Items.DIAMOND, 1 + rnd.nextInt(2)));
				loot.add(new ItemStack(Items.EMERALD, 1 + rnd.nextInt(3)));
				loot.add(new ItemStack(Items.IRON_BLOCK, 1));
				loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 1 + rnd.nextInt(3)));
				if (rnd.nextBoolean()) {
					loot.add(new ItemStack(Items.GOLDEN_APPLE));
				}
			}
			case DEADLY -> {
				loot.add(new ItemStack(Items.DIAMOND, 2 + rnd.nextInt(3)));
				loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + rnd.nextInt(4)));
				// «за чем идут» — но не гарантия: незерит в 30 %, тотем в 25 %.
				// Иначе кладовая превращается в станок, и в даль не идут за риском, а ходят за лутом
				if (rnd.nextInt(10) < 3) {
					loot.add(new ItemStack(Items.NETHERITE_INGOT));
				}
				if (rnd.nextInt(4) == 0) {
					loot.add(new ItemStack(Items.TOTEM_OF_UNDYING));
				}
				if (rnd.nextBoolean()) {
					loot.add(new ItemStack(Items.GOLD_BLOCK));
				}
			}
		}
		return loot;
	}

	// --- алтарь ---

	private static void useAltar(ServerLevel level, ServerPlayer player, Anomaly anomaly) {
		long now = System.currentTimeMillis();
		Long last = ALTAR_LAST.get(player.getUUID());
		if (last != null && now - last < ALTAR_COOLDOWN_MS) {
			return;
		}
		if (player.experienceLevel < ALTAR_LEVELS) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 0.8f, 0.7f);
			return;
		}
		// плата — остаток; на потолке она нулевая, и алтарь закрывается,
		// иначе тотемы льются за опыт без предела
		boolean residue = AnomalyMod.config.residue_enabled;
		if (residue && !canTrade(AnomalyResidue.hours(player.getUUID()), AnomalyMod.config.residue_cap_hours)) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 0.8f, 0.5f);
			return;
		}
		ALTAR_LAST.put(player.getUUID(), now);
		player.giveExperienceLevels(-ALTAR_LEVELS);
		AnomalyClass cls = AnomalyClass.byId(anomaly.cls);
		Random rnd = new Random(player.getUUID().getLeastSignificantBits() ^ System.nanoTime());
		ItemStack prize;
		if (cls == AnomalyClass.DEADLY) {
			prize = rnd.nextBoolean() ? new ItemStack(Items.TOTEM_OF_UNDYING)
					: new ItemStack(Items.NETHERITE_INGOT, 1 + rnd.nextInt(2));
		} else if (cls == AnomalyClass.DANGER) {
			prize = rnd.nextBoolean() ? new ItemStack(Items.DIAMOND, 4 + rnd.nextInt(4))
					: new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
		} else {
			prize = new ItemStack(Items.IRON_BLOCK, 2 + rnd.nextInt(3));
		}
		player.getInventory().placeItemBackInInventory(prize);
		// алтарь берёт своё: риск превращается в часы остатка
		if (residue) {
			AnomalyResidue.add(player.getUUID(), 1.5 + rnd.nextDouble() * 1.5);
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.9f, 0.6f);
		level.sendParticles(player, ParticleTypes.ENCHANT, true, true,
				player.getX(), player.getY() + 1.0, player.getZ(), 24, 0.8, 1.2, 0.8, 0.4);
	}

	// --- якоря и коллапс ---

	private static BlockPos collapse(ServerLevel level, Anomaly anomaly, ServerPlayer actor) {
		Zone zone = anomaly.zone(AnomalyClock.now());
		// награда там же, где кладовая: узлы прибиты к точке постановки, а зона с тех пор
		// уехала на километры — волна и лут не должны ждать игрока в чистом поле
		BlockPos reward = ringSpot(level, anomaly.node_cx, anomaly.node_cz);
		if (reward == null) {
			reward = surface(level, anomaly.node_cx, anomaly.node_cz);
		}
		if (reward == null) {
			reward = surface(level, zone.coreX(), zone.coreZ());
		}
		double cx = reward != null ? reward.getX() + 0.5 : anomaly.node_cx;
		double cz = reward != null ? reward.getZ() + 0.5 : anomaly.node_cz;
		// коллапс рушит узел: чанки 3×3 нужны загруженными, иначе getHeight отдаст дно мира
		// и половина волны родится в бедроке
		int chunkX = (int) Math.floor(cx) >> 4;
		int chunkZ = (int) Math.floor(cz) >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.getChunk(chunkX + dx, chunkZ + dz);
			}
		}
		Random rnd = new Random(anomaly.id * 0x9E3779B97F4A7C15L);
		AnomalyClass cls = AnomalyClass.byId(anomaly.cls);
		int mobs = cls == AnomalyClass.DEADLY ? 5 : cls == AnomalyClass.DANGER ? 3 : 2;
		for (int i = 0; i < mobs; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double distance = 3.0 + rnd.nextDouble() * 6.0;
			double x = cx + Math.cos(angle) * distance;
			double z = cz + Math.sin(angle) * distance;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
			Mob mob = rnd.nextBoolean()
					? EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT)
					: EntityTypes.SKELETON.create(level, EntitySpawnReason.EVENT);
			if (mob == null) {
				continue;
			}
			mob.snapTo(x + 0.5, y + 0.1, z + 0.5, rnd.nextFloat() * 360f, 0f);
			level.addFreshEntity(mob);
			if (actor != null) {
				mob.setTarget(actor);
			}
		}
		// награда за рейд: отдельный сундук рядом с кладовой. В саму кладовую не лезем —
		// там может лежать то, что игрок оставил, и стирать чужие вещи мод не имеет права
		if (reward != null) {
			level.setBlock(reward, Blocks.CHEST.defaultBlockState()
					.setValue(ChestBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
			fillChest(level, anomaly, reward, 1.6);
		}
		int coreY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				(int) Math.floor(cx), (int) Math.floor(cz));
		level.playSound(null, cx, coreY, cz, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 1.0f, 0.7f);
		level.sendParticles(ParticleTypes.EXPLOSION, cx, coreY + 1.0, cz, 8, 3.0, 1.5, 3.0, 0.0);
		AnomalyField.remove(anomaly.id);
		return reward;
	}

	// --- поиск узлов по позиции ---

	private static Anomaly altarAt(ServerLevel level, BlockPos pos) {
		for (Anomaly anomaly : AnomalyField.all()) {
			if (!anomaly.node_altar_ok || anomaly.remnant) {
				continue;
			}
			double[] altar = nodePos(anomaly, baseAngle(anomaly), 0.32);
			if ((int) Math.floor(altar[0]) == pos.getX() && (int) Math.floor(altar[1]) == pos.getZ()
					&& (int) Math.round(anomaly.node_altar_cy) == pos.getY()) {
				return anomaly;
			}
		}
		return null;
	}

	private static Anomaly anchorAt(ServerLevel level, BlockPos pos) {
		for (Anomaly anomaly : AnomalyField.all()) {
			if (!anomaly.nodes_placed || anomaly.remnant) {
				continue;
			}
			if (anchorIndex(anomaly, pos) >= 0) {
				return anomaly;
			}
		}
		return null;
	}

	private static int anchorIndex(Anomaly anomaly, BlockPos pos) {
		if (anomaly.anchor_ys == null || anomaly.anchor_ys.length != 3) {
			return -1;
		}
		for (int i = 0; i < 3; i++) {
			if (anomaly.anchor_ys[i] != pos.getY()) {
				continue;
			}
			double[] anchor = nodePos(anomaly, baseAngle(anomaly) + i * Math.PI * 2.0 / 3.0, 0.62);
			if ((int) Math.floor(anchor[0]) == pos.getX() && (int) Math.floor(anchor[1]) == pos.getZ()) {
				return i;
			}
		}
		return -1;
	}

	static double[] nodePos(Anomaly anomaly, double angle, double factor) {
		double radius = Math.max(20.0, anomaly.node_r) * factor;
		return new double[]{
				anomaly.node_cx + Math.cos(angle) * radius,
				anomaly.node_cz + Math.sin(angle) * radius
		};
	}

	static double baseAngle(Anomaly anomaly) {
		return (anomaly.id * 2.399963229728653) % (Math.PI * 2.0);
	}

	/** Поставить узлы немедленно: отладочная команда, не ждёт подхода игрока. */
	public static boolean placeNow(ServerLevel level, Anomaly anomaly) {
		if (anomaly.remnant) {
			return false;
		}
		Zone zone = anomaly.zone(AnomalyClock.now());
		double cx = Double.isFinite(anomaly.node_r) && anomaly.node_r > 0.0 ? anomaly.node_cx : zone.coreX();
		double cz = Double.isFinite(anomaly.node_r) && anomaly.node_r > 0.0 ? anomaly.node_cz : zone.coreZ();
		double r = Double.isFinite(anomaly.node_r) && anomaly.node_r > 0.0
				? anomaly.node_r : nodeRadius(zone.radius());
		// команда — отладка: чанки узлов грузим сами, иначе ставить некуда
		for (double[] at : nodeSpots(anomaly, cx, cz, r)) {
			level.getChunk((int) Math.floor(at[0]) >> 4, (int) Math.floor(at[1]) >> 4);
		}
		return placeNodes(level, anomaly, zone);
	}

	/**
	 * Радиус рейда, а не радиус зоны: якоря в 60 % радиуса трёхкилометровой зоны оказались бы
	 * в 1860 блоках — сквозь самую смертельную часть, куда не дойдёт ни один рейд.
	 */
	static double nodeRadius(double zoneRadius) {
		return Math.min(Math.max(20.0, zoneRadius), 160.0);
	}

	/** Позиции всех узлов: кладовая в ядре, алтарь на 32 %, три якоря на 62 %. */
	static java.util.List<double[]> nodeSpots(Anomaly anomaly, double cx, double cz, double r) {
		java.util.List<double[]> spots = new java.util.ArrayList<>(5);
		spots.add(new double[]{cx, cz});
		double angle = (anomaly.id * 2.399963229728653) % (Math.PI * 2.0);
		double radius = Math.max(20.0, r);
		spots.add(new double[]{cx + Math.cos(angle) * radius * 0.32, cz + Math.sin(angle) * radius * 0.32});
		for (int i = 0; i < 3; i++) {
			double a = angle + i * Math.PI * 2.0 / 3.0;
			spots.add(new double[]{cx + Math.cos(a) * radius * 0.62, cz + Math.sin(a) * radius * 0.62});
		}
		return spots;
	}

	/** Итог коллапса: сработал ли он и где награда (null — места не нашлось). */
	public record Collapse(boolean done, BlockPos reward) {
	}

	/** Отладочная команда: обрушить зону так же, как третий сломанный якорь. */
	public static Collapse collapseNow(ServerLevel level, Anomaly anomaly) {
		if (anomaly.remnant || !anomaly.nodes_placed) {
			return new Collapse(false, null);
		}
		return new Collapse(true, collapse(level, anomaly, null));
	}

	/**
	 * Строка для /anomaly nodes: где узлы, поставлены ли они и сколько якорей сломано.
	 * ✓ — узел поставлен, × — ещё нет (чанк загружен, место занято или не найдено), ? — чанк не загружен,
	 * · — якорь сломан. У кладовой дополнительно проверяется сам блок: её могли разобрать.
	 */
	public static String describe(ServerLevel level, Anomaly anomaly, double px, double pz) {
		if (!anomaly.nodes_placed && !anomaly.node_altar_ok && anomaly.anchors_placed == 0) {
			return "#" + anomaly.id + ": узлы не поставлены — подойди к ядру ближе " + Math.round(REACH)
					+ " блоков или /anomaly nodes spawn";
		}
		BlockPos cache = blockPos(anomaly.node_cx, anomaly.node_cy, anomaly.node_cz);
		double[] altar = nodePos(anomaly, baseAngle(anomaly), 0.32);
		int broken = Integer.bitCount(anomaly.anchors_broken);
		StringBuilder anchors = new StringBuilder();
		int standing = 0;
		for (int i = 0; i < 3; i++) {
			double[] anchor = nodePos(anomaly, baseAngle(anomaly) + i * Math.PI * 2.0 / 3.0, 0.62);
			int bit = 1 << i;
			boolean chunk = loaded(level, anchor[0], anchor[1]);
			if ((anomaly.anchors_broken & bit) != 0) {
				anchors.append('·');
			} else if ((anomaly.anchors_placed & bit) != 0) {
				anchors.append('✓');
				standing++;
			} else {
				anchors.append(chunk ? '×' : '?');
			}
		}
		return "#" + anomaly.id + " узлы: кладовая " + coord(cache)
				+ " " + (anomaly.node_cache_ok ? mark(level, cache, Blocks.CHEST) : loaded(level, anomaly.node_cx, anomaly.node_cz) ? "×" : "?")
				+ " (" + blocks(Math.hypot(anomaly.node_cx - px, anomaly.node_cz - pz)) + ")"
				+ " · алтарь " + coord(altar[0], altar[1]) + " "
				+ (anomaly.node_altar_ok ? "✓" : loaded(level, altar[0], altar[1]) ? "×" : "?")
				+ " · якоря " + anchors + " (" + standing + "/3 стоят)"
				+ (broken >= 3 ? " · схлопнута" : "");
	}

	private static boolean loaded(ServerLevel level, double x, double z) {
		return level != null && level.getChunkSource().hasChunk((int) Math.floor(x) >> 4,
				(int) Math.floor(z) >> 4);
	}

	/**
	 * Алтарь берёт плату остатком, поэтому у потолка он закрыт: иначе плата упирается в потолок,
	 * стоит ноль и редкое льётся без предела. Оставляем место на полную плату (до 3 ч).
	 */
	static boolean canTrade(double hours, double cap) {
		return Double.isFinite(hours) && Double.isFinite(cap) && cap > PAY_MAX && hours <= cap - PAY_MAX;
	}

	/** ✓ стоит, × на месте узла другой блок, ? чанк не загружен — проверять нечего. */
	private static String mark(ServerLevel level, BlockPos pos, Block block) {
		if (level == null || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
			return "?";
		}
		return level.getBlockState(pos).is(block) ? "✓" : "×";
	}

	private static BlockPos blockPos(double x, double y, double z) {
		return new BlockPos((int) Math.floor(x), (int) Math.round(y), (int) Math.floor(z));
	}

	private static String coord(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private static String coord(double x, double z) {
		return (int) Math.floor(x) + ", " + (int) Math.floor(z);
	}

	private static String blocks(double value) {
		return value >= 1000.0 ? String.format(java.util.Locale.ROOT, "%.1fк", value / 1000.0)
				: String.valueOf(Math.round(value));
	}
}
