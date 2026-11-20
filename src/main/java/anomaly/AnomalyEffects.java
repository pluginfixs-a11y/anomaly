package anomaly;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
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
 * толчок скорости, молния. Клиент ничего не ставит, поэтому ни HUD, ни своих визуалов нет —
 * дозу читаем по иконкам эффектов и по строке над хотбаром.
 */
public final class AnomalyEffects {
	/** Левитация вихря держится не дольше этого: иначе игрок улетает вверх и падает без контрплея. */
	private static final int MAX_LEVITATION_TICKS = 60;
	/** Иней набирается +30 за шаг: ваниль снимает 2 за тик, то есть нетто +10 за 0.5 с. */
	private static final int FREEZE_PER_STEP = 30;
	/** 140 — ванильный «полностью замёрз»: дальше уже идёт обморожение. */
	private static final int FREEZE_FULL = 140;

	private AnomalyEffects() {
	}

	/** Нахождение внутри аномалии: эффект по дозе и «физика» самого типа. */
	public static void apply(ServerLevel level, ServerPlayer player, Anomaly anomaly, AnomalyState state, AnomalyConfig config,
			double seconds) {
		int amplifier = state.amplifier();
		double dose = state.dose;
		switch (anomaly.type()) {
			case GRAVITY -> {
				effect(player, MobEffects.SLOWNESS, 60, amplifier);
				if (dose >= 0.6) {
					effect(player, MobEffects.MINING_FATIGUE, 60, amplifier);
				}
				pull(player, anomaly, 0.02 + 0.05 * dose);
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
				push(player, anomaly, 0.03 + 0.06 * dose);
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
					strike(level, player, anomaly, dose);
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

	/** Частицы и редкий звук — чтобы аномалию было видно и слышно до входа. */
	public static void ambience(ServerLevel level, ServerPlayer player, Anomaly anomaly, AnomalyConfig config) {
		double distance = anomaly.distanceTo(player.getX(), player.getZ());
		if (distance > config.view_range) {
			return;
		}
		var random = player.getRandom();
		int count = distance <= anomaly.radius() ? 3 : 2;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double radius = anomaly.radius() * (0.55 + 0.45 * random.nextDouble());
			double x = anomaly.x() + Math.cos(angle) * radius;
			double z = anomaly.z() + Math.sin(angle) * radius;
			double y = player.getY() - 1.0 + random.nextDouble() * 3.0;
			// overrideLimiter: обычный вызов режет частицы на 32 блоках, а view_range по умолчанию 48
			level.sendParticles(anomaly.type().particle, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
		if ((level.getGameTime() + anomaly.id()) % Math.max(1, config.sound_period) == 0) {
			// громкость 2.0 — слышно примерно на 32 блока, это и есть «подошёл и понял»
			level.playSound(null, anomaly.x(), player.getY(), anomaly.z(),
					anomaly.type().sound, SoundSource.AMBIENT, 2.0f, anomaly.type().pitch);
		}
	}

	/** Вход в аномалию: звук и подпись, чтобы игрок понял, что произошло. */
	public static void entry(ServerLevel level, ServerPlayer player, Anomaly anomaly) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 1.2f, 0.7f);
		player.sendOverlayMessage(Component.literal("Аномалия: " + anomaly.type().title)
				.withStyle(ChatFormatting.AQUA));
	}

	/**
	 * Строка над хотбаром вместо HUD: тип и доза в процентах. Отправляется не чаще, чем меняется
	 * на 5 %, иначе перебивает action bar других модов (варпы, локейт и т.п.) каждые полсекунды.
	 */
	public static void overlay(ServerPlayer player, AnomalyState state) {
		if (state.type == null || state.dose < 0.03) {
			return;
		}
		int percent = state.percent();
		if (Math.abs(percent - state.shownPercent) < 5) {
			return;
		}
		state.shownPercent = percent;
		ChatFormatting colour = state.dose >= 0.75 ? ChatFormatting.RED
				: state.dose >= 0.4 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
		player.sendOverlayMessage(Component.literal(state.type.title + " · " + percent + " %")
				.withStyle(colour));
	}

	private static void effect(ServerPlayer player, Holder<MobEffect> effect, int ticks, int amplifier) {
		player.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, true, true));
	}

	private static void pull(ServerPlayer player, Anomaly anomaly, double force) {
		double dx = anomaly.x() - player.getX();
		double dz = anomaly.z() - player.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			return;
		}
		shove(player, dx / length * force, dz / length * force);
	}

	private static void push(ServerPlayer player, Anomaly anomaly, double force) {
		double dx = player.getX() - anomaly.x();
		double dz = player.getZ() - anomaly.z();
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
	static boolean strike(ServerLevel level, ServerPlayer player, Anomaly anomaly, double dose) {
		var random = player.getRandom();
		if (random.nextFloat() > 0.35f) {
			return false;
		}
		double angle = random.nextDouble() * Math.PI * 2.0;
		double radius = random.nextDouble() * anomaly.radius();
		double x = anomaly.x() + Math.cos(angle) * radius;
		double z = anomaly.z() + Math.sin(angle) * radius;

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
