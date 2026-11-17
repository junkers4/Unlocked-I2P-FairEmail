package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;

// While the device is locked nothing of the app runs: the sync service has quit. This job only waits in the
// job scheduler, which holds it back while the device dozes and releases it when the screen comes on. Then,
// with the screen on and the device still locked, it waits for the unlock and resumes synchronizing at once,
// without the app being opened. Before the device dozes the job comes back with a growing delay.
public class JobUnlock extends JobService {
    private static final int JOB_ID = 0x1270C;
    private static final long[] DELAYS = {60, 120, 300, 600, 900}; // seconds

    private JobParameters params;
    private BroadcastReceiver receiver;

    static void schedule(Context context, int attempt) {
        try {
            JobScheduler scheduler = Helper.getSystemService(context, JobScheduler.class);
            if (scheduler == null)
                return;
            long delay = DELAYS[Math.min(attempt, DELAYS.length - 1)] * 1000L;
            PersistableBundle extras = new PersistableBundle();
            extras.putInt("attempt", attempt);
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, JobUnlock.class))
                    .setMinimumLatency(delay)
                    .setExtras(extras)
                    .build();
            scheduler.schedule(job);
            EntityLog.log(context, EntityLog.Type.Scheduling, "Unlock job attempt=" + attempt + " delay=" + delay / 1000 + "s");
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    static void cancel(Context context) {
        try {
            JobScheduler scheduler = Helper.getSystemService(context, JobScheduler.class);
            if (scheduler != null)
                scheduler.cancel(JOB_ID);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int attempt = params.getExtras().getInt("attempt", 0);
        EntityLog.log(this, EntityLog.Type.Scheduling, "Unlock job start attempt=" + attempt);

        if (!ScreenLock.isPaused(this)) {
            ServiceSynchronize.reschedule(this);
            return false;
        }

        PowerManager pm = Helper.getSystemService(this, PowerManager.class);
        if (pm == null || !pm.isInteractive()) {
            later(attempt + 1);
            return false;
        }

        // Screen on, still locked: stay until the unlock or the screen going off
        this.params = params;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean unlocked = Intent.ACTION_USER_PRESENT.equals(intent.getAction());
                EntityLog.log(context, EntityLog.Type.Scheduling, "Unlock job " + intent.getAction());
                done();
                if (unlocked)
                    ServiceSynchronize.reschedule(getApplicationContext());
                else
                    later(0);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        unregister();
        this.params = null;
        // Stopped by the system (time limit): back as soon as possible
        return true;
    }

    private void done() {
        unregister();
        JobParameters p = params;
        params = null;
        if (p != null)
            jobFinished(p, false);
    }

    private void unregister() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ex) {
                Log.w(ex);
            }
            receiver = null;
        }
    }

    // Scheduling the same job while it runs would stop it, so only once it has finished
    private void later(int attempt) {
        Context context = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (ScreenLock.isPaused(context))
                schedule(context, attempt);
        });
    }
}
