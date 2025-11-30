package com.bpareja.pomodorotec.pomodoro

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.os.CountDownTimer
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bpareja.pomodorotec.MainActivity
import com.bpareja.pomodorotec.PomodoroReceiver
import com.bpareja.pomodorotec.R
import com.bpareja.pomodorotec.utils.DataSyncManager

enum class Phase {
    FOCUS, BREAK
}

class PomodoroViewModel(application: Application) : AndroidViewModel(application) {
    init {
        instance = this
    }

    // Singleton para acceder al ViewModel desde el BroadcastReceiver
    companion object {
        internal var instance: PomodoroViewModel? = null
        fun skipBreak() {
            instance?.startFocusSession()
        }
    }

    private val context = getApplication<Application>().applicationContext

    // Estados observables (LiveData)
    private val _timeLeft = MutableLiveData("25:00") // Tiempo mostrado en UI
    val timeLeft: LiveData<String> = _timeLeft

    private val _isRunning = MutableLiveData(false) // Estado del timer
    val isRunning: LiveData<Boolean> = _isRunning

    private val _currentPhase = MutableLiveData(Phase.FOCUS)// Fase actual
    val currentPhase: LiveData<Phase> = _currentPhase

    private val _isSkipBreakButtonVisible = MutableLiveData(false)// Visibilidad botón saltar
    val isSkipBreakButtonVisible: LiveData<Boolean> = _isSkipBreakButtonVisible

    private val _progress = MutableLiveData(0f) // Progreso (0-1)
    val progress: LiveData<Float> = _progress

    // Variables de frases motivacionales
    private var currentMotivationalMessage: String = ""

    private val focusQuotes = listOf(
        "🚀 ¡El éxito es la suma de pequeños esfuerzos!",
        "🔥 Mantén la visión, confía en el proceso.",
        "🧠 Tu única competencia eres tú mismo.",
        "💎 La disciplina te lleva donde la motivación no alcanza.",
        "🎯 ¡Concéntrate! Estás construyendo tu futuro.",
        "⚡ Hazlo con pasión o cambia de estrategia.",
        "🦁 No te detengas hasta estar orgulloso."
    )

    private val breakQuotes = listOf(
        "🌿 Respira profundo y recarga energías.",
        "🧘 Estírate un poco, tu cuerpo lo agradecerá.",
        "💧 Bebe agua y despeja tu mente.",
        "🔋 ¡Gran trabajo! Te mereces este descanso.",
        "🌞 Desconecta un momento para volver con fuerza.",
        "🍵 Tómate un té y disfruta la calma.",
        "🎶 Escucha tu canción favorita y relájate."
    )

    // Variables de control del timer
    private var countDownTimer: CountDownTimer? = null

    private var totalTimeInMillis: Long = 25 * 60 * 1000L // Tiempo total (25 min por defecto)
    private var timeRemainingInMillis: Long = 25 * 60 * 1000L // Tiempo restante actual

    // ----------- FUNCIONES PRINCIPALES ------------

    fun startFocusSession() {
        countDownTimer?.cancel()
        // Limpiamos notificación previa para forzar que la nueva aparezca como Pop-up (Heads-up)
        cancelNotification()

        _currentPhase.value = Phase.FOCUS
        timeRemainingInMillis = 25 * 60 * 1000L
        totalTimeInMillis = timeRemainingInMillis
        _timeLeft.value = "25:00"
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = false

        // Seleccionar frase motivacional nueva
        currentMotivationalMessage = focusQuotes.random()

        // Iniciamos el timer (la notificación se lanzará dentro de startTimer)
        startTimer()
    }

    private fun startBreakSession() {
        // Limpiamos notificación previa
        cancelNotification()

        _currentPhase.value = Phase.BREAK
        timeRemainingInMillis = 5 * 60 * 1000L
        totalTimeInMillis = timeRemainingInMillis
        _timeLeft.value = "05:00"
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = true

        // Seleccionar frase de descanso nueva
        currentMotivationalMessage = breakQuotes.random()

        startTimer()
    }

    fun startTimer() {
        countDownTimer?.cancel()
        _isRunning.value = true

        // Actualizamos notificación inicial (con sonido/vibración si es el inicio)
        updateNotification(isUpdates = false)

        countDownTimer = object : CountDownTimer(timeRemainingInMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemainingInMillis = millisUntilFinished
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                val formattedTime = String.format("%02d:%02d", minutes, seconds)

                _timeLeft.value = formattedTime

                val progressValue = 1f - (millisUntilFinished.toFloat() / totalTimeInMillis.toFloat())
                _progress.value = progressValue

                // ----------- ACTUALIZAR UI EXTERNA (WIDGET Y NOTIFICACIÓN) -------------
                updateWidgetData()

                // Actualización silenciosa de la notificación (solo texto y barra de progreso)
                updateNotification(isUpdates = true)
            }

            override fun onFinish() {
                _isRunning.value = false
                _progress.value = 1f
                updateNotification(isUpdates = false, isFinished = true) // Notificar fin

                when (_currentPhase.value) {
                    Phase.FOCUS -> startBreakSession()
                    Phase.BREAK -> startFocusSession()
                    null -> {}
                }
            }
        }.start()
    }

    fun updateDurations(sessionDuration: Int, breakDuration: Int) {
        DataSyncManager.sendPomodoroData(
            context = getApplication(),
            sessionDuration = sessionDuration,
            breakDuration = breakDuration
        )
    }

    fun updateTimerData() {
        DataSyncManager.sendPomodoroData(
            context = getApplication(),
            sessionDuration = 25,
            breakDuration = 5
        )
    }

    fun pauseTimer() {
        countDownTimer?.cancel()
        _isRunning.value = false
        // Actualizar notificación para mostrar que está en pausa (y mostrar botón Reanudar)
        updateNotification(isUpdates = false)
    }

    fun resetTimer() {
        countDownTimer?.cancel()
        _isRunning.value = false
        _currentPhase.value = Phase.FOCUS
        timeRemainingInMillis = 25 * 60 * 1000L
        totalTimeInMillis = timeRemainingInMillis
        _timeLeft.value = "25:00"
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = false

        updateWidgetData()
        cancelNotification() // Limpiar notificación al resetear
    }

    // -------------- ACTUALIZACIÓN DE WIDGET -----------------

    private fun updateWidgetData() {
        val prefs = context.getSharedPreferences("pomodoro_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("phase", _currentPhase.value?.let { if (it == Phase.FOCUS) "Concentración" else "Descanso" } ?: "Concentración")
            putString("timeLeft", _timeLeft.value ?: "25:00")
            putInt("progress", ((1f - (timeRemainingInMillis.toFloat() / totalTimeInMillis.toFloat())) * 100).toInt())
            apply()
        }

        val intent = Intent(context, com.bpareja.pomodorotec.PomodoroWidgetProvider::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, com.bpareja.pomodorotec.PomodoroWidgetProvider::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }

    // ----------------- NOTIFICACIÓN AVANZADA Y MEJORADA ------------------------

    private fun cancelNotification() {
        with(NotificationManagerCompat.from(context)) {
            cancel(MainActivity.NOTIFICATION_ID)
        }
    }

    /**
     * @param isUpdates Si es true, la notificación no suena ni vibra, solo actualiza texto.
     * @param isFinished Si es true, muestra mensaje de finalización.
     */
    private fun updateNotification(isUpdates: Boolean, isFinished: Boolean = false) {
        // 1. Verificación de Permisos Robusta (Fix para "no aparece")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Si no hay permiso en Android 13+, salimos sin intentar notificar
                return
            }
        }

        // 2. Configuración de Textos e Imágenes
        val isFocus = _currentPhase.value == Phase.FOCUS
        val timeText = _timeLeft.value ?: "00:00"

        val title = if (isFinished) {
            if (isFocus) "🎉 ¡Objetivo Cumplido!" else "🔔 ¡Descanso Finalizado!"
        } else {
            if (isFocus) "🔥 Modo Foco Activo ($timeText)" else "☕ Modo Descanso ($timeText)"
        }

        val message = if (isFinished) {
            "¡Excelente racha! 🚀\n$currentMotivationalMessage\nToca para continuar."
        } else {
            if (isFocus) "$currentMotivationalMessage\n⏳ Restan $timeText para tu meta."
            else "$currentMotivationalMessage\n⏳ Restan $timeText para volver."
        }

        // Usamos LargeIcon en lugar de BigPicture para que el texto tenga prioridad y se lea completo
        val largeIconBitmap = BitmapFactory.decodeResource(
            context.resources,
            if (isFocus) R.drawable.focus_image else R.drawable.break_image
        )

        // Estilo BigText para asegurar que mensajes largos se vean completos
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(message)
            .setBigContentTitle(title)

        // 3. Colores y Vibración
        val notificationColor = if (isFocus) Color.rgb(178, 34, 34) else Color.rgb(46, 139, 87)

        // Configurar patrones de vibración. Si es isFinished, usamos uno MUY largo para simular "duración".
        val vibrationPattern = if (!isUpdates) {
            if (isFinished) {
                // Patrón de "Alarma" prolongada (~5-8 segundos de sensación)
                // espera, vibra, espera, vibra...
                longArrayOf(0, 500, 500, 500, 500, 500, 500, 500, 500, 1000)
            } else {
                // Patrón normal de inicio
                if (isFocus) longArrayOf(0, 100, 100, 100) else longArrayOf(0, 500, 500)
            }
        } else {
            longArrayOf(0) // Sin vibración en actualizaciones
        }

        // 4. Intents (Acciones)
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Intents para botones
        val pausePendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, PomodoroReceiver::class.java).apply { action = "PAUSE_TIMER" }, PendingIntent.FLAG_IMMUTABLE
        )
        val resumePendingIntent = PendingIntent.getBroadcast(
            context, 2, Intent(context, PomodoroReceiver::class.java).apply { action = "RESUME_TIMER" }, PendingIntent.FLAG_IMMUTABLE
        )
        val skipPendingIntent = PendingIntent.getBroadcast(
            context, 3, Intent(context, PomodoroReceiver::class.java).apply { action = "SKIP_BREAK" }, PendingIntent.FLAG_IMMUTABLE
        )
        val endPendingIntent = PendingIntent.getBroadcast(
            context, 4, Intent(context, PomodoroReceiver::class.java).apply { action = "END_TIMER" }, PendingIntent.FLAG_IMMUTABLE
        )

        // 5. Construcción de la Notificación
        val progressPercent = ((timeRemainingInMillis * 100) / totalTimeInMillis).toInt()
        val timerRunning = _isRunning.value == true

        // Prioridad: MAX para alertas nuevas (para que salgan flotando), LOW para actualizaciones (para que no molesten)
        val priority = if (!isUpdates) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW

        val builder = NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
            .setSmallIcon(if (isFocus) R.drawable.baseline_center_focus_strong_24 else R.drawable.baseline_free_breakfast_24)
            .setLargeIcon(largeIconBitmap) // Icono dinámico a la derecha
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(bigTextStyle) // Estilo de texto expandido
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentPendingIntent)
            .setColor(notificationColor)
            .setColorized(true)
            .setOngoing(timerRunning) // Persistente si corre
            .setAutoCancel(!timerRunning) // Cancelable si está en pausa
            .setOnlyAlertOnce(isUpdates) // Silencio en updates, Alerta en cambios de estado
            .setProgress(100, progressPercent, false)

        // Configuración de Sonido/Vibración solo si NO es un update de tick
        if (!isUpdates) {
            builder.setVibrate(vibrationPattern)
            builder.setLights(notificationColor, 1000, 1000)
            builder.setSound(RingtoneManager.getDefaultUri(
                if (isFocus) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
            ))
            builder.setDefaults(NotificationCompat.DEFAULT_ALL) // Asegura comportamiento estándar de alerta
        } else {
            builder.setSilent(true)
        }

        // 6. Botones Dinámicos (UX Mejorada)
        if (!isFinished) {
            if (timerRunning) {
                builder.addAction(R.drawable.baseline_pause_circle_24, "Pausar", pausePendingIntent)
                builder.addAction(R.drawable.ic_stop, "Terminar", endPendingIntent)
            } else {
                builder.addAction(R.drawable.ic_resume, "Reanudar", resumePendingIntent)
                builder.addAction(R.drawable.ic_stop, "Terminar", endPendingIntent)
            }
            if (!isFocus) {
                builder.addAction(R.drawable.ic_skip, "Saltar", skipPendingIntent)
            }
        } else {
            builder.addAction(R.drawable.ic_stop, "Cerrar", endPendingIntent)
        }

        // 7. Emitir Notificación
        NotificationManagerCompat.from(context).notify(MainActivity.NOTIFICATION_ID, builder.build())
    }
}