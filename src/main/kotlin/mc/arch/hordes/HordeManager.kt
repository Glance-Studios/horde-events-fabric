package mc.arch.hordes

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import org.slf4j.LoggerFactory
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Owns horde state: global defaults, per-type overrides, the one active event, and the second
 * ticker that counts the active event down and schedules the next one. Scheduling is tick-driven
 * rather than timer-driven, so everything stays on the server thread (see [onServerTick]).
 */
class HordeManager {

    data class Range(val min: Int, val max: Int)
    data class RangeD(val min: Double, val max: Double)

    /** Every inheritable per-horde setting. */
    data class Settings(
        val duration: Range, // seconds an event lasts
        val rate: Range,     // extra mobs per natural spawn
        val spread: Range,   // seconds a triggered batch trickles in (0 = instant)
        val radius: RangeD,  // blocks each extra mob fans out from the trigger
    )

    /** Per-type config: any non-null field overrides the matching default. */
    class Overrides(
        var duration: Range? = null,
        var rate: Range? = null,
        var spread: Range? = null,
        var radius: RangeD? = null,
    )

    private data class Scheduled(val dueTick: Long, val action: () -> Unit)

    // Persistence, set by HordeMod. Mutators write through to the config and save.
    lateinit var config: HordeConfig
    var onSave: () -> Unit = {}

    /** Re-read config.json from disk and re-[load] it. Set by HordeMod. */
    var reloadConfig: () -> Unit = {}

    // Global scheduling cadence (when ANY horde fires). Not a per-type setting.
    var intervalMin = 600
    var intervalMax = 1500

    /** Global defaults inherited by every horde type. */
    var defaults = Settings(Range(120, 300), Range(8, 15), Range(2, 6), RangeD(0.0, 1.5))
        private set

    /** Types eligible for hordes, each with its (possibly empty) overrides. */
    val mobs = linkedMapOf<EntityType<*>, Overrides>()

    var activeType: EntityType<*>? = null
        private set

    var activeSettings: Settings? = null
        private set

    private var secondsRemaining = 0
    private var secondsUntilNext = 0
    private var ticksToSecond = 20
    private var currentTick = 0L
    private val scheduled = mutableListOf<Scheduled>()
    private var running = false

    val isActive: Boolean get() = activeType != null
    val secondsLeft: Int get() = secondsRemaining
    val nextInSeconds: Int get() = secondsUntilNext

    /** Effective settings for [type]: its overrides layered on the global defaults. */
    fun resolve(type: EntityType<*>): Settings {
        val o = mobs[type] ?: return defaults
        return Settings(
            o.duration ?: defaults.duration,
            o.rate ?: defaults.rate,
            o.spread ?: defaults.spread,
            o.radius ?: defaults.radius,
        )
    }

    /** (Re)read all values from [config] into memory. Safe to call at runtime. */
    fun load() {
        val c = config
        intervalMin = c.eventInterval.min.coerceAtLeast(1)
        intervalMax = c.eventInterval.max.coerceAtLeast(intervalMin)

        defaults = Settings(
            duration = c.defaults.duration.toRange(floor = 1),
            rate = c.defaults.rate.toRange(floor = 0),
            spread = c.defaults.spread.toRange(floor = 0),
            radius = c.defaults.spreadRadius.toRangeD(floor = 0.0),
        )

        mobs.clear()
        c.mobs.forEach { (key, ov) ->
            val type = parseMob(key)
            if (type == null) {
                LOG.warn("Skipping unknown/non-living mob type in config: {}", key)
                return@forEach
            }
            mobs[type] = Overrides(
                duration = ov.duration.toRangeOpt(floor = 1),
                rate = ov.rate.toRangeOpt(floor = 0),
                spread = ov.spread.toRangeOpt(floor = 0),
                radius = ov.spreadRadius.toRangeDOpt(floor = 0.0),
            )
        }
    }

    fun start() {
        running = true
        scheduleNext()
    }

    fun shutdown() {
        running = false
        activeType = null
        activeSettings = null
        scheduled.clear()
    }

    /** Drive from ServerTickEvents.END_SERVER_TICK (20/s). Runs due delayed batches, then the 1 Hz logic. */
    fun onServerTick() {
        if (!running) return
        currentTick++

        if (scheduled.isNotEmpty()) {
            val due = scheduled.filter { it.dueTick <= currentTick }
            if (due.isNotEmpty()) {
                scheduled.removeAll(due)
                due.forEach { runCatching { it.action() } }
            }
        }

        if (--ticksToSecond <= 0) {
            ticksToSecond = 20
            secondTick()
        }
    }

    private fun secondTick() {
        if (isActive) {
            if (--secondsRemaining <= 0) endEvent()
        } else {
            if (--secondsUntilNext <= 0) startRandomEvent()
        }
    }

    private fun scheduleNext() {
        secondsUntilNext = randomInRange(intervalMin, intervalMax)
    }

    private fun schedule(delayTicks: Long, action: () -> Unit) {
        scheduled.add(Scheduled(currentTick + delayTicks.coerceAtLeast(0), action))
    }

    /** Fire an event for a random configured type using its resolved settings. */
    fun startRandomEvent(): EntityType<*>? {
        val type = mobs.keys.randomOrNull()
        if (type == null) {
            scheduleNext()
            return null
        }
        startEvent(type, resolve(type))
        return type
    }

    /** Fire (or replace) an event for [type] with [settings]. */
    fun startEvent(type: EntityType<*>, settings: Settings, durationOverride: Int? = null) {
        activeType = type
        activeSettings = settings
        secondsRemaining = (durationOverride ?: randomInRange(settings.duration.min, settings.duration.max))
            .coerceAtLeast(1)
        LOG.info("Horde event started: {} for {}s", displayName(type), secondsRemaining)
    }

    fun endEvent() {
        val ended = activeType
        activeType = null
        activeSettings = null
        scheduleNext()
        if (ended != null) LOG.info("Horde event ended: {}", displayName(ended))
    }

    /**
     * Spawn this trigger's extra mobs for [type] near [base]. Does nothing unless [type] is the
     * active horde type. The batch is delivered over a per-trigger spread window in random
     * sub-batches; a spread of 0 spawns it all at once.
     */
    fun spawnExtra(level: ServerLevel, base: BlockPos, type: EntityType<*>) {
        if (type != activeType) return
        val settings = activeSettings ?: return
        val total = randomInRange(settings.rate.min, settings.rate.max)
        if (total <= 0) return

        val spreadSeconds = randomInRange(settings.spread.min, settings.spread.max)
        if (spreadSeconds <= 0) {
            spawnGroup(level, base, type, total, settings.radius)
            return
        }

        val spreadTicks = spreadSeconds.toLong() * 20L
        var remaining = total
        while (remaining > 0) {
            val size = minOf(remaining, Random.nextInt(1, MAX_BATCH + 1))
            remaining -= size
            val delay = Random.nextLong(0, spreadTicks + 1)
            schedule(delay) { spawnGroup(level, base, type, size, settings.radius) }
        }
    }

    private fun spawnGroup(level: ServerLevel, base: BlockPos, type: EntityType<*>, size: Int, radius: RangeD) {
        // Chunk may have unloaded between scheduling and firing, so do not force-load it back.
        if (!level.hasChunkAt(base)) return
        repeat(size) {
            val distance = if (radius.max <= radius.min) radius.min else Random.nextDouble(radius.min, radius.max)
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val x = base.x + 0.5 + cos(angle) * distance
            val z = base.z + 0.5 + sin(angle) * distance
            val pos = BlockPos.containing(x, base.y.toDouble(), z)
            type.spawn(level, pos, EntitySpawnReason.EVENT)
        }
    }

    // --- Mutators that also persist to config ---

    fun setInterval(min: Int, max: Int) {
        intervalMin = min
        intervalMax = max
        config.eventInterval = HordeConfig.IntRange(min, max)
        onSave()
        if (!isActive) scheduleNext()
    }

    fun setDefaultDuration(min: Int, max: Int) {
        defaults = defaults.copy(duration = Range(min, max))
        config.defaults.duration = HordeConfig.IntRange(min, max)
        onSave()
    }

    fun setDefaultRate(min: Int, max: Int) {
        defaults = defaults.copy(rate = Range(min, max))
        config.defaults.rate = HordeConfig.IntRange(min, max)
        onSave()
    }

    fun setDefaultSpread(min: Int, max: Int) {
        defaults = defaults.copy(spread = Range(min, max))
        config.defaults.spread = HordeConfig.IntRange(min, max)
        onSave()
    }

    fun setDefaultRadius(min: Double, max: Double) {
        defaults = defaults.copy(radius = RangeD(min, max))
        config.defaults.spreadRadius = HordeConfig.DoubleRange(min, max)
        onSave()
    }

    /** Per-type rate override. */
    fun setRate(type: EntityType<*>, min: Int, max: Int) {
        mobs.getOrPut(type) { Overrides() }.rate = Range(min, max)
        config.mobs.getOrPut(displayName(type)) { HordeConfig.MobOverrides() }.rate =
            HordeConfig.IntRange(min, max)
        onSave()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("horde-events")

        /** Largest sub-batch spawned at once when spreading a triggered horde over its window. */
        const val MAX_BATCH = 4

        /** Parse a config/command name into a living, spawnable EntityType, or null. */
        fun parseMob(name: String): EntityType<*>? {
            val id = Identifier.tryParse(name.lowercase()) ?: return null
            val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return null
            return if (type.category != MobCategory.MISC) type else null
        }

        /** Canonical display name, e.g. "ZOMBIE" (matches the Paper version's EntityType.name). */
        fun displayName(type: EntityType<*>): String =
            BuiltInRegistries.ENTITY_TYPE.getKey(type).path.uppercase()

        /** All living/spawnable entity type ids (lowercase paths), for tab-completion. */
        fun livingTypeNames(): List<String> =
            BuiltInRegistries.ENTITY_TYPE
                .filter { it.category != MobCategory.MISC }
                .mapNotNull { BuiltInRegistries.ENTITY_TYPE.getKey(it)?.path }
                .sorted()

        /** Inclusive random int in [min, max]; tolerant of max <= min. */
        fun randomInRange(min: Int, max: Int): Int =
            if (max <= min) min else Random.nextInt(min, max + 1)
    }
}
