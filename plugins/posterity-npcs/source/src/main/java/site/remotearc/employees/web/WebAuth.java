package site.remotearc.employees.web;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signing in to the map from the game: /map gives a one-time code (10
 * minutes) in a link; opening it swaps the code for a session cookie that
 * lasts 30 days. Only a hash of each session is kept on disk.
 */
final class WebAuth {

    static final long CODE_MS = 10 * 60_000L, SESSION_MS = 30L * 24 * 3600_000L;
    /** However many times one player signs in, only their most recent sessions stay valid. */
    private static final int MAX_SESSIONS_PER_PLAYER = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Code(UUID player, long until, String to) {}
    record Session(UUID player, long until) {}

    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    /** sha256(token) → session. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final File file;

    WebAuth(File file) {
        this.file = file;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        for (String k : y.getKeys(false)) {
            try {
                Session s = new Session(UUID.fromString(y.getString(k + ".player", "")), y.getLong(k + ".until"));
                if (s.until() > now) sessions.put(k, s);
            } catch (IllegalArgumentException ignored) {
                // a broken entry
            }
        }
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A one-time sign-in code for this player; {@code to} is where the link should land (a map hash), or null. */
    String code(UUID player, String to) {
        long now = System.currentTimeMillis();
        codes.values().removeIf(c -> c.until() < now);
        String c = random(12);
        codes.put(c, new Code(player, now + CODE_MS, to));
        return c;
    }

    /** Uses a code up: {session token, landing hash} or null. */
    String[] redeem(String code) {
        Code c = code == null ? null : codes.remove(code);
        if (c == null || c.until() < System.currentTimeMillis()) return null;
        String token = random(32);
        sessions.put(hash(token), new Session(c.player(), System.currentTimeMillis() + SESSION_MS));
        dropOldest(c.player());
        save();
        return new String[]{token, c.to()};
    }

    /** However many browsers one player signs in from, keeps only the newest {@link #MAX_SESSIONS_PER_PLAYER}: old ones can't just pile up forever. */
    private void dropOldest(UUID player) {
        var theirs = sessions.entrySet().stream().filter(en -> en.getValue().player().equals(player))
                .sorted(Map.Entry.comparingByValue(java.util.Comparator.comparingLong(Session::until))).toList();
        for (int i = 0; i < theirs.size() - MAX_SESSIONS_PER_PLAYER; i++) sessions.remove(theirs.get(i).getKey());
    }

    /** Who the session token belongs to, or null (also null once they're banned: the map isn't a way around that). */
    UUID who(String token) {
        if (token == null || token.isEmpty()) return null;
        Session s = sessions.get(hash(token));
        if (s == null) return null;
        if (s.until() < System.currentTimeMillis()) {
            sessions.remove(hash(token));
            return null;
        }
        if (org.bukkit.Bukkit.getOfflinePlayer(s.player()).isBanned()) {
            sessions.remove(hash(token));
            return null;
        }
        return s.player();
    }

    /** Signs a player out everywhere; how many sessions that ended. */
    int signOut(UUID player) {
        int before = sessions.size();
        sessions.values().removeIf(s -> s.player().equals(player));
        save();
        return before - sessions.size();
    }

    int count(UUID player) {
        return (int) sessions.values().stream().filter(s -> s.player().equals(player)).count();
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        sessions.forEach((k, s) -> {
            y.set(k + ".player", s.player().toString());
            y.set(k + ".until", s.until());
        });
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException ignored) {
            // they'll sign in again after a restart
        }
    }
}
