package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Registration} that runs one action the first time it is closed.
 */
public final class CloseableRegistration implements Registration {

    private final String owner;
    private final Runnable onClose;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public CloseableRegistration(String owner, Runnable onClose) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            onClose.run();
        }
    }
}
