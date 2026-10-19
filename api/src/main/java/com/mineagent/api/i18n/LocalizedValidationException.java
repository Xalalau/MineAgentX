package com.mineagent.api.i18n;

import java.util.Objects;

/** Stable validation identity with an unchanged English diagnostic for legacy consumers. */
public final class LocalizedValidationException extends IllegalArgumentException {
    private final String key;
    private final Object[] arguments;

    public LocalizedValidationException(String key, String englishMessage, Throwable cause, Object... arguments) {
        super(englishMessage, cause);
        this.key = Objects.requireNonNull(key);
        this.arguments = Objects.requireNonNull(arguments).clone();
    }

    public String key() { return key; }
    public Object[] arguments() { return arguments.clone(); }
}
