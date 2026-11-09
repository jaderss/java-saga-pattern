package dev.jaderss.saga.messaging.contract;

public enum Participant {
    RISK("saga.risk.commands"),
    ACCOUNT("saga.account.commands");

    private final String commandTopic;

    Participant(String commandTopic) {
        this.commandTopic = commandTopic;
    }

    public String commandTopic() {
        return commandTopic;
    }
}
