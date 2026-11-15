package site.remotearc.employees;

import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * The one trait every employee has. It's rolled when they're hired (the owner
 * doesn't get a say) and kept for life, even if they're fired and hired again.
 * Now and then the trait takes over and they stop working for a minute or two:
 * see {@link site.remotearc.employees.job.Distractions}.
 */
public enum Personality {

    LOVES_SHEEP("Absolutely loves sheep", "follows any sheep that wanders by", Kind.FOLLOW, EntityType.SHEEP),
    LOVES_COWS("Adores cows", "trails after cows, admiring them", Kind.FOLLOW, EntityType.COW, EntityType.MOOSHROOM),
    LOVES_CHICKENS("Chicken whisperer", "chases chickens round to chat with them", Kind.FOLLOW, EntityType.CHICKEN),
    LOVES_PIGS("Pig fanatic", "follows pigs about, delighted", Kind.FOLLOW, EntityType.PIG),
    LOVES_CATS("Cat person", "drops everything to follow a cat", Kind.FOLLOW, EntityType.CAT, EntityType.OCELOT),
    LOVES_DOGS("Dog person", "can't resist following a wolf or dog", Kind.FOLLOW, EntityType.WOLF),
    LOVES_HORSES("Horse lover", "wanders off after horses, donkeys and mules", Kind.FOLLOW,
            EntityType.HORSE, EntityType.DONKEY, EntityType.MULE),
    LOVES_RABBITS("Bunny chaser", "chases rabbits that hop past", Kind.FOLLOW, EntityType.RABBIT),
    LOVES_TO_SWIM("Loves to swim", "jumps into nearby water for a swim", Kind.SWIM),
    FLOWER_SNIFFER("Flower sniffer", "stops to smell the flowers", Kind.FLOWERS),
    CAMPFIRE_DREAMER("Campfire dreamer", "warms their hands at any lit campfire", Kind.CAMPFIRE),
    STARGAZER("Stargazer", "stops to stare at the stars on clear nights", Kind.STARGAZE),
    RAIN_DANCER("Rain dancer", "dances whenever it rains", Kind.DANCE),
    NAPPER("Sleepyhead", "takes naps on the job", Kind.NAP),
    CHATTERBOX("Chatterbox", "wanders over to chat with anyone nearby", Kind.CHAT),
    DAYDREAMER("Daydreamer", "wanders off exploring, lost in thought", Kind.WANDER);

    /** How the trait shows itself. */
    public enum Kind { FOLLOW, SWIM, FLOWERS, CAMPFIRE, STARGAZE, DANCE, NAP, CHAT, WANDER }

    public final String display;
    public final String does;
    public final Kind kind;
    /** The animals it can't resist (FOLLOW only). */
    public final Set<EntityType> loves;

    Personality(String display, String does, Kind kind, EntityType... loves) {
        this.display = display;
        this.does = does;
        this.kind = kind;
        this.loves = loves.length == 0 ? EnumSet.noneOf(EntityType.class) : EnumSet.of(loves[0], loves);
    }

    public static Personality random(Random random) {
        Personality[] all = values();
        return all[random.nextInt(all.length)];
    }

    public static Personality parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
