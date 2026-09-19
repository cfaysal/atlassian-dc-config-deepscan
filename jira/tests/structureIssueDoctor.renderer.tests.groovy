import structuredoctor.*

// Synthetic, permission-scoped records only. This exercises the actual mapper,
// analyzers and renderer, rather than checking source-code strings.
def relations = LiveJiraGateway.mapIssues([
    [issueId: 1L, issueTypeId: 10L, issueKey: 'DEMO-1', summary: 'Ziel A', issueTypeName: 'Ziel', leadingParentIds: []],
    [issueId: 2L, issueTypeId: 10L, issueKey: 'DEMO-2', summary: 'Ziel B', issueTypeName: 'Ziel', leadingParentIds: []],
    [issueId: 3L, issueTypeId: 20L, issueKey: 'DEMO-3', summary: 'KPI <script>alert(1)</script>', issueTypeName: 'KPI', nativeParentId: 1L, leadingParentIds: [1L]],
    [issueId: 4L, issueTypeId: 20L, issueKey: 'DEMO-4', summary: 'KPI ohne Zuordnung', issueTypeName: 'KPI', leadingParentIds: []]
])
def hierarchy = new HierarchySnapshot(levels: [
    new HierarchyLevel(rank: 2L, levelId: 'goal', name: 'Strategisches Ziel', issueTypeIds: [10L]),
    new HierarchyLevel(rank: 1L, levelId: 'kpi', name: 'Aktionsfeld', issueTypeIds: [20L])
], fingerprint: 'hierarchy-1')
def occurrence = { String id, long issueId, Long parent, String provenance ->
    new OccurrenceSnapshot(occurrenceId: id, issueId: issueId, rowId: id,
        parentIssueId: parent, parentPath: parent == null ? [] : [parent],
        depth: parent == null ? 0 : 1, position: 0, provenance: provenance,
        creatorId: provenance == 'PERMANENT' ? null : '21', provenanceComplete: true)
}
def snapshot = new StructureSnapshot(structureId: 9L, revision: 'v1', hierarchy: hierarchy,
    generators: [new GeneratorSnapshot(generatorId: 21L, moduleKey: 'synthetic:children',
        type: 'EXTENDER', order: 1, enabled: true, parameters: [:], revision: 'g1', complete: true)],
    occurrences: [occurrence('r1', 1L, null, 'PERMANENT'), occurrence('r2', 2L, null, 'PERMANENT'),
        occurrence('keep', 3L, 1L, 'ADVANCED_ROADMAPS'), occurrence('remove', 3L, 2L, 'PERMANENT'),
        occurrence('orphan', 4L, null, 'PERMANENT')],
    relations: relations, fingerprint: 'forest-1', complete: true)
def coverage = ['jira-hierarchy', 'structure-snapshot', 'jira-data', 'automation-rules', 'automation-audit'].collect { name ->
    new SourceCoverage(source: name, provider: 'LIVE',
        state: name.startsWith('automation') ? ReadState.UNAVAILABLE : ReadState.COMPLETE,
        reason: name.startsWith('automation') ? 'API not proven' : null)
}
def analysis = new DoctorAnalysis(snapshotId: 'analysis-synthetic', structureId: 9L,
    requestedAuditDays: 7, snapshot: snapshot, coverage: coverage,
    hierarchy: new CoreHierarchyAnalyzer().analyze(snapshot),
    duplicates: new CoreDuplicateAnalyzer().analyze(snapshot),
    causalClaims: [], complete: false, blockers: ['automation-rules', 'automation-audit'])
def renderer = new DoctorRenderer()
def catalogue = ReadResult.complete([new StructureChoice(id: 9L, name: 'Demo Portfolio')])
String page = renderer.render(catalogue, analysis, null)
def failures = []
int passed = 0
def check = { String name, boolean result -> if (result) passed++ else failures.add(name) }
check('readable issue key survives mapper', page.contains('DEMO-3'))
check('title is HTML escaped', page.contains('KPI &lt;script&gt;alert(1)&lt;/script&gt;'))
check('no injected script', !page.contains('<script>alert(1)</script>'))
check('type is displayed', page.contains('Vorgangstyp: KPI'))
check('expected Jira parent is named', page.contains('Jira-Parent (Soll für die Structure)'))
check('actual Structure parent is named separately', page.contains('Parent in der Structure (Ist)'))
check('orphan is not equated with invalid Jira data', page.contains('nicht automatisch ein Fehler'))
check('read-only investigation is distinguished from repair', page.contains('Prüfschritt, keine automatische Reparatur'))
check('missing automation is not a clean bill of health', page.contains('Nicht geprüft: Automation-Regeln'))
check('false negative conflict message absent', !page.contains('Keine belegten Konflikte.'))
check('duplicate choice is opt-in', page.contains('De-Dupe für diese Gruppe'))
check('duplicate evidence remains understandable', page.contains('Die Vorkommen liegen auf unterschiedlichen Structure-Pfaden.'))
check('only duplicate groups have finding checkboxes', (page =~ /class="finding"/).count == 1)
check('hierarchy findings have issue-specific cards', (page =~ /class="finding-card"/).count == 2)
check('no selection is prechecked', !(page =~ /<input[^>]+\schecked(?:\s|>)/).find())
check('selected structure is preserved', page.contains('value="9" selected'))
check('audit window is preserved', page.contains('max="365" value="7"'))
check('issue link has safe context-relative destination', page.contains('href="../../../../browse/DEMO-3"'))
def incomplete = analysis.copyWith(snapshot: snapshot.copyWith(complete: false),
    hierarchy: new HierarchyAnalysis(findings: [], complete: false, blockers: ['jira-hierarchy']),
    duplicates: new DuplicateAnalysis(groups: [], findings: [], complete: false, blockers: ['structure-snapshot']),
    coverage: coverage.collect { it.source == 'jira-hierarchy' ? it.copyWith(state: ReadState.UNAVAILABLE) : it })
String missing = renderer.render(catalogue, incomplete, null)
check('empty failed read does not claim no findings', missing.contains('Hierarchie nicht vollständig geprüft'))
check('empty failed duplicate read does not claim none', missing.contains('Duplikate nicht vollständig geprüft'))
String focused = renderer.render(catalogue, analysis.copyWith(issueKeyFilter: 'DEMO-4', displayIssueId: 4L), null)
check('display filter removes unrelated duplicate group', !focused.contains('class="finding"'))
check('display filter preserved', focused.contains('value="DEMO-4"'))
def titledRelations = relations.collect { it.copyWith(summary: 'Changed display title') }
check('display metadata does not change repair fingerprint',
    snapshot.planningFingerprint() == snapshot.copyWith(relations: titledRelations).planningFingerprint())
String fallback = renderer.render(catalogue, analysis.copyWith(snapshot: snapshot.copyWith(
    relations: relations.collect { it.copyWith(issueKey: null, summary: null, issueTypeName: null) })), null)
check('missing metadata is explicitly identified', fallback.contains('Vorgang #3 (Details nicht verfügbar)'))
def cappedCoverage = coverage.collect { it.source == 'automation-audit' ?
    it.copyWith(state: ReadState.COMPLETE, coverage: Coverage.bounded(7L, 2L, true, '2026-09-19', '2026-09-20')) : it }
String capped = renderer.render(catalogue, analysis.copyWith(coverage: cappedCoverage), null)
check('COMPLETE state with capped audit coverage remains unverified', capped.contains('Ausführungen nicht vollständig geprüft.'))
check('actual read coverage is visible', capped.contains('2 von 7') && capped.contains('begrenzt'))
String unnormalized = renderer.render(catalogue, analysis.copyWith(
    coverage: coverage.collect { it.source == 'automation-rules' ? it.copyWith(state: ReadState.COMPLETE) : it },
    automation: new AutomationAnalysis(findings: [], rules: [], complete: false, blockers: ['automation-rule-normalization'])), null)
check('unsupported rule normalization cannot report no conflicts', !unnormalized.contains('Keine Konflikte nach den implementierten Regelprüfungen erkannt.'))
if (System.getProperty('previewOutput')) new File(System.getProperty('previewOutput')).setText(page, 'UTF-8')
println "Structure Doctor renderer tests: ${passed} passed, ${failures.size()} failed"
failures.each { println 'FAIL: ' + it }
assert failures.isEmpty()
