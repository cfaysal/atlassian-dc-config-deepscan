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
}
