package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlaylistParser {

    private final TrackParser trackParser = new TrackParser();

    public PlaylistData parse(JsonBrowser playlist, Map<Long, JsonBrowser> stubTracks) {
        long id = playlist.get("id").asLong(0);
        String title = playlist.get("title").text();
        String permalinkUrl = playlist.get("permalink_url").text();
        String username = playlist.get("user").get("username").text();

        List<TrackData> tracks = new ArrayList<>();
        for (JsonBrowser entry : playlist.get("tracks").values()) {
            JsonBrowser track = stubTracks.getOrDefault(entry.get("id").asLong(0), entry);
            if (!track.get("title").isNull() && !track.get("user").isNull()) {
                tracks.add(trackParser.parse(track));
            }
        }

        return new PlaylistData(id, title, permalinkUrl,
                username != null ? username : "Unknown", tracks);
    }
}
