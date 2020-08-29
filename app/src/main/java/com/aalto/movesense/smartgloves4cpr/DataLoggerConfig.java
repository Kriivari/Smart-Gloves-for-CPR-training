package com.aalto.movesense.smartgloves4cpr;

import com.google.gson.annotations.SerializedName;
/**
 * Created by lipponep on 23.11.2017.
 */
public class DataLoggerConfig {

    @SerializedName("config")
    public final Config config;

    public DataLoggerConfig(Config config) {
        this.config = config;
    }

    public static class Config {
        @SerializedName("dataEntries")
        public final DataEntries dataEntries;

        public Config(DataEntries dataEntries) {
            this.dataEntries = dataEntries;
        }
    }

    public static class DataEntries {
        @SerializedName("dataEntry")
        public final DataEntry[] dataEntry;

        public DataEntries(DataEntry[] dataEntry) {
            this.dataEntry = dataEntry;
        }
    }

    public static class DataEntry {
        @SerializedName("path")
        public final String path;

        public DataEntry(String path) {
            this.path = path;
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();

        for (DataEntry de : config.dataEntries.dataEntry) {
            if (sb.length() > 0)
                sb.append("\n");

            sb.append(de.path);
        }

        return sb.toString();
    }
}

