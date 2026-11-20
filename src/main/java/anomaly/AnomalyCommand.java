package anomaly;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

/**
 * {@code /anomaly} — только для операторов: посмотреть настройки, свою дозу и что рядом.
 * Нужна владельцу, чтобы проверить мод без клиентских модов и без полёта на 20к.
 */
public final class AnomalyCommand {
	private AnomalyCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("anomaly")
						.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
						.executes(context -> status(context.getSource()))
						.then(Commands.literal("dose").executes(context -> dose(context.getSource())))
						.then(Commands.literal("near").executes(context -> near(context.getSource())))));
	}

	private static int status(CommandSourceStack source) {
		AnomalyConfig config = AnomalyMod.config;
		reply(source, "Аномалии: " + (config.enabled ? "включены" : "выключены")
				+ (config.overworld_only ? ", только Верхний мир" : ", все измерения"));
		reply(source, "Ближе " + config.safe_radius + " блоков зон нет; плотность: "
				+ String.format("%.3f", config.densityAt(config.safe_radius)) + " на 3к → "
				+ String.format("%.3f", config.densityAt(20000)) + " на 20к (на чанк)");
		reply(source, "Радиус " + config.radius_min + ".." + config.radius_max
				+ ", стопки с " + config.stack_from
				+ ", доза +" + config.dose_gain_per_second + "/с, спад " + config.dose_decay_per_second + "/с");
		reply(source, "Типы: " + AnomalyField.enabledTypes(config));
		return 1;
	}

	private static int dose(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AnomalyState state = AnomalyMod.stateOf(player.getUUID());
		if (state == null || state.type == null) {
			reply(source, "Доза 0 % (в аномалии не был)");
			return 1;
		}
		reply(source, state.type.title + " · доза " + state.percent() + " % · усилитель " + state.amplifier());
		return 1;
	}

	private static int near(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.level();
		AnomalyConfig config = AnomalyMod.config;
		List<Anomaly> found = AnomalyField.near(level, player.getX(), player.getZ(), config.view_range, config);
		if (found.isEmpty()) {
			reply(source, "В " + config.view_range + " блоках аномалий нет");
			return 1;
		}
		found.sort(Comparator.comparingDouble(anomaly -> anomaly.distanceTo(player.getX(), player.getZ())));
		int shown = 0;
		for (Anomaly anomaly : found) {
			if (shown++ >= 6) {
				break;
			}
			reply(source, anomaly.type().title + " · радиус " + Math.round(anomaly.radius())
					+ " · " + Math.round(anomaly.distanceTo(player.getX(), player.getZ())) + " блоков"
					+ (anomaly.contains(player.getX(), player.getZ()) ? " · вы внутри" : ""));
		}
		return 1;
	}

	private static void reply(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
	}
}
