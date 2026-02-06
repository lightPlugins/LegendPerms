package io.nexstudios.legendperms.perms;


import java.util.UUID;

public interface PermissionResolver {
    PermissionDecision decide(UUID uuid, String node);
}


