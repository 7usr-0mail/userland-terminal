package tech.ula

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.* // ktlint-disable no-wildcard-imports
import com.termux.app.TermuxActivity
import tech.ula.model.entities.App
import tech.ula.model.entities.ServiceType
import tech.ula.model.repositories.UlaDatabase
import tech.ula.model.entities.Session
import tech.ula.utils.* // ktlint-disable no-wildcard-imports
import kotlin.coroutines.CoroutineContext
import java.io.File

class ServerService : Service(), CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    companion object {
        const val SERVER_SERVICE_RESULT: String = "tech.ula.ServerService.RESULT"
    }

    private val activeSessions: MutableMap<Long, Session> = mutableMapOf()

    private val logger: Logger = SentryLogger()

    private lateinit var broadcaster: LocalBroadcastManager
    private lateinit var androidCtlBridge: AndroidCtlBridge

    private val notificationManager: NotificationConstructor by lazy {
        NotificationConstructor(this)
    }

    private val busyboxExecutor by lazy {
        val ulaFiles = UlaFiles(this, this.applicationInfo.nativeLibraryDir)
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, ulaFiles)
        BusyboxExecutor(ulaFiles, prootDebugLogger)
    }

    private val localServerManager by lazy {
        LocalServerManager(this.filesDir.path, busyboxExecutor).apply {
            outputListener = { line -> sendServerOutputBroadcast(line) }
        }
    }

    override fun onCreate() {
        broadcaster = LocalBroadcastManager.getInstance(this)
        androidCtlBridge = AndroidCtlBridge(this).also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.getStringExtra("type")) {
            "start" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                this.launch { startSession(session) }
            }
            "stopApp" -> {
                val app: App = intent.getParcelableExtra("app")!!
                stopApp(app)
            }
            "restartRunningSession" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                startClient(session)
            }
            "kill" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                killSession(session)
            }
            "filesystemIsBeingDeleted" -> {
                val filesystemId: Long = intent.getLongExtra("filesystemId", -1)
                cleanUpFilesystem(filesystemId)
            }
            "stopAll" -> {
                activeSessions.forEach { (_, session) ->
                    killSession(session)
                }
            }
        }

        return START_STICKY
    }

    // Used in conjunction with manifest attribute `android:stopWithTask="true"`
    // to clean up when app is swiped away.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        androidCtlBridge.stop()
        super.onDestroy()
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
    }

    private fun removeSession(session: Session) {
        activeSessions.remove(session.pid)
        if (activeSessions.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateSession(session: Session) = CoroutineScope(Dispatchers.Default).launch {
        UlaDatabase.getInstance(this@ServerService).sessionDao().updateSession(session)
    }

    private fun killSession(session: Session) {
        localServerManager.stopService(session)
        removeSession(session)
        session.active = false
        updateSession(session)
    }

    private suspend fun startSession(session: Session) {
        LocalSessionTrace.append(this, "START session=${session.name} fs=${session.filesystemId} user=${session.username} port=${session.port}")
        androidCtlBridge.provision(session.filesystemId, File(filesDir, "${session.filesystemId}/support"), session.username)
        startForeground(NotificationConstructor.serviceNotificationId, notificationManager.buildPersistentServiceNotification())
        if (session.serviceType == ServiceType.Local) {
            session.active = true
            session.pid = session.id
            updateSession(session)
            activeSessions[session.pid] = session
            startClient(session)
            return
        }
        session.pid = localServerManager.startServer(session)

        // Previously this loop had no exit condition. If the server binary was
        // missing or failed to launch, the service waited forever and the UI sat
        // on "Starting service" with no explanation. Bound the wait and report.
        val timeoutMillis = 60_000L
        val pollIntervalMillis = 500L
        var waited = 0L
        while (!localServerManager.isServerRunning(session)) {
            delay(pollIntervalMillis)
            waited += pollIntervalMillis
            if (waited % 10_000L == 0L) {
                sendServerOutputBroadcast("[service] waiting for server... ${waited / 1000}s")
            }
            if (waited >= timeoutMillis) {
                sendServerOutputBroadcast("[service] giving up after ${timeoutMillis / 1000}s")
                // Tear down whatever we started. Previously the timeout returned
                // without killing it, so a half-started dropbear kept holding
                // port 2022 and every later attempt died with
                // "Address already in use / No listening ports available".
                sendServerOutputBroadcast("[service] cleaning up the failed server")
                try {
                    localServerManager.stopService(session)
                } catch (err: Exception) {
                    sendServerOutputBroadcast("[service] cleanup failed: ${'$'}{err.message}")
                }
                sendDialogBroadcast("serverFailedToStart")
                stopForeground(true)
                stopSelf()
                return
            }
        }

        session.active = true
        updateSession(session)
        startClient(session)
        activeSessions[session.pid] = session
    }

    private fun stopApp(app: App) {
        val appSessions = activeSessions.filter { (_, session) ->
            session.name == app.name
        }
        appSessions.forEach { (_, session) ->
            killSession(session)
        }
    }

    private fun startClient(session: Session) {
        when (session.serviceType) {
            ServiceType.Local -> startLocalClient(session)
            ServiceType.Ssh -> startSshClient(session)
            ServiceType.Vnc -> startVncClient(session, "com.iiordanov.freebVNC")
            ServiceType.Xsdl -> startXsdlClient("x.org.server")
            else -> sendDialogBroadcast("unhandledSessionServiceType")
        }
        sendSessionActivatedBroadcast()
    }

    private fun startLocalClient(session: Session) {
        LocalSessionTrace.append(this, "LOCAL activity launch fs=${session.filesystemId} user=${session.username}")
        val intent = Intent(this, AndroidShellActivity::class.java)
                .putExtra("localFilesystemId", session.filesystemId)
                .putExtra("localSessionName", session.name)
                .putExtra("localUsername", session.username)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (err: Exception) {
            LocalSessionTrace.append(this, "LOCAL activity launch failure: ${err.javaClass.simpleName}: ${err.message}")
            session.active = false
            updateSession(session)
            activeSessions.remove(session.pid)
        }
    }

    private fun startSshClient(session: Session) {
        LocalSessionTrace.append(this, "OPEN terminal client session=${session.name} port=${session.port}")
        // Launch the activity with its documented ssh:// URI. Starting
        // TermuxService directly first caused it to open TermuxActivity without
        // connection data; that activity then overwrote the service fields with
        // empty values, so dbclient exited immediately while the server stayed up.
        val connectionUri = Uri.Builder()
                .scheme("ssh")
                .encodedAuthority("${Uri.encode(session.username)}@localhost:${session.port}")
                .path("/")
                .fragment(session.name)
                .build()
        val termIntent = Intent(this, TermuxActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(connectionUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            startActivity(termIntent)
        } catch (err: Exception) {
            logger.addExceptionBreadcrumb(Exception("Could not open in-app terminal: ${err.message}"))
            sendDialogBroadcast("unhandledSessionServiceType")
        }
    }

    private fun startVncClient(session: Session, packageName: String) {
        val bVncIntent = Intent()
        bVncIntent.action = Intent.ACTION_VIEW
        bVncIntent.type = "application/vnd.vnc"
        bVncIntent.data = Uri.parse("vnc://127.0.0.1:5951/?VncUsername=${session.username}&VncPassword=${session.vncPassword}")
        bVncIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        if (clientIsPresent(bVncIntent)) {
            this.startActivity(bVncIntent)
        } else {
            getClient(packageName)
        }
    }

    private fun startXsdlClient(packageName: String) {
        val xsdlIntent = Intent()
        xsdlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        xsdlIntent.data = Uri.parse("x11://give.me.display:4721")

        if (clientIsPresent(xsdlIntent)) {
            startActivity(xsdlIntent)
        } else {
            getClient(packageName)
        }
    }

    private fun clientIsPresent(intent: Intent): Boolean {
        val activities = packageManager.queryIntentActivities(intent, 0)
        return (activities.size > 0)
    }

    private fun getClient(packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            this.startActivity(intent)
        } catch (err: ActivityNotFoundException) {
            sendDialogBroadcast("playStoreMissingForClient")
        }
    }

    private fun cleanUpFilesystem(filesystemId: Long) {
        activeSessions.values.filter { it.filesystemId == filesystemId }
                .forEach { killSession(it) }
    }

    private fun sendSessionActivatedBroadcast() {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "sessionActivated")
        broadcaster.sendBroadcast(intent)
    }

    private fun sendServerOutputBroadcast(line: String) {
        LocalSessionTrace.append(this, "SERVER $line")
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "serverOutput")
                .putExtra("line", line)
        broadcaster.sendBroadcast(intent)
    }

    private fun sendDialogBroadcast(type: String) {
        LocalSessionTrace.append(this, "DIALOG $type")
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "dialog")
                .putExtra("dialogType", type)
        broadcaster.sendBroadcast(intent)
    }
}