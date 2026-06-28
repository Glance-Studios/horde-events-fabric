package mc.arch.hordes

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.world.entity.EntityType

/**
 * `/horde ...` admin surface. Requires permission level 2 (op), and every setter persists to
 * config.json.
 */
object HordeCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, manager: HordeManager) {
        dispatcher.register(
            literal("horde")
                // Op level 2 (gamemasters), via the 1.21.11 permission-check API.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes { help(it.source); 1 }
                .then(literal("help").executes { help(it.source); 1 })
                .then(literal("status").executes { status(it.source, manager); 1 })
                .then(literal("stop").executes { stop(it.source, manager); 1 })
                .then(literal("list").executes { list(it.source, manager); 1 })
                .then(literal("reload").executes {
                    manager.reloadConfig(); it.source.tell("&aReloaded config."); 1
                })
                // start [type] [seconds]
                .then(
                    literal("start")
                        .executes { startRandom(it.source, manager); 1 }
                        .then(
                            argument("type", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(manager.mobs.keys.map { HordeManager.displayName(it).lowercase() }, b) }
                                .executes { startType(it, manager, null); 1 }
                                .then(
                                    argument("seconds", IntegerArgumentType.integer(1))
                                        .executes { startType(it, manager, IntegerArgumentType.getInteger(it, "seconds")); 1 }
                                )
                        )
                )
                // force <type> [duration] [rateMin rateMax] [spreadMin spreadMax]
                .then(
                    literal("force")
                        .then(
                            typeArg(manager)
                                .executes { force(it, manager, false, false); 1 }
                                .then(
                                    argument("duration", IntegerArgumentType.integer(1))
                                        .executes { force(it, manager, false, false); 1 }
                                        .then(
                                            argument("rateMin", IntegerArgumentType.integer(0))
                                                .then(
                                                    argument("rateMax", IntegerArgumentType.integer(0))
                                                        .executes { force(it, manager, true, false); 1 }
                                                        .then(
                                                            argument("spreadMin", IntegerArgumentType.integer(0))
                                                                .then(
                                                                    argument("spreadMax", IntegerArgumentType.integer(0))
                                                                        .executes { force(it, manager, true, true); 1 }
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                // rate <type> <min> <max>
                .then(
                    literal("rate").then(
                        typeArg(manager).then(
                            argument("min", IntegerArgumentType.integer(0)).then(
                                argument("max", IntegerArgumentType.integer(0)).executes { rate(it, manager); 1 }
                            )
                        )
                    )
                )
                // interval <min> <max>
                .then(
                    literal("interval").then(
                        argument("min", IntegerArgumentType.integer(1)).then(
                            argument("max", IntegerArgumentType.integer(1)).executes {
                                val mn = IntegerArgumentType.getInteger(it, "min")
                                val mx = IntegerArgumentType.getInteger(it, "max").coerceAtLeast(mn)
                                manager.setInterval(mn, mx)
                                it.source.tell("&aInterval = &f$mn-$mx&a seconds.")
                                1
                            }
                        )
                    )
                )
                // default <duration|rate|spread> <min> <max> ; default radius <min> <max>
                .then(
                    literal("default")
                        .then(intPairDefault(manager, "duration", 1) { m, mn, mx -> m.setDefaultDuration(mn, mx) })
                        .then(intPairDefault(manager, "rate", 0) { m, mn, mx -> m.setDefaultRate(mn, mx) })
                        .then(intPairDefault(manager, "spread", 0) { m, mn, mx -> m.setDefaultSpread(mn, mx) })
                        .then(
                            literal("radius").then(
                                argument("min", DoubleArgumentType.doubleArg(0.0)).then(
                                    argument("max", DoubleArgumentType.doubleArg(0.0)).executes {
                                        val mn = DoubleArgumentType.getDouble(it, "min")
                                        val mx = DoubleArgumentType.getDouble(it, "max").coerceAtLeast(mn)
                                        manager.setDefaultRadius(mn, mx)
                                        it.source.tell("&aDefault spread-radius = &f${fmt(mn)}-${fmt(mx)}&a blocks.")
                                        1
                                    }
                                )
                            )
                        )
                )
        )
    }

    // --- argument builders ---

    private fun typeArg(manager: HordeManager) =
        argument("type", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(HordeManager.livingTypeNames(), b) }

    /** A `default <key> <min> <max>` int-pair branch; the apply lambda runs on success. */
    private fun intPairDefault(
        manager: HordeManager,
        key: String,
        floor: Int,
        apply: (HordeManager, Int, Int) -> Unit,
    ) = literal(key).then(
        argument("min", IntegerArgumentType.integer(floor)).then(
            argument("max", IntegerArgumentType.integer(floor)).executes { ctx ->
                val mn = IntegerArgumentType.getInteger(ctx, "min")
                val mx = IntegerArgumentType.getInteger(ctx, "max").coerceAtLeast(mn)
                apply(manager, mn, mx)
                ctx.source.tell("&aDefault $key = &f$mn-$mx&a.")
                1
            }
        )
    )

    // --- handlers ---

    private fun startRandom(source: CommandSourceStack, manager: HordeManager) {
        val type = manager.startRandomEvent()
        if (type == null) source.tell("&cNo mob types configured. Add one with /horde rate <type> <min> <max>, or use /horde force.")
        else source.tell("&aStarted horde: &f${HordeManager.displayName(type)} &7(${manager.secondsLeft}s)")
    }

    private fun startType(ctx: CommandContext<CommandSourceStack>, manager: HordeManager, seconds: Int?) {
        val arg = StringArgumentType.getString(ctx, "type")
        val type = resolve(ctx.source, arg) ?: return
        if (type !in manager.mobs) {
            ctx.source.tell("&c${HordeManager.displayName(type)} isn't configured. Add it (/horde rate ${HordeManager.displayName(type).lowercase()} <min> <max>) or use /horde force.")
            return
        }
        manager.startEvent(type, manager.resolve(type), seconds)
        ctx.source.tell("&aStarted horde: &f${HordeManager.displayName(type)} &7(${manager.secondsLeft}s)")
    }

    private fun force(ctx: CommandContext<CommandSourceStack>, manager: HordeManager, rate: Boolean, spread: Boolean) {
        val type = resolve(ctx.source, StringArgumentType.getString(ctx, "type")) ?: return
        val duration = runCatching { IntegerArgumentType.getInteger(ctx, "duration") }.getOrNull()
        var settings = manager.resolve(type)
        if (rate) {
            val mn = IntegerArgumentType.getInteger(ctx, "rateMin")
            val mx = IntegerArgumentType.getInteger(ctx, "rateMax").coerceAtLeast(mn)
            settings = settings.copy(rate = HordeManager.Range(mn, mx))
        }
        if (spread) {
            val mn = IntegerArgumentType.getInteger(ctx, "spreadMin")
            val mx = IntegerArgumentType.getInteger(ctx, "spreadMax").coerceAtLeast(mn)
            settings = settings.copy(spread = HordeManager.Range(mn, mx))
        }
        manager.startEvent(type, settings, duration)
        ctx.source.tell("&aForced horde: &f${HordeManager.displayName(type)} &7for ${manager.secondsLeft}s, ${describe(settings)}")
    }

    private fun stop(source: CommandSourceStack, manager: HordeManager) {
        if (!manager.isActive) {
            source.tell("&7No horde event is active.")
            return
        }
        val ended = manager.activeType
        manager.endEvent()
        source.tell("&eStopped horde: &f${ended?.let { HordeManager.displayName(it) }}")
    }

    private fun status(source: CommandSourceStack, manager: HordeManager) {
        if (manager.isActive) {
            source.tell("&6Active horde: &f${manager.activeType?.let { HordeManager.displayName(it) }} &7- ${manager.secondsLeft}s remaining")
            manager.activeSettings?.let { source.tell("&7 ${describe(it)}") }
        } else {
            source.tell("&7No active horde. Next attempt in ~${manager.nextInSeconds}s.")
        }
        source.tell("&7interval ${manager.intervalMin}-${manager.intervalMax}s  |  defaults: ${describe(manager.defaults)}")
    }

    private fun rate(ctx: CommandContext<CommandSourceStack>, manager: HordeManager) {
        val type = resolve(ctx.source, StringArgumentType.getString(ctx, "type")) ?: return
        val mn = IntegerArgumentType.getInteger(ctx, "min")
        val mx = IntegerArgumentType.getInteger(ctx, "max").coerceAtLeast(mn)
        manager.setRate(type, mn, mx)
        ctx.source.tell("&aRate for &f${HordeManager.displayName(type)}&a = &f$mn-$mx &7extra per natural spawn.")
    }

    private fun list(source: CommandSourceStack, manager: HordeManager) {
        source.tell("&6Horde settings:")
        source.tell("&7 interval: &f${manager.intervalMin}-${manager.intervalMax}s")
        source.tell("&7 defaults: &f${describe(manager.defaults)}")
        if (manager.mobs.isEmpty()) {
            source.tell("&7 (no mob types configured)")
            return
        }
        manager.mobs.forEach { (type, o) ->
            val overrides = listOfNotNull(
                o.duration?.let { "duration ${it.min}-${it.max}s" },
                o.rate?.let { "rate ${it.min}-${it.max}" },
                o.spread?.let { "spread ${it.min}-${it.max}s" },
                o.radius?.let { "radius ${fmt(it.min)}-${fmt(it.max)}" },
            )
            val desc = if (overrides.isEmpty()) "inherits defaults" else "overrides ${overrides.joinToString(", ")}"
            val marker = if (type == manager.activeType) " &c<< ACTIVE" else ""
            source.tell("&7 - &f${HordeManager.displayName(type)}&7: $desc$marker")
        }
    }

    private fun help(source: CommandSourceStack) {
        source.tell("&6&lHordeEvents &7- random hordes flood vanilla spawns with extra mobs of a type")
        source.tell("&f/horde start [type] [seconds] &7- fire an event now (random type if omitted; must be configured)")
        source.tell("&f/horde force <type> [duration] [rateMin] [rateMax] [spreadMin] [spreadMax]")
        source.tell("&f/horde stop &7- end the active event")
        source.tell("&f/horde status &7- active horde + settings")
        source.tell("&f/horde rate <type> <min> <max> &7- per-type extra-per-spawn override")
        source.tell("&f/horde default <duration|rate|spread|radius> <min> <max> &7- set a global default")
        source.tell("&f/horde interval <min> <max> &7- gap between events, seconds")
        source.tell("&f/horde list &7- all settings + per-type overrides")
        source.tell("&f/horde reload &7- reload config.json")
    }

    // --- helpers ---

    private fun resolve(source: CommandSourceStack, arg: String): EntityType<*>? {
        val type = HordeManager.parseMob(arg)
        if (type == null) source.tell("&cUnknown or non-living mob type: &f$arg")
        return type
    }

    private fun describe(s: HordeManager.Settings): String =
        "rate ${s.rate.min}-${s.rate.max}/spawn, duration ${s.duration.min}-${s.duration.max}s, " +
            "spread ${s.spread.min}-${s.spread.max}s, radius ${fmt(s.radius.min)}-${fmt(s.radius.max)}"

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    private fun CommandSourceStack.tell(text: String) {
        sendSuccess({ Msg.of(text) }, false)
    }
}
