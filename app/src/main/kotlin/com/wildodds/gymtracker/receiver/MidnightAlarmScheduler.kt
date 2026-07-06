package com.wildodds.gymtracker.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId

object MidnightAlarmScheduler {

  private const val REQUEST_CODE = 7_001

  fun schedule(context: Context) {
  val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  val pi = buildPendingIntent(context)
  val triggerAtMillis = nextMidnightMillis()
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
  alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
  } else {
  alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
  }
  }

  fun cancel(context: Context) {
  val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  alarmManager.cancel(buildPendingIntent(context))
  }

  private fun buildPendingIntent(context: Context): PendingIntent =
  PendingIntent.getBroadcast(
  context, REQUEST_CODE,
  Intent(context, MidnightResetReceiver::class.java),
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  fun nextMidnightMillis(): Long =
  LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
