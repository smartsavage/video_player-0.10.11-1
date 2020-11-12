package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


final class VideoPlayer {

  private static final String FORMAT_SS = "ss";
  private static final String FORMAT_DASH = "dash";
  private static final String FORMAT_HLS = "hls";
  private static final String FORMAT_OTHER = "other";

  private SimpleExoPlayer exoPlayer;

  private Surface surface;

  private final TextureRegistry.SurfaceTextureEntry textureEntry;

  private QueuingEventSink eventSink = new QueuingEventSink();

  private final EventChannel eventChannel;

  private boolean isInitialized = false;
  private DefaultTrackSelector trackSelector;
  VideoPlayer(
      Context context,
      EventChannel eventChannel,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      String dataSource,
      String formatHint) {
    this.eventChannel = eventChannel;
    this.textureEntry = textureEntry;

    trackSelector = new DefaultTrackSelector();
    ((DefaultTrackSelector) trackSelector).setParameters(new DefaultTrackSelector.ParametersBuilder()
            .setRendererDisabled(C.TRACK_TYPE_VIDEO, false)
            .build());
    exoPlayer = ExoPlayerFactory.newSimpleInstance(context, trackSelector);

    Uri uri = Uri.parse(dataSource);

    DataSource.Factory dataSourceFactory;
    if (isHTTP(uri)) {
      dataSourceFactory =
          new DefaultHttpDataSourceFactory(
              "ExoPlayer",
              null,
              DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
              DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
              true);
    } else {
      dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
    }
    MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, context);
    exoPlayer.prepare(mediaSource);


    setupVideoPlayer(eventChannel, textureEntry);
  }

  private static boolean isHTTP(Uri uri) {
    if (uri == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = uri.getScheme();
    return scheme.equals("http") || scheme.equals("https");
  }

  private MediaSource buildMediaSource(
      Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint, Context context) {
    int type;
    if (formatHint == null) {
      type = Util.inferContentType(uri.getLastPathSegment());
    } else {
      switch (formatHint) {
        case FORMAT_SS:
          type = C.TYPE_SS;
          break;
        case FORMAT_DASH:
          type = C.TYPE_DASH;
          break;
        case FORMAT_HLS:
          type = C.TYPE_HLS;
          break;
        case FORMAT_OTHER:
          type = C.TYPE_OTHER;
          break;
        default:
          type = -1;
          break;
      }
    }
    switch (type) {
      case C.TYPE_SS:
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
            .createMediaSource(uri);
      case C.TYPE_DASH:
        return new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
            .createMediaSource(uri);
      case C.TYPE_HLS:
        return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri);
      case C.TYPE_OTHER:
        return new ExtractorMediaSource.Factory(mediaDataSourceFactory)
            .setExtractorsFactory(new DefaultExtractorsFactory())
            .createMediaSource(uri);
      default:
        {
          throw new IllegalStateException("Unsupported type: " + type);
        }
    }
  }

  private void setupVideoPlayer(
      EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry) {

    eventChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink sink) {
            eventSink.setDelegate(sink);
          }

          @Override
          public void onCancel(Object o) {
            eventSink.setDelegate(null);
          }
        });

    surface = new Surface(textureEntry.surfaceTexture());
    exoPlayer.setVideoSurface(surface);
    setAudioAttributes(exoPlayer);
    exoPlayer.addTextOutput((TextRenderer.Output) cues -> {
      Map<String, Object> event1 = new HashMap<>();
      eventSink.success(event1);
      if (cues != null && cues.size() > 0) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "subtitle");
        event.put("values", cues.get(0).text.toString());
        eventSink.success(event);
      }
      if (cues != null && cues.size() == 0) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "subtitle");
        event.put("values", "");
        eventSink.success(event);
      }
    });
    exoPlayer.addMetadataOutput((MetadataRenderer.Output) metadata -> {
      if (metadata != null && metadata.length() > 0 ) {
        final com.google.android.exoplayer2.metadata.Metadata.Entry entry = metadata.get(0);
        if (entry instanceof TextInformationFrame){
          Map<String, Object> event = new HashMap<>();
          event.put("event", "metadata");
          event.put("values", ((TextInformationFrame)entry).value);
          eventSink.success(event);
        }
      }

    });

    exoPlayer.addListener(
        new EventListener() {

          @Override
          public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
              sendBufferingUpdate();
            } else if (playbackState == Player.STATE_READY) {
              if (!isInitialized) {
                isInitialized = true;
                sendInitialized();
                getSubtitles();
                if (trackInfos.size() > 0)
                {
                  Map<String,Object> trackInfo = trackInfos.get(0);
                  int trackIndex = (int)trackInfo.get("trackIndex");
                  int groupIndex = (int)trackInfo.get("groupIndex");
                  setSubtitles(trackIndex,groupIndex);
                }
              }
            } else if (playbackState == Player.STATE_ENDED) {
              Map<String, Object> event = new HashMap<>();
              event.put("event", "completed");
              eventSink.success(event);
            }
          }

          @Override
          public void onPlayerError(final ExoPlaybackException error) {
            if (eventSink != null) {
              eventSink.error("VideoError", "Video player had error " + error, null);
            }
          }
        });
  }

  private List<Map<String,Object>> trackInfos = new ArrayList<Map<String,Object>>();
  private void getSubtitles() {
    List<Map<?,?>> rawSubtitleItems = new ArrayList<Map<?,?>>();
    TrackGroupArray trackGroups;
    int rendererIndex = 2;
    DefaultTrackSelector.SelectionOverride override;

    MappingTrackSelector.MappedTrackInfo trackInfo =
            trackSelector == null ? null : trackSelector.getCurrentMappedTrackInfo();
    if (trackSelector == null || trackInfo == null) {
      // TrackSelector not initialized
      return;
    }

    trackGroups = trackInfo.getTrackGroups(rendererIndex);
    DefaultTrackSelector.Parameters parameters = trackSelector.getParameters();

    // Add per-track views.
    for (int groupIndex = 0; groupIndex < trackGroups.length; groupIndex++) {
      TrackGroup group = trackGroups.get(groupIndex);
      for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {

        if (group.getFormat(trackIndex).language != null || group.getFormat(trackIndex).label != null) {
          Map<String,Object> raw = new HashMap<String,Object>();
          raw.put("language", group.getFormat(trackIndex).language);
          raw.put("label", group.getFormat(trackIndex).label);
          raw.put("trackIndex", trackIndex);
          raw.put("groupIndex", groupIndex);
          raw.put("renderIndex", rendererIndex);
          rawSubtitleItems.add(raw);
          trackInfos.add(raw);
        }
      }
    }
    Map<String, Object> event = new HashMap<>();
    event.put("event", "subtitleList");
    event.put("values", rawSubtitleItems);
    eventSink.success(event);

  }

  void setSubtitles(int trackIndex, int groupIndex) {
    boolean isDisabled;
    TrackGroupArray trackGroups;
    int rendererIndex = 2;
    DefaultTrackSelector.SelectionOverride override;

    MappingTrackSelector.MappedTrackInfo trackInfo =
            trackSelector == null ? null : trackSelector.getCurrentMappedTrackInfo();
    if (trackSelector == null || trackInfo == null) {
      // TrackSelector not initialized
      return;
    }

    trackGroups = trackInfo.getTrackGroups(rendererIndex);
    DefaultTrackSelector.Parameters parameters = trackSelector.getParameters();
    isDisabled = parameters.getRendererDisabled(rendererIndex);
    DefaultTrackSelector.ParametersBuilder parametersBuilder = trackSelector.buildUponParameters();
    parametersBuilder.setRendererDisabled(rendererIndex, isDisabled);
    override = new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
    if (override != null) {
      parametersBuilder.setSelectionOverride(rendererIndex, trackGroups, override);
    } else {
      parametersBuilder.clearSelectionOverrides(rendererIndex);
    }
    trackSelector.setParameters(parametersBuilder);
  }

  void sendBufferingUpdate() {
    Map<String, Object> event = new HashMap<>();
    event.put("event", "bufferingUpdate");
    List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
    // iOS supports a list of buffered ranges, so here is a list with a single range.
    event.put("values", Collections.singletonList(range));
    eventSink.success(event);
  }

  @SuppressWarnings("deprecation")
  private static void setAudioAttributes(SimpleExoPlayer exoPlayer) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      exoPlayer.setAudioAttributes(
          new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build());
    } else {
      exoPlayer.setAudioStreamType(C.STREAM_TYPE_MUSIC);
    }
  }

  void play() {
    exoPlayer.setPlayWhenReady(true);
  }

  void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  void setLooping(boolean value) {
    exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  void setVolume(double value) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
    exoPlayer.setVolume(bracketedValue);
  }

  void seekTo(int location) {
    exoPlayer.seekTo(location);
  }

  long getPosition() {
    return exoPlayer.getCurrentPosition();
  }

  long getDuration() {
    return exoPlayer.getDuration();
  }


  @SuppressWarnings("SuspiciousNameCombination")
  private void sendInitialized() {
    if (isInitialized) {
      Map<String, Object> event = new HashMap<>();
      event.put("event", "initialized");
      event.put("duration", exoPlayer.getDuration());

      if (exoPlayer.getVideoFormat() != null) {
        Format videoFormat = exoPlayer.getVideoFormat();
        int width = videoFormat.width;
        int height = videoFormat.height;
        int rotationDegrees = videoFormat.rotationDegrees;
        // Switch the width/height if video was taken in portrait mode
        if (rotationDegrees == 90 || rotationDegrees == 270) {
          width = exoPlayer.getVideoFormat().height;
          height = exoPlayer.getVideoFormat().width;
        }
        event.put("width", width);
        event.put("height", height);
      }
      eventSink.success(event);
    }
  }

  void dispose() {
    if (isInitialized) {
      exoPlayer.stop();
    }
    textureEntry.release();
    eventChannel.setStreamHandler(null);
    if (surface != null) {
      surface.release();
    }
    if (exoPlayer != null) {
      exoPlayer.release();
    }
  }
}
