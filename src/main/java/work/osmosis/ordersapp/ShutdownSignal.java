package work.osmosis.ordersapp;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Says when the service has begun shutting down, so the background jobs can stop between steps.
 *
 * <p>The scheduler is told to wait for a running job rather than interrupt it (an interrupt while
 * the database is being written closes its file), so nothing else ever tells a job to stop. A job
 * that loops — the Gmail read walks all ~700 messages after every start, which takes minutes — then
 * kept going for the scheduler's full 45 seconds, the deploy gave up on the restart, rolled it back
 * and tried again: almost a minute lost on most Orders deploys. Checked between steps instead, a job
 * stops within one step and the shutdown takes about a second.
 *
 * <p>A {@link SmartLifecycle} in the default phase, so it is stopped before the web server finishes
 * and long before the scheduler is waited on or the database is closed.
 */
@Component
public class ShutdownSignal implements SmartLifecycle {

    private volatile boolean requested;
    private volatile boolean running;

    /** True once shutdown has begun; a job should finish the step in hand and return. */
    public boolean requested() {
        return requested;
    }

    @Override
    public void start() {
        requested = false;
        running = true;
    }

    @Override
    public void stop() {
        requested = true;
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
