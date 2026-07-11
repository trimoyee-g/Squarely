package com.squarely.notification.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Fires the recurring tick daily. Kept thin — all logic lives in RecurringService. */
@Component
public class RecurringScheduler {

    private final RecurringService recurring;

    public RecurringScheduler(RecurringService recurring) {
        this.recurring = recurring;
    }

    // ponytail: single @Scheduled cron. Move to Quartz + a DB lock only when you run
    // multiple replicas and need exactly-once firing across them.
    @Scheduled(cron = "${recurring.tick-cron:0 0 6 * * *}")   // 06:00 daily
    public void dailyTick() {
        recurring.tick(LocalDate.now());
    }
}
