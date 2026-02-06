package io.nexstudios.legendperms.perms.model;

import io.nexstudios.legendperms.perms.PermissionDecision;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public final class LegendGroup {
    private final String name;
    @Setter
    private volatile int priority;
    private volatile String prefix;
    private final Map<String, PermissionDecision> permissions = new ConcurrentHashMap<>();

    // default values
    public LegendGroup(String name) {
        this.name = name;
        this.priority = 0;
        this.prefix = "";
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }
}