package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SoundCloudService {

    // SoundCloud answers HTTP 400 when /tracks is asked for more ids than this.
    private static final int TRACKS_PER_REQUEST = 50;

    private final SoundCloudHttpClient client;
    private final TrackParser trackParser = new TrackParser();
    private final PlaylistParser playlistParser = new PlaylistParser();

    public SoundCloudService(SoundCloudHttpClient client) {
        this.client = client;
    }

    public Object resolveUrl(HttpInterface http, String url) throws IOException {
        String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
        String json = client.get(http, "/resolve?url=" + encoded);
        JsonBrowser data = JsonBrowser.parse(json);
        String kind = data.get("kind").text();

        return switch (kind) {
            case "track" -> trackParser.parse(data);
            case "playlist" -> playlistParser.parse(data, fetchStubTracks(http, data));
            default -> throw new IOException("Unsupported resource kind '" + kind + "' for: " + url);
        };
    }

    public List<TrackData> search(HttpInterface http, String query) throws IOException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String json = client.get(http, "/search/tracks?q=" + encoded + "&limit=10");
        JsonBrowser root = JsonBrowser.parse(json);

        List<TrackData> results = new ArrayList<>();
        for (JsonBrowser track : root.get("collection").values()) {
            results.add(trackParser.parse(track));
        }
        return results;
    }

    public StreamData getStream(HttpInterface http, long trackId) throws IOException {
        String json = client.get(http, "/tracks/" + trackId);
        JsonBrowser track = JsonBrowser.parse(json);
        JsonBrowser transcodings = track.get("media").get("transcodings");

        if (transcodings.isNull() || transcodings.values().isEmpty()) {
            throw new IOException("No transcodings found for track: " + trackId);
        }

        String progressiveUrl = null;
        String hlsFmp4SqUrl = null;
        String hlsFmp4LqUrl = null;

        for (JsonBrowser t : transcodings.values()) {
            if (t.get("snipped").asBoolean(false)) continue;

            String protocol = t.get("format").get("protocol").text();
            String mime = t.get("format").get("mime_type").text();
            String quality = t.get("quality").text();
            String url = t.get("url").text();

            if ("progressive".equals(protocol) && mime != null && mime.contains("mpeg")) {
                progressiveUrl = url;
                break;
            }

            // SoundCloud no longer serves progressive streams, and the legacy HLS
            // audio/mpegurl transcoding resolver answers HTTP 404. The only working
            // format is fragmented-MP4 HLS (audio/mp4, codecs=mp4a.40.2).
            if ("hls".equals(protocol) && mime != null && mime.startsWith("audio/mp4")) {
                if ("sq".equals(quality) && hlsFmp4SqUrl == null) {
                    hlsFmp4SqUrl = url;
                } else if (hlsFmp4LqUrl == null) {
                    hlsFmp4LqUrl = url;
                }
            }
        }

        if (progressiveUrl != null) {
            return new StreamData(resolveStreamUrl(http, progressiveUrl),
                    StreamData.Protocol.PROGRESSIVE, "audio/mpeg");
        }
        if (hlsFmp4SqUrl != null) {
            return new StreamData(resolveStreamUrl(http, hlsFmp4SqUrl),
                    StreamData.Protocol.HLS_FMP4, "audio/mp4");
        }
        if (hlsFmp4LqUrl != null) {
            return new StreamData(resolveStreamUrl(http, hlsFmp4LqUrl),
                    StreamData.Protocol.HLS_FMP4, "audio/mp4");
        }

        throw new IOException("No playable stream found for track: " + trackId);
    }

    private String resolveStreamUrl(HttpInterface http, String transcodingUrl) throws IOException {
        String json = client.getAbsolute(http, transcodingUrl);
        JsonBrowser data = JsonBrowser.parse(json);
        String url = data.get("url").text();
        if (url == null || url.isEmpty()) {
            throw new IOException("Empty stream URL from: " + transcodingUrl);
        }
        return url;
    }

    /**
     * Playlists only inline their first few tracks; the rest come back as
     * id-only stubs, so look those up by id.
     */
    private Map<Long, JsonBrowser> fetchStubTracks(HttpInterface http, JsonBrowser playlist) throws IOException {
        List<String> ids = new ArrayList<>();
        for (JsonBrowser track : playlist.get("tracks").values()) {
            if (track.get("title").isNull()) {
                ids.add(track.get("id").text());
            }
        }

        Map<Long, JsonBrowser> tracks = new HashMap<>();
        for (int i = 0; i < ids.size(); i += TRACKS_PER_REQUEST) {
            List<String> batch = ids.subList(i, Math.min(i + TRACKS_PER_REQUEST, ids.size()));
            String json = client.get(http, "/tracks?ids=" + String.join(",", batch));
            for (JsonBrowser track : JsonBrowser.parse(json).values()) {
                tracks.put(track.get("id").asLong(0), track);
            }
        }
        return tracks;
    }
}
