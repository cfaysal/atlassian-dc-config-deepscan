package structuredoctor

/** Human-readable observations and opt-in duplicate selection, not repair authority. */
final class DoctorFindingRenderer {
    private final DoctorReportSupport report

    DoctorFindingRenderer(DoctorReportSupport report) { this.report = report }

    String hierarchy(Finding finding) {
        Map descriptions = [
            ORPHAN: ['Kein Jira-Parent erfasst', 'Dieser Vorgang hat in den gelesenen Jira-Daten keine Elternbeziehung.',
                'Ein fehlender Parent ist nicht automatisch ein Fehler. Fachlich prüfen, ob eine Zuordnung erforderlich ist. Die globale Hierarchie definiert Ebenen, aber keine Pflicht zur Befüllung.'],
            WRONG_PATH: ['Abweichender Structure-Pfad', 'Dieses Vorkommen steht unter einem anderen Vorgang als dem gelesenen Jira-Parent.',
                'Prüfen, ob die abweichende Darstellung beabsichtigt ist. Falls die Structure die Jira-Hierarchie abbilden soll, den erzeugenden Generator und seine Linkrichtung prüfen. Bei Duplikaten das gewünschte Vorkommen in der Gruppe unten auswählen.'],
            MISSING_PARENT: ['Elternhinweis ohne Jira-Parent', 'Ein Elternkandidat ist vorhanden, aber kein nativer Jira-Parent erfasst.',
                'Den Kandidaten fachlich und anhand seiner Hierarchiestufe prüfen. Ein Link allein beweist nicht, dass dieser Vorgang als Jira-Parent gesetzt werden soll.'],
            CONFLICTING_PARENT: ['Widersprüchliche Elternbeziehungen', 'Die gelesenen Elternbeziehungen liefern mehrere oder abweichende Kandidaten.',
                'Festlegen, welche Beziehung fachlich maßgeblich ist. Danach Feldwerte, Links und gegebenenfalls schreibende Automation-Regeln vergleichen. Ohne Regel- und Ausführungsbelege bleibt die Ursache offen.'],
            INVALID_LEVEL: ['Jira-Parent auf abweichender Ebene', 'Der gelesene Jira-Parent liegt nicht genau eine konfigurierte Ebene über dem Vorgang.',
                'Vorgangstyp und Elternzuordnung anhand der bestehenden Jira-Hierarchie prüfen. Die globale Jira-Hierarchie wird niemals angepasst.']
        ]
        List text = descriptions[finding.type.name()] ?: [finding.type.name(), finding.summary, 'Die aufgeführten Belege fachlich prüfen.']
        IssueRelationSnapshot relation = report.issues[finding.issueId]
        List<OccurrenceSnapshot> occurrences = (report.analysis.snapshot?.occurrences ?: []).findAll {
            it.issueId == finding.issueId && (!finding.occurrenceIds || finding.occurrenceIds.contains(it.occurrenceId))
        }
        String paths = occurrences.collect { occurrence ->
            '<div class="occurrence"><p><strong>Parent in der Structure (Ist):</strong> ' +
                report.structureParent(occurrence.parentIssueId) + '</p><p><strong>Structure-Pfad:</strong> ' +
                report.path(occurrence.parentPath, finding.issueId) + '</p><p><strong>Quelle:</strong> ' +
                report.provenance(occurrence.provenance, occurrence.creatorId) +
                ' · Zeile <code>' + DoctorReportSupport.html(occurrence.rowId) + '</code></p></div>'
        }.join('')
        '<article class="finding-card"><h3>' + DoctorReportSupport.html(text[0]) + '</h3><p class="issue-title">' +
            report.issue(finding.issueId, true) + '</p>' + report.metadata(finding.issueId) +
            '<p>' + DoctorReportSupport.html(text[1]) + '</p><p><strong>Jira-Parent (Soll für die Structure):</strong> ' +
            (relation == null ? 'Nicht ermittelt' : report.issue(relation.nativeParentId, true)) +
            '</p><p><strong>Übergeordnete Jira-Ebene:</strong> ' + report.expectedLevel(finding.issueId) + '</p>' +
            (relation?.leadingParentIds ? '<p><strong>Gelesene Elternkandidaten:</strong> ' +
                relation.leadingParentIds.collect { report.issue(it) }.join(', ') + '</p>' : '') +
            (paths ?: '<p class="note">Nur als Jira-Vorfahre mitgelesen, kein eigenes Vorkommen in dieser Structure.</p>') +
            '<div class="advice"><strong>Prüfschritt, keine automatische Reparatur</strong><p>' +
            DoctorReportSupport.html(text[2]) + '</p></div>' + DoctorReportSupport.blockers(finding.blockers) + '</article>'
    }

    String duplicate(DuplicateGroup group) {
        String id = DoctorReportSupport.html(group.id)
        boolean hierarchyKnown = report.sourceComplete('jira-hierarchy') && report.sourceComplete('jira-data')
        String choices = group.occurrences.collect { DuplicateOccurrence occurrence ->
            String occurrenceId = DoctorReportSupport.html(occurrence.occurrenceId)
            String permanent = occurrence.provenance == 'PERMANENT' ?
                '<label class="choice"><input class="permanent-row" type="checkbox" disabled data-occurrence="' +
                    occurrenceId + '" value="' + DoctorReportSupport.html(occurrence.rowId) +
                    '"><span>Entfernung dieser dauerhaften Structure-Zeile für den Plan freigeben. Der Jira-Vorgang bleibt erhalten.</span></label>' : ''
            '<div class="occurrence"><label class="choice"><input type="radio" disabled name="retain-' + id +
                '" value="' + occurrenceId + '"><span>Vorkommen ' + occurrence.ordinal + ' behalten' +
                (occurrence.recommended && hierarchyKnown ? ' <span class="badge">Empfehlung</span>' : '') +
                '</span></label><p><strong>Structure-Pfad:</strong> ' + report.path(occurrence.parentPath, group.issueId) +
                '</p><p><strong>Parent in der Structure (Ist):</strong> ' + report.structureParent(occurrence.parentIssueId) +
                '</p><p><strong>Quelle:</strong> ' + report.provenance(occurrence.provenance, occurrence.creatorId) +
                ' · Zeile <code>' + DoctorReportSupport.html(occurrence.rowId) + '</code></p>' +
                '<p>Direkte Hierarchiestufe: <strong>' + hierarchyMatch(hierarchyKnown, occurrence) +
                '</strong>. Übereinstimmung mit Jira-Parent: <strong>' +
                parentMatch(group.issueId, occurrence) + '</strong>.</p>' + permanent + '</div>'
        }.join('')
        '<article class="duplicate-card" data-group="' + id + '"><h3>' + report.issue(group.issueId, true) +
            '</h3>' + report.metadata(group.issueId) + '<p><strong>' + group.occurrences.size() +
            ' Vorkommen desselben Jira-Vorgangs</strong>, keine mehrfach angelegten Jira-Vorgänge.</p>' +
            '<p><strong>Jira-Parent (Soll für die Structure):</strong> ' +
            (report.issues[group.issueId] == null ? 'Nicht ermittelt' : report.issue(report.issues[group.issueId].nativeParentId, true)) + '</p>' +
            duplicateEvidence(group) +
            '<label class="choice"><input class="finding" type="checkbox" value="' + id + '"' +
            (group.selectable ? '' : ' disabled') + '><span>De-Dupe für diese Gruppe</span></label>' +
            '<p class="note">Nur aktivierte Gruppen werden berücksichtigt. Wählen Sie genau das Vorkommen, das bleiben soll. ' +
            'Die übrigen Vorkommen sollen aus der Structure verschwinden, nicht aus Jira. ' +
            'Bei generierten Zeilen kann dies eine ausdrücklich zu bestätigende Änderung der Structure-Generatoren erfordern. ' +
            'Diese Auswahl führt noch keine Änderung aus.</p>' + choices +
            DoctorReportSupport.blockers(group.blockers) + '</article>'
    }

    private String parentMatch(long issueId, DuplicateOccurrence occurrence) {
        if (!report.sourceComplete('jira-data') || report.issues[issueId] == null) return 'nicht geprüft'
        if (report.issues[issueId].nativeParentId == null) return 'kein Jira-Parent erfasst'
        occurrence.nativeHierarchy ? 'ja' : 'nein'
    }

    private static String hierarchyMatch(boolean known, DuplicateOccurrence occurrence) {
        if (!known) return 'nicht geprüft'
        occurrence.hierarchyValid ? 'passt' : 'passt nicht'
    }

    private String duplicateEvidence(DuplicateGroup group) {
        Map translations = [
            'Multiple generators render this work item': 'Mehrere Generatoren erzeugen Vorkommen dieses Vorgangs.',
            'Occurrences follow multiple paths': 'Die Vorkommen liegen auf unterschiedlichen Structure-Pfaden.',
            'Permanent and generated occurrences overlap': 'Dauerhafte und durch Generatoren erzeugte Zeilen überschneiden sich.',
            'Native hierarchy and Jira link paths overlap': 'Advanced-Roadmaps- und Jira-Link-Pfade überschneiden sich.',
            'No enabled duplicates filter is present': 'In der gelesenen Konfiguration wurde kein aktiver Duplikatfilter erkannt.',
            'Duplicates filter runs before an occurrence source': 'Ein Duplikatfilter steht vor einer Quelle weiterer Vorkommen.',
            'Semantically identical occurrences remain separate physical rows': 'Gleicher Pfad und gleiche Quelle, aber unterschiedliche Structure-Zeilen.'
        ]
        '<details><summary>Beobachtungen zu dieser Gruppe</summary><ul>' + (group.explanations ?: []).collect {
            '<li>' + DoctorReportSupport.html(translations[it] ?: it) + '</li>'
        }.join('') + '</ul><p class="note">Diese Beobachtungen erklären die Darstellung, belegen allein aber keine schreibende Automation als Ursache. Bei unvollständigen Quellen sind sie vorläufig.</p></details>'
    }
}
