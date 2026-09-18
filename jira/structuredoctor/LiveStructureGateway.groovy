package structuredoctor

final class LiveStructureGateway implements StructureCatalogProvider, StructureSnapshotProvider {
    private final Closure<ReadResult<List<StructureChoice>>> catalogReader
    private final Closure<ReadResult<StructureSnapshot>> snapshotReader

    LiveStructureGateway(Closure<ReadResult<List<StructureChoice>>> catalogReader,
                         Closure<ReadResult<StructureSnapshot>> snapshotReader) {
        this.catalogReader = catalogReader
        this.snapshotReader = snapshotReader
    }

    @Override
    ReadResult<List<StructureChoice>> listStructures() {
        if (catalogReader == null) {
            return ReadResult.unavailable('The Structure catalog read API has not been proven')
        }
        try {
            ReadResult<List<StructureChoice>> result = catalogReader.call()
            result ?: ReadResult.failed('The Structure catalog reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Structure catalog read failed')
        }
    }

    @Override
    ReadResult<StructureSnapshot> readStructure(long structureId) {
        if (structureId <= 0L) {
            return ReadResult.failed('A positive Structure ID is required')
        }
        if (snapshotReader == null) {
            return ReadResult.unavailable('The complete Structure snapshot API has not been proven')
        }
        try {
            ReadResult<StructureSnapshot> result = snapshotReader.call(structureId)
            result ?: ReadResult.failed('The Structure snapshot reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Structure snapshot read failed')
        }
    }
}
