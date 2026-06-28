package mc.arch.hordes

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-disk config (JSON, via the Gson that Minecraft already bundles, so no extra dependency).
 * A global event interval, inheritable defaults, and per-type overrides.
 */
class HordeConfig {

    class IntRange(@JvmField var min: Int = 0, @JvmField var max: Int = 0)
    class DoubleRange(@JvmField var min: Double = 0.0, @JvmField var max: Double = 0.0)

    class Defaults {
        @JvmField var duration = IntRange(120, 300)
        @JvmField var rate = IntRange(8, 15)
        @JvmField var spread = IntRange(2, 6)
        @JvmField var spreadRadius = DoubleRange(0.0, 1.5)
    }

    /** Any non-null field overrides the matching default for this type. */
    class MobOverrides {
        @JvmField var duration: IntRange? = null
        @JvmField var rate: IntRange? = null
        @JvmField var spread: IntRange? = null
        @JvmField var spreadRadius: DoubleRange? = null
    }

    @JvmField var eventInterval = IntRange(600, 1500)
    @JvmField var defaults = Defaults()

    /** Eligible types. Key = entity id (e.g. "ZOMBIE" or "minecraft:zombie"); value = overrides. */
    @JvmField var mobs: MutableMap<String, MobOverrides> = linkedMapOf("ZOMBIE" to MobOverrides())

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        /** Load the config, writing a default one if it doesn't exist (or is unreadable). */
        fun load(path: Path): HordeConfig {
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path).use { reader ->
                        GSON.fromJson(reader, HordeConfig::class.java)
                    }
                }.getOrNull()?.let { return it }
            }
            val fresh = HordeConfig()
            runCatching { fresh.save(path) }
            return fresh
        }
    }
}

// --- config -> runtime range conversions (with flooring, mirroring the Paper readers) ---

fun HordeConfig.IntRange.toRange(floor: Int): HordeManager.Range {
    val mn = min.coerceAtLeast(floor)
    val mx = max.coerceAtLeast(mn)
    return HordeManager.Range(mn, mx)
}

fun HordeConfig.DoubleRange.toRangeD(floor: Double): HordeManager.RangeD {
    val mn = min.coerceAtLeast(floor)
    val mx = max.coerceAtLeast(mn)
    return HordeManager.RangeD(mn, mx)
}

fun HordeConfig.IntRange?.toRangeOpt(floor: Int): HordeManager.Range? = this?.toRange(floor)
fun HordeConfig.DoubleRange?.toRangeDOpt(floor: Double): HordeManager.RangeD? = this?.toRangeD(floor)
