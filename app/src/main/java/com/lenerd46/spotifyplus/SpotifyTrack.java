package com.lenerd46.spotifyplus;

public class SpotifyTrack {
    public final String title;
    public final String artist;
    public final String album;
    public final String uri;
    public final long duration;
    public final long position;
    public final String color;
    public final long lastUpdated;
    public final String imageId;
    public final boolean saved;

    public SpotifyTrack(String title, String artist, String album, String uri, long position, String color, long lastUpdated, String imageId, long duration, boolean saved) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.uri = uri;
        this.position = position;
        this.color = color;
        this.lastUpdated = lastUpdated;
        this.imageId = imageId.split(":").length == 3 ? imageId.split(":")[2] : "https://i.scdn.co/image/ab67616d0000b27332a7d87248d1b75463483df5";
        this.duration = duration;
        this.saved = saved;
    }
}
