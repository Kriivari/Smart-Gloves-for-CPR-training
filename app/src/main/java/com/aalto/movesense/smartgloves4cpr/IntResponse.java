package com.aalto.movesense.smartgloves4cpr;

import com.google.gson.annotations.SerializedName;
/**
 * Created by lipponep on 30.1.2018.
 */
public class IntResponse {

    @SerializedName("Content")
    public int content;

    public IntResponse() {}
    public IntResponse(int content) {
        this.content= content;
    }
}