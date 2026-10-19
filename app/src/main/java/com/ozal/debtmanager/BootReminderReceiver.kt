package com.ozal.debtmanager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.util.Calendar

class BootReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
        val people = JSONArray(prefs.getString("people_v2", "[]"))
        val transactions = JSONArray(prefs.getString("transactions_v2", "[]"))
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        for (i in 0 until people.length()) {
            val p = people.getJSONObject(i)
            val id = p.optLong("id")
            val name = p.optString("name")
            val kind = p.optString("kind")
            val dueDate = p.optLong("dueDate")
            var balance = 0L
            for (j in 0 until transactions.length()) {
                val t = transactions.getJSONObject(j)
                if (t.optLong("personId") != id) continue
                val actionName = t.optString("action")
                val increase = (kind == "بدهی من" && actionName == "قرض گرفتم") ||
                    (kind == "طلب من" && actionName == "قرض دادم")
                val decrease = (kind == "بدهی من" && actionName == "پس دادم") ||
                    (kind == "طلب من" && actionName == "پس گرفتم")
                if (increase) balance += t.optLong("amount")
                if (decrease) balance -= t.optLong("amount")
            }
            val alarmIntent = Intent(context, DueReminderReceiver::class.java).apply {
                putExtra("personId", id); putExtra("personName", name)
            }
            val pending = PendingIntent.getBroadcast(context, id.toInt(), alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (dueDate <= 0L || balance <= 0L) {
                alarm.cancel(pending)
                continue
            }
            val trigger = Calendar.getInstance().apply {
                timeInMillis = dueDate
                set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (trigger > now) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            else alarm.cancel(pending)
        }
    }
}
