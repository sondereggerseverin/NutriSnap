package ch.nutrisnap.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import ch.nutrisnap.app.MainActivity

/** Scheme/Host, das [MainActivity] erkennt, um direkt den Quick-Add-Flow im Tagebuch
 *  zu öffnen (siehe MainActivity.extractQuickAddRoute). */
const val QUICK_ADD_DEEP_LINK = "nutrisnap://quickadd"

class QuickAddAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(QUICK_ADD_DEEP_LINK)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NutriSnapWidget().update(context, glanceId)
    }
}
