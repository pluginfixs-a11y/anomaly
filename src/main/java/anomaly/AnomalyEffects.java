package anomaly;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;

import java.util.List;

/**
 * Всё, чем аномалия действует на игрока. Только ванильные каналы: эффекты, частицы, звук,
 * толчок скорости, молния. Клиент ничего не ставит, поэтому ни HUD, ни своих визуалов нет.
 * <p>
 * Решает не «тип зоны», а её набор компонентов: пассивные действуют внутри, видимые — у края и
 * рядом. Синергии поднимают силу на 25 % за пару, «ночной сбор» удваивает всё ночью.
 */
public final class AnomalyEffects {
	private AnomalyEffects() {
	}

	/** Внутри зоны: пассивные компоненты по дозе. */
	public static void apply(ServerLevel level, ServerPlayer player, Zone zone, AnomalyState state,
			AnomalyConfig config, double seconds) {
		List<AnomalyComponent> components = zone.components();
		if (components.isEmpty()) {
			return;
		}
		double intensity = AnomalyComponent.intensity(components);
		if (components.contains(AnomalyComponent.NIGHT_TOLL) && isNight(level)) {
			intensity *= 2.0;
		}
		for (AnomalyComponent component : components) {
			if (!component.isVisible()) {
				component.apply(level, player, zone, state, state.dose, intensity, seconds, config);
			}
		}
	}

	/** Видимое: и внутри, и у края — в пределах {@code view_range} от границы. */
	public static void ambience(ServerLevel level, ServerPlayer player, Zone zone, AnomalyConfig config,
			AnomalyState state) {
		if (zone.signedDistance(player.getX(), player.getZ()) > config.view_range) {
			return;
		}
		for (AnomalyComponent component : zone.components()) {
			component.pulse(level, player, zone, config, state);
		}
	}

	/** Вход в аномалию: только звук. Текст в UI не выводим — механики объясняются средой. */
	public static void entry(ServerLevel level, ServerPlayer player, Zone zone) {
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 1.2f, 0.7f);
	}

	/**
	 * Доза уже спадает, но эффект ещё висит — «послевкусие». Общее, а не по компонентам: тело
	 * помнит, что было плохо, но уже не знает, от чего именно.
	 */
	public static void lingering(ServerLevel level, ServerPlayer player, AnomalyState state, AnomalyConfig config) {
		// в креативе игрок не болеет: после края зоны строителю не нужна слепота
		if (player.isCreative()) {
			return;
		}
		int amplifier = state.dose >= 0.5 ? 1 : 0;
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.SLOWNESS, 40, amplifier, false, true, true));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DARKNESS, 40, 0, false, true, true));
	}

	// --- движение ---

	/** Тяга: к ядру, а не к геометрическому центру — ядро смещено и ползёт. */
	static void shoveTowards(ServerPlayer player, Zone zone, double force) {
		double dx = zone.coreX() - player.getX();
		double dz = zone.coreZ() - player.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			return;
		}
		shove(player, dx / length * force, dz / length * force);
	}

	static void shoveAway(ServerPlayer player, Zone zone, double force) {
		double dx = player.getX() - zone.coreX();
		double dz = player.getZ() - zone.coreZ();
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			// в самом ядре нечего делить — толкаем в случайную сторону
			double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
			shove(player, Math.cos(angle) * force, Math.sin(angle) * force);
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

	/** Молния без огня и урона: только вспышка и гром. */
	static void visualBolt(ServerLevel level, double x, double y, double z) {
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt == null) {
			return;
		}
		bolt.setVisualOnly(true);
		bolt.snapTo(x, y, z);
		level.addFreshEntity(bolt);
	}

	/** Ночь по мировым часам: 13000–23000 — тёмная половина суток. */
	static boolean isNight(ServerLevel level) {
		long time = level.getOverworldClockTime() % 24000L;
		return time >= 13000L && time <= 23000L;
	}
}
