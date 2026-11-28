package anomaly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
	XP_LEAK("xp_leak", "утечка опыта", Kind.PASSIVE, AnomalyClass.DANGER, 1, null, 0x7FCF7A),
	COLD_CREEP("cold_creep", "холод", Kind.PASSIVE, AnomalyClass.DANGER, 2, "damage", 0xBFE3FF),
	AIR_DRAIN("air_drain", "удушье", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x6FB8C8),
	PRESSURE("pressure", "давление", Kind.PASSIVE, AnomalyClass.DANGER, 2, "damage", 0xB85C5C),
	NIGHT_TOLL("night_toll", "ночной сбор", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x3A3F55),
	DRIP("drip", "капель", Kind.PASSIVE, AnomalyClass.SAFE, 1, "sound", 0x7E8A96),
	/** Тянет к ядру: идти против течения дорого, у края не отсидеться. */
	TRACTION("traction", "тяга", Kind.PASSIVE, AnomalyClass.DANGER, 2, "motion", 0x9AA0A6),
	/** Отталкивает от ядра: выйти можно, но не по прямой. */
	REPULSION("repulsion", "отторжение", Kind.PASSIVE, AnomalyClass.DANGER, 2, "motion", 0xB0A6C0),
	/** В зоне не заснуть: сон прерывается, как только игрок ложится. */
	BAD_SLEEP("bad_sleep", "дурной сон", Kind.PASSIVE, AnomalyClass.SAFE, 1, null, 0x4A3F55),
	/** Мобы слышат дальше: подходят раньше, чем их видно. */
	MOB_EARS("mob_ears", "чуткие мобы", Kind.PASSIVE, AnomalyClass.SAFE, 1, null, 0x8C7F6A),
	/** Свет гаснет вокруг: свечи, фонари, костры — и сами закрываются двери. */
	DARK_SUCKING("dark_sucking", "свет гаснет", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x2B2B33),
	/** Гибельный цвет: трава под ногами умирает пятнами, как после долгой засухи. */
	HUSK_BLOOM("husk_bloom", "гибельный цвет", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x8A7B4A),
	/** Износ: броня и инструмент в руке стачиваются сами. */
	WEAR("wear", "износ", Kind.PASSIVE, AnomalyClass.DANGER, 2, null, 0x9A9080),
	/** Порча: еда в сумке скисает в гниль, и это видно только когда её достал. */
	LOOT_SOUR("loot_sour", "порча", Kind.PASSIVE, AnomalyClass.DANGER, 2, "food", 0x6B7A3A),
	/** Кривые стрелы: снаряды в зоне уводит в сторону, и стрелок промахивается. */
	ARROW_BEND("arrow_bend", "кривые стрелы", Kind.PASSIVE, AnomalyClass.DANGER, 1, null, 0xC0A86A),

	// --- видимые ---
	LIGHT_WALL("light_wall", "световая стена", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0),
	ASH_FALL("ash_fall", "пепел", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0x8A8578),
	ECHO_BURSTS("echo_bursts", "хлопки", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0xC8C0B0),
	STORM_CORE("storm_core", "грозовое ядро", Kind.VISIBLE, AnomalyClass.DANGER, 2, "show", 0xFFF07A),
	GLOW_ALL("glow_all", "подсветка", Kind.VISIBLE, AnomalyClass.DANGER, 2, null, 0xA8E6CF),
	FLOAT_ALL("float_all", "невесомость", Kind.VISIBLE, AnomalyClass.DANGER, 3, null, 0xD8C8FF),
	/** Чёрная фигура вдалеке: стоит и смотрит, исчезает, когда подходишь. */
	SHADES("shades", "силуэты", Kind.VISIBLE, AnomalyClass.SAFE, 1, "show", 0x2A2A33),
	/** Наковальни падают с неба: не молния, а именно чугун сверху. */
	ANVIL_RAIN("anvil_rain", "наковальни", Kind.VISIBLE, AnomalyClass.DANGER, 3, "show", 0x6E6E78),
	/** Кольцо шёпота: в нескольких шагах кругом что-то говорит, а никого нет. */
	MADNESS_RING("madness_ring", "кольцо шёпота", Kind.VISIBLE, AnomalyClass.DANGER, 2, "show", 0x7A5FA0);

	public enum Kind {
		PASSIVE, VISIBLE
	}

	/** Пары, которые дороже и злее: мороз + пепел, гроза + невесомость. */
	private static final String[][] SYNERGIES = {
			{"cold_creep", "ash_fall"},
			{"storm_core", "float_all"}
	};

	/** Модификатор «чуткие мобы»: transient, чтобы не копился при повторных пульсах. */
	private static final Identifier EARS_ID = Identifier.fromNamespaceAndPath("anomaly", "ears");

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
				if (state.pulseTicks % period < state.lastStep && player.totalExperience > 0) {
					player.giveExperiencePoints(-1);
				}
			}
			case BAD_SLEEP -> {
				// сон не берёт: ложишься — просыпаешься
				if (player.isSleeping()) {
					player.stopSleeping();
					level.playSound(null, player.getX(), player.getY(), player.getZ(),
							SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 0.7f, 0.55f);
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
			// износ: раз в ~20 с одна вещь теряет единицу прочности. Медленно, но за долгий
			// поход это ровно та цена, которую видно только дома
			case WEAR -> {
				long period = 400L + 10L * Math.floorMod(zone.id(), 20L);
				if (state.pulseTicks % period < state.lastStep) {
					wearArmour(player);
				}
			}
			// порча: раз в ~30 с часть еды в сумке скисает в гниль
			case LOOT_SOUR -> {
				if (state.pulseTicks % 600L < state.lastStep) {
					sourFood(player);
				}
			}
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
		double sd = zone.signedDistance(player.getX(), player.getZ());
		// пассивные компоненты действуют внутри зоны: их звуки и пыль снаружи вводили бы в
		// заблуждение («еда утекает» там, где ничего не утекает). Взрывы, молнии и проломы —
		// тоже внутри: за краем игрок должен видеть только косметику, а не воронки у себя под ногами.
		if (sd > 0.0 && (this == HUNGER_DRAIN || this == SATURATION_BURN || this == XP_LEAK
				|| this == AIR_DRAIN || this == TRACTION || this == REPULSION || this == MOB_EARS
				|| this == ECHO_BURSTS || this == STORM_CORE || this == PRESSURE
				|| this == WEAR || this == LOOT_SOUR || this == ARROW_BEND
				|| this == DARK_SUCKING || this == HUSK_BLOOM || this == ANVIL_RAIN
				|| this == GLOW_ALL || this == FLOAT_ALL || this == ASH_FALL || this == MADNESS_RING)) {
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
					double[] spot = eventSpot(level, player, zone, rnd);
					double x = spot[0];
					double z = spot[1];
					double y = surfaceOr(level, x, z, player.getY()) + 1.0;
					level.sendParticles(player, ParticleTypes.EXPLOSION, true, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
					// радиус слышимости ванильного звука = max(16, 16·vol): 0.35 до игрока не доходил
					level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 1.6f, 1.6f);
					// хлопок — не только картинка: маленькая воронка на 1–2 блока. Только у
					// классов, которые вообще рушат мир: у безопасной зоны бюджет нулевой.
					// nearY — поверхность этой колонки, а не высота игрока: дальняя воронка
					// иначе отказывала бы, потому что рельеф там на 10 блоков выше
					if (zone.cls().damages() && canMark(level, x, z)) {
						AnomalyScars.microCrater(level, x, z, 2.2 + rnd.nextDouble() * 0.8,
								1 + rnd.nextInt(2), surfaceOr(level, x, z, player.getY()));
					}
				}
			}
			case STORM_CORE -> {
				if (rnd.nextInt(40) == 0) {
					double[] spot = eventSpot(level, player, zone, rnd);
					double x = spot[0];
					double z = spot[1];
					double boltY = surfaceOr(level, x, z, player.getY());
					AnomalyEffects.visualBolt(level, x, boltY, z);
					// молния должна бить: визуальная вспышка без урона учила, что гроза безопасна
					for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
							new AABB(x - 3.0, boltY - 6.0, z - 3.0, x + 3.0, boltY + 6.0, z + 3.0))) {
						if (Math.hypot(victim.getX() - x, victim.getZ() - z) < 3.0) {
							victim.hurt(level.damageSources().magic(), 3.0f);
						}
					}
					// молния оставляет гарь, а иногда и воронку: следы, которые видно потом
					// nearY — поверхность этой колонки: у дальнего удара рельеф другой,
					// и «выше игрока на 4 блока» глушило след насовсем
					if (canMark(level, x, z)) {
						AnomalyScars.scorch(level, x, z, 1.5 + rnd.nextDouble(), boltY);
						if (rnd.nextInt(3) == 0) {
							AnomalyScars.microCrater(level, x, z, 2.2, 1, boltY);
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
						// за краем зоны только косметика: иначе животные у базы взлетают
						// и падают с уроном
						if (zone.signedDistance(entity.getX(), entity.getZ()) > 0.0) {
							continue;
						}
						entity.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, 0, false, true, true));
					}
					// игрок висит только внутри зоны: иначе левитация «догоняет» снаружи и убивает падением.
					// И не выше восьми блоков над землёй: без потолка набегало 1 бл/с, а выход из зоны
					// давал падение с десятков блоков — то есть смерть после боя
					if (zone.signedDistance(player.getX(), player.getZ()) <= 0.0) {
						double ground = surfaceOr(level, player.getX(), player.getZ(), player.getY());
						if (player.getY() < ground + 8.0) {
							player.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, 0, false, true, true));
						} else {
							player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, false, true, true));
						}
					}
					// и дроп тоже: «всё, что не приколочено» — но, как и мобы, только в зоне
					for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
							new AABB(player.getX() - 16.0, player.getY() - 6.0, player.getZ() - 16.0,
									player.getX() + 16.0, player.getY() + 8.0, player.getZ() + 16.0))) {
						if (zone.signedDistance(item.getX(), item.getZ()) <= 0.0) {
							item.push(0.0, 0.08, 0.0);
						}
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
					if (canMark(level, player.getX(), player.getZ()) && rnd.nextInt(40) == 0) {
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
			case MOB_EARS -> grantEars(level, player, rnd);
			case SHADES -> shades(level, player, rnd);
			case PRESSURE -> {
				// 1/8 при 2 Гц — раз в 4 с; 2 искры раз в 10 с на глаз не читались
				if (rnd.nextInt(8) == 0) {
					level.sendParticles(player, ParticleTypes.CRIT, true, true,
							player.getX() + (rnd.nextDouble() - 0.5) * 6.0,
							player.getY() + 1.0 + rnd.nextDouble() * 2.0,
							player.getZ() + (rnd.nextDouble() - 0.5) * 6.0, 6, 0.0, 0.0, 0.0, 0.15);
				}
				// давление проламывает пол на один блок: раз в ~20 с. В 4–6 блоках от игрока:
				// ближе microCrater колонку не берёт (защита «не ставить блок в игрока»),
				// и пол под ногами не рвался никогда
				if (canMark(level, player.getX(), player.getZ()) && rnd.nextInt(40) == 0) {
					double spotAngle = rnd.nextDouble() * Math.PI * 2.0;
					double spotDist = 4.0 + rnd.nextDouble() * 2.0;
					AnomalyScars.microCrater(level,
							player.getX() + Math.cos(spotAngle) * spotDist,
							player.getZ() + Math.sin(spotAngle) * spotDist, 0.6, 1, player.getY());
				}
			}
			case DARK_SUCKING -> {
				// свет в зоне не держится: свечи и костры гаснут, двери закрываются сами.
				// Только там, где зоне разрешено менять мир: в 6-км полосе и при выключенных
				// разрушениях базу игрока не трогаем
				if (rnd.nextInt(30) == 0 && canMark(level, player.getX(), player.getZ())) {
					darkSuck(level, player);
				}
			}
			case HUSK_BLOOM -> {
				// трава умирает пятнами: то же, что гарь от молнии, но без вспышки
				if (rnd.nextInt(60) == 0 && canMark(level, player.getX(), player.getZ())) {
					AnomalyScars.scorch(level,
							player.getX() + (rnd.nextDouble() - 0.5) * 10.0,
							player.getZ() + (rnd.nextDouble() - 0.5) * 10.0, 2.5, player.getY());
				}
			}
			case ARROW_BEND -> {
				// снаряды в зоне ведёт в сторону: стрелок целится в игрока, а попадает мимо
				for (Arrow arrow : level.getEntitiesOfClass(Arrow.class,
						new AABB(player.getX() - 24.0, player.getY() - 12.0, player.getZ() - 24.0,
								player.getX() + 24.0, player.getY() + 12.0, player.getZ() + 24.0))) {
					var motion = arrow.getDeltaMovement();
					double length = Math.hypot(motion.x, motion.z);
					if (length < 0.15) {
						continue;
					}
					arrow.push(-motion.z / length * 0.09, 0.0, motion.x / length * 0.09);
				}
			}
			case ANVIL_RAIN -> {
				// наковальня с неба: то, чего никто не ждёт и что трудно списать на баг.
				// Раз в ~100 с, только там, где зоне вообще разрешено менять мир
				if (rnd.nextInt(200) == 0 && canMark(level, player.getX(), player.getZ())) {
					anvilRain(level, player, rnd);
				}
			}
			case MADNESS_RING -> {
				// кольцо шёпота: голоса в трёх шагах кругом, а никого нет
				if (rnd.nextInt(8) == 0) {
					whisperRing(level, player, rnd);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Разрешены ли правки мира микрособытиями: включены разрушения, точка не в полосе подавления
	 * (ближе 6к мир не трогаем даже микрособытием) и сервер не задыхается.
	 */
	private static boolean canMark(ServerLevel level, double x, double z) {
		AnomalyConfig config = AnomalyMod.config;
		return config.scar_enabled
				&& Math.hypot(x, z) >= config.suppress_until
				&& level.getServer().getAverageTickTimeNanos() / 1_000_000.0
						<= Math.min(config.mspt_limit, 30.0);
	}

	/**
	 * Где рвать и куда бить молнией: обычно рядом с игроком, но раз в четыре раза — в 40–140
	 * блоках, всё ещё внутри зоны. Дальний хлопок видно и слышно (частицы и звук уходят по своим
	 * координатам), а воронка остаётся там: следы не обязаны появляться под ногами.
	 */
	private static double[] eventSpot(ServerLevel level, ServerPlayer player, Zone zone,
			net.minecraft.util.RandomSource rnd) {
		double angle = rnd.nextDouble() * Math.PI * 2.0;
		if (rnd.nextInt(4) == 0) {
			double far = 40.0 + rnd.nextDouble() * 100.0;
			double fx = player.getX() + Math.cos(angle) * far;
			double fz = player.getZ() + Math.sin(angle) * far;
			if (zone.signedDistance(fx, fz) <= 0.0
					&& level.getChunkSource().hasChunk((int) Math.floor(fx) >> 4, (int) Math.floor(fz) >> 4)) {
				return new double[]{fx, fz};
			}
		}
		// рядом — как было: ±12 блока, а не точка вплотную к игроку
		double near = 4.0 + rnd.nextDouble() * 8.0;
		return new double[]{
				player.getX() + Math.cos(angle) * near,
				player.getZ() + Math.sin(angle) * near};
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

	/** Мобы слышат дальше: follow range +8, чтобы подходили раньше, чем их видно. */
	private static void grantEars(ServerLevel level, ServerPlayer player, net.minecraft.util.RandomSource rnd) {
		if (rnd.nextInt(4) != 0) {
			return;
		}
		AABB box = new AABB(player.getX() - 32.0, player.getY() - 16.0, player.getZ() - 32.0,
				player.getX() + 32.0, player.getY() + 16.0, player.getZ() + 32.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
			var range = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
			if (range != null && !range.hasModifier(EARS_ID)) {
				range.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
						EARS_ID, 8.0,
						net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
			}
		}
	}

	/**
	 * Чёрная фигура вдалеке: 9 точек пыли складываются в силуэт на земле в 24–40 блоках.
	 * Частицы, а не сущность: армор-стенд пришлось бы убирать, а игрок уходит — и он остаётся.
	 */
	private static void shades(ServerLevel level, ServerPlayer player, net.minecraft.util.RandomSource rnd) {
		if (rnd.nextInt(20) != 0) {
			return;
		}
		double angle = rnd.nextDouble() * Math.PI * 2.0;
		double dist = 24.0 + rnd.nextDouble() * 16.0;
		double sx = player.getX() + Math.cos(angle) * dist;
		double sz = player.getZ() + Math.sin(angle) * dist;
		int bx = (int) Math.floor(sx);
		int bz = (int) Math.floor(sz);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		int base = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		if (base <= level.getMinY() + 2) {
			return;
		}
		var ink = new net.minecraft.core.particles.DustParticleOptions(0x0C0C12, 1.8f);
		level.sendParticles(player, ink, true, true, sx, base + 1.75, sz, 1, 0.0, 0.0, 0.0, 0.0);
		level.sendParticles(player, ink, true, true, sx, base + 1.25, sz, 3, 0.16, 0.3, 0.16, 0.0);
		level.sendParticles(player, ink, true, true, sx, base + 0.55, sz, 3, 0.22, 0.2, 0.22, 0.0);
		level.sendParticles(player, ink, true, true, sx, base + 0.15, sz, 2, 0.3, 0.1, 0.3, 0.0);
	}

	/** Износ: одна надетая броня теряет единицу прочности. Инструмент не трогаем. */
	private static void wearArmour(ServerPlayer player) {
		EquipmentSlot[] slots = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.HEAD, EquipmentSlot.FEET};
		int start = player.getRandom().nextInt(slots.length);
		for (int i = 0; i < slots.length; i++) {
			EquipmentSlot slot = slots[(start + i) % slots.length];
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty() || !stack.isDamageableItem()) {
				continue;
			}
			stack.hurtAndBreak(1, player, slot);
			return;
		}
	}

	/**
	 * Порча: ровно один предмет еды в сумке превращается в гниль. Не трогаем золотые яблоки
	 * (это не «еда», а ресурс) и не съедаем последний стак целиком — иначе поход в даль
	 * заканчивается голодной смертью от одного невидимого эффекта.
	 */
	private static void sourFood(ServerPlayer player) {
		// один бросок на событие, а не на каждый стак: иначе с пятью видами еды
		// «один предмет» превращалось в один предмет каждые несколько секунд
		if (player.getRandom().nextInt(4) != 0) {
			return;
		}
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !stack.has(DataComponents.FOOD) || stack.is(Items.ROTTEN_FLESH)
					|| stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
				continue;
			}
			stack.shrink(1);
			player.getInventory().placeItemBackInInventory(new ItemStack(Items.ROTTEN_FLESH));
			return;
		}
	}

	/** Свет гаснет: свечи и костры тушатся, открытые двери закрываются. Одна правка за вызов. */
	private static void darkSuck(ServerLevel level, ServerPlayer player) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int baseX = (int) Math.floor(player.getX());
		int baseZ = (int) Math.floor(player.getZ());
		int baseY = (int) Math.floor(player.getY());
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				for (int dy = -3; dy <= 3; dy++) {
					pos.set(baseX + dx, baseY + dy, baseZ + dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					if (state.getBlock() instanceof CandleBlock && state.getValue(CandleBlock.LIT)) {
						level.setBlock(pos, state.setValue(CandleBlock.LIT, false), Block.UPDATE_ALL);
						level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
								SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 0.7f);
						return;
					}
					if (state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)) {
						level.setBlock(pos, state.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
						level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
								SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 0.7f);
						return;
					}
					if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN)) {
						level.setBlock(pos, state.setValue(DoorBlock.OPEN, false), Block.UPDATE_ALL);
						level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
								SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 0.9f, 0.8f);
						return;
					}
				}
			}
		}
	}

	/** Наковальня падает с десяти блоков над точкой рядом с игроком. */
	private static void anvilRain(ServerLevel level, ServerPlayer player, net.minecraft.util.RandomSource rnd) {
		double x = player.getX() + (rnd.nextDouble() - 0.5) * 6.0;
		double z = player.getZ() + (rnd.nextDouble() - 0.5) * 6.0;
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		if (surface <= level.getMinY() + 2 || surface >= level.getMaxY() - 16) {
			return;
		}
		int startY = Math.min(level.getMaxY() - 4, surface + 6);
		FallingBlockEntity anvil = FallingBlockEntity.fall(level, new BlockPos(bx, startY, bz),
				Blocks.ANVIL.defaultBlockState());
		if (anvil != null) {
			// с шести блоков — не ваншот без брони, но всё ещё больно
			anvil.setHurtsEntities(1.2f, 20);
		}
	}

	/** Кольцо шёпота: восемь точек в шести шагах, у каждой свой голос. */
	private static void whisperRing(ServerLevel level, ServerPlayer player, net.minecraft.util.RandomSource rnd) {
		double radius = 5.0 + rnd.nextDouble() * 3.0;
		double start = rnd.nextDouble() * Math.PI * 2.0;
		var ink = new net.minecraft.core.particles.DustParticleOptions(0x1A1622, 0.5f);
		for (int i = 0; i < 8; i++) {
			double angle = start + i * Math.PI / 4.0;
			// разброс по радиусу и высоте: ровная окружность читается как геометрический артефакт
			double r = radius + (rnd.nextDouble() - 0.5) * 3.0;
			double x = player.getX() + Math.cos(angle) * r;
			double z = player.getZ() + Math.sin(angle) * r;
			level.sendParticles(player, ink, true, true, x, player.getY() + 0.6 + rnd.nextDouble() * 1.6, z,
					2, 0.25, 0.35, 0.25, 0.0);
			// голос в самой точке: шёпот «с той стороны», а не в ушах игрока.
			// SOUL_ESCAPE, а не SCULK_CLICKING: тот же звук — подпись компонента SHADES
			if (i % 3 == 0) {
				level.playSound(null, x, player.getY(), z, SoundEvents.SOUL_ESCAPE.value(),
						SoundSource.AMBIENT, 0.35f, 1.2f + (float) rnd.nextDouble() * 0.6f);
			}
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_TENDRIL_CLICKS,
				SoundSource.AMBIENT, 0.3f, 1.4f);
	}

	/**
	 * Звук-подпись компонента: обучение без букв. У каждого свой звук, поэтому игрок узнаёт,
	 * что именно в зоне, по слуху — ещё до того, как увидит эффект.
	 */
	public SoundEvent signature() {
		return switch (this) {
			case HUNGER_DRAIN -> SoundEvents.SCULK_CATALYST_BLOOM;
			case SATURATION_BURN -> SoundEvents.GENERIC_DRINK.value();
			case XP_LEAK -> SoundEvents.ENCHANTMENT_TABLE_USE;
			case COLD_CREEP -> SoundEvents.GLASS_STEP;
			case AIR_DRAIN -> SoundEvents.PLAYER_HURT_DROWN;
			case PRESSURE -> SoundEvents.BONE_BLOCK_STEP;
			case NIGHT_TOLL -> SoundEvents.BEACON_AMBIENT;
			case DRIP -> SoundEvents.CANDLE_AMBIENT;
			case TRACTION -> SoundEvents.BONE_MEAL_USE;
			case REPULSION -> SoundEvents.ANVIL_STEP;
			case BAD_SLEEP -> SoundEvents.PLAYER_BREATH;
			case MOB_EARS -> SoundEvents.BAT_TAKEOFF;
			case DARK_SUCKING -> SoundEvents.CANDLE_EXTINGUISH;
			case HUSK_BLOOM -> SoundEvents.SCULK_BLOCK_SPREAD;
			case WEAR -> SoundEvents.ANVIL_HIT;
			case LOOT_SOUR -> SoundEvents.PLAYER_BURP;
			case ARROW_BEND -> SoundEvents.GLASS_HIT;
			case LIGHT_WALL -> SoundEvents.AMETHYST_BLOCK_CHIME;
			case ASH_FALL -> SoundEvents.FIRE_EXTINGUISH;
			case ECHO_BURSTS -> SoundEvents.FIREWORK_ROCKET_BLAST;
			case STORM_CORE -> SoundEvents.LIGHTNING_BOLT_THUNDER;
			case GLOW_ALL -> SoundEvents.AMETHYST_BLOCK_RESONATE;
			case FLOAT_ALL -> SoundEvents.CONDUIT_ACTIVATE;
			case SHADES -> SoundEvents.SCULK_CLICKING;
			case ANVIL_RAIN -> SoundEvents.ANVIL_LAND;
			case MADNESS_RING -> SoundEvents.RESPAWN_ANCHOR_AMBIENT;
		};
	}

	/**
	 * Питч подписи: у каждого компонента свой, чтобы два разных сигнала не сливались
	 * в один и тот же «звук зоны». 0.60–1.37 по номеру компонента.
	 */
	public float signaturePitch() {
		return 0.60f + ordinal() * 0.77f / 25f;
	}

	/** Высота рельефа для точки или запасное значение, если чанк не загружен. */
	private static double surfaceOr(ServerLevel level, double x, double z, double fallback) {		int bx = (int) Math.floor(x);
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



