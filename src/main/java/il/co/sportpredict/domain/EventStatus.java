package il.co.sportpredict.domain;

public enum EventStatus {
    SCHEDULED,
    LIVE,
    FINISHED,
    POSTPONED,
    CANCELLED;

    public boolean isFinal() {
        return this == FINISHED;
    }
}
