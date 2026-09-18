package structuredoctor

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper

@CompileStatic
final class CoreSupport {
    private CoreSupport() {
        throw new UnsupportedOperationException('utility class')
    }

    static String html(Object value) {
        String.valueOf(value == null ? '' : value)
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#39;')
    }

    static Object jsonSafe(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value
        }
        if (value instanceof Map) {
            Map<String, Object> safeMap = new LinkedHashMap<String, Object>()
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                safeMap.put(String.valueOf(entry.key), jsonSafe(entry.value))
            }
            return safeMap
        }
        if (value instanceof Iterable) {
            List<Object> safeItems = []
            for (Object nested : (Iterable<?>) value) {
                safeItems.add(jsonSafe(nested))
            }
            return safeItems
        }
        if (value.class.isArray()) {
            List<Object> safeItems = []
            for (Object nested : (Object[]) value) {
                safeItems.add(jsonSafe(nested))
            }
            return safeItems
        }
        String.valueOf(value)
    }

    static String queryValue(Object queryParams, String name) {
        Object value = queryParams == null ? null : InvokerHelper.invokeMethod(queryParams, 'getFirst', name)
        value == null ? null : String.valueOf(value).trim()
    }
}
