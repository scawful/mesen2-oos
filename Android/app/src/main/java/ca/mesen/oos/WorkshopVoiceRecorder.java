package ca.mesen.oos;

import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;

/**
 * Thin MediaRecorder wrapper for Workshop voice notes. Uses the platform
 * default MIC route (built-in when present; otherwise active BT HFP / wired).
 */
final class WorkshopVoiceRecorder {
    private MediaRecorder mRecorder;
    private File mTarget;
    private long mStartedElapsedMs;
    private boolean mRecording;

    boolean isRecording() {
        return mRecording;
    }

    void start(File target) throws IOException {
        if (mRecording) {
            throw new IOException("Already recording a voice note");
        }
        if (target == null) {
            throw new IOException("Voice note path is missing");
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Could not clear previous voice note file");
        }
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(target.getAbsolutePath());
            recorder.setMaxDuration(WorkshopVoiceNote.MAX_DURATION_MS);
            recorder.prepare();
            recorder.start();
        } catch (RuntimeException | IOException error) {
            try {
                recorder.reset();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            throw new IOException("Could not start microphone: " + error.getMessage(), error);
        }
        mRecorder = recorder;
        mTarget = target;
        mStartedElapsedMs = SystemClock.elapsedRealtime();
        mRecording = true;
    }

    /** @return duration in milliseconds */
    long stop() throws IOException {
        if (!mRecording || mRecorder == null) {
            throw new IOException("Not recording");
        }
        long duration = Math.max(0, SystemClock.elapsedRealtime() - mStartedElapsedMs);
        MediaRecorder recorder = mRecorder;
        mRecorder = null;
        mRecording = false;
        try {
            recorder.stop();
        } catch (RuntimeException error) {
            try {
                recorder.reset();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            if (mTarget != null && mTarget.exists()) {
                mTarget.delete();
            }
            throw new IOException("Could not finish voice note: " + error.getMessage(), error);
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        return duration;
    }

    void cancel() {
        if (mRecorder != null) {
            try {
                if (mRecording) {
                    mRecorder.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                mRecorder.reset();
            } catch (Exception ignored) {
            }
            try {
                mRecorder.release();
            } catch (Exception ignored) {
            }
        }
        mRecorder = null;
        mRecording = false;
        if (mTarget != null && mTarget.exists()) {
            mTarget.delete();
        }
        mTarget = null;
    }
}
