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
}
