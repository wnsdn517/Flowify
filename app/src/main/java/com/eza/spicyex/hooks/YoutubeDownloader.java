package com.eza.spicyex.hooks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.xposed.XpLog;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Minimal YouTube Music downloader for Spicy EX.
 * Searches YouTube for the current Spotify track, extracts the best audio stream via NewPipe,
 * and downloads it to the app's internal files directory.
 */
public final class YoutubeDownloader {
    private static final String TAG = "YoutubeDownloader";
    private static final int NOTIFICATION_ID = 0x59444C; // "YDL"
    private static final String CHANNEL_ID = "youtube_downloads";
    private static final String DOWNLOAD_DIR_NAME = "YouTubeMusic";

    private final Context context;
    private final NativeSpicyLyricsHook hook;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    // NewPipe is initialized once; the extractor is created per extraction
    private volatile boolean newPipeInitialized = false;
    private final Object initLock = new Object();

    // Serialize extractions (Rhino JS parsing is CPU-heavy and not thread-safe)
    private final ReentrantLock extractionLock = new ReentrantLock();

    private volatile DownloadTask currentTask;

    public YoutubeDownloader(Context context, NativeSpicyLyricsHook hook) {
        this.context = context.getApplicationContext();
        this.hook = hook;
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "YouTube Music Downloads",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Downloads from YouTube for offline playback");
            channel.setShowBadge(false);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    /** Ensures NewPipe is initialized with our OkHttp downloader. */
    private void ensureNewPipeInit() {
        if (newPipeInitialized) return;
        synchronized (initLock) {
            if (newPipeInitialized) return;
            try {
                NewPipe.init(new OkHttpDownloader());
                newPipeInitialized = true;
                XpLog.log(TAG + " NewPipe initialized");
            } catch (Throwable t) {
                XpLog.log(TAG + " NewPipe init failed: " + t);
            }
        }
    }

    /**
     * Starts downloading the currently playing track from YouTube.
     * Returns immediately; progress is shown via notification.
     */
    public void downloadCurrentTrack() {
        SpotifyTrack track = hook.getCurrentTrackSafely();
        if (track == null || track.title == null || track.artist == null) {
            XpLog.log(TAG + " No track playing or missing metadata");
            return;
        }

        if (currentTask != null && !currentTask.isDone()) {
            XpLog.log(TAG + " Download already in progress");
            return;
        }

        currentTask = new DownloadTask(track);
        executor.execute(currentTask);
    }

    /** Cancels any ongoing download. */
    public void cancel() {
        if (currentTask != null) {
            currentTask.cancel();
            currentTask = null;
        }
    }

    /** @return true if a download is currently in progress. */
    public boolean isDownloading() {
        return currentTask != null && !currentTask.isDone();
    }

    private File getDownloadDir() {
        File dir = new File(context.getFilesDir(), DOWNLOAD_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private void showNotification(String title, String text, int progress, boolean indeterminate) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, progress, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void hideNotification() {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private void showToast(String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    private final class DownloadTask implements Runnable {
        private final SpotifyTrack track;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile boolean done;

        DownloadTask(SpotifyTrack track) {
            this.track = track;
        }

        boolean isDone() { return done; }

        void cancel() {
            cancelled.set(true);
        }

        @Override
        public void run() {
            try {
                ensureNewPipeInit();
                if (!newPipeInitialized) {
                    showToast("NewPipe initialization failed");
                    return;
                }

                // Step 1: Search YouTube for the track
                String query = track.title + " " + track.artist;
                XpLog.log(TAG + " Searching YouTube for: " + query);
                showNotification("Downloading...", "Searching: " + query, 0, true);

                String videoId = searchYouTube(query);
                if (cancelled.get()) return;
                if (videoId == null) {
                    showToast("Track not found on YouTube");
                    return;
                }
                XpLog.log(TAG + " Found videoId: " + videoId);

                // Step 2: Extract audio stream (serialized)
                showNotification("Downloading...", "Resolving stream...", 10, true);
                AudioStreamInfo audioStream = extractAudioStream(videoId);
                if (cancelled.get()) return;
                if (audioStream == null) {
                    showToast("Could not extract audio stream");
                    return;
                }

                // Step 3: Download the stream
                String fileName = sanitizeFileName(track.artist + " - " + track.title) + ".m4a";
                File destFile = new File(getDownloadDir(), fileName);
                XpLog.log(TAG + " Downloading to: " + destFile.getAbsolutePath());

                downloadStream(audioStream.url, destFile, audioStream.headers);
                if (cancelled.get()) {
                    if (destFile.exists()) destFile.delete();
                    return;
                }

                showToast("Downloaded: " + fileName);
                XpLog.log(TAG + " Download complete: " + destFile.getAbsolutePath());

            } catch (Throwable t) {
                if (!cancelled.get()) {
                    XpLog.log(TAG + " Download failed: " + t);
                    showToast("Download failed: " + t.getMessage());
                }
            } finally {
                done = true;
                currentTask = null;
                hideNotification();
            }
        }

        /** Searches YouTube and returns the best matching videoId. */
        private String searchYouTube(String query) {
            try {
                ensureNewPipeInit();
                // Use YouTube service (not YouTubeMusic) for search - more reliable
                org.schabi.newpipe.extractor.services.youtube.YouTubeService service =
                        (org.schabi.newpipe.extractor.services.youtube.YouTubeService) NewPipe.getService(ServiceList.YouTube);
                ListLinkHandler handler = service.getSearchLinkHandler(query, 0);
                List<String> urls = new ArrayList<>();
                while (handler.hasNext()) {
                    List<String> batch = handler.next();
                    if (batch != null) urls.addAll(batch);
                    if (!urls.isEmpty()) break;
                }

                // Filter for watch URLs
                for (String url : urls) {
                    if (url.contains("watch?v=")) {
                        String videoId = url.substring(url.indexOf("watch?v=") + 8);
                        int amp = videoId.indexOf('&');
                        if (amp > 0) videoId = videoId.substring(0, amp);
                        if (videoId.length() == 11) return videoId;
                    }
                }
                return null;
            } catch (ExtractionException | IOException e) {
                XpLog.log(TAG + " Search failed: " + e);
                return null;
            }
        }

        /** Holds extracted stream info. */
        private static class AudioStreamInfo {
            final String url;
            final java.util.Map<String, String> headers;
            final int bitrate;

            AudioStreamInfo(String url, java.util.Map<String, String> headers, int bitrate) {
                this.url = url;
                this.headers = headers;
                this.bitrate = bitrate;
            }
        }

        /** Extracts the best audio stream for a videoId (serialized). */
        private AudioStreamInfo extractAudioStream(String videoId) {
            extractionLock.lock();
            try {
                String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
                org.schabi.newpipe.extractor.services.youtube.YouTubeService service =
                        (org.schabi.newpipe.extractor.services.youtube.YouTubeService) NewPipe.getService(ServiceList.YouTube);
                StreamExtractor extractor = service.getStreamExtractor(watchUrl);
                extractor.fetchPage(); // Required before accessing audioStreams

                List<AudioStream> audioStreams = extractor.getAudioStreams();
                if (audioStreams == null || audioStreams.isEmpty()) return null;

                // Filter for progressive HTTP streams only (DASH/HLS are manifests, not direct URLs)
                List<AudioStream> progressive = new ArrayList<>();
                for (AudioStream s : audioStreams) {
                    if (s.getUrl() != null && !s.getUrl().isEmpty()
                            && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                        progressive.add(s);
                    }
                }
                if (progressive.isEmpty()) return null;

                // Pick best M4A (AAC in MP4), fallback to best any
                AudioStream bestM4a = null;
                AudioStream bestAny = null;
                for (AudioStream s : progressive) {
                    if (s.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A) {
                        if (bestM4a == null || s.getAverageBitrate() > bestM4a.getAverageBitrate()) {
                            bestM4a = s;
                        }
                    }
                    if (bestAny == null || s.getAverageBitrate() > bestAny.getAverageBitrate()) {
                        bestAny = s;
                    }
                }
                AudioStream chosen = bestM4a != null ? bestM4a : bestAny;
                if (chosen == null) return null;

                // AudioStream.headers is a field (Map<String, String>), not a method
                return new AudioStreamInfo(chosen.getUrl(), chosen.headers, chosen.getAverageBitrate());
            } catch (ExtractionException | IOException e) {
                XpLog.log(TAG + " Stream extraction failed: " + e);
                return null;
            } finally {
                extractionLock.unlock();
            }
        }

        /** Downloads a stream URL to a file with progress updates. */
        private void downloadStream(String url, File destFile, java.util.Map<String, String> headers) throws IOException {
            Request.Builder requestBuilder = new Request.Builder().url(url);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
                    requestBuilder.addHeader(h.getKey(), h.getValue());
                }
            }
            // googlevideo URLs need a proper User-Agent matching the one that minted the URL
            requestBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            okhttp3.Request request = requestBuilder.build();

            okhttp3.Response response = httpClient.newCall(request).execute();
            try (ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null) {
                    throw new IOException("HTTP " + response.code());
                }

                long total = body.contentLength();
                if (total <= 0) total = -1;

                try (InputStream in = body.byteStream();
                     OutputStream out = new FileOutputStream(destFile)) {

                    byte[] buffer = new byte[64 * 1024];
                    long downloaded = 0;
                    int read;
                    int lastProgress = -1;

                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled.get()) throw new IOException("Cancelled");
                        out.write(buffer, 0, read);
                        downloaded += read;

                        if (total > 0) {
                            int progress = (int) ((downloaded * 100) / total);
                            if (progress != lastProgress) {
                                lastProgress = progress;
                                mainHandler.post(() -> showNotification(
                                        "Downloading...",
                                        sanitizeFileName(track.artist + " - " + track.title),
                                        progress,
                                        false
                                ));
                            }
                        }
                    }
                    out.flush();
                }
            }
        }
    }

    /** OkHttp-backed Downloader for NewPipe. */
    private static class OkHttpDownloader extends Downloader {
        private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        @Override
        public Response execute(Request request) {
            Request.Builder builder = new Request.Builder()
                    .method(request.getHttpMethod(), request.getDataToSend() != null
                            ? RequestBody.create(request.getDataToSend(), MediaType.parse("application/json"))
                            : null)
                    .url(request.getUrl());

            boolean hasUA = false;
            for (java.util.Map.Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();
                if ("User-Agent".equalsIgnoreCase(name) && !values.isEmpty()) {
                    hasUA = true;
                }
                for (String v : values) {
                    builder.addHeader(name, v);
                }
            }
            if (!hasUA) {
                builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            }

            try {
                okhttp3.Response response = CLIENT.newCall(builder.build()).execute();
                return new Response(
                        response.code(),
                        response.message(),
                        response.headers.toMultimap(),
                        response.body() != null ? response.body().string() : "",
                        response.request().url().toString()
                );
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}