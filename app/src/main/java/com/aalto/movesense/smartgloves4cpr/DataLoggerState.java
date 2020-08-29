package com.aalto.movesense.smartgloves4cpr;
/**
 * Created by lipponep on 23.11.2017.
 */
public class DataLoggerState extends IntResponse {

    public DataLoggerState(int state) {
        this.content = state;
    }

    public String toString() {
        switch (content) {
            case 1: return "INVALID";
            case 2: return "READY";
            case 3: return "LOGGING";
        }
        return "UNKNOWN";
    }
}