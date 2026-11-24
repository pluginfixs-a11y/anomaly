package anomaly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * «Остаток»: сколько игрок ещё не отдышался после глубокой аномалии.
 * <p>
 * Это отдельная шкала **на игрока** в реальных часах, а не в часах поля: она не должна
 * ускоряться отладочным {@code timescale} и не должна тикать, пока игрок офлайн. Лежит
 * в {@code world/data/anomaly_residue.json} по UUID, переживает рестарт и смену измерения.
 * <p>
 * Растёт только в глубоких зонах (опасная/смертельная) и от пиковой дозы, в безопасных не
 * растёт вовсе. Спадает сам по себе, вдвое быстрее у центра мира, ещё быстрее рядом с другим
 * игроком, а сон снимает сразу 1–2 часа. Потолок 24 часа: наказание не бывает вечным.
 * <p>
 * Наружу это выходит только последствиями — тошнотой, отказом портала, слепотой, голодом.
 * Никаких шкал и цифр в интерфейсе.
 */
public final class AnomalyResidue {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "anomaly_residue.json";
	private static final double HOUR_MS = 3_600_000.0;

	/** Пороги состояний, часы остатка. */
	public static final double TREMOR = 2.0;
	public static final double AFTERTASTE = 5.0;
	public static final double SEAL = 8.0;
	public static final double VEIL = 12.0;
	public static final double DEEP_HUNGER = 16.0;

	/** Сколько остатка даёт одна секунда в зоне, часы на реальный час. */
	private static final double GAIN_DANGER = 3.0;
	private static final double GAIN_DEADLY = 7.0;

	private static Data data = new Data();
	private static Path path;
	private static boolean dirty;
	private static long lastSaveMs;

	/** Одна запись на игрока. Публичные поля — это и есть JSON-формат файла. */
	public static final class Entry {
		public double hours;
		/** Реальное время последнего обновления: по нему считается шаг, а не по тику. */
		public long updated_ms;

		/** Время, когда игрок лёг: по нему видно, был это сон или «полежал и встал». */
		transient long asleepSinceMs;
		/** Была ли ночь в момент, когда лёг: на сервере будят всех сразу, и «сон до утра» длится тик. */
		transient boolean asleepAtNight;
		transient int lastFood = -1;
		transient float lastSat;
		transient long lastRefuseMs;
		transient long tremorSec = -1L;
		transient long veilSec = -1L;
	}

	public static final class Data {
		public int version = 1;
		public Map<String, Entry> players = new LinkedHashMap<>();
	}

	private AnomalyResidue() {
	}

	// --- жизненный цикл ---

	public static void load(MinecraftServer server) {
		path = server.getWorldPath(LevelResource.DATA).resolve(FILE_NAME).toAbsolutePath().normalize();
		Data loaded = read(path);
		data = loaded != null ? loaded : new Data();
		if (data.players == null) {
			data.players = new LinkedHashMap<>();
		}
		// в файл мог попасть Infinity из ручной правки: без клампа печать не снялась бы никогда
		data.players.values().removeIf(entry -> entry == null);
		for (Entry entry : data.players.values()) {
			entry.hours = clamp(entry.hours);
			if (entry.updated_ms <= 0L) {
				entry.updated_ms = System.currentTimeMillis();
			}
		}
		dirty = false;
		lastSaveMs = System.currentTimeMillis();
		AnomalyMod.LOGGER.info("Anomaly: остаток загружен — {} игроков", data.players.size());
	}

	/** Раз в секунду из общего тика: пишем не чаще раза в минуту, как и поле. */
	public static void tick() {
		// записи, которые дотаили до нуля, не копим: иначе файл растёт по числу всех заходивших.
		// dirty здесь не ставим: удаление пустой записи не требует перезаписи файла
		data.players.values().removeIf(entry -> entry.hours <= 0.0);
		long now = System.currentTimeMillis();
		if (dirty && now - lastSaveMs > 60_000L) {
			// не saveNow(): Gson и два syscall в тик-потоке — ровно то, что уже вынесли у поля
			saveAsync();
		}
	}

	public static boolean save() {
		if (!dirty) {
			return true;
		}
		// ждём фоновую запись, иначе sync-сейв на /stop уйдёт раньше неё и остаток
		// откатится на минуту назад
		if (writing) {
			try {
				RESIDUE_WRITER.submit(() -> {
				}).get(1, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception ignored) {
				// не дождались — ниже пишем синхронно, seq отменит старый снимок
			}
		}
		return saveNow();
	}

	/** Фоновая запись: файл остатка маленький, но и он не должен блокировать тик. */
	private static void saveAsync() {
		if (writing || path == null) {
			return;
		}
		writing = true;
		dirty = false;
		// отметку ставим сразу: иначе при медленном диске тик ставил бы новую запись каждую секунду
		lastSaveMs = System.currentTimeMillis();
		long mySeq = ++seq;
		String json;
		try {
			json = GSON.toJson(data);
		} catch (RuntimeException e) {
			writing = false;
			dirty = true;
			return;
		}
		Path file = path;
		try {
			RESIDUE_WRITER.execute(() -> {
				boolean ok = true;
				try {
					ok = writeText(file, json, mySeq);
				} catch (RuntimeException e) {
					ok = false;
					AnomalyMod.LOGGER.error("Anomaly: фоновая запись остатка упала", e);
				}
				writing = false;
				if (!ok) {
					dirty = true;
				}
			});
		} catch (RuntimeException rejected) {
			// пул отказал: иначе writing останется true и остаток перестанет сохраняться
			writing = false;
			dirty = true;
			AnomalyMod.LOGGER.error("Anomaly: не смог поставить запись остатка в очередь", rejected);
		}
	}

	private static final java.util.concurrent.ExecutorService RESIDUE_WRITER =
			java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "anomaly-residue-save");
				thread.setDaemon(true);
				return thread;
			});
	private static volatile boolean writing;
	private static volatile long seq;

	static boolean saveNow() {
		lastSaveMs = System.currentTimeMillis();
		String json = GSON.toJson(data);
		long mySeq;
		synchronized (SAVE_LOCK) {
			// номер выдаём под замком: иначе воркер, уже прошедший сверку, положит старый снимок
			// поверх этого синхронного
			mySeq = ++seq;
		}
		boolean ok = path != null && writeText(path, json, mySeq);
		dirty = !ok;
		return ok;
	}

	static boolean write(Path file, Data payload) {
		return writeText(file, GSON.toJson(payload));
	}

	private static final Object SAVE_LOCK = new Object();

	/** Запись со сверкой номера снимка под замком: старый воркер не откатывает новый файл. */
	private static boolean writeText(Path file, String json, long mySeq) {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tmp = file.resolveSibling(file.getFileName() + "." + System.nanoTime() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			cleanTmp(parent, file);
			synchronized (SAVE_LOCK) {
				if (mySeq != seq) {
					// синхронный сейв успел раньше: этот снимок старше и в файл не идёт
					try {
						Files.deleteIfExists(tmp);
					} catch (IOException ignored) {
						// уберёт следующая запись
					}
					return true;
				}
				move(tmp, file);
			}
			return true;
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Anomaly: не смог записать остаток в {}", file, e);
			return false;
		}
	}

	private static void move(Path tmp, Path file) throws IOException {
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailed) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static boolean writeText(Path file, String json) {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			cleanTmp(parent, file);
			Path tmp = file.resolveSibling(file.getFileName() + "." + System.nanoTime() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			AnomalyMod.LOGGER.error("Anomaly: не смог записать остаток в {}", file, e);
			return false;
		}
	}

	/**
	 * Убрать брошенные временные файлы: kill -9 в момент записи оставлял их в world/data.
	 * Чужие (свежие) не трогаем — их может писать остановленный поток.
	 */
	private static void cleanTmp(Path parent, Path file) {
		if (parent == null) {
			return;
		}
		String prefix = file.getFileName().toString() + ".";
		long cutoff = System.currentTimeMillis() - 30 * 60_000L;
		try (var list = Files.newDirectoryStream(parent, prefix + "*.tmp")) {
			for (Path stale : list) {
				try {
					if (Files.getLastModifiedTime(stale).toMillis() < cutoff) {
						Files.deleteIfExists(stale);
					}
				} catch (IOException ignored) {
					// следующий сейв попробует снова
				}
			}
		} catch (IOException ignored) {
			// нет каталога или прав — не повод падать
		}
	}

	static Data read(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
		} catch (Exception e) {
			AnomalyMod.LOGGER.error("Anomaly: остаток {} не читается ({}), начинаю с чистого (старый в .bak)",
					file, e.getMessage());
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
						StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// копия не обязательна: файл всё равно битый
			}
			return null;
		}
	}

	// --- шкала ---

	public static double hours(UUID player) {
		Entry entry = data.players.get(player.toString());
		return entry == null ? 0.0 : entry.hours;
	}

	public static double hours(ServerPlayer player) {
		return hours(player.getUUID());
	}

	public static void set(UUID player, double value) {
		Entry entry = entry(player);
		entry.hours = clamp(value);
		entry.updated_ms = System.currentTimeMillis();
		dirty = true;
	}

	public static void add(UUID player, double value) {
		Entry entry = entry(player);
		entry.hours = clamp(entry.hours + value);
		entry.updated_ms = System.currentTimeMillis();
		dirty = true;
	}

	public static void clear(UUID player) {
		data.players.remove(player.toString());
		dirty = true;
	}

	public static String state(double hours) {
		if (hours >= DEEP_HUNGER) {
			return "голод глубины";
		}
		if (hours >= VEIL) {
			return "пелена";
		}
		if (hours >= SEAL) {
			return "печать ада";
		}
		if (hours >= AFTERTASTE) {
			return "оскомина";
		}
		if (hours >= TREMOR) {
			return "дрожь";
		}
		return hours > 0.0 ? "след" : "чисто";
	}

	private static double clamp(double value) {
		double cap = AnomalyMod.config == null ? 24.0 : AnomalyMod.config.residue_cap_hours;
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(cap, value));
	}

	private static Entry entry(UUID player) {
		String key = player.toString();
		Entry entry = data.players.get(key);
		if (entry == null) {
			entry = new Entry();
			entry.updated_ms = System.currentTimeMillis();
			data.players.put(key, entry);
		}
		return entry;
	}

	/**
	 * Шаг шкалы как чистая функция: рост за этот шаг, спад, «рядом с другим» и центр мира.
	 * Вынесено отдельно, чтобы самопроверка гоняла числа, а не игрока.
	 */
	static double nextHours(double hours, double growth, double decayPerHour, double dtHours,
			boolean company, boolean center, double cap) {
		double decay = decayPerHour * (center ? 2.0 : 1.0);
		double next = hours + growth - decay * dtHours;
		if (company) {
			// «рядом с другим игроком −10 % в час»: это доля текущего остатка, а не константа
			next *= Math.pow(0.9, Math.max(0.0, dtHours));
		}
		if (!Double.isFinite(next)) {
			return hours;
		}
		return Math.max(0.0, Math.min(Math.max(0.0, cap), next));
	}

	/** Часы остатка, которые приносит одна секунда в зоне: безопасная не даёт ничего. */
	static double growthPerHour(AnomalyClass cls, double depth, double dose) {
		double rate = switch (cls) {
			case SAFE -> 0.0;
			case DANGER -> GAIN_DANGER;
			case DEADLY -> GAIN_DEADLY;
		};
		// у края вчетверо слабее, чем в ядре: «зайти по колено» не наказывается как «дойти до центра»
		if (rate <= 0.0) {
			// безопасная зона не наказывает вообще, даже полной дозой
			return 0.0;
		}
		double body = rate * (0.25 + 0.75 * Math.max(0.0, Math.min(1.0, depth)));
		// пиковая доза: пока в крови полная доза, остаток добирается быстрее
		return body + 1.5 * Math.max(0.0, Math.min(1.0, dose)) * Math.max(0.0, Math.min(1.0, dose));
	}

	// --- шаг на игроке ---

	/** Вызывается с периодом тика эффектов. {@code inside} — в какой зоне игрок, если в какой-то. */
	public static void update(ServerPlayer player, double seconds, Zone inside, double dose) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.residue_enabled) {
			return;
		}
		// в креативе и наблюдателем не болеют, но шкала всё равно тает: иначе наблюдатель —
		// бесплатная пауза, а админ в креативе никогда не отдышится
		boolean immune = player.isCreative() || player.isSpectator();
		long now = System.currentTimeMillis();
		Entry entry = entry(player.getUUID());
		// шаг по реальным часам: сервер мог стоять, а игрок — нет; минута за один вызов уже много
		double dtHours = Math.min(0.25, Math.max(0.0, (now - entry.updated_ms) / HOUR_MS));
		entry.updated_ms = now;
		if (dtHours <= 0.0) {
			return;
		}

		double growth = 0.0;
		if (inside != null && !inside.remnant() && !immune) {
			double radius = Math.max(1.0, inside.radius());
			double depth = -inside.signedDistance(player.getX(), player.getZ()) / radius;
			growth = growthPerHour(inside.cls(), depth, dose) * dtHours;
		}
		boolean company = hasCompany(player);
		boolean center = Math.hypot(player.getX(), player.getZ()) < 2000.0;
		double next = nextHours(entry.hours, growth, config.residue_decay_per_hour, dtHours,
				company, center, config.residue_cap_hours);
		if (Math.abs(next - entry.hours) > 1.0e-6) {
			entry.hours = next;
			dirty = true;
		}
		if (!immune) {
			effects(player, entry, now);
		}
	}

	/** «Рядом с другим игроком −10 % в час»: это доля текущего остатка, а не константа. */
	private static boolean hasCompany(ServerPlayer player) {
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			if (other != player && other.level() == player.level()
					&& other.distanceToSqr(player) < 32.0 * 32.0) {
				return true;
			}
		}
		return false;
	}

	/** Последствия остатка. Всё тихо, без текста: эффект — это и есть сообщение. */
	private static void effects(ServerPlayer player, Entry entry, long now) {
		if (entry.hours >= DEEP_HUNGER) {
			deepHunger(player, entry);
		} else {
			entry.lastFood = -1;
		}
		long sec = now / 1000L;
		long phase = phase(player.getUUID());

		if (entry.hours >= TREMOR && sec != entry.tremorSec
				&& window(sec, phase, 240, 5)) {
			entry.tremorSec = sec;
			player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 90, 0, false, true, true));
			player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0, false, true, true));
		}
		if (entry.hours >= VEIL && sec != entry.veilSec
				&& window(sec, phase, 150, 4)) {
			entry.veilSec = sec;
			player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, true, true));
			player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, true, true));
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.AMBIENT, 0.35f, 0.6f);
		}
	}

	/**
	 * «Голод глубины»: еда насыщает вдвое хуже. Клиент предсказывает еду сам, поэтому
	 * откат делается по разнице шкалы сытости, а не запретом предмета.
	 */
	private static void deepHunger(ServerPlayer player, Entry entry) {
		FoodData food = player.getFoodData();
		int level = food.getFoodLevel();
		float saturation = food.getSaturationLevel();
		if (entry.lastFood >= 0 && level > entry.lastFood) {
			int gained = level - entry.lastFood;
			int keep = Math.max(1, (int) Math.ceil(gained * 0.5));
			food.setFoodLevel(Math.min(20, entry.lastFood + keep));
			food.setSaturation(Math.min(food.getSaturationLevel(), entry.lastSat + keep * 0.5f));
		}
		entry.lastFood = food.getFoodLevel();
		entry.lastSat = food.getSaturationLevel();
	}

	/** Детерминированное окно на UUID: одинаковое у всех, кто видит этого игрока, и без таймеров. */
	static boolean window(long sec, long phase, int period, int length) {
		if (period <= 0 || length <= 0) {
			return false;
		}
		return Math.floorMod(sec + phase, period) < length;
	}

	private static long phase(UUID player) {
		return Math.floorMod(player.getMostSignificantBits() ^ player.getLeastSignificantBits(), 3600L);
	}

	// --- последствия, которые спрашивают другие классы ---

	/** «Оскомина»: в окно 5–10 минут из каждых 20 элитра не держит фейерверк. */
	public static boolean mufflesFirework(ServerPlayer player) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled || !config.residue_enabled || player.isCreative() || player.isSpectator()) {
			return false;
		}
		if (hours(player) < AFTERTASTE) {
			return false;
		}
		long sec = System.currentTimeMillis() / 1000L;
		return window(sec, phase(player.getUUID()), 1200, 420);
	}

	/** «Печать ада»: портал в Нижний не берёт. Возврат из Нижнего — не блокируем. */
	public static boolean sealsNether(ServerPlayer player) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled || !config.residue_enabled || player.isCreative() || player.isSpectator()) {
			return false;
		}
		return hours(player) >= SEAL;
	}

	/**
	 * Насколько злее бьёт зона у игрока с остатком: 1.0 до «оскомины», до 1.5 к «голоду глубины».
	 * Так остаток мешает идти в аномалию, а не только висит тьмой по ночам.
	 */
	static double hazardScale(double hours) {
		if (!Double.isFinite(hours) || hours <= AFTERTASTE) {
			return 1.0;
		}
		double span = Math.max(1.0, DEEP_HUNGER - AFTERTASTE);
		return 1.0 + 0.5 * Math.min(1.0, (hours - AFTERTASTE) / span);
	}

	public static double hazardScale(ServerPlayer player) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled || !config.residue_enabled) {
			return 1.0;
		}
		return hazardScale(hours(player));
	}

	/** Сколько преследователей приводит остаток при входе в зону: 0 — никто не идёт. */
	static int raidCount(double hours) {
		if (!Double.isFinite(hours) || hours < VEIL) {
			return 0;
		}
		return (int) Math.min(3, 1 + Math.floor((hours - VEIL) / 4.0));
	}

	/**
	 * «Рейды остатка»: с «пеленой» и выше игрок не может просто зайти в зону — за ним идут.
	 * Мобы появляются в 8–16 блоках, сразу с целью на игроке: отсидеться у края не выйдет.
	 */
	public static void raidOnEnter(ServerLevel level, ServerPlayer player, Zone zone) {
		AnomalyConfig config = AnomalyMod.config;
		if (config == null || !config.enabled || !config.residue_enabled || zone.remnant()) {
			return;
		}
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		int count = raidCount(hours(player));
		if (count <= 0) {
			return;
		}
		Random rnd = new Random(player.getUUID().getLeastSignificantBits() ^ System.nanoTime());
		for (int i = 0; i < count; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double distance = 8.0 + rnd.nextDouble() * 8.0;
			double x = player.getX() + Math.cos(angle) * distance;
			double z = player.getZ() + Math.sin(angle) * distance;
			int bx = (int) Math.floor(x);
			int bz = (int) Math.floor(z);
			if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
				continue;
			}
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			if (y <= level.getMinY() + 2 || y >= level.getMaxY() - 4) {
				continue;
			}
			Mob stalker = rnd.nextBoolean()
					? EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT)
					: EntityTypes.SKELETON.create(level, EntitySpawnReason.EVENT);
			if (stalker == null) {
				continue;
			}
			stalker.snapTo(x, y + 0.1, z, rnd.nextFloat() * 360f, 0f);
			stalker.addTag("anomaly:stalker");
			level.addFreshEntity(stalker);
			stalker.setTarget(player);
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.WARDEN_ROAR, SoundSource.AMBIENT, 0.7f, 0.6f);
	}

	/** Отказ портала слышно и видно телом: портал гудит низко, а игрока отбрасывает кулдауном. */	public static void onPortalRefused(ServerPlayer player) {
		Entry entry = entry(player.getUUID());
		long now = System.currentTimeMillis();
		if (now - entry.lastRefuseMs < 1500L) {
			return;
		}
		entry.lastRefuseMs = now;
		player.setPortalCooldown(60);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.PORTAL_AMBIENT, SoundSource.AMBIENT, 0.8f, 0.5f);
	}

	/** События шины: сон снимает 1–2 часа, фейерверк в «оскомине» глохнет. */
	public static void register() {
		// цель миксина загружаем сразу: провал инжектора должен быть виден на старте сервера,
		// а не в момент, когда игрок с остатком 8+ часов впервые войдёт в портал
		AnomalyMod.LOGGER.info("Anomaly: портальный миксин на {}", net.minecraft.world.entity.PortalProcessor.class.getName());
		EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
			if (entity instanceof ServerPlayer player) {
				Entry entry = entry(player.getUUID());
				entry.asleepSinceMs = System.currentTimeMillis();
				entry.asleepAtNight = player.level().isDarkOutside();
			}
		});
		EntitySleepEvents.STOP_SLEEPING.register((entity, pos) -> {
			AnomalyConfig config = AnomalyMod.config;
			if (config == null || !config.enabled || !config.residue_enabled) {
				return;
			}
			if (!(entity instanceof ServerPlayer player)) {
				return;
			}
			Entry entry = entry(player.getUUID());
			long sleptMs = entry.asleepSinceMs > 0L ? System.currentTimeMillis() - entry.asleepSinceMs : 0L;
			boolean skippedNight = entry.asleepAtNight && isDay(player);
			entry.asleepSinceMs = 0L;
			entry.asleepAtNight = false;
			// «полежал минуту и встал» сном не считается: иначе кровать — бесплатный целитель.
			// Ночь на сервере пропускают сразу, как лёг последний спящий (сон длится тик),
			// поэтому «лёг ночью, встал днём» — тоже полноценный сон.
			if ((sleptMs >= 60_000L || skippedNight) && entry.hours > 0.0) {
				double relief = 1.0 + player.getRandom().nextDouble();
				entry.hours = clamp(entry.hours - relief);
				entry.updated_ms = System.currentTimeMillis();
				dirty = true;
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
						SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 0.6f);
			}
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = player.getItemInHand(hand);
			if (!stack.is(Items.FIREWORK_ROCKET) || !player.isFallFlying()) {
				return InteractionResult.PASS;
			}
			if (!mufflesFirework(serverPlayer)) {
				return InteractionResult.PASS;
			}
			// шипит и не зажигается: понятно без единой буквы
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8f, 1.3f);
			return InteractionResult.FAIL;
		});
	}

	/** День по мировым часам: 0–13000. */
	private static boolean isDay(ServerPlayer player) {
		return player.level().getOverworldClockTime() % 24000L < 13000L;
	}
}
