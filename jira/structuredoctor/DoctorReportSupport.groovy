package structuredoctor

/** Display-only context. Never resolves additional issues or changes evidence. */
final class DoctorReportSupport {
    final DoctorAnalysis analysis
    final Map<Long, IssueRelationSnapshot> issues

    DoctorReportSupport(DoctorAnalysis analysis) {
        this.analysis = analysis
        this.issues = (analysis.snapshot?.relations ?: []).collectEntries { [(it.issueId): it] }
    }

    static String html(Object value) { CoreSupport.html(value) }

    String issue(Long id, boolean withTitle = false) {
        if (id == null) return 'Kein Jira-Parent erfasst'
        IssueRelationSnapshot item = issues[id]
        if (!item?.issueKey) return 'Vorgang #' + id + ' (Details nicht verf\u00FCgbar)'
        String link = '<a href="../../../../browse/' +
            java.net.URLEncoder.encode(item.issueKey, 'UTF-8') +
            '" target="_blank" rel="noopener noreferrer">' + html(item.issueKey) + '</a>'
        link + (withTitle && item.summary ? ' \u00B7 ' + html(item.summary) : '')
    }

    String metadata(long id) {
        IssueRelationSnapshot item = issues[id]
        HierarchyLevel level = levelFor(id)
        '<p class="note">Vorgangstyp: ' + html(item?.issueTypeName ?: 'nicht verf\u00FCgbar') +
            ' \u00B7 Jira-Ebene: ' + html(level?.name ?: 'nicht ermittelt') + '</p>'
    }

    HierarchyLevel levelFor(Long id) {
        Long typeId = issues[id]?.issueTypeId
        (analysis.snapshot?.hierarchy?.levels ?: []).find { it.issueTypeIds.contains(typeId) }
    }

    String expectedLevel(long id) {
        HierarchyLevel child = levelFor(id)
        HierarchyLevel parent = child == null ? null :
            (analysis.snapshot?.hierarchy?.levels ?: []).find { it.rank == child.rank + 1L }
        parent == null ? 'Keine \u00FCbergeordnete Ebene ermittelt' : html(parent.name)
    }

    String path(List<Long> parents, long childId) {
        ((parents ?: []) + [childId]).collect { issue(it) }.join(' <span aria-hidden="true"> &gt; </span> ')
    }

    String structureParent(Long id) {
        id == null ? 'Structure-Wurzel (kein \u00FCbergeordneter Vorgang)' : issue(id, true)
    }

    String provenance(String value, String creatorId) {
        Map labels = [PERMANENT: 'Dauerhafte Structure-Zeile', ADVANCED_ROADMAPS: 'Advanced-Roadmaps-Generator',
            JIRA_LINK: 'Jira-Link-Generator', GENERATOR: 'Generator', UNKNOWN: 'Herkunft nicht ermittelt']
        String result = html(labels[value] ?: value ?: 'Herkunft nicht ermittelt')
        if (creatorId) {
            GeneratorSnapshot generator = analysis.snapshot?.generators?.find {
                String.valueOf(it.generatorId) == creatorId
            }
            result += ' #' + html(creatorId)
            if (generator) result += ' <span class="note">(' + html(generator.moduleKey) + ')</span>'
        }
        result
    }

    boolean sourceComplete(String name) {
        SourceCoverage source = analysis.coverage?.find { it.source == name }
        source?.state == ReadState.COMPLETE && (source.coverage == null || source.coverage.complete())
    }

    String coverageTable() {
        Map names = ['jira-hierarchy': 'Globale Jira-Hierarchie', 'structure-snapshot': 'Structure und Generatoren',
            'jira-data': 'Jira-Vorg\u00E4nge und Elternbeziehungen', 'automation-rules': 'Automation-Regeln',
            'automation-audit': 'Automation-Ausf\u00FChrungen']
        Map states = [COMPLETE: 'Vollst\u00E4ndig gelesen', INCOMPLETE: 'Teilweise gelesen',
            UNAVAILABLE: 'Nicht verf\u00FCgbar', FAILED: 'Lesen fehlgeschlagen']
        String rows = (analysis.coverage ?: []).collect { SourceCoverage item ->
            boolean complete = sourceComplete(item.source)
            String status = item.state == ReadState.COMPLETE && !complete ? 'Teilweise gelesen' :
                states[item.state?.name()] ?: 'Unbekannt'
            String coverage = item.coverage == null ? '' : '<p>Abdeckung: ' + item.coverage.actual +
                ' von ' + item.coverage.requested + (item.coverage.capped ? ' (begrenzt)' : '') +
                '. Zeitraum: ' + html(item.coverage.fromInclusive ?: 'nicht angegeben') + ' bis ' +
                html(item.coverage.toInclusive ?: 'nicht angegeben') + '.</p>'
            '<tr><td>' + html(names[item.source] ?: item.source) + '</td><td>' +
                html(status) + '</td><td>' +
                html(item.provider == 'JSON_FALLBACK' ? 'JSON-Import' : item.provider) + '</td><td>' +
                (complete ? 'F\u00FCr diese Quelle vollst\u00E4ndig.' :
                    'Keine vollst\u00E4ndige Aussage m\u00F6glich.<details><summary>Technischer Grund</summary>' +
                    html(item.reason ?: 'Keine vollst\u00E4ndige Lesedeckung') + '</details>') + coverage + '</td></tr>'
        }.join('')
        '<table><thead><tr><th>Datenquelle</th><th>Lesestatus</th><th>Zugriff</th><th>Bedeutung</th></tr></thead><tbody>' + rows + '</tbody></table>'
    }

    static String blockers(List<String> values) {
        if (!values) return ''
        Map labels = ['automation-rules': 'Automation-Regeln nicht vollst\u00E4ndig gepr\u00FCft',
            'automation-audit': 'Ausf\u00FChrungsbelege fehlen', 'automation-audit-coverage': 'Auditzeitraum nicht vollst\u00E4ndig belegt',
            'jira-hierarchy': 'Jira-Hierarchie nicht vollst\u00E4ndig gepr\u00FCft', 'jira-data': 'Jira-Daten nicht vollst\u00E4ndig gelesen',
            'jira-relations': 'Elternbeziehungen nicht vollst\u00E4ndig gelesen', 'structure-snapshot': 'Structure-Abbild unvollst\u00E4ndig',
            'structure-forest': 'Structure-Zeilen nicht vollst\u00E4ndig gelesen', 'structure-generators': 'Generator-Konfiguration unvollst\u00E4ndig',
            'occurrence-provenance': 'Herkunft eines Vorkommens nicht belegt',
            'no-hierarchy-valid-occurrence': 'Kein Vorkommen mit passender direkter Hierarchiestufe erkannt',
            'physical-occurrence-identity': 'Structure-Zeile nicht eindeutig identifiziert',
            'proposal-source': 'Ermittlung ausf\u00FChrbarer Reparaturen nicht verf\u00FCgbar',
            'automation-rule-normalization': 'Nicht alle Regelbestandteile konnten ausgewertet werden',
            'stale-snapshot': 'Daten wurden ge\u00E4ndert. Bitte erneut analysieren.']
        '<details class="blockers"><summary>Offene Voraussetzungen (' + values.size() + ')</summary><ul>' +
            values.collect { '<li>' + html(labels[it] ?: 'Weitere Pr\u00FCfung erforderlich') +
                ' <code>(' + html(it) + ')</code></li>' }.join('') + '</ul></details>'
    }
}
