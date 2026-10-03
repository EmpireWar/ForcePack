package com.convallyria.forcepack.api.managed;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered list of prepared packs plus the policy for offering them. Immutable.
 *
 * <p>Order is the intended client pack-stack order, lowest priority first. A selection is
 * always built from an immutable snapshot of prepared packs, so publishing it cannot
 * observe a half-finished preparation.</p>
 */
public final class PackSelection {

    private static final PackSelection EMPTY =
            new PackSelection(Collections.emptyList(), null, false, FailurePolicy.NOTIFY);

    private final List<PreparedPack> packs;
    private final @Nullable String prompt;
    private final boolean required;
    private final FailurePolicy failurePolicy;

    private PackSelection(List<PreparedPack> packs,
                          @Nullable String prompt,
                          boolean required,
                          FailurePolicy failurePolicy) {
        this.packs = Collections.unmodifiableList(new ArrayList<>(packs));
        this.prompt = prompt;
        this.required = required;
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        for (PreparedPack pack : this.packs) {
            Objects.requireNonNull(pack, "packs must not contain null");
        }
    }

    /**
     * @return a selection offering nothing, with a recoverable policy
     */
    public static PackSelection empty() {
        return EMPTY;
    }

    /**
     * @param packs the packs to offer, lowest priority first
     * @param prompt the prompt to show, or null for the platform default
     * @param required whether the client must accept
     * @param failurePolicy what to do if application fails
     * @return the selection
     */
    public static PackSelection of(List<PreparedPack> packs,
                                   @Nullable String prompt,
                                   boolean required,
                                   FailurePolicy failurePolicy) {
        return new PackSelection(packs, prompt, required, failurePolicy);
    }

    /**
     * Replaces the packs while inheriting this selection's policy. This is how a provider
     * overrides content without silently changing enforcement.
     *
     * @param newPacks the replacement packs
     * @return a new selection
     */
    public PackSelection withPacks(List<PreparedPack> newPacks) {
        return new PackSelection(newPacks, prompt, required, failurePolicy);
    }

    /**
     * Replaces the policy while keeping the packs. Use this for a test offer that must be
     * recoverable even though the configured default is required.
     *
     * @param newRequired whether the client must accept
     * @param newFailurePolicy what to do if application fails
     * @return a new selection
     */
    public PackSelection withPolicy(boolean newRequired, FailurePolicy newFailurePolicy) {
        return new PackSelection(packs, prompt, newRequired, newFailurePolicy);
    }

    /**
     * @return the packs to offer, lowest priority first, never null
     */
    public List<PreparedPack> packs() {
        return packs;
    }

    public Optional<String> prompt() {
        return Optional.ofNullable(prompt);
    }

    /**
     * Whether the client must accept this selection.
     *
     * <p>With {@code true}, modern clients can disconnect on decline regardless of the
     * configured kick action. A test offer that must be recoverable therefore needs
     * {@code false}.</p>
     *
     * @return true if the offer is required
     */
    public boolean required() {
        return required;
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    public boolean isEmpty() {
        return packs.isEmpty();
    }

    @Override
    public String toString() {
        return "PackSelection{packs=" + packs.size() + ", required=" + required
                + ", policy=" + failurePolicy + '}';
    }
}
