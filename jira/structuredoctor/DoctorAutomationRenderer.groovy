package structuredoctor

/** Read evidence only. An associated issue is not proof of a particular field change. */
final class DoctorAutomationRenderer {
    static String evidence(DoctorReportSupport report) {
        DoctorAnalysis analysis = report.analysis
        List<AutomationRuleSnapshot> rules = analysis.automation?.rules ?: []
        List<AutomationAuditSnapshot> audit = (analysis.auditEntries ?: []).findAll {
            analysis.displayIssueId == null || it.issueId == analysis.displayIssueId
        }
        String ruleCount = report.sourceComplete('automation-rules') ?
            rules.size() + ' aktive Regeln im gelesenen Analyseumfang.' :
            'Gesamtzahl aktiver Regeln unbekannt; bisher ' + rules.size() + ' Regeln aus dem Teilergebnis verfügbar.'
        String auditCount = report.sourceComplete('automation-audit') ?
            audit.size() + ' protokollierte Regel-/Vorgangszuordnungen in dieser Anzeige.' :
            'Ausführungshistorie unvollständig; bisher ' + audit.size() +
                ' Zuordnungen verfügbar. Das ist kein Nachweis für ausgebliebene Ausführungen.'
        String ruleRows = rules.collect { AutomationRuleSnapshot rule ->
            '<tr><td>' + rule.ruleId + '</td><td>' + CoreSupport.html(rule.trigger) +
                '</td><td>' + CoreSupport.html((rule.projectIds ?: []).join(', ') ?: 'Global') +
                '</td><td>' + (rule.complete ? 'Nach unterstütztem Schema auswertbar' :
                    'Nicht vollständig auswertbar: Aktion oder Script benötigt eine gesonderte Codeprüfung') + '</td></tr>'
        }.join('')
        String auditRows = audit.sort(false) { a, b -> b.occurredAt <=> a.occurredAt }.take(20).collect { item ->
            '<tr><td>' + CoreSupport.html(item.occurredAt) + '</td><td>' + item.ruleId + '</td><td>' +
                report.issue(item.issueId) + '</td><td>' + (item.successful ? 'Erfolg protokolliert' :
                    'Kein erfolgreicher Abschluss belegt') + '</td></tr>'
        }.join('')
        '<p><strong>' + ruleCount + '</strong> ' +
            'Globale Regeln werden mitgelesen. Eine Regel kann weitere Projekte oder Vorgänge betreffen. ' +
            'Unbekannte Aktionen und eingebettete Groovy-Scripte werden niemals ausgeführt und nicht als unproblematisch bewertet.</p>' +
            (ruleRows ? '<details><summary>Gelesene aktive Regeln</summary><table><thead><tr><th>Regel-ID</th><th>Auslöser</th><th>Projekt-IDs</th><th>Auswertbarkeit</th></tr></thead><tbody>' + ruleRows + '</tbody></table></details>' : '') +
            '<p><strong>' + auditCount + '</strong> ' +
            'Angezeigt werden höchstens 20 der gelesenen Zuordnungen, nach Zeitpunkt sortiert. Eine Zuordnung belegt keine konkrete Parent-Änderung und keine Ursache des Structure-Problems. ' +
            'Gelesen werden vorhandene Protokolle innerhalb der konfigurierten Aufbewahrung; bereits gelöschte Historie ist nicht rekonstruierbar.</p>' +
            (auditRows ? '<details open><summary>Ausführungsbelege</summary><table><thead><tr><th>Zeitpunkt (UTC)</th><th>Regel-ID</th><th>Vorgang</th><th>Ergebnis</th></tr></thead><tbody>' + auditRows + '</tbody></table></details>' : '')
    }
}
