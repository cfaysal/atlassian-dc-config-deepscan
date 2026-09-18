package structuredoctor

import groovy.json.JsonSlurper
import groovy.transform.Immutable

import java.nio.charset.StandardCharsets

@Immutable(copyWith = true)
class DoctorHttpDecision {
    boolean allowed
    int status
    String error
}

final class DoctorHttpGuard {
    static final int MAX_JSON_BYTES = 65_536

    static DoctorHttpDecision requireJson(String contentType, String body) {
        String mediaType = contentType?.split(';', 2)?.first()?.trim()
        if (!'application/json'.equalsIgnoreCase(mediaType)) {
            return reject(415, 'UNSUPPORTED_MEDIA_TYPE')
        }
        int size = (body ?: '').getBytes(StandardCharsets.UTF_8).length
        size > MAX_JSON_BYTES ? reject(413, 'REQUEST_TOO_LARGE') : allow()
    }

    static DoctorHttpDecision requireQueryKeys(Collection<?> actual,
                                               Collection<String> allowed) {
        Set<String> keys = (actual ?: []).collect { String.valueOf(it) } as Set<String>
        keys.every { String key -> (allowed ?: []).contains(key) } ?
            allow() : reject(400, 'UNSUPPORTED_QUERY_PARAMETER')
    }

    static Map<String, Object> parseJsonObject(String body) {
        try {
            Object parsed = body?.trim() ? new JsonSlurper().parseText(body) : null
            if (!(parsed instanceof Map)) throw new IllegalArgumentException('JSON object required')
            (Map<String, Object>) parsed
        } catch (Exception ignored) {
            throw new DoctorBoundaryException(400, 'INVALID_JSON')
        }
    }

    private static DoctorHttpDecision allow() {
        new DoctorHttpDecision(allowed: true, status: 0, error: null)
    }

    private static DoctorHttpDecision reject(int status, String error) {
        new DoctorHttpDecision(allowed: false, status: status, error: error)
    }
}

final class DoctorBoundaryException extends IllegalArgumentException {
    final int status
    final String code

    DoctorBoundaryException(int status, String code) {
        super(code)
        this.status = status
        this.code = code
    }
}
