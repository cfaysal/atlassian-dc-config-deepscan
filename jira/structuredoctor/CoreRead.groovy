package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
enum ReadState {
    COMPLETE,
    INCOMPLETE,
    FAILED,
    UNAVAILABLE
}

@CompileStatic
final class Coverage {
    final long requested
    final long actual
    final boolean capped
    final String fromInclusive
    final String toInclusive

    private Coverage(long requested, long actual, boolean capped,
                     String fromInclusive, String toInclusive) {
        if (requested < 0L) {
            throw new IllegalArgumentException('requested must not be negative')
        }
        if (actual < 0L || actual > requested) {
            throw new IllegalArgumentException('actual must be between zero and requested')
        }
        this.requested = requested
        this.actual = actual
        this.capped = capped
        this.fromInclusive = fromInclusive
        this.toInclusive = toInclusive
    }

    static Coverage bounded(long requested, long actual, boolean capped,
                            String fromInclusive, String toInclusive) {
        new Coverage(requested, actual, capped, fromInclusive, toInclusive)
    }

    boolean complete() {
        !capped && actual == requested
    }
}

@CompileStatic
final class EvidenceRequirement {
    final String source
    final String fingerprint
    final boolean required

    EvidenceRequirement(String source, String fingerprint, boolean required) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException('source is required')
        }
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException('fingerprint is required')
        }
        this.source = source
        this.fingerprint = fingerprint
        this.required = required
    }
}

@CompileStatic
final class ReadResult<T> {
    final ReadState state
    final T value
    final String reason
    final Coverage coverage

    private ReadResult(ReadState state, T value, String reason, Coverage coverage) {
        if (state == null) {
            throw new IllegalArgumentException('state is required')
        }
        if (state != ReadState.COMPLETE && (reason == null || reason.trim().isEmpty())) {
            throw new IllegalArgumentException('non-complete reads require a reason')
        }
        this.state = state
        this.value = value
        this.reason = reason
        this.coverage = coverage
    }

    static <T> ReadResult<T> complete(T value) {
        new ReadResult<T>(ReadState.COMPLETE, value, null, null)
    }

    static <T> ReadResult<T> complete(T value, Coverage coverage) {
        new ReadResult<T>(ReadState.COMPLETE, value, null, coverage)
    }

    static <T> ReadResult<T> incomplete(T partialValue, String reason) {
        new ReadResult<T>(ReadState.INCOMPLETE, partialValue, reason, null)
    }

    static <T> ReadResult<T> incomplete(T partialValue, String reason, Coverage coverage) {
        new ReadResult<T>(ReadState.INCOMPLETE, partialValue, reason, coverage)
    }

    static <T> ReadResult<T> failed(String reason) {
        new ReadResult<T>(ReadState.FAILED, null, reason, null)
    }

    static <T> ReadResult<T> unavailable(String reason) {
        new ReadResult<T>(ReadState.UNAVAILABLE, null, reason, null)
    }

    boolean complete() {
        state == ReadState.COMPLETE && (coverage == null || coverage.complete())
    }
}
