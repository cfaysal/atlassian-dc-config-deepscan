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

    static StructureSnapshot mapStructure(long structureId,
                                          String revision,
                                          Collection<Map<String, Object>> generatorRecords,
                                          Collection<Map<String, Object>> rowRecords,
                                          boolean sourceComplete) {
        if (structureId <= 0L || !revision || rowRecords == null) {
            throw new IllegalArgumentException('Structure snapshot input is invalid')
        }
        List<GeneratorSnapshot> generators = (generatorRecords ?: []).collect {
            Map<String, Object> record ->
                new GeneratorSnapshot(
                    generatorId: number(record.generatorId, 'generatorId'),
                    moduleKey: text(record.moduleKey, 'moduleKey'),
                    type: text(record.type, 'type'),
                    order: integer(record.order, 'order'),
                    enabled: Boolean.TRUE == record.enabled,
                    parameters: (Map<String, Object>) (record.parameters ?: [:]),
                    revision: text(record.revision, 'revision'),
                    complete: Boolean.TRUE == record.complete)
        }.sort { GeneratorSnapshot left, GeneratorSnapshot right ->
            left.order <=> right.order ?: left.generatorId <=> right.generatorId
        }
        List<Map<String, Object>> rows = new ArrayList<>(rowRecords)
        boolean traversalComplete = true
        List<OccurrenceSnapshot> occurrences = []
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows[index]
            if (row.issueId == null) continue
            List<Long> path = []
            Set<Integer> visited = [] as Set<Integer>
            int parentIndex = integer(row.parentIndex, 'parentIndex')
            while (parentIndex >= 0) {
                if (parentIndex >= rows.size() || !visited.add(parentIndex)) {
                    traversalComplete = false
                    break
                }
                Map<String, Object> parent = rows[parentIndex]
                if (parent.issueId != null) {
                    path.add(0, number(parent.issueId, 'parentIssueId'))
                }
                parentIndex = integer(parent.parentIndex, 'parentIndex')
            }
            long issueId = number(row.issueId, 'issueId')
            String rowId = text(row.rowId, 'rowId')
            String provenance = text(row.provenance, 'provenance')
            String creatorId = row.creatorId == null ? null : String.valueOf(row.creatorId)
            boolean provenanceComplete = Boolean.TRUE == row.provenanceComplete
            String occurrenceId = CoreCanonical.deterministicId('occurrence', [
                structureId: structureId, issueId: issueId, rowId: rowId,
                parentPath: path, provenance: provenance, creatorId: creatorId
            ])
            occurrences.add(new OccurrenceSnapshot(
                occurrenceId: occurrenceId, issueId: issueId, rowId: rowId,
                parentPath: path, parentIssueId: path.isEmpty() ? null : path.last(),
                depth: integer(row.depth, 'depth'),
                position: integer(row.position, 'position'),
                provenance: provenance, creatorId: creatorId,
                provenanceComplete: provenanceComplete))
        }
        boolean complete = sourceComplete && traversalComplete &&
            generators.every { GeneratorSnapshot generator -> generator.complete } &&
            occurrences.every { OccurrenceSnapshot occurrence -> occurrence.provenanceComplete }
        Map<String, Object> canonical = [
            structureId: structureId, revision: revision,
            generators: generators.collect { GeneratorSnapshot generator -> [
                generatorId: generator.generatorId, moduleKey: generator.moduleKey,
                type: generator.type, order: generator.order, enabled: generator.enabled,
                parameters: generator.parameters, revision: generator.revision,
                complete: generator.complete
            ] },
            occurrences: occurrences.collect { OccurrenceSnapshot occurrence -> [
                occurrenceId: occurrence.occurrenceId, issueId: occurrence.issueId,
                rowId: occurrence.rowId, parentPath: occurrence.parentPath,
                parentIssueId: occurrence.parentIssueId, depth: occurrence.depth,
                position: occurrence.position, provenance: occurrence.provenance,
                creatorId: occurrence.creatorId,
                provenanceComplete: occurrence.provenanceComplete
            ] }
        ]
        new StructureSnapshot(
            structureId: structureId, revision: revision, hierarchy: null,
            generators: generators, occurrences: occurrences, relations: [],
            fingerprint: CoreCanonical.sha256(canonical), complete: complete)
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number) && !(String.valueOf(value) ==~ /-?[0-9]+/)) {
            throw new IllegalArgumentException(name + ' is invalid')
        }
        Long.parseLong(String.valueOf(value))
    }

    private static int integer(Object value, String name) {
        long result = number(value, name)
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + ' is outside the supported range')
        }
        (int) result
    }

    private static String text(Object value, String name) {
        String result = value == null ? null : String.valueOf(value).trim()
        if (!result) throw new IllegalArgumentException(name + ' is required')
        result
    }
}
