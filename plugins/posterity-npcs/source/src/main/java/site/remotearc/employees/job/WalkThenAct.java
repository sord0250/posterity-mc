package site.remotearc.employees.job;

import org.bukkit.util.Vector;
import site.remotearc.employees.util.Pathfinder;

/** Walks to a spot near a target, then runs {@link #act} every tick until it finishes. */
public abstract class WalkThenAct implements Task {

    protected final Vector target;
    private final Pathfinder.Goal goal;
    private boolean arrived;

    protected WalkThenAct(Vector target, double reach, double maxBelow, double maxAbove) {
        this.target = target;
        this.goal = Pathfinder.near(target.getX(), target.getY(), target.getZ(), reach, maxBelow, maxAbove);
    }

    protected abstract Result act(Worker w);

    @Override
    public Vector workTarget() {
        return target;
    }

    /** Runs once, the tick the employee arrives. */
    protected void arrive(Worker w) {}

    @Override
    public final Result tick(Worker w) {
        if (!arrived) {
            switch (w.walk(goal, target)) {
                case MOVING -> {
                    return Result.RUNNING;
                }
                case FAILED -> {
                    w.markUnreachable(target);
                    return Result.FAILED;
                }
                case ARRIVED -> {
                    arrived = true;
                    arrive(w);
                }
            }
        }
        return act(w);
    }
}
