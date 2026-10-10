package io.hostlookup.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import de.obsp.hostlookup.R
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object LookupSession {
    enum class Phase { IDLE, RUNNING, ENRICHING, DONE, FAILED }

    data class Snapshot(
        val generation: Long,
        val revision: Int,
        val phase: Phase,
        val domain: String,
        val result: LookupResult?,
        val error: String,
    )

    fun interface Listener {
        fun onSnapshot(snapshot: Snapshot)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val generation = AtomicLong()
    private val revision = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var snapshot = Snapshot(0, 0, Phase.IDLE, "", null, "")

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onSnapshot(snapshot)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isCurrent(generation: Long): Boolean = generation == this.generation.get()

    fun start(context: Context, domain: String) {
        val generation = this.generation.incrementAndGet()
        LookupService.cancelActive()
        publish(Snapshot(generation, revision.incrementAndGet(), Phase.RUNNING, domain, null, ""))
        val intent = Intent(context, LookupService::class.java)
            .putExtra(LookupService.EXTRA_DOMAIN, domain)
            .putExtra(LookupService.EXTRA_GENERATION, generation)
        context.startForegroundService(intent)
    }

    fun cancel(context: Context) {
        val generation = this.generation.incrementAndGet()
        LookupService.cancelActive()
        publish(Snapshot(generation, revision.incrementAndGet(), Phase.IDLE, "", null, ""))
        context.stopService(Intent(context, LookupService::class.java))
    }

    fun publish(generation: Long, phase: Phase, domain: String, result: LookupResult?, error: String) {
        if (generation != this.generation.get()) return
        val next = Snapshot(generation, revision.incrementAndGet(), phase, domain, result, error)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(next)
        } else {
            main.post {
                if (generation == this.generation.get()) apply(next)
            }
        }
    }

    private fun publish(next: Snapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) apply(next) else main.post { apply(next) }
    }

    private fun apply(next: Snapshot) {
        snapshot = next
        listeners.forEach { it.onSnapshot(next) }
    }
}

internal class LookupService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val domain = intent?.getStringExtra(EXTRA_DOMAIN).orEmpty()
        val generation = intent?.getLongExtra(EXTRA_GENERATION, 0L) ?: 0L
        startInForeground(domain, enriching = false)
        if (domain.isBlank() || !LookupSession.isCurrent(generation)) {
            stopLookup(startId)
            return START_NOT_STICKY
        }
        val task = RipeStatClient.Task()
        active.set(task)
        worker.execute {
            try {
                runLookup(domain, generation, task)
            } finally {
                if (LookupSession.isCurrent(generation)) stopLookup(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelActive()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        cancelActive()
        val current = LookupSession.snapshot
        if (current.result != null && LookupSession.isCurrent(current.generation)) {
            LookupSession.publish(current.generation, LookupSession.Phase.DONE, current.domain, current.result, current.error)
        }
        stopLookup(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    private fun runLookup(domain: String, generation: Long, task: RipeStatClient.Task) {
        DnsBridge.initialize(applicationContext)
        val unknown = getString(R.string.unknown_resolver)
        val parsed = try {
            LookupResult.parse(DnsBridge.lookup(domain), unknown)
        } catch (error: Exception) {
            if (task.cancelled || !LookupSession.isCurrent(generation)) return
            LookupSession.publish(generation, LookupSession.Phase.FAILED, domain, null, error.message ?: error.toString())
            return
        }
        if (task.cancelled || !LookupSession.isCurrent(generation)) return
        rememberDomain(applicationContext, domain)
        LookupSession.publish(generation, LookupSession.Phase.ENRICHING, domain, parsed.snapshot(), "")
        showNotification(domain, enriching = true)
        if (!task.cancelled && LookupSession.isCurrent(generation)) {
            parsed.replaceInfrastructureAddresses(resolveInfrastructureHosts(parsed.infrastructureTargets()))
            LookupSession.publish(generation, LookupSession.Phase.ENRICHING, domain, parsed.snapshot(), "")
        }
        if (task.cancelled || !LookupSession.isCurrent(generation)) return
        RipeStatClient.enrich(
            applicationContext,
            parsed.allIpAddresses(),
            parsed.whoisEntries,
            task,
        ) { entries, error, finished ->
            if (task.cancelled || !LookupSession.isCurrent(generation)) return@enrich
            parsed.replaceWhois(entries, error)
            val phase = if (finished) LookupSession.Phase.DONE else LookupSession.Phase.ENRICHING
            LookupSession.publish(generation, phase, domain, parsed.snapshot(), error)
        }
    }

    private fun startInForeground(domain: String, enriching: Boolean) {
        val notification = buildNotification(domain, enriching)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showNotification(domain: String, enriching: Boolean) {
        val notification = buildNotification(domain, enriching)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(domain: String, enriching: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.lookup_channel), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val title = if (domain.isBlank()) getString(R.string.app_name) else getString(R.string.looking_up, domain)
        val body = getString(if (enriching) R.string.enrichment_notification_body else R.string.lookup_notification_body)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lookup)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(getColor(R.color.brand))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    private fun stopLookup(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    companion object {
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_GENERATION = "generation"
        private const val CHANNEL_ID = "lookups"
        private const val NOTIFICATION_ID = 7
        private val active = AtomicReference<RipeStatClient.Task?>()
        private val worker = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hostlookup-lookup").apply { isDaemon = true }
        }

        fun cancelActive() {
            active.getAndSet(null)?.cancel()
        }
    }
}
