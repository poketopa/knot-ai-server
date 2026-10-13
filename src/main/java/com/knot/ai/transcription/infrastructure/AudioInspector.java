package com.knot.ai.transcription.infrastructure;

import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

class AudioInspector {
    private static final double MAXIMUM_DURATION_SECONDS = 7200;
    private static final long MAXIMUM_PCM_BYTES = 230_400_000L;
    private static final long WAV_HEADER_ALLOWANCE_BYTES = 1_048_576L;
    private static final int RIFF_CHUNK_ID = 0x52494646;
    private static final int WAVE_FORMAT_ID = 0x57415645;
    private static final int FORMAT_CHUNK_ID = 0x666d7420;
    private static final int DATA_CHUNK_ID = 0x64617461;

    void verifyContainerDuration(Path durationFile) throws IOException {
        String probeDuration = Files.readString(durationFile)
                .trim();
        if (probeDuration.equals("N/A")) {
            return;
        }
        double declaredDuration;
        try {
            declaredDuration = Double.parseDouble(probeDuration);
        } catch (NumberFormatException exception) {
            throw new JobException(JobErrorCode.INVALID_AUDIO_DURATION);
        }
        if (!Double.isFinite(declaredDuration) || declaredDuration <= 0
                || declaredDuration > MAXIMUM_DURATION_SECONDS) {
            throw new JobException(JobErrorCode.AUDIO_DURATION_EXCEEDED);
        }
    }

    double readPcmDuration(Path wav) throws IOException {
        if (Files.size(wav) > MAXIMUM_PCM_BYTES + WAV_HEADER_ALLOWANCE_BYTES) {
            throw new JobException(JobErrorCode.AUDIO_DURATION_EXCEEDED);
        }
        double duration;
        try (RandomAccessFile file = new RandomAccessFile(
                wav.toFile(),
                "r"
        )) {
            if (file.length() < 44 || file.readInt() != RIFF_CHUNK_ID) {
                throw new JobException(JobErrorCode.INVALID_AUDIO_FORMAT);
            }
            file.skipBytes(4);
            if (file.readInt() != WAVE_FORMAT_ID) {
                throw new JobException(JobErrorCode.INVALID_AUDIO_FORMAT);
            }
            duration = readChunks(file);
        }
        if (duration <= 0 || duration > MAXIMUM_DURATION_SECONDS) {
            throw new JobException(JobErrorCode.AUDIO_DURATION_EXCEEDED);
        }
        return duration;
    }

    private double readChunks(RandomAccessFile file) throws IOException {
        boolean formatVerified = false;
        while (file.getFilePointer() + 8 <= file.length()) {
            int chunkId = file.readInt();
            long chunkSize = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
            long position = file.getFilePointer();
            if (chunkSize > file.length() - position) {
                throw new JobException(JobErrorCode.INVALID_AUDIO_FORMAT);
            }
            if (chunkId == FORMAT_CHUNK_ID) {
                verifyFormatChunk(
                        file,
                        chunkSize
                );
                formatVerified = true;
            } else if (chunkId == DATA_CHUNK_ID) {
                return readDataDuration(
                        chunkSize,
                        formatVerified
                );
            }
            file.seek(position + chunkSize + (chunkSize % 2));
        }
        throw new JobException(JobErrorCode.INVALID_AUDIO_FORMAT);
    }

    private void verifyFormatChunk(
            RandomAccessFile file,
            long chunkSize
    ) throws IOException {
        if (chunkSize < 16 || Short.reverseBytes(file.readShort()) != 1 || Short.reverseBytes(file.readShort()) != 1
                || Integer.reverseBytes(file.readInt()) != 16000 || Integer.reverseBytes(file.readInt()) != 32000
                || Short.reverseBytes(file.readShort()) != 2 || Short.reverseBytes(file.readShort()) != 16) {
            throw new JobException(JobErrorCode.INVALID_AUDIO_FORMAT);
        }
    }

    private double readDataDuration(
            long chunkSize,
            boolean formatVerified
    ) {
        if (!formatVerified || chunkSize % 2 != 0) {
            throw new JobException(JobErrorCode.INVALID_AUDIO_FORMAT);
        }
        if (chunkSize > MAXIMUM_PCM_BYTES) {
            throw new JobException(JobErrorCode.AUDIO_DURATION_EXCEEDED);
        }
        return chunkSize / 32000.0;
    }
}
