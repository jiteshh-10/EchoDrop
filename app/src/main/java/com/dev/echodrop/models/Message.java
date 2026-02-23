package com.dev.echodrop.models;

import java.util.UUID;

public class Message {
    public enum Scope {
        LOCAL,
        ZONE,
        EVENT
    }

    public enum Priority {
        ALERT,
        NORMAL,
        BULK
    }

    private final String id;
    private final String text;
    private final Scope scope;
    private final Priority priority;
    private final long createdAt;
    private final long expiresAt;
    private final boolean read;

    public Message(String text, Scope scope, Priority priority, long createdAt, long expiresAt, boolean read) {
        this(UUID.randomUUID().toString(), text, scope, priority, createdAt, expiresAt, read);
    }

    public Message(String id, String text, Scope scope, Priority priority, long createdAt, long expiresAt, boolean read) {
        this.id = id;
        this.text = text;
        this.scope = scope;
        this.priority = priority;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.read = read;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public Scope getScope() {
        return scope;
    }

    public Priority getPriority() {
        return priority;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public boolean isRead() {
        return read;
    }
}
