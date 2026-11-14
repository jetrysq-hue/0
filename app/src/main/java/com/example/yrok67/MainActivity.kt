package com.example.yrok67

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Mode(val title: String) {
    SCHOOL("У школі"),
    REMOTE("Дистанційно")
}

data class Bell(val start: Int, val end: Int)

data class ScheduleData(
    val week: Map<Int, List<String>>,
    val school: List<Bell>,
    val remote: List<Bell>,
    val emojis: Map<String, String>,
    val notice: String,
    val messages: List<Msg> = emptyList()
)

data class Msg(val id: Long, val text: String, val time: String)

// ===================== РОЗКЛАД (вбудований + з інтернету) =====================

object Schedule {

    private val defaultEmojis: Map<String, String> = mapOf(
        "Біологія" to "🧬",
        "Українська мова" to "🇺🇦",
        "Англійська мова" to "🇬🇧",
        "Алгебра" to "➗",
        "Геометрія" to "📐",
        "Фізика" to "⚛️",
        "Історія" to "🏛️",
        "Зарубіжна література" to "📚",
        "Хімія" to "🧪",
        "Українська література" to "📖",
        "Фізична культура" to "🏃",
        "Географія" to "🌍",
        "Мистецтво" to "🎨",
        "Трудове навчання" to "🔨",
        "Правознавство" to "⚖️",
        "Інформатика" to "💻",
        "Фінансова грамотність" to "💰"
    )

    private fun t(h: Int, m: Int) = h * 60 + m

    // Запасний розклад: використовується, поки з інтернету нічого не завантажено
    private val DEFAULT = ScheduleData(
        week = mapOf(
            Calendar.MONDAY to listOf(
                "Біологія", "Українська мова", "Англійська мова",
                "Алгебра", "Фізика", "Історія", "Зарубіжна література"
            ),
            Calendar.TUESDAY to listOf(
                "Історія", "Геометрія", "Хімія", "Українська література",
                "Фізична культура", "Географія", "Мистецтво"
            ),
            Calendar.WEDNESDAY to listOf(
                "Алгебра", "Хімія", "Українська мова",
                "Англійська мова", "Фізика", "Трудове навчання"
            ),
            Calendar.THURSDAY to listOf(
                "Фізика", "Правознавство", "Географія",
                "Українська література", "Зарубіжна література", "Фізична культура"
            ),
            Calendar.FRIDAY to listOf(
                "Інформатика", "Алгебра", "Українська мова", "Англійська мова",
                "Біологія", "Фінансова грамотність", "Фізична культура"
            )
        ),
        school = listOf(
            Bell(t(8, 30), t(9, 0)), Bell(t(9, 5), t(9, 35)),
            Bell(t(9, 55), t(10, 25)), Bell(t(10, 45), t(11, 15)),
            Bell(t(11, 35), t(12, 5)), Bell(t(12, 10), t(12, 40)),
            Bell(t(12, 45), t(13, 15)), Bell(t(13, 20), t(13, 50))
        ),
        remote = listOf(
            Bell(t(8, 30), t(9, 15)), Bell(t(9, 25), t(10, 10)),
            Bell(t(10, 30), t(11, 15)), Bell(t(11, 35), t(12, 20)),
            Bell(t(12, 30), t(13, 15)), Bell(t(13, 25), t(14, 10)),
            Bell(t(14, 20), t(15, 5))
        ),
        emojis = defaultEmojis,
        notice = ""
    )

    @Volatile
    private var data: ScheduleData = DEFAULT

    val week: Map<Int, List<String>> get() = data.week
    val notice: String get() = data.notice
    val messages: List<Msg> get() = data.messages

    val weekDays: List<Int>
        get() = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        ).filter { !data.week[it].isNullOrEmpty() }

    fun emoji(subject: String): String = data.emojis[subject] ?: "📘"

    fun withEmoji(subject: String): String = "${emoji(subject)} $subject"

    // Додає оголошення (якщо воно є) до тексту
    fun withNotice(text: String?): String? {
        val parts = ArrayList<String>()
        if (!text.isNullOrEmpty()) parts.add(text)
        if (data.notice.isNotEmpty()) parts.add("📢 ${data.notice}")
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    private val dayAccusative: Map<Int, String> = mapOf(
        Calendar.MONDAY to "понеділок",
        Calendar.TUESDAY to "вівторок",
        Calendar.WEDNESDAY to "середу",
        Calendar.THURSDAY to "четвер",
        Calendar.FRIDAY to "п'ятницю",
        Calendar.SATURDAY to "суботу",
        Calendar.SUNDAY to "неділю"
    )

    private val dayNominative: Map<Int, String> = mapOf(
        Calendar.MONDAY to "Понеділок",
        Calendar.TUESDAY to "Вівторок",
        Calendar.WEDNESDAY to "Середа",
        Calendar.THURSDAY to "Четвер",
        Calendar.FRIDAY to "П'ятниця",
        Calendar.SATURDAY to "Субота",
        Calendar.SUNDAY to "Неділя"
    )

    fun dayName(dayOfWeek: Int): String = dayAccusative[dayOfWeek] ?: ""

    fun dayTitle(dayOfWeek: Int): String = dayNominative[dayOfWeek] ?: ""

    fun bells(mode: Mode): List<Bell> =
        if (mode == Mode.SCHOOL) data.school else data.remote

    fun format(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    // Читає збережений з інтернету розклад; якщо його немає або він зіпсований — вбудований
    fun load(context: Context) {
        val json = Prefs.getScheduleJson(context)
        data = (if (json != null) parse(json) else null) ?: DEFAULT
    }

    fun parse(text: String): ScheduleData? {
        return try {
            val root = JSONObject(text)
            val school = parseBells(root.getJSONArray("school_bells"))
            val remote = parseBells(root.getJSONArray("remote_bells"))
            if (school.isEmpty() || remote.isEmpty()) return null

            val weekObj = root.getJSONObject("week")
            val keys = mapOf(
                "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
                "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
                "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
                "sun" to Calendar.SUNDAY
            )
            val week = HashMap<Int, List<String>>()
            for ((key, dow) in keys) {
                val arr = weekObj.optJSONArray(key) ?: continue
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isNotEmpty()) list.add(name)
                }
                if (list.isNotEmpty()) week[dow] = list
            }
            if (week.isEmpty()) return null

            val emojis = HashMap<String, String>(defaultEmojis)
            val emojiObj = root.optJSONObject("emoji")
            if (emojiObj != null) {
                val iter = emojiObj.keys()
                while (iter.hasNext()) {
                    val name = iter.next()
                    val value = emojiObj.optString(name)
                    if (value.isNotEmpty()) emojis[name] = value
                }
            }
            val msgs = ArrayList<Msg>()
            val msgArr = root.optJSONArray("messages")
            if (msgArr != null) {
                for (i in 0 until msgArr.length()) {
                    val o = msgArr.optJSONObject(i) ?: continue
                    val text = o.optString("text", "").trim()
                    val id = o.optLong("id", 0L)
                    if (text.isNotEmpty() && id > 0L) msgs.add(Msg(id, text, o.optString("time", "")))
                }
            }
            msgs.sortBy { it.id }
            ScheduleData(week, school, remote, emojis, root.optString("notice", "").trim(), msgs.takeLast(20))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseBells(arr: JSONArray): List<Bell> {
        val out = ArrayList<Bell>()
        for (i in 0 until arr.length()) {
            val pair = arr.getJSONArray(i)
            val start = parseTime(pair.getString(0))
            val end = parseTime(pair.getString(1))
            if (start == null || end == null || end <= start) {
                throw IllegalArgumentException("bad time")
            }
            out.add(Bell(start, end))
        }
        return out
    }

    private fun parseTime(s: String): Int? {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

// ===================== ЗАВАНТАЖЕННЯ РОЗКЛАДУ З ІНТЕРНЕТУ =====================

object ScheduleSync {
    // Посилання на файл розкладу. Якщо ваш логін або назва репозиторію інші — змініть тут.
    const val SCHEDULE_URL =
        "https://raw.githubusercontent.com/jetrysq-hue/jetry-schedule/main/schedule.json"

    private const val AUTO_INTERVAL_MS = 15L * 1000

    fun isStale(context: Context): Boolean =
        System.currentTimeMillis() - Prefs.getLastAttempt(context) > AUTO_INTERVAL_MS

    // Повертає true, якщо вдалося завантажити коректний розклад
    fun fetch(context: Context): Boolean {
        Prefs.setLastAttempt(context, System.currentTimeMillis())
        var conn: HttpURLConnection? = null
        try {
            val freshUrl = "$SCHEDULE_URL?t=${System.currentTimeMillis()}"
            conn = URL(freshUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            conn.setRequestProperty("Pragma", "no-cache")
            if (conn.responseCode != 200) return false
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .trim().removePrefix("\uFEFF")
            if (text.length > 100_000) return false
            val fresh = Schedule.parse(text) ?: return false
            val old = Prefs.getScheduleJson(context)?.let { Schedule.parse(it) }
            Prefs.setScheduleJson(context, text)
            Prefs.setLastOk(context, System.currentTimeMillis())
            Schedule.load(context)
            handleChanges(context, old, fresh)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            conn?.disconnect()
        }
    }

    // Порівнює новий розклад зі старим і показує сповіщення
    private fun handleChanges(context: Context, old: ScheduleData?, fresh: ScheduleData) {
        if (old != null) {
            if (old.week != fresh.week || old.school != fresh.school || old.remote != fresh.remote) {
                Notifier.show(
                    context, 1001, "Розклад змінено",
                    "Відкрийте застосунок, щоб побачити нові уроки й дзвінки."
                )
            } else if (fresh.notice.isNotEmpty() && fresh.notice != old.notice) {
                Notifier.show(context, 1002, "Оголошення", fresh.notice)
            }
        }

        val last = Prefs.getLastMsgId(context)
        val maxId = fresh.messages.maxOfOrNull { it.id } ?: 0L
        if (last < 0L) {
            // перше завантаження: старі повідомлення не показуємо
            Prefs.setLastMsgId(context, maxId)
        } else {
            val fresher = fresh.messages.filter { it.id > last }.sortedBy { it.id }
            for (m in fresher.takeLast(3)) {
                Notifier.show(context, (m.id % 1_000_000_000L).toInt() + 2000, "Повідомлення", m.text)
            }
            if (maxId > last) Prefs.setLastMsgId(context, maxId)
        }
    }
}

// ===================== СПОВІЩЕННЯ =====================

object Notifier {
    private const val CHANNEL = "schedule_updates"

    fun show(context: Context, id: Int, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Розклад і повідомлення", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = PendingIntent.getActivity(
                context, 10, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (e: Exception) {
            // сповіщення не повинні ламати застосунок
        }
    }
}

// ===================== ЛОГІКА СТАНУ УРОКУ =====================

sealed class LessonState {
    object NoLessons : LessonState()
    object Finished : LessonState()
    data class Upcoming(val number: Int, val subject: String, val bell: Bell, val millisLeft: Long) : LessonState()
    data class Current(val number: Int, val subject: String, val bell: Bell, val millisLeft: Long) : LessonState()
}

object LessonLogic {
    fun compute(mode: Mode, now: Calendar = Calendar.getInstance()): LessonState {
        val subjects = Schedule.week[now.get(Calendar.DAY_OF_WEEK)]
            ?: return LessonState.NoLessons
        val bells = Schedule.bells(mode)

        val nowMs =
            ((now.get(Calendar.HOUR_OF_DAY) * 60L + now.get(Calendar.MINUTE)) * 60L +
                    now.get(Calendar.SECOND)) * 1000L + now.get(Calendar.MILLISECOND)

        for (i in subjects.indices) {
            if (i >= bells.size) break
            val bell = bells[i]
            val startMs = bell.start * 60_000L
            val endMs = bell.end * 60_000L
            if (nowMs < startMs) return LessonState.Upcoming(i + 1, subjects[i], bell, startMs - nowMs)
            if (nowMs < endMs) return LessonState.Current(i + 1, subjects[i], bell, endMs - nowMs)
        }
        return LessonState.Finished
    }

    fun lessonAfter(mode: Mode, number: Int, now: Calendar = Calendar.getInstance()): Pair<String, Bell>? {
        val subjects = Schedule.week[now.get(Calendar.DAY_OF_WEEK)] ?: return null
        val bells = Schedule.bells(mode)
        return if (number < subjects.size && number < bells.size) {
            subjects[number] to bells[number]
        } else null
    }

    fun nextDayText(mode: Mode, now: Calendar = Calendar.getInstance()): String? {
        val c = now.clone() as Calendar
        val bells = Schedule.bells(mode)
        for (offset in 1..7) {
            c.add(Calendar.DAY_OF_YEAR, 1)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val subjects = Schedule.week[dow]
            if (subjects != null && subjects.isNotEmpty() && bells.isNotEmpty()) {
                val day = if (offset == 1) "завтра" else "у ${Schedule.dayName(dow)}"
                return "Наступний урок: ${Schedule.withEmoji(subjects[0])}, " +
                        "$day о ${Schedule.format(bells[0].start)}"
            }
        }
        return null
    }
}

// ===================== ЗБЕРЕЖЕННЯ =====================

object Prefs {
    private const val FILE = "jetrylessons_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_JSON = "schedule_json"
    private const val KEY_LAST_ATTEMPT = "last_attempt"
    private const val KEY_LAST_OK = "last_ok"

    private fun p(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMode(context: Context): Mode {
        val name = p(context).getString(KEY_MODE, Mode.SCHOOL.name)
        return try {
            Mode.valueOf(name ?: Mode.SCHOOL.name)
        } catch (e: IllegalArgumentException) {
            Mode.SCHOOL
        }
    }

    fun setMode(context: Context, mode: Mode) {
        p(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getScheduleJson(context: Context): String? = p(context).getString(KEY_JSON, null)
    fun setScheduleJson(context: Context, json: String) {
        p(context).edit().putString(KEY_JSON, json).apply()
    }

    fun getLastAttempt(context: Context): Long = p(context).getLong(KEY_LAST_ATTEMPT, 0L)
    fun setLastAttempt(context: Context, time: Long) {
        p(context).edit().putLong(KEY_LAST_ATTEMPT, time).apply()
    }

    fun getLastMsgId(context: Context): Long = p(context).getLong("last_msg_id", -1L)
    fun setLastMsgId(context: Context, id: Long) {
        p(context).edit().putLong("last_msg_id", id).apply()
    }

    fun getLastOk(context: Context): Long = p(context).getLong(KEY_LAST_OK, 0L)
    fun setLastOk(context: Context, time: Long) {
        p(context).edit().putLong(KEY_LAST_OK, time).apply()
    }
}

// ===================== ВІДЖЕТ 1: НАСТУПНИЙ УРОК =====================

class LessonWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_MODE -> {
                val newMode = if (Prefs.getMode(context) == Mode.SCHOOL) Mode.REMOTE else Mode.SCHOOL
                Prefs.setMode(context, newMode)
                updateAll(context)
            }
            ACTION_REFRESH,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> updateAll(context)
        }
        if (intent.action == ACTION_REFRESH ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE
        ) {
            syncIfStale(context, goAsync())
        }
    }

    override fun onDisabled(context: Context) {
        updateAll(context)
    }

    companion object {
        const val ACTION_TOGGLE_MODE = "com.example.yrok67.TOGGLE_MODE"
        const val ACTION_REFRESH = "com.example.yrok67.REFRESH"

        // Раз на кілька годин підтягує свіжий розклад з інтернету у фоні
        fun syncIfStale(context: Context, pending: BroadcastReceiver.PendingResult?) {
            if (!ScheduleSync.isStale(context)) {
                pending?.finish()
                return
            }
            val app = context.applicationContext
            Thread {
                try {
                    if (ScheduleSync.fetch(app)) updateAll(app)
                } catch (e: Exception) {
                    // ігноруємо
                } finally {
                    pending?.finish()
                }
            }.start()
        }

        // Оновлює ОБИДВА віджети і планує наступне оновлення
        fun updateAll(context: Context) {
            try {
                Schedule.load(context)
                val manager = AppWidgetManager.getInstance(context)
                val mainIds = manager.getAppWidgetIds(ComponentName(context, LessonWidgetProvider::class.java))
                val dayIds = manager.getAppWidgetIds(ComponentName(context, DayWidgetProvider::class.java))
                val mode = Prefs.getMode(context)
                val state = LessonLogic.compute(mode)
                if (mainIds.isNotEmpty()) {
                    manager.updateAppWidget(mainIds, buildViews(context, mode, state))
                }
                if (dayIds.isNotEmpty()) {
                    manager.updateAppWidget(dayIds, DayWidgetProvider.buildViews(context, mode, state))
                }
                scheduleNext(context, state)
            } catch (e: Exception) {
                // віджет не повинен падати
            }
        }

        private fun buildViews(context: Context, mode: Mode, state: LessonState): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_lesson)
            v.setTextViewText(R.id.mode_chip, mode.title)
            v.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            v.setOnClickPendingIntent(R.id.mode_chip, toggleIntent(context))

            when (state) {
                is LessonState.Upcoming -> {
                    showLesson(v, "НАСТУПНИЙ УРОК", state.number, state.subject, state.bell)
                    showCountdown(v, "До початку ·", state.millisLeft)
                    showNext(v, Schedule.withNotice(null))
                }
                is LessonState.Current -> {
                    showLesson(v, "ПОТОЧНИЙ УРОК", state.number, state.subject, state.bell)
                    showCountdown(v, "До дзвінка ·", state.millisLeft)
                    val next = LessonLogic.lessonAfter(mode, state.number)
                    val text = if (next != null) {
                        "Далі: ${Schedule.withEmoji(next.first)} · ${Schedule.format(next.second.start)}"
                    } else {
                        "Це останній урок на сьогодні"
                    }
                    showNext(v, Schedule.withNotice(text))
                }
                LessonState.Finished -> {
                    showMessage(v, "Уроки на сьогодні закінчилися")
                    showNext(v, Schedule.withNotice(LessonLogic.nextDayText(mode)))
                }
                LessonState.NoLessons -> {
                    showMessage(v, "Сьогодні уроків немає")
                    showNext(v, Schedule.withNotice(LessonLogic.nextDayText(mode)))
                }
            }
            return v
        }

        private fun showNext(v: RemoteViews, text: String?) {
            if (text == null) {
                v.setViewVisibility(R.id.next_info, View.GONE)
            } else {
                v.setViewVisibility(R.id.next_info, View.VISIBLE)
                v.setTextViewText(R.id.next_info, text)
            }
        }

        private fun showLesson(v: RemoteViews, header: String, number: Int, subject: String, bell: Bell) {
            v.setTextViewText(R.id.header, header)
            v.setViewVisibility(R.id.lesson_pill, View.VISIBLE)
            v.setTextViewText(
                R.id.lesson_pill,
                "$number-Й УРОК · ${Schedule.format(bell.start)}–${Schedule.format(bell.end)}"
            )
            v.setTextViewText(R.id.subject, Schedule.withEmoji(subject))
            v.setTextViewTextSize(R.id.subject, TypedValue.COMPLEX_UNIT_SP, 26f)
        }

        private fun showCountdown(v: RemoteViews, label: String, millisLeft: Long) {
            v.setViewVisibility(R.id.bottom_bar, View.VISIBLE)
            v.setTextViewText(R.id.bottom_label, label)
            val base = SystemClock.elapsedRealtime() + millisLeft
            v.setChronometerCountDown(R.id.countdown, true)
            v.setChronometer(R.id.countdown, base, null, true)
        }

        private fun showMessage(v: RemoteViews, text: String) {
            v.setTextViewText(R.id.header, "НАСТУПНИЙ УРОК")
            v.setViewVisibility(R.id.lesson_pill, View.GONE)
            v.setViewVisibility(R.id.bottom_bar, View.GONE)
            v.setTextViewText(R.id.subject, text)
            v.setTextViewTextSize(R.id.subject, TypedValue.COMPLEX_UNIT_SP, 22f)
        }

        fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun toggleIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 2,
                Intent(context, LessonWidgetProvider::class.java).setAction(ACTION_TOGGLE_MODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun refreshIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 3,
                Intent(context, LessonWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun scheduleNext(context: Context, state: LessonState) {
            val now = System.currentTimeMillis()
            val lessonTrigger = when (state) {
                is LessonState.Upcoming -> now + state.millisLeft + 500
                is LessonState.Current -> now + state.millisLeft + 500
                else -> now + 20_000L
            }
            val periodic = now + 20_000L
            val triggerAt = minOf(lessonTrigger, periodic)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = refreshIntent(context)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        private fun cancelAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(refreshIntent(context))
        }
    }
}

// ===================== ВІДЖЕТ 2: РОЗКЛАД НА ДЕНЬ =====================

class DayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        LessonWidgetProvider.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            LessonWidgetProvider.syncIfStale(context, goAsync())
        }
    }

    override fun onDisabled(context: Context) {
        LessonWidgetProvider.updateAll(context)
    }

    companion object {
        private val ROW_IDS = intArrayOf(
            R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4,
            R.id.row_5, R.id.row_6, R.id.row_7, R.id.row_8
        )
        private val TIME_IDS = intArrayOf(
            R.id.time_1, R.id.time_2, R.id.time_3, R.id.time_4,
            R.id.time_5, R.id.time_6, R.id.time_7, R.id.time_8
        )
        private val SUBJ_IDS = intArrayOf(
            R.id.subj_1, R.id.subj_2, R.id.subj_3, R.id.subj_4,
            R.id.subj_5, R.id.subj_6, R.id.subj_7, R.id.subj_8
        )

        fun buildViews(context: Context, mode: Mode, state: LessonState): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_day)
            v.setTextViewText(R.id.day_mode_chip, mode.title)
            v.setOnClickPendingIntent(R.id.day_root, LessonWidgetProvider.openAppIntent(context))
            v.setOnClickPendingIntent(R.id.day_mode_chip, LessonWidgetProvider.toggleIntent(context))

            val now = Calendar.getInstance()
            val dow = now.get(Calendar.DAY_OF_WEEK)
            val subjects = Schedule.week[dow] ?: emptyList()
            val bells = Schedule.bells(mode)
            val count = minOf(subjects.size, bells.size, ROW_IDS.size)

            v.setTextViewText(R.id.day_header, "СЬОГОДНІ · ${Schedule.dayTitle(dow).uppercase()}")

            val activeIdx = when (state) {
                is LessonState.Current -> state.number - 1
                is LessonState.Upcoming -> state.number - 1
                else -> -1
            }
            val isCurrent = state is LessonState.Current
            val finished = state is LessonState.Finished

            val white = context.getColor(R.color.text_primary)
            val gray = context.getColor(R.color.text_secondary)
            val blue = context.getColor(R.color.accent_blue)

            for (i in ROW_IDS.indices) {
                if (i < count) {
                    val bell = bells[i]
                    v.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                    v.setTextViewText(TIME_IDS[i], "${i + 1} · ${Schedule.format(bell.start)}")
                    v.setTextViewText(SUBJ_IDS[i], Schedule.withEmoji(subjects[i]))

                    val past = finished || (activeIdx >= 0 && i < activeIdx)
                    val active = activeIdx == i
                    when {
                        past -> {
                            v.setTextColor(TIME_IDS[i], gray)
                            v.setTextColor(SUBJ_IDS[i], gray)
                            v.setInt(ROW_IDS[i], "setBackgroundResource", 0)
                        }
                        active && isCurrent -> {
                            v.setTextColor(TIME_IDS[i], blue)
                            v.setTextColor(SUBJ_IDS[i], white)
                            v.setInt(ROW_IDS[i], "setBackgroundResource", R.drawable.pill_blue)
                        }
                        active -> {
                            v.setTextColor(TIME_IDS[i], blue)
                            v.setTextColor(SUBJ_IDS[i], white)
                            v.setInt(ROW_IDS[i], "setBackgroundResource", R.drawable.pill_dark)
                        }
                        else -> {
                            v.setTextColor(TIME_IDS[i], gray)
                            v.setTextColor(SUBJ_IDS[i], white)
                            v.setInt(ROW_IDS[i], "setBackgroundResource", 0)
                        }
                    }
                } else {
                    v.setViewVisibility(ROW_IDS[i], View.GONE)
                }
            }

            when (state) {
                LessonState.NoLessons -> {
                    v.setViewVisibility(R.id.day_message, View.VISIBLE)
                    v.setTextViewText(R.id.day_message, "Сьогодні уроків немає")
                    showFooter(v, Schedule.withNotice(LessonLogic.nextDayText(mode)))
                }
                LessonState.Finished -> {
                    v.setViewVisibility(R.id.day_message, View.GONE)
                    val next = LessonLogic.nextDayText(mode)
                    val text = if (next != null) {
                        "Уроки на сьогодні закінчилися\n$next"
                    } else {
                        "Уроки на сьогодні закінчилися"
                    }
                    showFooter(v, Schedule.withNotice(text))
                }
                else -> {
                    v.setViewVisibility(R.id.day_message, View.GONE)
                    showFooter(v, Schedule.withNotice(null))
                }
            }
            return v
        }

        private fun showFooter(v: RemoteViews, text: String?) {
            if (text == null) {
                v.setViewVisibility(R.id.day_footer, View.GONE)
            } else {
                v.setViewVisibility(R.id.day_footer, View.VISIBLE)
                v.setTextViewText(R.id.day_footer, text)
            }
        }
    }
}

// ===================== ЕКРАН ЗАСТОСУНКУ =====================

class MainActivity : AppCompatActivity() {

    private lateinit var tabMode: TextView
    private lateinit var tabWeek: TextView
    private lateinit var scrollMode: ScrollView
    private lateinit var scrollWeek: ScrollView
    private lateinit var modeContainer: LinearLayout
    private lateinit var weekContainer: LinearLayout

    private val blue = Color.parseColor("#0A84FF")
    private val blueSoft = Color.parseColor("#330A84FF")
    private val white = Color.WHITE
    private val gray = Color.parseColor("#8E8E93")
    private val cardTop = Color.parseColor("#1C1C1E")
    private val cardBottom = Color.parseColor("#101012")
    private val pillGray = Color.parseColor("#2A2A2D")
    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tabMode = findViewById(R.id.tab_mode)
        tabWeek = findViewById(R.id.tab_week)
        scrollMode = findViewById(R.id.scroll_mode)
        scrollWeek = findViewById(R.id.scroll_week)
        modeContainer = findViewById(R.id.mode_container)
        weekContainer = findViewById(R.id.week_container)

        tabMode.setOnClickListener { showTab(0) }
        tabWeek.setOnClickListener { showTab(1) }
        showTab(0)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            syncNow(false)
            pollHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
        LessonWidgetProvider.updateAll(this)
        pollHandler.removeCallbacks(pollRunnable)
        if (System.currentTimeMillis() - Prefs.getLastAttempt(this) > 15_000L) {
            syncNow(false)
            pollHandler.postDelayed(pollRunnable, 15_000L)
        } else {
            pollHandler.postDelayed(pollRunnable, 5_000L)
        }
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    // ---------- допоміжні функції для вигляду ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun tv(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(matchParent, wrapContent)
        p.topMargin = dp(top)
        return p
    }

    private fun card(strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cardTop, cardBottom))
        bg.cornerRadius = dp(24).toFloat()
        if (strokeDp > 0) bg.setStroke(dp(strokeDp), strokeColor)
        c.background = bg
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.layoutParams = lp(12)
        return c
    }

    private fun sectionTitle(text: String, sub: String?, into: LinearLayout) {
        val t = tv(text, 20f, white, true)
        t.layoutParams = lp(20)
        into.addView(t)
        if (sub != null) {
            val s = tv(sub, 13f, gray)
            s.layoutParams = lp(2)
            into.addView(s)
        }
    }

    private fun lessonRow(
        num: String, subject: String, time: String,
        numColor: Int, subjColor: Int, bg: Int?
    ): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(8), dp(5), dp(8), dp(5))
        if (bg != null) r.background = shape(bg, 12)
        r.layoutParams = lp(2)
        val n = tv(num, 13f, numColor)
        n.layoutParams = LinearLayout.LayoutParams(dp(24), wrapContent)
        val s = tv(subject, 15f, subjColor, true)
        s.layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        val t = tv(time, 13f, numColor)
        r.addView(n)
        r.addView(s)
        r.addView(t)
        return r
    }

    // ---------- вкладки ----------

    private fun showTab(i: Int) {
        scrollMode.visibility = if (i == 0) View.VISIBLE else View.GONE
        scrollWeek.visibility = if (i == 1) View.VISIBLE else View.GONE
        styleTab(tabMode, i == 0)
        styleTab(tabWeek, i == 1)
    }

    private fun styleTab(t: TextView, active: Boolean) {
        t.background = if (active) shape(blueSoft, 12) else null
        t.setTextColor(if (active) blue else gray)
    }

    private fun renderAll() {
        Schedule.load(this)
        renderMode()
        renderWeek()
    }

    private fun select(mode: Mode) {
        Prefs.setMode(this, mode)
        renderAll()
        LessonWidgetProvider.updateAll(this)
    }

    private fun syncNow(manual: Boolean) {
        val app = applicationContext
        Thread {
            val ok = ScheduleSync.fetch(app)
            runOnUiThread {
                if (ok) LessonWidgetProvider.updateAll(app)
                renderAll()
                if (manual) {
                    val msg = if (ok) "Розклад оновлено" else "Не вдалося оновити. Перевірте інтернет"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- вкладка 1: режим і дзвінки ----------

    private fun modeCard(m: Mode, active: Boolean): LinearLayout {
        val c = card(if (active) blue else Color.TRANSPARENT, if (active) 2 else 0)
        val bells = Schedule.bells(m)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = tv(m.title, 19f, white, true)
        title.layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        head.addView(title)
        if (active) {
            val badge = tv("Активний", 12f, blue, true)
            badge.background = shape(blueSoft, 12)
            badge.setPadding(dp(10), dp(3), dp(10), dp(3))
            head.addView(badge)
        }
        c.addView(head)

        if (bells.isNotEmpty()) {
            val span = "${bells.size} уроків · " +
                    "${Schedule.format(bells.first().start)}–${Schedule.format(bells.last().end)}"
            c.addView(tv(span, 13f, gray))
        }
        for ((i, b) in bells.withIndex()) {
            c.addView(
                lessonRow(
                    "${i + 1}",
                    "${Schedule.format(b.start)} – ${Schedule.format(b.end)}",
                    "${b.end - b.start} хв",
                    gray, white, null
                )
            )
        }
        c.setOnClickListener { select(m) }
        return c
    }

    private fun renderMode() {
        val c = modeContainer
        c.removeAllViews()
        val mode = Prefs.getMode(this)

        if (Schedule.notice.isNotEmpty()) {
            val n = card(blue, 1)
            n.addView(tv("📢 ${Schedule.notice}", 15f, white, true))
            c.addView(n)
        }

        sectionTitle("Формат навчання", "Оберіть розклад дзвінків. Віджети оновляться одразу.", c)
        for (m in Mode.values()) c.addView(modeCard(m, m == mode))

        val msgs = Schedule.messages
        if (msgs.isNotEmpty()) {
            sectionTitle("Повідомлення", null, c)
            val mc = card()
            val last = msgs.takeLast(5).reversed()
            for ((i, m) in last.withIndex()) {
                val t = tv(m.text, 15f, white)
                t.layoutParams = lp(if (i == 0) 0 else 12)
                mc.addView(t)
                if (m.time.isNotEmpty()) mc.addView(tv(m.time, 12f, gray))
            }
            c.addView(mc)
        }

        sectionTitle("Оновлення", null, c)
        val sc = card()
        val lastOk = Prefs.getLastOk(this)
        val status = if (lastOk > 0L) {
            "Розклад оновлено: " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(lastOk))
        } else {
            "Вбудований розклад. З інтернету ще не завантажувався."
        }
        sc.addView(tv(status, 14f, gray))
        val btn = tv("Оновити розклад", 16f, white, true)
        btn.gravity = Gravity.CENTER
        btn.background = shape(blue, 14)
        btn.setPadding(dp(16), dp(12), dp(16), dp(12))
        btn.layoutParams = lp(12)
        btn.setOnClickListener { syncNow(true) }
        sc.addView(btn)
        c.addView(sc)
    }

    // ---------- вкладка 2: тиждень ----------

    private fun renderWeek() {
        val c = weekContainer
        c.removeAllViews()
        val mode = Prefs.getMode(this)
        val bells = Schedule.bells(mode)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val state = LessonLogic.compute(mode)
        val activeIdx = when (state) {
            is LessonState.Current -> state.number - 1
            is LessonState.Upcoming -> state.number - 1
            else -> -1
        }
        val isCurrent = state is LessonState.Current
        val finished = state is LessonState.Finished

        sectionTitle("Розклад на тиждень", "${mode.title}. Час за вибраним розкладом дзвінків.", c)

        val days = Schedule.weekDays
        for (dow in days) {
            val subjects = Schedule.week[dow] ?: continue
            val isToday = dow == today
            val box = card(if (isToday) blue else Color.TRANSPARENT, if (isToday) 2 else 0)

            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            val name = tv(Schedule.dayTitle(dow), 18f, if (isToday) blue else white, true)
            name.layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
            head.addView(name)
            val tag = if (isToday) "Сьогодні" else "${minOf(subjects.size, bells.size)} уроків"
            head.addView(tv(tag, 12f, if (isToday) blue else gray, isToday))
            box.addView(head)

            for (i in subjects.indices) {
                if (i >= bells.size) break
                val b = bells[i]
                var numColor = gray
                var subjColor = white
                var bg: Int? = null
                if (isToday) {
                    val past = finished || (activeIdx >= 0 && i < activeIdx)
                    if (past) {
                        subjColor = gray
                    } else if (i == activeIdx) {
                        numColor = blue
                        bg = if (isCurrent) blueSoft else pillGray
                    }
                }
                box.addView(
                    lessonRow(
                        "${i + 1}", Schedule.withEmoji(subjects[i]),
                        Schedule.format(b.start), numColor, subjColor, bg
                    )
                )
            }
            c.addView(box)
        }

        if (!days.contains(Calendar.SATURDAY) && !days.contains(Calendar.SUNDAY)) {
            val w = tv("Субота й неділя: вихідні", 14f, gray)
            w.layoutParams = lp(16)
            c.addView(w)
        }
    }
}
