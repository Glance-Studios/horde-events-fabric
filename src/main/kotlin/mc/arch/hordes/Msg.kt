package mc.arch.hordes

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/** Converts legacy `&`-color strings (as the Paper version used) into chat Components. */
object Msg {
    fun of(text: String): MutableComponent {
        val root = Component.empty()
        var style = Style.EMPTY
        val sb = StringBuilder()

        fun flush() {
            if (sb.isNotEmpty()) {
                root.append(Component.literal(sb.toString()).setStyle(style))
                sb.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&' && i + 1 < text.length) {
                val fmt = ChatFormatting.getByCode(text[i + 1].lowercaseChar())
                if (fmt != null) {
                    flush()
                    style = if (fmt == ChatFormatting.RESET) Style.EMPTY else style.applyFormat(fmt)
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        flush()
        return root
    }
}
