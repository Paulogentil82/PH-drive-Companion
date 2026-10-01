package com.phdrive.companion.car

/**
 * Estados da automação de início/encerramento de viagem (MICROETAPA 3B.2).
 */
enum class AutoTripState {
    DISABLED,
    IDLE,
    VEHICLE_IDENTIFIED,
    ARMED,
    WAITING_FOR_MOVEMENT,
    STARTING,
    TRIP_ACTIVE,
    WAITING_FOR_STOP,
    ENDING,
    ERROR
}
