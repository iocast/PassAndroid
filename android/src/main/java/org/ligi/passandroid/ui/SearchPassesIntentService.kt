package org.ligi.passandroid.ui

import android.app.IntentService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.github.salomonbrys.kodein.instance
import org.greenrobot.eventbus.EventBus
import org.ligi.passandroid.App
import org.ligi.passandroid.R
import org.ligi.passandroid.Tracker
import org.ligi.passandroid.events.ScanFinishedEvent
import org.ligi.passandroid.events.ScanProgressEvent
import org.ligi.passandroid.functions.fromURI
import org.ligi.passandroid.model.PassStore
import org.ligi.passandroid.model.PastLocationsStore
import org.ligi.passandroid.model.pass.Pass
import org.ligi.passandroid.ui.UnzipPassController.InputStreamUnzipControllerSpec
import org.ligi.tracedroid.logging.Log
import java.io.File
import java.util.*

private const val NOTIFICATION_CHANNEL_ID = "transactions"

class SearchPassesIntentService : IntentService("SearchPassesIntentService") {

    private val notifyManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var shouldFinish: Boolean = false
    private var progressNotificationBuilder: NotificationCompat.Builder? = null
    private var findNotificationBuilder: NotificationCompat.Builder? = null

    private var foundList = ArrayList<Pass>()

    private var lastProgressUpdate: Long = 0

    val passStore: PassStore = App.kodein.instance()
    val bus: EventBus = App.kodein.instance()
    val tracker: Tracker = App.kodein.instance()

    override fun onHandleIntent(intent: Intent?) {

        foundList.clear()

        if (Build.VERSION.SDK_INT > 25) {

            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "PassAndroid Pass scan", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Notifications when PassAndroid is scanning for passes"
            notifyManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(applicationContext, 1, Intent(baseContext, PassListActivity::class.java), 0)
        progressNotificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).setContentTitle(getString(R.string.scanning_for_passes))
                .setSmallIcon(R.drawable.ic_refresh)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setProgress(1, 1, true)

        findNotificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).setAutoCancel(true).setSmallIcon(R.drawable.ic_launcher)

        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        for (path in PastLocationsStore(preferences, tracker).locations) {
            searchIn(File(path), false)
        }

        // note to future_me: yea one thinks we only need to search root here, but root was /system for me and so
        // did not contain "/SDCARD" #dontoptimize
        // on my phone:

        // | /mnt/sdcard/Download << this looks kind of stupid as we do /mnt/sdcard later and hence will go here twice
        // but this helps finding passes in Downloads ( where they are very often ) fast - some users with lots of files on the SDCard gave
        // up the refreshing of passes as it took so long to traverse all files on the SDCard
        // one could think about not going there anymore but a short look at this showed that it seems cost more time to check than what it gains
        // in download there are mostly single files in a flat dir - no huge tree behind this imho
        searchIn(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), true)

        // | /system
        searchIn(Environment.getRootDirectory(), true)

        // | /mnt/sdcard
        searchIn(Environment.getExternalStorageDirectory(), true)

        // | /cache
        searchIn(Environment.getDownloadCacheDirectory(), true)

        // | /data
        searchIn(Environment.getDataDirectory(), true)
        notifyManager.cancel(PROGRESS_NOTIFICATION_ID)

        bus.post(ScanFinishedEvent(foundList))
    }

    /**
     * recursive voyage starting at path to find files named .pkpass
     */
    private fun searchIn(path: File, recursive: Boolean) {

        if (System.currentTimeMillis() - lastProgressUpdate > 1000) {
            lastProgressUpdate = System.currentTimeMillis()
            val msg = path.toString()
            bus.post(ScanProgressEvent(msg))
            progressNotificationBuilder!!.setContentText(msg)
            notifyManager.notify(PROGRESS_NOTIFICATION_ID, progressNotificationBuilder!!.build())
        }

        val files = path.listFiles()

        if (files == null || files.isEmpty()) {
            // no files here
            return
        }


        for (file in files) {
            if (shouldFinish) {
                return
            }
            Log.i("search " + file.absoluteFile)
            if (recursive && file.isDirectory) {
                searchIn(file, true)
            } else if (file.name.toLowerCase().endsWith(".pkpass") || file.name.toLowerCase().endsWith(".espass")) {
                Log.i("found" + file.absolutePath)

                try {
                    val ins = fromURI(baseContext, Uri.parse("file://" + file.absolutePath))
                    val onSuccessCallback = SearchSuccessCallback(baseContext,
                            passStore,
                            foundList,
                            findNotificationBuilder!!,
                            file,
                            notifyManager)
                    val spec = InputStreamUnzipControllerSpec(ins!!,
                            baseContext,
                            passStore,
                            onSuccessCallback,
                            object : UnzipPassController.FailCallback {
                                override fun fail(reason: String) {
                                    Log.i("fail", reason)
                                }
                            })
                    UnzipPassController.processInputStream(spec)
                } catch (e: Exception) {
                    tracker.trackException("Error in SearchPassesIntentService", e, false)
                }
            }
        }
    }

    override fun onDestroy() {
        shouldFinish = true
        super.onDestroy()
    }

    companion object {
        const val PROGRESS_NOTIFICATION_ID = 1
        const val FOUND_NOTIFICATION_ID = 2
        const val REQUEST_CODE = 1
    }
}
