package site.remotearc.kingdom;

import org.bukkit.configuration.file.FileConfiguration;

/** A typed snapshot of config.yml. */
public final class Settings {

    public final String currency;

    Settings(FileConfiguration c) {
        currency = c.getString("currency-symbol", "$");
    }
}
