package site.remotearc.employees;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.profile.PlayerTextures;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * An employee's look. Three sources:
 *  - DEFAULT: one of the nine skins built into the game client (no network needed),
 *             chosen through the profile id the way vanilla picks default skins;
 *  - PLAYER:  a copy of a real Minecraft account's skin, fetched once and stored
 *             with its signature so it never changes or needs another lookup;
 *  - TEXTURE: any texture in a resource pack, e.g. "arc:entity/employee/miner"
 *             for assets/arc/textures/entity/employee/miner.png in the server pack.
 */
public record Skin(Kind kind, String id, boolean slim, String value, String signature) {

    public enum Kind { DEFAULT, PLAYER, TEXTURE }

    public static final List<String> DEFAULT_SKINS = List.of(
            "steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri");

    public static Skin builtIn(String name, boolean slim) {
        return new Skin(Kind.DEFAULT, name.toLowerCase(Locale.ROOT), slim, null, null);
    }

    public static Skin random(Random r) {
        String name = DEFAULT_SKINS.get(r.nextInt(DEFAULT_SKINS.size()));
        boolean slim = name.equals("alex") || (!name.equals("steve") && r.nextBoolean());
        return builtIn(name, slim);
    }

    public static Skin player(String name, String value, String signature) {
        return new Skin(Kind.PLAYER, name, false, value, signature);
    }

    /**
     * Looks up a real player's current skin (off the main thread) and hands it
     * back on the main thread, or null if there's no such account.
     */
    public static void fetchPlayer(org.bukkit.plugin.Plugin plugin, String name, java.util.function.Consumer<Skin> done) {
        com.destroystokyo.paper.profile.PlayerProfile profile = org.bukkit.Bukkit.createProfile(name);
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok;
            try {
                ok = profile.complete(true);
            } catch (RuntimeException ex) {
                ok = false;
            }
            ProfileProperty tex = null;
            for (ProfileProperty pp : profile.getProperties()) if (pp.getName().equals("textures")) tex = pp;
            Skin skin = ok && tex != null && tex.getSignature() != null
                    ? player(profile.getName() != null ? profile.getName() : name, tex.getValue(), tex.getSignature()) : null;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> done.accept(skin));
        });
    }

    public static Skin texture(String key, boolean slim) {
        return new Skin(Kind.TEXTURE, key, slim, null, null);
    }

    public ResolvableProfile profile(UUID uuid) {
        return switch (kind) {
            case PLAYER -> ResolvableProfile.resolvableProfile()
                    .uuid(uuid)
                    .name(id)
                    .addProperty(new ProfileProperty("textures", value, signature))
                    .build();
            case DEFAULT -> ResolvableProfile.resolvableProfile().uuid(defaultSkinId(uuid)).build();
            case TEXTURE -> patched(Key.key(id));
        };
    }

    /**
     * The game picks one of its built-in skins from a profile's id when the
     * profile has no textures: index floorMod(id.hashCode(), 18) into
     * slim alex..zuri, then wide alex..zuri. Rather than pointing at the texture
     * files (whose paths change between versions), find an id near the
     * employee's own that lands on the wanted skin.
     */
    private UUID defaultSkinId(UUID base) {
        int index = DEFAULT_SKINS_ALPHA.indexOf(id);
        if (index < 0) index = DEFAULT_SKINS_ALPHA.indexOf("steve");
        int want = (slim ? 0 : 9) + index;
        long most = base.getMostSignificantBits(), least = base.getLeastSignificantBits();
        for (long k = 0; k < 10_000; k++) {
            UUID u = new UUID(most, least + k);
            if (Math.floorMod(u.hashCode(), 18) == want) return u;
        }
        return base;
    }

    private static final List<String> DEFAULT_SKINS_ALPHA = List.of(
            "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");

    private ResolvableProfile patched(Key body) {
        PlayerTextures.SkinModel model = slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC;
        return ResolvableProfile.resolvableProfile()
                .skinPatch(p -> p.body(body).model(model))
                .build();
    }

    public String describe() {
        return switch (kind) {
            case DEFAULT -> Character.toUpperCase(id.charAt(0)) + id.substring(1) + (slim ? " (slim)" : "");
            case PLAYER -> id + "'s skin";
            case TEXTURE -> "texture " + id;
        };
    }

    public void save(ConfigurationSection s) {
        s.set("kind", kind.name());
        s.set("id", id);
        s.set("slim", slim);
        if (kind == Kind.PLAYER) {
            s.set("value", value);
            s.set("signature", signature);
        }
    }

    public static Skin load(ConfigurationSection s, Random fallback) {
        if (s == null) return random(fallback);
        try {
            Kind k = Kind.valueOf(s.getString("kind", "DEFAULT"));
            String id = s.getString("id", "steve");
            boolean slim = s.getBoolean("slim");
            return switch (k) {
                case DEFAULT -> builtIn(id, slim);
                case TEXTURE -> texture(id, slim);
                case PLAYER -> {
                    String v = s.getString("value"), sig = s.getString("signature");
                    yield v == null ? random(fallback) : player(id, v, sig);
                }
            };
        } catch (IllegalArgumentException ex) {
            return random(fallback);
        }
    }
}
