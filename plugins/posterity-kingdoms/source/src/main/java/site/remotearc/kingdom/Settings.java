package site.remotearc.kingdom;

import org.bukkit.configuration.file.FileConfiguration;

/** A typed snapshot of config.yml. */
public final class Settings {

    public final String currency;
    public final int flagEmeralds;

    Settings(FileConfiguration c) {
        currency = c.getString("currency-symbol", "$");
        flagEmeralds = c.getInt("flag-emeralds", 3);
    }
}
