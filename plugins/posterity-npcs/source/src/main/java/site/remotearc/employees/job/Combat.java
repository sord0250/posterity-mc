package site.remotearc.employees.job;

/** Melee numbers for employees that fight (ranchers, guards): a survival player with the same weapon, no enchantments. */
final class Combat {

    private Combat() {}

    /** Damage per hit. */
    static double damage(Worker w) {
        String t = w.jobSettings().tool().name();
        String tier = t.contains("_") ? t.substring(0, t.indexOf('_')) : t;
        double sword = switch (tier) {
            case "WOODEN", "GOLDEN" -> 4;
            case "STONE" -> 5;
            case "IRON" -> 6;
            case "DIAMOND" -> 7;
            case "NETHERITE" -> 8;
            default -> 1;
        };
        if (t.endsWith("_SWORD")) return sword;
        if (t.endsWith("_AXE")) return sword + 3;
        return 1;
    }

    /** Ticks between hits: a sword recharges in 12.5 ticks, an axe in about 20, a fist in 5. */
    static int interval(Worker w) {
        String tool = w.jobSettings().tool().name();
        double base = tool.endsWith("_SWORD") ? 12.5 : tool.endsWith("_AXE") ? 20 : 5;
        return Math.max(1, (int) Math.round(base / w.workSpeed()));
    }
}
