package site.remotearc.employees.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.MaterialList;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-world market moves for merchant prices (market.real-world): each
 * configured ticker (gold futures, lumber, live cattle, bitcoin...) is
 * fetched from Yahoo Finance's public chart API, and its items' prices are
 * scaled by how far today's price is from its 3-month average, amplified and
 * clamped. Only the movement matters, never the dollar amount, so a game
 * economy stays a game economy. Fetched off the main thread every so often;
 * on any failure the multiplier is simply 1.
 */
public final class RealPrices {

    /** One ticker's latest reading. */
    public record Quote(String ticker, String label, double now, double average, double multiplier) {
        public double change() {
            return average == 0 ? 0 : now / average - 1;
        }
    }

    private static final Pattern PRICE = Pattern.compile("\"regularMarketPrice\"\\s*:\\s*([0-9.]+)");
    private static final Pattern CLOSES = Pattern.compile("\"close\"\\s*:\\s*\\[([^\\]]*)\\]");

    private final ArcEmployees plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile Map<String, Quote> quotes = Map.of();
    private volatile Map<Material, String> tickerFor = Map.of();
    private volatile Map<String, String> labels = Map.of();
    private BukkitTask task;

    public RealPrices(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    /** (Re)starts fetching if enabled; call on startup and after a config reload. */
    public void restart() {
        if (task != null) task.cancel();
        task = null;
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("market.real-world");
        Map<Material, String> map = new EnumMap<>(Material.class);
        Map<String, String> labels = new LinkedHashMap<>();
        if (c != null && c.isConfigurationSection("tickers")) {
            ConfigurationSection t = c.getConfigurationSection("tickers");
            for (String key : t.getKeys(false)) {
                ConfigurationSection s = t.getConfigurationSection(key);
                if (s == null) continue;
                String ticker = s.getString("symbol", key);
                labels.put(ticker, s.getString("name", ticker));
                Set<Material> items = MaterialList.parse(s.getStringList("items"), plugin.getLogger(), "market.real-world.tickers." + key);
                for (Material m : items) map.put(m, ticker);
            }
        }
        tickerFor = map;
        this.labels = labels;
        if (!enabled()) {
            quotes = Map.of();
            return;
        }
        long every = Math.max(10, c.getLong("refresh-minutes", 60)) * 60 * 20;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchAll, 20L, every);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("market.real-world.enabled", false);
    }

    /** The multiplier for an item's price (1 if it follows nothing, or data is missing). */
    public double multiplier(Material m) {
        if (!enabled()) return 1;
        String t = tickerFor.get(m);
        Quote q = t == null ? null : quotes.get(t);
        return q == null ? 1 : q.multiplier();
    }

    /** The quote an item follows, or null. */
    public Quote quoteFor(Material m) {
        if (!enabled()) return null;
        String t = tickerFor.get(m);
        return t == null ? null : quotes.get(t);
    }

    public Map<String, Quote> quotes() {
        return quotes;
    }

    private void fetchAll() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("market.real-world");
        if (c == null) return;
        double amplify = Math.max(0, c.getDouble("amplify", 3));
        double lo = Math.max(0.01, c.getDouble("min-multiplier", 0.5)), hi = Math.max(lo, c.getDouble("max-multiplier", 2.0));
        Map<String, Quote> out = new HashMap<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String ticker = e.getKey();
            try {
                String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
                        + "?range=3mo&interval=1d";
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Mozilla/5.0 (ArcEmployees market)").GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) continue;
                Matcher pm = PRICE.matcher(r.body()), cm = CLOSES.matcher(r.body());
                if (!pm.find() || !cm.find()) continue;
                double now = Double.parseDouble(pm.group(1)), sum = 0;
                int n = 0;
                for (String v : cm.group(1).split(",")) {
                    v = v.trim();
                    if (v.isEmpty() || v.equals("null")) continue;
                    sum += Double.parseDouble(v);
                    n++;
                }
                if (n == 0 || now <= 0) continue;
                double avg = sum / n;
                double mult = Math.max(lo, Math.min(hi, Math.pow(now / avg, amplify)));
                out.put(ticker, new Quote(ticker, e.getValue(), now, avg, mult));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE, "Real-world price for " + ticker + " unavailable", ex);
            }
        }
        if (!out.isEmpty()) quotes = out;
    }

    /** For the price list: "Lumber -7.9% → ×0.78". */
    public static String describe(Quote q) {
        return q.label() + " " + String.format("%+.1f%%", q.change() * 100) + " vs its 3-month average → ×" + String.format("%.2f", q.multiplier());
    }
}
