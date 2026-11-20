package anomaly;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
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
	private AnomalyEffects() {
	}

	/** Нахождение внутри аномалии: эффект по дозе и «физика» самого типа. */
	public static void apply(ServerLevel level, ServerPlayer player, Anomaly anomaly, AnomalyState state, AnomalyConfig config) {
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
					effect(player, MobEffects.LEVITATION, 30, 0);
				}
				push(player, anomaly, 0.03 + 0.06 * dose);
			}
			case FROST -> {
				effect(player, MobEffects.SLOWNESS, 60, amplifier);
				// иней на экране: клиент рисует его сам по счётчику заморозки
				player.setTicksFrozen(Math.min(300, player.getTicksFrozen() + 20));
				if (dose >= 0.8) {
					player.hurt(level.damageSources().freeze(), 1.0f);
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
			case VORTEX -> effect(player, MobEffects.NAUSEA, 40, 0);
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
			level.sendParticles(anomaly.type().particle, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
		if ((level.getGameTime() + anomaly.id()) % Math.max(1, config.sound_period) == 0) {
			level.playSound(null, anomaly.x(), player.getY(), anomaly.z(),
					anomaly.type().sound, SoundSource.AMBIENT, 0.9f, anomaly.type().pitch);
		}
	}

	/** Вход в аномалию: звук и подпись, чтобы игрок понял, что произошло. */
	public static void entry(ServerLevel level, ServerPlayer player, Anomaly anomaly) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 1.2f, 0.7f);
		player.sendOverlayMessage(Component.literal("Аномалия: " + anomaly.type().title)
				.withStyle(ChatFormatting.AQUA));
	}

	/** Строка над хотбаром вместо HUD: тип и доза в процентах. */
	public static void overlay(ServerPlayer player, AnomalyState state) {
		if (state.type == null || state.dose < 0.03) {
			return;
		}
		ChatFormatting colour = state.dose >= 0.75 ? ChatFormatting.RED
				: state.dose >= 0.4 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
		player.sendOverlayMessage(Component.literal(state.type.title + " · " + state.percent() + " %")
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
		player.push(dx / length * force, 0.0, dz / length * force);
	}

	private static void push(ServerPlayer player, Anomaly anomaly, double force) {
		double dx = player.getX() - anomaly.x();
		double dz = player.getZ() - anomaly.z();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			// в самом центре вихря нечего делить — толкаем в случайную сторону
			player.push(force, 0.0, 0.0);
			return;
		}
		player.push(dx / length * force, 0.0, dz / length * force);
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

