package anomaly;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Обучение без букв, вторая половина: звук-подпись компонента (см. {@link AnomalyComponent#signature()})
 * и достижения за то, что игрок сделал сам. Достижение — единственный текст, который мод вообще
 * показывает, и он не объясняет механику: «вот что ты пережил», а не «нажми W».
 * <p>
 * Данные лежат в {@code data/anomaly/advancement/*.json} в самом jar; клиенту ничего не нужно —
 * ванильный клиент рисует дерево и тосты по серверным данным, а {@code fallback} в тексте
 * показывает русскую строку без ресурспака.
 */
final class AnomalyProgress {
	private static final String CRITERION = "criterion";
	private static final Identifier ROOT = Identifier.fromNamespaceAndPath("anomaly", "root");
	private static final Identifier SURVIVED = Identifier.fromNamespaceAndPath("anomaly", "survived");
	private static final Identifier CORE = Identifier.fromNamespaceAndPath("anomaly", "core");
	private static final Identifier NESTED = Identifier.fromNamespaceAndPath("anomaly", "nested");

	/** Сколько тиков надо пробыть внутри, чтобы выход считался «выживанием». */
	static final int SURVIVE_TICKS = 600;
	/** Порог дозы для «выживания»: мимоходом через край зоны достижение не выдаётся. */
	static final double SURVIVE_DOSE = 0.6;
	/** Тиков в ядре (глубина > 0.85) для достижения «в самом сердце». */
	static final int CORE_TICKS = 100;

	private AnomalyProgress() {
	}

	static void onEnter(ServerPlayer player, Zone zone) {
		award(player, ROOT);
		if (zone.parentId() != 0L) {
			award(player, NESTED);
		}
	}

	/**
	 * Выживание: внутри опасной или смертельной зоны не меньше 30 с, с полной дозой, на глубине
	 * от 30 % радиуса — и вышел живым. Кромка не считается: иначе достижение берётся шагом.
	 */
	static boolean survives(int ticksInside, double dose, boolean hazard, boolean alive, double depth) {
		return alive && hazard && ticksInside >= SURVIVE_TICKS && dose >= SURVIVE_DOSE && depth >= 0.3;
	}

	static void onSurvived(ServerPlayer player) {
		award(player, SURVIVED);
	}

	static void onCore(ServerPlayer player) {
		award(player, CORE);
	}

	/** Выдача по id: нет такой записи в датапаке — молчим, а не падаем. */
	static void award(ServerPlayer player, Identifier id) {
		if (player == null || player.level() == null || player.level().getServer() == null) {
			return;
		}
		AdvancementHolder holder = player.level().getServer().getAdvancements().get(id);
		if (holder == null) {
			return;
		}
		try {
			player.getAdvancements().award(holder, CRITERION);
		} catch (RuntimeException e) {
			// выдача достижения не должна ронять тик игрока
			AnomalyMod.LOGGER.warn("Anomaly: не смог выдать достижение {}: {}", id, e.toString());
		}
	}
}
