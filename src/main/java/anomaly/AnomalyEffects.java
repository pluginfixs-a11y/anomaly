package anomaly;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;

/**
 * Всё, чем аномалия действует на игрока. Только ванильные каналы: эффекты, частицы, звук,
 * толчок скорости, молния. Клиент ничего не ставит, поэтому ни HUD, ни своих визуалов нет.
 * <p>
 * В 0.2 зоны стали большими (100–3000 блоков), поэтому «рядом» — это расстояние до границы,
 * а частицы и звук цепляются за ближайший край, а не за центр в трёх километрах.
 */
public final class AnomalyEffects {
	/** Левитация вихря держится не дольше этого: иначе игрок улетает вверх и падает без контрплея. */
	private static final int MAX_LEVITATION_TICKS = 60;
	/** Иней набирается +30 за шаг: ваниль снимает 2 за тик, то есть нетто +10 за 0.5 с. */
	private static final int FREEZE_PER_STEP = 30;
	/** 140 — ванильный «полностью замёрз»: дальше уже идёт обморожение. */
	private static final int FREEZE_FULL = 140;
	/** Молния бьёт не дальше этого от игрока: иначе в трёхкилометровой зоне она невидима. */
	private static final double STRIKE_RANGE = 24.0;

	private AnomalyEffects() {
	}

	/** Нахождение внутри аномалии: эффект по дозе и «физика» самого типа. */
	public static void apply(ServerLevel level, ServerPlayer player, Zone zone, AnomalyState state, AnomalyConfig config,
			double seconds) {
		int amplifier = state.amplifier();
		double dose = state.dose;
		switch (zone.type()) {
			case GRAVITY -> {
				effect(player, MobEffects.SLOWNESS, 60, amplifier);
				if (dose >= 0.6) {
					effect(player, MobEffects.MINING_FATIGUE, 60, amplifier);
				}
				pull(player, zone, 0.02 + 0.05 * dose);
			}
			case VORTEX -> {
				if (dose >= 0.3) {
					effect(player, MobEffects.NAUSEA, 60, 0);
				}
				if (dose >= 0.6) {
					// подъём ограничен по времени: без потолка игрок уходит вверх навсегда,
					// а когда левитация гаснет — падает с высоты насмерть
					if (state.ticksInside <= MAX_LEVITATION_TICKS) {
						effect(player, MobEffects.LEVITATION, 30, 0);
					} else {
						effect(player, MobEffects.SLOW_FALLING, 40, 0);
					}
				}
				push(player, zone, 0.03 + 0.06 * dose);
			}
			case FROST -> {
				effect(player, MobEffects.SLOWNESS, 60, amplifier);
				int frozen = Math.min(FREEZE_FULL * 2, player.getTicksFrozen() + FREEZE_PER_STEP);
				player.setTicksFrozen(frozen);
				// бьём раз в 20 тиков, а не каждые полсекунды: частый урон держит invulnerableTime
				// и съедает удары мобов по игроку. Счётчик свой, чтобы период тика не влиял.
				state.damageTicks += AnomalyState.stepTicks(seconds);
				if (dose >= 0.8 && state.damageTicks >= 20) {
					state.damageTicks = 0;
					player.hurt(level.damageSources().freeze(), 2.0f);
				}
			}
			case SHADOW -> {
				effect(player, MobEffects.DARKNESS, 80, 0);
				if (dose >= 0.7) {
					effect(player, MobEffects.BLINDNESS, 40, 0);
				}
			}
			case STATIC -> {
				if (dose >= 0.3) {
					strike(level, player, zone, dose);
				}
			}
		}
	}

	/** Доза уже спадает, но эффект ещё висит — «хвост» после выхода. */
	public static void lingering(ServerLevel level, ServerPlayer player, AnomalyState state, AnomalyConfig config) {
		AnomalyType type = state.type;
		if (type == null) {
			return;
		}
		switch (type) {
			case GRAVITY -> effect(player, MobEffects.SLOWNESS, 40, 0);
			case VORTEX -> effect(player, MobEffects.SLOW_FALLING, 100, 0);
			case FROST -> effect(player, MobEffects.SLOWNESS, 40, 0);
			case SHADOW -> effect(player, MobEffects.DARKNESS, 40, 0);
			case STATIC -> effect(player, MobEffects.WEAKNESS, 40, 0);
		}
	}

	/**
	 * Частицы и редкий звук — чтобы аномалию было видно и слышно до входа.
	 * Точка привязки — игрок внутри и ближайший край снаружи: центр большой зоны бесполезен.
	 */
	public static void ambience(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config) {
		double signed = zone.signedDistance(player.getX(), player.getZ());
		if (signed > config.view_range) {
			return;
		}
		boolean inside = signed <= 0.0;
		double angle = Math.atan2(player.getZ() - zone.z(), player.getX() - zone.x());
		double[] edge = inside ? null : zone.anomaly().boundaryPoint(angle, zone.ageHours(), zone.radius());

		double anchorX = inside ? player.getX() : edge[0];
		double anchorZ = inside ? player.getZ() : edge[1];
		double spread = inside ? 6.0 : 12.0;

		var random = player.getRandom();
		int count = inside ? 3 : 2;
		for (int i = 0; i < count; i++) {
			double a = random.nextDouble() * Math.PI * 2.0;
			double radius = 1.5 + random.nextDouble() * spread;
			double x = anchorX + Math.cos(a) * radius;
			double z = anchorZ + Math.sin(a) * radius;
			double y = player.getY() - 1.0 + random.nextDouble() * 3.0;
			// overrideLimiter: обычный вызов режет частицы на 32 блоках, а view_range по умолчанию 48
			level.sendParticles(zone.type().particle, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
		if ((level.getGameTime() + zone.id()) % Math.max(1, config.sound_period) == 0) {
			// громкость 2.0 — слышно примерно на 32 блока, это и есть «подошёл и понял»
			level.playSound(null, anchorX, player.getY(), anchorZ,
					zone.type().sound, SoundSource.AMBIENT, 2.0f, zone.type().pitch);
		}
	}

	/** Вход в аномалию: только звук. Текст в UI не выводим — механики объясняются средой. */
	public static void entry(ServerLevel level, ServerPlayer player, Zone zone) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 1.2f, 0.7f);
	}

	private static void effect(ServerPlayer player, Holder<MobEffect> effect, int ticks, int amplifier) {
		player.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, true, true));
	}

	private static void pull(ServerPlayer player, Zone zone, double force) {
		double dx = zone.x() - player.getX();
		double dz = zone.z() - player.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			return;
		}
		shove(player, dx / length * force, dz / length * force);
	}

	private static void push(ServerPlayer player, Zone zone, double force) {
		double dx = player.getX() - zone.x();
		double dz = player.getZ() - zone.z();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			// в самом центре вихря нечего делить — толкаем в случайную сторону
			shove(player, force, 0.0);
			return;
		}
		shove(player, dx / length * force, dz / length * force);
	}

	/**
	 * Толчок игрока. Одного {@code push} мало: клиент — хозяин своей позиции, а
	 * {@code ChunkMap$TrackedEntity.updatePlayer} для самого игрока выходит сразу
	 * ({@code if (player == entity) return}), поэтому скорость надо отправить пакетом —
	 * ровно так же, как это делает ванильный взрыв.
	 */
	private static void shove(ServerPlayer player, double dx, double dz) {
		player.push(dx, 0.0, dz);
		if (player.connection != null) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	/** Разряд: вспышка молнии без огня и урона по округе, урон только вплотную. */
	static boolean strike(ServerLevel level, ServerPlayer player, Zone zone, double dose) {
		var random = player.getRandom();
		if (random.nextFloat() > 0.35f) {
			return false;
		}
		double angle = random.nextDouble() * Math.PI * 2.0;
		double radius = random.nextDouble() * Math.min(zone.radius(), STRIKE_RANGE);
		double x = player.getX() + Math.cos(angle) * radius;
		double z = player.getZ() + Math.sin(angle) * radius;

		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt == null) {
			return false;
		}
		bolt.setVisualOnly(true);
		bolt.snapTo(x, player.getY(), z);
		level.addFreshEntity(bolt);

		if (Math.hypot(player.getX() - x, player.getZ() - z) < 3.0) {
			player.hurt(level.damageSources().lightningBolt(), (float) (2.0 + 3.0 * dose));
		}
		return true;
	}
}
