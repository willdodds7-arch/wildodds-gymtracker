package com.wildodds.gymtracker.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId

object MidnightAlarmScheduler {

  private const val REQUEST_CODE = 7_001

  // A daily habit-streak reset at ~midnight does NOT need to-the-second precision, so we use an
  // inexact allow-while-idle alarm. This avoids the SCHEDULE_EXACT_ALARM permission entirely, which
  // Google Play restricts to genuine alarm/timer/calendar apps — a habit reset would not qualify.
  fun schedule(context: Context) {
  val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  val pi = buildPendingIntent(context)
  alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnightMillis(), pi)
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
