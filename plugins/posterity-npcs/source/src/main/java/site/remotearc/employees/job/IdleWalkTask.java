package site.remotearc.employees.job;

import org.bukkit.util.Vector;

/** Strolls to a spot, or heads home, while there is nothing to do. */
final class IdleWalkTask extends WalkThenAct {

    @Override
    public org.bukkit.util.Vector workTarget() {
        return null;   // going to storage or strolling, not working
    }


    private final String label;

    IdleWalkTask(Vector spot, String label) {
        super(spot, 1.2, 2, 2);
        this.label = label;
    }

    @Override
    protected Result act(Worker w) {
        return Result.DONE;
    }

    @Override
    public String status(Worker w) {
        return label != null ? label : w.job.idleStatus(w);
    }

    @Override
    public boolean idle() {
        return true;
    }
}
