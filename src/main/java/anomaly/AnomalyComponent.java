package anomaly;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Компонент аномалии — одна чёрточка её характера. Аномалия не «тип», а набор: голод, холод, тяга,
 * световая стена. Пассивные действуют на игрока, видимые — на то, что он видит и слышит.
 * <p>
 * Каждый компонент стоит единицы бюджета смертоносности, и класс аномалии этот бюджет ограничивает:
 * безопасная набирает дешёвые и не может набрать урон в принципе (у неё нет компонентов с
 * {@code minClass = DANGER}). Запрещённые пары сделаны группами: не больше одного «прямого урона»
 * (кроме смертельных), одной «еды», двух «шоу».
 * <p>
 * Всё здесь работает на серверных каналах: эффекты, частицы, звуки, голод, опыт, воздух, заморозка,
 * смена скорости. Ни одного клиентского класса.
 */
public enum AnomalyComponent {
	// --- пассивные ---
	HUNGER_DRAIN("hunger_drain", "голод", Kind.PASSIVE, AnomalyClass.SAFE, 1, "food", 0x8FA36B),
	SATURATION_BURN("saturation_burn", "пустая еда", Kind.PASSIVE, AnomalyClass.DANGER, 2, "food", 0x9C8A5A),
	XP_LEAK("xp_leak", "утечка опыта", Kind.PASSIVE, AnomalyClass.SAFE, 1, null, 0x7FCF7A),
	COLD_CREEP("cold_creep", "холод", Kind.PASSIVE, AnomalyClass.DANGER, 2, "damage", 0xBFE3FF),
	AIR_DRAIN("air_drain", "удушье", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x6FB8C8),
	PRESSURE("pressure", "давление", Kind.PASSIVE, AnomalyClass.DANGER, 2, "damage", 0xB85C5C),
	NIGHT_TOLL("night_toll", "ночной сбор", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x3A3F55),
	DRIP("drip", "капель", Kind.PASSIVE, AnomalyClass.SAFE, 1, "sound", 0x7E8A96),
	/** Тянет к ядру: идти против течения дорого, у края не отсидеться. */
	TRACTION("traction", "тяга", Kind.PASSIVE, AnomalyClass.DANGER, 2, "motion", 0x9AA0A6),
	/** Отталкивает от ядра: выйти можно, но не по прямой. */
	REPULSION("repulsion", "отторжение", Kind.PASSIVE, AnomalyClass.DANGER, 2, "motion", 0xB0A6C0),

	// --- видимые ---
	LIGHT_WALL("light_wall", "световая стена", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0),
	ASH_FALL("ash_fall", "пепел", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0x8A8578),
	ECHO_BURSTS("echo_bursts", "хлопки", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0xC8C0B0),
	STORM_CORE("storm_core", "грозовое ядро", Kind.VISIBLE, AnomalyClass.DANGER, 2, "show", 0xFFF07A),
	GLOW_ALL("glow_all", "подсветка", Kind.VISIBLE, AnomalyClass.DANGER, 2, null, 0xA8E6CF),
	FLOAT_ALL("float_all", "невесомость", Kind.VISIBLE, AnomalyClass.DANGER, 3, null, 0xD8C8FF);

	public enum Kind {
		PASSIVE, VISIBLE
	}

	/** Пары, которые дороже и злее: мороз + пепел, гроза + невесомость. */
	private static final String[][] SYNERGIES = {
			{"cold_creep", "ash_fall"},
			{"storm_core", "float_all"}
	};

	public final String id;
	public final String title;
	public final Kind kind;
	/** С какого класса компонент вообще разрешён (SAFE — всем, DANGER — опасным и смертельным). */
	public final AnomalyClass minClass;
	public final int cost;
	/** Группа взаимоисключения: не больше одного из группы (у «шоу» и «урона» лимит выше). */
	public final String group;
	/** Оттенок компонента для края и частиц; 0 — не подмешивать (край остаётся цвета класса). */
	public final int color;

	AnomalyComponent(String id, String title, Kind kind, AnomalyClass minClass, int cost, String group, int color) {
		this.id = id;
		this.title = title;
		this.kind = kind;
		this.minClass = minClass;
		this.cost = cost;
		this.group = group;
		this.color = color;
	}

	public static AnomalyComponent byId(String id) {
		if (id != null) {
			for (AnomalyComponent component : values()) {
				if (component.id.equals(id)) {
					return component;
				}
			}
		}
		return null;
	}

	/** Парсинг списка id из файла: незнакомые и повторные выбрасываются. */
	public static List<AnomalyComponent> parse(List<String> ids) {
		List<AnomalyComponent> out = new ArrayList<>();
		if (ids == null) {
			return out;
		}
		for (String id : ids) {
			AnomalyComponent component = byId(id);
			if (component != null && !out.contains(component)) {
				out.add(component);
			}
		}
		return out;
	}

	public boolean isVisible() {
		return kind == Kind.VISIBLE;
	}

	public boolean allows(AnomalyClass cls) {
		return cls.ordinal() >= minClass.ordinal();
	}

	// --- генерация набора ---

	static int minCount(AnomalyClass cls) {
		return switch (cls) {
			case SAFE -> 1;
			case DANGER -> 2;
			case DEADLY -> 3;
		};
	}

	static int maxCount(AnomalyClass cls) {
		return switch (cls) {
			case SAFE -> 2;
			case DANGER -> 4;
			case DEADLY -> 6;
		};
	}

	static int budgetCap(AnomalyClass cls) {
		return switch (cls) {
			case SAFE -> 2;
			case DANGER -> 5;
			case DEADLY -> 8;
		};
	}

	/** Всё, что классу вообще разрешено и не выключено в конфиге. */
	public static List<AnomalyComponent> allowed(AnomalyClass cls, AnomalyConfig config) {
		List<AnomalyComponent> out = new ArrayList<>();
		for (AnomalyComponent component : values()) {
			if (component.allows(cls) && (config == null || config.isEnabled(component))) {
				out.add(component);
			}
		}
		return out;
	}

	/**
	 * Набор на аномалию: сначала по одному пассивному и одному видимому (чтобы зона была и
	 * ощутимой, и заметной), потом добор по бюджету. Крупные получают больше.
	 */
	public static List<AnomalyComponent> roll(AnomalyClass cls, Random rnd, AnomalyConfig config, double radius) {
		List<AnomalyComponent> pool = allowed(cls, config);
		if (pool.isEmpty()) {
			return new ArrayList<>();
		}
		Collections.shuffle(pool, rnd);
		int count = minCount(cls) + rnd.nextInt(maxCount(cls) - minCount(cls) + 1);
		if (radius >= 800.0) {
			count++;
		}
		int budget = budgetCap(cls) + (radius >= 1800.0 ? 2 : 0);

		List<AnomalyComponent> picked = new ArrayList<>(count);
		// видимое берём первым: зона без видимого компонента невидима в мире, а это не «аномалия»
		budget -= addFirst(picked, pool, Kind.VISIBLE, budget, cls);
		if (count >= 2) {
			budget -= addFirst(picked, pool, Kind.PASSIVE, budget, cls);
		}
		for (AnomalyComponent candidate : pool) {
			if (picked.size() >= count) {
				break;
			}
			if (canAdd(picked, candidate, budget, cls)) {
				budget -= effectiveCost(picked, candidate);
				picked.add(candidate);
			}
		}
		if (picked.isEmpty()) {
			picked.add(pool.get(0));
		}
		picked.sort((a, b) -> a.ordinal() - b.ordinal());
		return picked;
	}

	/** Добавляет первый подходящий компонент вида и возвращает его цену (0, если не нашлось). */
	private static int addFirst(List<AnomalyComponent> picked, List<AnomalyComponent> pool, Kind kind,
			int budget, AnomalyClass cls) {
		for (AnomalyComponent component : pool) {
			if (component.kind == kind && canAdd(picked, component, budget, cls)) {
				picked.add(component);
				return effectiveCost(picked, component);
			}
		}
		return 0;
	}

	private static boolean canAdd(List<AnomalyComponent> picked, AnomalyComponent candidate, int budget,
			AnomalyClass cls) {
		if (picked.contains(candidate)) {
			return false;
		}
		if (effectiveCost(picked, candidate) > budget) {
			return false;
		}
		if (candidate.group == null) {
			return true;
		}
		int sameGroup = 0;
		for (AnomalyComponent component : picked) {
			if (candidate.group.equals(component.group)) {
				sameGroup++;
			}
		}
		int limit = switch (candidate.group) {
			case "food" -> 1;
			case "damage" -> cls == AnomalyClass.DEADLY ? 2 : 1;
			case "show" -> cls == AnomalyClass.DEADLY ? 3 : 2;
			default -> 1;
		};
		return sameGroup < limit;
	}

	/** Синергия делает компонент дороже на единицу — «считаются дороже» из концепции. */
	private static int effectiveCost(List<AnomalyComponent> picked, AnomalyComponent candidate) {
		boolean synergy = false;
		for (String[] pair : SYNERGIES) {
			AnomalyComponent a = byId(pair[0]);
			AnomalyComponent b = byId(pair[1]);
			if ((candidate == a && picked.contains(b)) || (candidate == b && picked.contains(a))) {
				synergy = true;
			}
		}
		return candidate.cost + (synergy ? 1 : 0);
	}

	public static int synergyCount(List<AnomalyComponent> components) {
		int count = 0;
		for (String[] pair : SYNERGIES) {
			if (components.contains(byId(pair[0])) && components.contains(byId(pair[1]))) {
				count++;
			}
		}
		return count;
	}

	/** 1.0 без синергий, +25 % за каждую пару. */
	public static double intensity(List<AnomalyComponent> components) {
		return 1.0 + 0.25 * synergyCount(components);
	}

	/** Полная цена набора: стоимости компонентов плюс по единице за каждую синергию. */
	public static int totalCost(List<AnomalyComponent> components) {
		int sum = 0;
		for (AnomalyComponent component : components) {
			sum += component.cost;
		}
		return sum + synergyCount(components);
	}

	public static List<String> ids(List<AnomalyComponent> components) {
		List<String> out = new ArrayList<>(components.size());
		for (AnomalyComponent component : components) {
			out.add(component.id);
		}
		return out;
	}

	public static String titles(List<AnomalyComponent> components) {
		if (components.isEmpty()) {
			return "нет";
		}
		StringBuilder text = new StringBuilder();
		for (AnomalyComponent component : components) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(component.title);
		}
		return text.toString();
	}

	// --- поведение ---

	/** Внутри зоны: пассивные действуют на игрока. {@code dose} 0..1, {@code intensity} — синергии и ночь. */
	public void apply(ServerLevel level, ServerPlayer player, Zone zone, AnomalyState state, double dose,
			double intensity, double seconds, AnomalyConfig config) {
		switch (this) {
			case HUNGER_DRAIN -> player.causeFoodExhaustion((float) (0.02 * dose * intensity * seconds));
			case SATURATION_BURN -> {
				FoodData food = player.getFoodData();
				if (food.getSaturationLevel() > 0.0f) {
					food.setSaturation(Math.max(0.0f,
							food.getSaturationLevel() - (float) (0.15 * dose * intensity * seconds)));
				}
			}
			case XP_LEAK -> {
				// минус в опыт на нуле уводит счётчик в отрицательные значения — не трогаем пустой карман;
				// раз в 20–40 с. Период кратен шагу тика, иначе он почти никогда не совпадёт с сеткой
				long period = 400L + 10L * Math.floorMod(zone.id(), 40L);
				if (state.pulseTicks % period == 0L && player.totalExperience > 0) {
					player.giveExperiencePoints(-1);
				}
			}
			// ваниль снимает 2 за тик, то есть 20 за 0.5 с; +30 за шаг даёт нетто +10 и реальный набор инея
			case COLD_CREEP -> {
				if (!player.isCreative()) {
					player.setTicksFrozen(Math.min(300, player.getTicksFrozen()
							+ (int) Math.round(3.0 * AnomalyState.stepTicks(seconds) * dose * intensity)));
				}
			}
			case AIR_DRAIN -> {
				if (player.isUnderWater() && !player.isCreative()) {
					// не режем нулём: ванильный урон начинается только при воздухе ≤ −20
					int drain = (int) Math.round(0.8 * AnomalyState.stepTicks(seconds) * dose * intensity);
					player.setAirSupply(Math.max(-20, player.getAirSupply() - Math.max(1, drain)));
				}
			}
			case PRESSURE -> {
				// 0.5 урона раз в 20 с, а не раз в 2: иначе «опасная» убивает за полторы минуты
				state.damageTicks += AnomalyState.stepTicks(seconds);
				if (state.damageTicks >= 400) {
					state.damageTicks = 0;
					player.hurt(level.damageSources().magic(), (float) (0.5 * dose * intensity));
				}
			}
			case TRACTION -> AnomalyEffects.shoveTowards(player, zone, 0.02 + 0.05 * dose, state);
			case REPULSION -> AnomalyEffects.shoveAway(player, zone, 0.02 + 0.05 * dose, state);
			default -> {
				// видимые в apply ничего не делают; DRIP и NIGHT_TOLL работают через pulse/множитель
			}
		}
	}

	/**
	 * Видимое и слышимое: вызывается, когда игрок в пределах {@code view_range}, — и внутри, и у края.
	 * Частицы ставятся у самого игрока, а не в центре зоны: у области в три километра центра не видно.
	 */
	public void pulse(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config, AnomalyState state) {
		var rnd = player.getRandom();
		// пассивные компоненты действуют внутри зоны: их звуки и пыль снаружи вводили бы в
		// заблуждение («еда утекает» там, где ничего не утекает)
		if ((this == HUNGER_DRAIN || this == SATURATION_BURN || this == XP_LEAK || this == AIR_DRAIN
				|| this == TRACTION || this == REPULSION)
				&& zone.signedDistance(player.getX(), player.getZ()) > 0.0) {
			return;
		}
		switch (this) {
			case LIGHT_WALL -> {
				// стену рисует ambience: он знает, что у зоны есть light_wall, и делает дугу выше,
				// а второй проход по тем же точкам — это двойные пакеты без нового вида
			}
			case ASH_FALL -> {
				// пепел падает сверху и виден только под открытым небом: в пещере он появлялся бы
				// в камне над головой, где его никто не увидит
				double ceiling = level.getHeight(
						net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						(int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
				if (ceiling > player.getY() + 2.0) {
					break;
				}
				for (int i = 0; i < 6; i++) {
					level.sendParticles(player, ParticleTypes.ASH, true, true,
							player.getX() + (rnd.nextDouble() - 0.5) * 8.0,
							player.getY() + 3.0 + rnd.nextDouble() * 3.0,
							player.getZ() + (rnd.nextDouble() - 0.5) * 8.0,
							1, 0.0, -0.02, 0.0, 0.0);
				}
			}
			case ECHO_BURSTS -> {
				if (rnd.nextInt(24) == 0) {
					double x = player.getX() + (rnd.nextDouble() - 0.5) * 24.0;
					double z = player.getZ() + (rnd.nextDouble() - 0.5) * 24.0;
					double y = surfaceOr(level, x, z, player.getY()) + 1.0;
					level.sendParticles(player, ParticleTypes.EXPLOSION, true, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
					// радиус слышимости ванильного звука = max(16, 16·vol): 0.35 до игрока не доходил
					level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 1.6f, 1.6f);
					// хлопок — не только картинка: маленькая воронка на 1–2 блока
					if (canMark(level)) {
						AnomalyScars.microCrater(level, x, z, 0.8 + rnd.nextDouble() * 0.8, 1 + rnd.nextInt(2), player.getY());
					}
				}
			}
			case STORM_CORE -> {
				if (rnd.nextInt(40) == 0) {
					double x = player.getX() + (rnd.nextDouble() - 0.5) * 32.0;
					double z = player.getZ() + (rnd.nextDouble() - 0.5) * 32.0;
					AnomalyEffects.visualBolt(level, x, surfaceOr(level, x, z, player.getY()), z);
					// молния оставляет гарь, а иногда и воронку: следы, которые видно потом
					if (canMark(level)) {
						AnomalyScars.scorch(level, x, z, 1.5 + rnd.nextDouble(), player.getY());
						if (rnd.nextInt(3) == 0) {
							AnomalyScars.microCrater(level, x, z, 0.7, 1, player.getY());
						}
					}
				}
			}
			case GLOW_ALL -> {
				if (state.pulseTicks % 40 < state.lastStep) {
					for (LivingEntity entity : nearby(level, player, 24.0)) {
						entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false, false));
					}
				}
			}
			case FLOAT_ALL -> {
				if (state.pulseTicks % 40 < state.lastStep) {
					for (LivingEntity entity : nearby(level, player, 24.0)) {
						entity.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, 0, false, true, true));
					}
					// игрок висит только внутри зоны: иначе левитация «догоняет» снаружи и убивает падением
					if (zone.signedDistance(player.getX(), player.getZ()) <= 0.0) {
						player.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, 0, false, true, true));
					}
					// и дроп тоже: «всё, что не приколочено»
					for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
							new AABB(player.getX() - 16.0, player.getY() - 6.0, player.getZ() - 16.0,
									player.getX() + 16.0, player.getY() + 8.0, player.getZ() + 16.0))) {
						item.push(0.0, 0.08, 0.0);
					}
				}
			}
			case DRIP -> {
				if (rnd.nextInt(30) == 0) {
					level.playSound(null, player.getX() + (rnd.nextDouble() - 0.5) * 8.0, player.getY() + 2.0,
							player.getZ() + (rnd.nextDouble() - 0.5) * 8.0,
							SoundEvents.BEEHIVE_DRIP, SoundSource.AMBIENT, 0.5f, 0.7f);
				}
			}
			case COLD_CREEP -> {
				// холод видно: редкие снежинки вокруг того, кто внутри
				if (zone.signedDistance(player.getX(), player.getZ()) <= 0.0) {
					for (int i = 0; i < 2; i++) {
						level.sendParticles(player, ParticleTypes.SNOWFLAKE, true, true,
								player.getX() + (rnd.nextDouble() - 0.5) * 8.0,
								player.getY() + rnd.nextDouble() * 3.0,
								player.getZ() + (rnd.nextDouble() - 0.5) * 8.0,
								1, 0.0, -0.01, 0.0, 0.0);
					}
					// наледь остаётся на земле: раз в ~20 с маленькое пятно снега
					if (canMark(level) && rnd.nextInt(40) == 0) {
						AnomalyScars.frost(level,
								player.getX() + (rnd.nextDouble() - 0.5) * 8.0,
								player.getZ() + (rnd.nextDouble() - 0.5) * 8.0, 2.5, player.getY());
					}
				}
			}
			case HUNGER_DRAIN -> {
				// невидимый пассив должен хотя бы звучать: желудок урчит, когда еда утекает
				if (rnd.nextInt(200) == 0) {
					level.playSound(null, player.getX(), player.getY(), player.getZ(),
							SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 0.6f, 0.9f);
				}
			}
			case SATURATION_BURN -> {
				if (rnd.nextInt(200) == 0) {
					level.playSound(null, player.getX(), player.getY(), player.getZ(),
							SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 0.6f, 0.9f);
				}
			}
			case XP_LEAK -> {
				if (rnd.nextInt(100) == 0) {
					level.playSound(null, player.getX(), player.getY(), player.getZ(),
							SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.AMBIENT, 0.5f, 1.4f);
				}
			}
			case AIR_DRAIN -> {
				// плеск только у того, кто действительно под водой
				if (player.isUnderWater() && rnd.nextInt(20) == 0) {
					level.playSound(null, player.getX(), player.getY() + 1.2, player.getZ(),
							SoundEvents.GENERIC_SPLASH, SoundSource.AMBIENT, 0.4f, 1.2f);
				}
			}
			case TRACTION, REPULSION -> dustTowardsCore(level, player, zone, rnd, this == REPULSION);
			case PRESSURE -> {
				// 1/8 при 2 Гц — раз в 4 с; 2 искры раз в 10 с на глаз не читались
				if (rnd.nextInt(8) == 0) {
					level.sendParticles(player, ParticleTypes.CRIT, true, true,
							player.getX() + (rnd.nextDouble() - 0.5) * 6.0,
							player.getY() + 1.0 + rnd.nextDouble() * 2.0,
							player.getZ() + (rnd.nextDouble() - 0.5) * 6.0, 6, 0.0, 0.0, 0.0, 0.15);
				}
				// давление проламывает пол на один блок: раз в ~20 с
				if (canMark(level) && rnd.nextInt(40) == 0) {
					AnomalyScars.microCrater(level,
							player.getX() + (rnd.nextDouble() - 0.5) * 6.0,
							player.getZ() + (rnd.nextDouble() - 0.5) * 6.0, 0.6, 1, player.getY());
				}
			}
			default -> {
			}
		}
	}

	/** Разрешены ли правки мира микрособытиями: включены разрушения и сервер не задыхается. */
	private static boolean canMark(ServerLevel level) {
		return AnomalyMod.config.scar_enabled
				&& level.getServer().getAverageTickTimeNanos() / 1_000_000.0 <= AnomalyMod.config.mspt_limit;
	}

	/** Пыль между игроком и ядром: тяга и отторжение перестают быть невидимыми. */
	private static void dustTowardsCore(ServerLevel level, ServerPlayer player, Zone zone,
			net.minecraft.util.RandomSource rnd, boolean away) {
		if (rnd.nextInt(4) != 0) {
			return;
		}
		double dx = zone.coreX() - player.getX();
		double dz = zone.coreZ() - player.getZ();
		double dist = Math.hypot(dx, dz);
		if (dist < 2.0) {
			return;
		}
		if (away) {
			dx = -dx;
			dz = -dz;
		}
		// пыль у самого игрока (4–12 блоков), а не у ядра в сотнях блоков: там её никто не увидит
		double t = Math.min(0.35, (4.0 + rnd.nextDouble() * 8.0) / dist);
		var color = new net.minecraft.core.particles.DustParticleOptions(zone.edgeColor(), 1.2f);
		level.sendParticles(player, color, true, true,
				player.getX() + dx * t,
				player.getY() + 1.0 + rnd.nextDouble(),
				player.getZ() + dz * t,
				2, 0.15, 0.25, 0.15, 0.02);
	}

	/** Высота рельефа для точки или запасное значение, если чанк не загружен. */
	private static double surfaceOr(ServerLevel level, double x, double z, double fallback) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return fallback;
		}
		return level.getHeight(
				net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
	}

	private static List<LivingEntity> nearby(ServerLevel level, ServerPlayer player, double range) {
		AABB box = new AABB(player.getX() - range, player.getY() - 8.0, player.getZ() - range,
				player.getX() + range, player.getY() + 16.0, player.getZ() + range);
		return level.getEntitiesOfClass(LivingEntity.class, box, entity -> entity != player && entity.isAlive());
	}

	public String describe() {
		return id + " (" + (kind == Kind.PASSIVE ? "пассив" : "видимое") + ", " + minClass.id + ", стоимость "
				+ cost + ")";
	}
}
