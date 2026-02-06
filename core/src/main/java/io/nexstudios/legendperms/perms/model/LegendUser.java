package io.nexstudios.legendperms.perms.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public final class LegendUser {
    private final UUID uuid;
    private final Set<String> groups = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> temporaryGroups = new ConcurrentHashMap<>();

    public LegendUser(UUID uuid) {
        this.uuid = uuid;
    }
}