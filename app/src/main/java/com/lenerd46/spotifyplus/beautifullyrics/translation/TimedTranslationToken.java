package com.lenerd46.spotifyplus.beautifullyrics.translation;

public final class TimedTranslationToken {
    public final String text;
    public final double startTime;
    public final double endTime;

    public TimedTranslationToken(String text, double startTime, double endTime) {
        this.text = text;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
