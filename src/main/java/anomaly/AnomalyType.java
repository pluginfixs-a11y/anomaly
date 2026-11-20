package anomaly;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Пять аномалий первой версии. Всё, чем тип себя выдаёт, — ванильные частицы и звук:
 * клиент мод не ставит, своих ассетов нет.
 */
public enum AnomalyType {
	/** Тянет к центру, замедляет, на высокой дозе — копаешь медленно. */
	GRAVITY("gravity", "Тяжесть", ParticleTypes.ASH, SoundEvents.ANVIL_LAND, 0.5f),
	/** Отталкивает от центра и на высокой дозе поднимает в воздух. */
	VORTEX("vortex", "Вихрь", ParticleTypes.CLOUD, SoundEvents.ELYTRA_FLYING, 0.9f),
	/** Морозит: замедление, иней на экране, на высокой дозе бьёт холодом. */
	FROST("frost", "Мороз", ParticleTypes.SNOWFLAKE, SoundEvents.BUCKET_EMPTY_POWDER_SNOW, 0.7f),
	/** Гасит свет: тьма вокруг игрока, на высокой дозе — слепота. */
	SHADOW("shadow", "Мрак", ParticleTypes.SQUID_INK, SoundEvents.AMBIENT_CAVE.value(), 1.0f),
	/** Бьёт разрядами рядом с игроком. */
	STATIC("static", "Разряд", ParticleTypes.ELECTRIC_SPARK, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.5f);

	public final String id;
	public final String title;
	public final SimpleParticleType particle;
	public final SoundEvent sound;
	/** Высота звука: чем ниже, тем «тяжелее» голос аномалии. */
	public final float pitch;

	AnomalyType(String id, String title, SimpleParticleType particle, SoundEvent sound, float pitch) {
		this.id = id;
		this.title = title;
		this.particle = particle;
		this.sound = sound;
		this.pitch = pitch;
	}

	public static AnomalyType byId(String id) {
		if (id != null) {
			for (AnomalyType type : values()) {
				if (type.id.equals(id)) {
					return type;
				}
			}
		}
		return SHADOW;
	}
}

