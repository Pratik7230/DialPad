package com.pratikpatil.dialpad;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists saved call recordings with play / pause / seek, share and delete.
 */
public class RecordingsActivity extends AppCompatActivity {

    private final List<RecordingStore.Recording> items = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerView rv;
    private View emptyView;
    private TextView tvSummary;
    private Adapter adapter;

    // Playback
    @Nullable private MediaPlayer player;
    @Nullable private RecordingStore.Recording playing;
    private boolean paused = false;

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (player != null && playing != null) {
                int idx = items.indexOf(playing);
                if (idx >= 0) adapter.notifyItemChanged(idx, "progress");
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        rv = findViewById(R.id.rvRecordings);
        emptyView = findViewById(R.id.emptyRecordings);
        tvSummary = findViewById(R.id.tvRecordingsSummary);

        adapter = new Adapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
            paused = true;
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        io.shutdownNow();
    }

    private void load() {
        io.execute(() -> {
            List<RecordingStore.Recording> list = RecordingStore.list(this);
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                stopPlaybackIfMissing(list);
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                updateSummary();
            });
        });
    }

    private void updateSummary() {
        long total = 0;
        for (RecordingStore.Recording r : items) total += r.sizeBytes;
        tvSummary.setText(items.isEmpty() ? RecordingStore.LOCATION_LABEL
                : items.size() + (items.size() == 1 ? " recording · " : " recordings · ")
                  + Formatter.formatShortFileSize(this, total));
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ==================== PLAYBACK ====================

    private void togglePlay(RecordingStore.Recording r) {
        if (r.equals(playing) && player != null) {
            if (player.isPlaying()) { player.pause(); paused = true; }
            else { player.start(); paused = false; handler.post(progressTicker); }
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlayback();
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            if (r.path != null) mp.setDataSource(r.path);
            else mp.setDataSource(this, r.uri);
            mp.prepare();
        } catch (Exception e) {
            mp.release();
            Toast.makeText(this, "Can't play this recording", Toast.LENGTH_SHORT).show();
            return;
        }
        mp.setOnCompletionListener(m -> stopPlayback());
        mp.start();
        player = mp;
        playing = r;
        paused = false;
        handler.post(progressTicker);
        adapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        handler.removeCallbacks(progressTicker);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playing = null;
        paused = false;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void stopPlaybackIfMissing(List<RecordingStore.Recording> fresh) {
        if (playing == null) return;
        for (RecordingStore.Recording r : fresh) if (r.uri.equals(playing.uri)) return;
        stopPlayback();
    }

    // ==================== SHARE / DELETE ====================

    private void share(RecordingStore.Recording r) {
        if (r.path != null) {
            // Android 9 and older: file:// URIs can't be shared, point to the folder instead
            Toast.makeText(this, "Find it in " + RecordingStore.LOCATION_LABEL, Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(RecordingStore.MIME)
                .putExtra(Intent.EXTRA_STREAM, r.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share recording"));
    }

    private void confirmDelete(RecordingStore.Recording r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete recording?")
                .setMessage(r.callerLabel() + "\nThis can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (r.equals(playing)) stopPlayback();
                    io.execute(() -> {
                        boolean ok = RecordingStore.delete(this, r);
                        handler.post(() -> {
                            if (ok) {
                                int idx = items.indexOf(r);
                                if (idx >= 0) { items.remove(idx); adapter.notifyItemRemoved(idx); }
                                updateSummary();
                            } else {
                                Toast.makeText(this, "Couldn't delete recording", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    // ==================== ADAPTER ====================

    private class Adapter extends RecyclerView.Adapter<Holder> {

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recording, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            if (payloads.contains("progress")) bindProgress(h, items.get(position));
            else onBindViewHolder(h, position);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            RecordingStore.Recording r = items.get(position);
            h.name.setText(r.callerLabel());

            String when = DateUtils.formatDateTime(RecordingsActivity.this, r.dateMillis,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
            String dur = r.durationMs > 0 ? formatMs(r.durationMs) : "—";
            h.meta.setText(when + " · " + dur + " · "
                    + Formatter.formatShortFileSize(RecordingsActivity.this, r.sizeBytes));

            boolean isCurrent = r.equals(playing);
            boolean isPlaying = isCurrent && !paused && player != null;
            h.play.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            h.play.setContentDescription(isPlaying ? "Pause" : "Play");
            h.play.setOnClickListener(v -> togglePlay(r));
            h.itemView.setOnClickListener(v -> togglePlay(r));
            h.share.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? View.VISIBLE : View.GONE);
            h.share.setOnClickListener(v -> share(r));
            h.delete.setOnClickListener(v -> confirmDelete(r));

            h.playback.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            h.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (fromUser && player != null && r.equals(playing)) player.seekTo(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
            bindProgress(h, r);
        }

        private void bindProgress(Holder h, RecordingStore.Recording r) {
            if (!r.equals(playing) || player == null) return;
            int dur = Math.max(player.getDuration(), 0);
            int pos = Math.max(player.getCurrentPosition(), 0);
            h.seek.setMax(dur);
            h.seek.setProgress(pos);
            h.position.setText(formatMs(pos) + " / " + formatMs(dur));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageButton play, share, delete;
        final TextView name, meta, position;
        final LinearLayout playback;
        final SeekBar seek;

        Holder(View v) {
            super(v);
            play = v.findViewById(R.id.btnPlay);
            share = v.findViewById(R.id.btnShare);
            delete = v.findViewById(R.id.btnDeleteRecording);
            name = v.findViewById(R.id.tvRecordingName);
            meta = v.findViewById(R.id.tvRecordingMeta);
            position = v.findViewById(R.id.tvPosition);
            playback = v.findViewById(R.id.llPlayback);
            seek = v.findViewById(R.id.seekRecording);
        }
    }
}
