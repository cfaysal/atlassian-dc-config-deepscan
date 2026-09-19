package structuredoctor

final class LiveConfigurationDiscovery implements HierarchyProvider {
    private final Closure<ReadResult<HierarchySnapshot>> hierarchyReader

    LiveConfigurationDiscovery(Closure<ReadResult<HierarchySnapshot>> hierarchyReader) {
        this.hierarchyReader = hierarchyReader
    }

    @Override
    ReadResult<HierarchySnapshot> readHierarchy() {
        if (hierarchyReader == null) {
            return ReadResult.unavailable(
                'The installed Jira hierarchy read API has not been proven')
        }
        try {
            ReadResult<HierarchySnapshot> result = hierarchyReader.call()
            result ?: ReadResult.failed('The Jira hierarchy reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Jira hierarchy read failed')
        }
    }

    static HierarchySnapshot mapHierarchy(Collection<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException('Jira hierarchy is empty')
        }
        List<HierarchyLevel> levels = records.collect { Map<String, Object> record ->
            long rank = number(record.rank, 'rank')
            String levelId = text(record.levelId, 'levelId')
            String name = text(record.name, 'name')
            List<Long> issueTypeIds = ((Collection<?>) (record.issueTypeIds ?: []))
                .collect { Object value -> number(value, 'issueTypeId') }
                .unique()
                .sort()
            if (issueTypeIds.isEmpty()) {
                throw new IllegalArgumentException('Hierarchy level has no issue types')
            }
            new HierarchyLevel(rank: rank, levelId: levelId, name: name,
                issueTypeIds: issueTypeIds)
        }.sort { HierarchyLevel left, HierarchyLevel right ->
            right.rank <=> left.rank
        }
        if (levels*.rank.unique().size() != levels.size()) {
            throw new IllegalArgumentException('Hierarchy ranks are not unique')
        }
        Map<String, Object> canonical = [levels: levels.collect { HierarchyLevel level ->
            [rank: level.rank, levelId: level.levelId, name: level.name,
             issueTypeIds: level.issueTypeIds]
        }]
        new HierarchySnapshot(levels: levels,
            fingerprint: CoreCanonical.sha256(canonical))
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number) && !(String.valueOf(value) ==~ /-?[0-9]+/)) {
            throw new IllegalArgumentException(name + ' is invalid')
        }
        Long.parseLong(String.valueOf(value))
    }

    private static String text(Object value, String name) {
        String result = value == null ? null : String.valueOf(value).trim()
        if (!result) throw new IllegalArgumentException(name + ' is required')
        result
    }
}
