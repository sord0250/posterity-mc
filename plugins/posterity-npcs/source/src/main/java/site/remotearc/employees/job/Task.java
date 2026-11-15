package site.remotearc.employees.job;

/** One unit of work a {@link Worker} runs tick by tick until it reports DONE or FAILED. */
public interface Task {

    enum Result { RUNNING, DONE, FAILED }

    Result tick(Worker w);

    /** Shown in the menu, e.g. "Felling an oak tree". */
    String status(Worker w);

    /** Filler tasks (going home, strolling) that should not count as working. */
    default boolean idle() {
        return false;
    }

    /** The block it works on, checked against no-work zones before it starts (null = not a work spot). */
    default org.bukkit.util.Vector workTarget() {
        return null;
    }

    /** Called once when the task ends for any reason. */
    default void end(Worker w) {}
}
