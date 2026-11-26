package com.music.bitchord.car

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Catches up the bridge when the app process starts.
 *
 * A boot receiver is still the primary path, but some skins drop it for apps
 * that are not autostart-whitelisted. Declaring a provider lets us reconcile the
 * switch with the service without touching Application or MainActivity: once
 * the process is up (and again on its first Activity, i.e. when the user opens
 * the app) we start the bridge if the switch says on, and stop it if not.
 */
class CarBridgeInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return true
        // Providers are installed before Application.onCreate has finished
        // setting up the player/data singletons; defer to the next main-loop pass.
        Handler(Looper.getMainLooper()).post {
            reconcile(app)
            (app as? Application)?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                        // The no-UI control Activity only exists to apply an
                        // ON/OFF/RESTART command. Reconciling here runs *before*
                        // its onCreate and can restart what the command is
                        // about to turn off, leaving a stale VPN interface.
                        if (activity is CarBridgeControlActivity) return
                        reconcile(app)
                    }

                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                },
            )
        }
        return true
    }

    private fun reconcile(app: android.content.Context) = CarBridgeController.syncFromPrefs(app)

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ) = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}
