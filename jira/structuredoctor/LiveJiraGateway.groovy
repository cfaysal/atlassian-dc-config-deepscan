package structuredoctor

final class LiveJiraGateway implements JiraDataProvider {
    private final Closure<ReadResult<List<IssueRelationSnapshot>>> issueReader

    LiveJiraGateway(Closure<ReadResult<List<IssueRelationSnapshot>>> issueReader) {
        this.issueReader = issueReader
    }

    @Override
    ReadResult<List<IssueRelationSnapshot>> readIssues(Collection<Long> issueIds) {
        if (issueReader == null) {
            return ReadResult.unavailable('The batched Jira relationship API has not been proven')
        }
        try {
            ReadResult<List<IssueRelationSnapshot>> result = issueReader.call(issueIds ?: [])
            result ?: ReadResult.failed('The Jira relationship reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Jira relationship read failed')
        }
    }

    static List<IssueRelationSnapshot> mapIssues(
        Collection<Map<String, Object>> records) {
        if (records == null) {
            throw new IllegalArgumentException('Jira issue records are required')
        }
        records.collect { Map<String, Object> record ->
            List<Long> parents = ((Collection<?>) (record.leadingParentIds ?: []))
                .collect { Object value -> number(value, 'leadingParentId') }
                .unique()
                .sort()
            new IssueRelationSnapshot(
                issueId: number(record.issueId, 'issueId'),
                issueTypeId: number(record.issueTypeId, 'issueTypeId'),
                projectId: record.projectId == null ? null : number(record.projectId, 'projectId'),
                issueKey: record.issueKey == null ? null : String.valueOf(record.issueKey),
                summary: record.summary == null ? null : String.valueOf(record.summary),
                issueTypeName: record.issueTypeName == null ? null : String.valueOf(record.issueTypeName),
                nativeParentId: record.nativeParentId == null ? null :
                    number(record.nativeParentId, 'nativeParentId'),
                leadingParentIds: parents,
                revisions: ((Map<?, ?>) (record.revisions ?: [:])).collectEntries {
                    Object key, Object value ->
                        [(String.valueOf(key)): String.valueOf(value)]
                } as Map<String, String>)
        }
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number) && !(String.valueOf(value) ==~ /-?[0-9]+/)) {
            throw new IllegalArgumentException(name + ' is invalid')
        }
        Long.parseLong(String.valueOf(value))
    }
}
