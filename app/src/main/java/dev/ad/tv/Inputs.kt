package dev.ad.tv

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class TvInput(val id: String, val label: String)

/** Connected HDMI inputs, labelled with the CEC device name when the TV reports one. */
object Inputs {
    fun list(context: Context): List<TvInput> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val all = try {
            tim.tvInputList
        } catch (e: Exception) {
            return emptyList()
        }
        val hdmi = all.filter { it.type == TvInputInfo.TYPE_HDMI }
        val parents = hdmi.filter { it.parentId == null }
        return parents.mapNotNull { port ->
            val children = hdmi.filter { it.parentId == port.id && connected(tim, it) }
            if (children.isEmpty() && !connected(tim, port)) return@mapNotNull null
            val portName = name(context, port)
            val device = children.firstOrNull()?.let { name(context, it) }
            TvInput(children.firstOrNull()?.id ?: port.id, if (device != null && device != portName) "$portName — $device" else portName)
        }
    }

    fun open(context: Context, input: TvInput) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Apps.start(context, intent)
    }

    private fun connected(tim: TvInputManager, info: TvInputInfo) = try {
        tim.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED
    } catch (e: Exception) {
        false
    }

    private fun name(context: Context, info: TvInputInfo): String =
        info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() } ?: info.loadLabel(context).toString()
}
