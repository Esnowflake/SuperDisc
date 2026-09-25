package dev.superdisc;

import java.io.InterruptedIOException;

/** Cancellation and final cache publication share a lock; cancelled work cannot publish later. */
public final class WorkToken {
    private volatile boolean cancelled;
    public synchronized void cancel() { cancelled=true; }
    public boolean cancelled() { return cancelled; }
    public void check() throws InterruptedIOException {
        if(cancelled)throw new InterruptedIOException("Audio task cancelled");
    }
}
