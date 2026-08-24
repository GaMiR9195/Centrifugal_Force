package com.playsi.centrifugal_force.internal;

import org.jetbrains.annotations.Nullable;

public interface AdhesionAccess {
    AdhesionState centrifugalForce$getOrCreateAdhesionState();
    @Nullable AdhesionState centrifugalForce$peekAdhesionState();
}
