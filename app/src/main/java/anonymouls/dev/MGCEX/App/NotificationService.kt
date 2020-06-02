package anonymouls.dev.MGCEX.App

import android.bluetooth.BluetoothAdapter.STATE_CONNECTED
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.os.IBinder
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import anonymouls.dev.MGCEX.DatabaseProvider.DatabaseController
import anonymouls.dev.MGCEX.DatabaseProvider.NotifyFilterTable
import anonymouls.dev.MGCEX.util.TopExceptionHandler
import anonymouls.dev.MGCEX.util.Utils
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue


class NotificationService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationService.contentResolver = contentResolver
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Algorithm.tryForceStartListener(this)
        return START_STICKY
    }

    override fun onListenerConnected() {
        if (!IsActive) {
            IsActive = true
            Repeater = AsyncRepeater()
            Repeater!!.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
        super.onListenerConnected()
    }
    override fun onListenerDisconnected() {
        IsActive = false
        Repeater?.cancel(false)
        Repeater = null
        super.onListenerDisconnected()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        instance = this
        if (!Algorithm.IsActive || Settings.Global.getInt(contentResolver, "zen_mode")>0) return
        val pack = sbn.packageName
        if (!NotifyFilterTable.IsEnabled(pack,
                        DatabaseController.getDCObject(applicationContext).currentDataBase!!))
            return
        val extras = sbn.notification.extras
        var title = extras.getString("android.title")
        var text = "-----"
        try {
            text = Objects.requireNonNull(extras.getCharSequence("android.text")).toString()
        } catch (ex: NullPointerException) {

        }

        val msgrcv = Intent(NotifyAction)
        msgrcv.putExtra("app", pack)
        msgrcv.putExtra("title", title)
        msgrcv.putExtra("text", text)
        if (UartService.instance != null && (UartService.instance!!.mConnectionState >= STATE_CONNECTED
                        || DeviceControllerActivity.StatusCode >= 3)) {
            sendBroadcast(msgrcv)
            if (title == null) title = ""
            PendingList.add(CustomNotification(pack, title, text, sbn))
        }
        else
            return
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        findAndDeleteByID(sbn?.id)
        super.onNotificationRemoved(sbn)
    }
    override fun onBind(intent: Intent): IBinder? {
        instance = this
        return super.onBind(intent)
    }

    inner class CustomNotification(AppText : String, TitleText : String, ContentText : String, sbn: StatusBarNotification) {
        private var titleText   : String = ""
        private var contentText : String = ""
        private var appText     : String = ""
        private var sbn : StatusBarNotification = sbn

        var ID          : Int    = -1
        var repeats     : Int    = 0
        var isLocked    : Boolean = false
        var ready       : Boolean = false
        var timer       : Timer   = Timer(false)
        var cancelled   : Boolean = false


        init {
            this.sbn = sbn
            this.titleText   = TitleText
            this.contentText = ContentText
            this.ID          = sbn.id
            this.appText     = AppText
            timer.schedule(object : TimerTask() {
                override fun run() {
                    ready = true
                }
            }, (sharedPrefs!!.getInt(SettingsActivity.secondsNotify,5)*500).toLong(),
                (sharedPrefs!!.getInt(SettingsActivity.secondsNotify,5)*1000).toLong())
        }
        private fun checkIsActive(): Boolean{
            val aN = activeNotifications
            for (notify in aN){
                if (notify.id == this.ID) { cancelled = true; return  true;}
            }
            return  false
        }
        fun sendToDevice(){
            if (!isLocked){
                isLocked = true;
                return
            } else {
                if (!ready || !checkIsActive()) return
                repeats++
                ready = false
                val msgrcv = Intent(NotifyAction)
                msgrcv.putExtra("app", appText)
                msgrcv.putExtra("title", titleText)
                msgrcv.putExtra("text", contentText)
                if (UartService.instance != null && UartService.instance!!.mConnectionState >= STATE_CONNECTED)
                    sendBroadcast(msgrcv)
            }
        }
    }


    companion object {

        val NotifyAction = "ExternalNotify"
        var Repeater     : AsyncRepeater?       = null
        var instance     : NotificationService? = null
        var IsActive     : Boolean              = false
        var PendingList  : ConcurrentLinkedQueue<CustomNotification> = ConcurrentLinkedQueue()
        var sharedPrefs  : SharedPreferences?    = null
        var contentResolver: ContentResolver?     = null

        fun findAndDeleteByID(ID : Int?){
            for (CN in PendingList){
                if (CN.ID == ID
                        || CN.repeats >= sharedPrefs!!.getInt(SettingsActivity.repeatsNumbers,5)
                        || CN.cancelled)
                    PendingList.remove(CN)
            }
        }

        class AsyncRepeater : AsyncTask<String, Void, Boolean>(){

            private var topExceptionHandler: TopExceptionHandler = TopExceptionHandler(instance!!.applicationContext)

            override fun doInBackground(vararg params: String?): Boolean {
                if (sharedPrefs == null)
                    sharedPrefs = Utils.GetSharedPrefs(instance!!)
                Thread.currentThread().name = "NotifyRepeaters"
                Thread.currentThread().priority = Thread.MIN_PRIORITY
                while (IsActive){
                    if (Settings.Global.getInt(contentResolver, "zen_mode") == 0) {
                        for (CN in PendingList) {
                            CN.sendToDevice()
                            if (CN.repeats >= sharedPrefs!!.getInt(SettingsActivity.repeatsNumbers, 3))
                                PendingList.remove(CN)
                            Thread.sleep(3000)
                        }
                    } else PendingList.clear()

                    Thread.sleep((sharedPrefs!!.getInt(SettingsActivity.secondsNotify, 5) * 1000).toLong())
                }
                return true
            }
        }
    }
}