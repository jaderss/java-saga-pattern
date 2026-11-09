package dev.jaderss.saga.messaging.participant;

class SimulatedCrash extends RuntimeException {

    SimulatedCrash(String message) {
        super(message, null, false, false);
    }
}
