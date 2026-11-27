package procomp.core.git;

/**
 * Mutating Git operations tracked as {@code Git Run}s per {@code Repository}.
 */
public enum GitOperation {
    CLONE("clone"),
    FETCH("fetch"),
    PULL("pull"),
    SWITCH("switch"),
    STASH("stash");

    private final String cliName;

    GitOperation(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }
}
