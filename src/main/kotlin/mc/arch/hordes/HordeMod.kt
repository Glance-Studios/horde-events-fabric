package mc.arch.hordes

import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/**
 * Server-side entrypoint. Players install nothing. This mod only ever runs on the server, uses
 * vanilla mobs and a vanilla command, and registers no new content.
 */
class HordeMod : DedicatedServerModInitializer {

    override fun onInitializeServer() {
        val configPath = FabricLoader.getInstance().configDir.resolve("horde-events.json")

        val manager = HordeManager()

        fun bind(config: HordeConfig) {
            manager.config = config
            manager.onSave = { config.save(configPath) }
        }

        bind(HordeConfig.load(configPath))
        manager.reloadConfig = {
            bind(HordeConfig.load(configPath))
            manager.load()
        }
        manager.load()
        manager.start()

        // 20 Hz server tick drives the manager (countdowns + delayed spawn batches).
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { manager.onServerTick() })

        // /horde admin command.
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            HordeCommand.register(dispatcher, manager)
        })

        // Expose to the natural-spawn Mixin.
        Hordes.manager = manager

        LOG.info("HordeEvents (Fabric) enabled with {} configured mob type(s).", manager.mobs.size)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("horde-events")
    }
}

/** Static handoff so the (Java) spawn Mixin can reach the running manager. */
object Hordes {
    @JvmField
    var manager: HordeManager? = null
}
