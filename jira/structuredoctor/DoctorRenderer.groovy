package structuredoctor

final class DoctorRenderer {
    String render(ReadResult<List<StructureChoice>> structures,
                  DoctorAnalysis analysis,
                  ProposalPlan plan) {
        String options = (structures?.value ?: []).collect { StructureChoice item ->
            '<option value="' + item.id + '"' + (analysis?.structureId == item.id ? ' selected' : '') + '>' + CoreSupport.html(item.name) +
                ' (#' + item.id + ')</option>'
        }.join('\n')
        String catalogProblem = structures?.complete() ? '' : message(
            'Structure-Liste unvollst\u00e4ndig', structures?.reason ?: 'Keine Lesedeckung')
        String analysisHtml = analysis == null ? '' : renderAnalysis(analysis)
        String planHtml = plan == null ? '' : renderPlan(plan)
        """<!doctype html>
<html lang="de"><head><meta charset="utf-8"><title>Structure Doctor</title>
<style>
body{font:14px Arial,sans-serif;color:#172b4d;background:#f4f5f7;margin:0;padding:28px}
main{max-width:1180px;margin:auto}.card{background:#fff;border:1px solid #dfe1e6;border-radius:6px;padding:20px;margin:0 0 18px}
h1,h2{margin-top:0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}
label{display:block;font-weight:600;margin:10px 0 5px}select,input:not([type=checkbox]):not([type=radio]){box-sizing:border-box;width:100%;padding:9px;border:1px solid #7a869a;border-radius:3px}
.choice{display:flex;align-items:flex-start;gap:10px;line-height:1.5}.choice input{flex:none;width:18px;height:18px;margin:2px 0}
.finding-card,.duplicate-card{border:1px solid #dfe1e6;border-radius:6px;padding:18px;margin:16px 0}.occurrence{border-left:3px solid #b3d4ff;padding:1px 14px;margin:14px 0;background:#f8f9fc}
.advice{background:#eef4ff;padding:12px;border-radius:4px}.advice p{margin-bottom:0}.badge{font-size:12px;color:#44546f;font-weight:normal}.issue-title{font-size:16px}a{color:#0052cc}h3{margin:0 0 12px}
button{margin-top:14px;background:#0052cc;color:#fff;border:0;border-radius:3px;padding:10px 14px;font-weight:600}button:disabled{background:#6b778c}
table{width:100%;border-collapse:collapse}th,td{border:1px solid #dfe1e6;padding:7px;text-align:left;vertical-align:top}
.warning{border-left:5px solid #ffab00}.error{border-left:5px solid #de350b}.note{color:#44546f}.blockers{color:#ae2a19}
code{word-break:break-all}details{margin:10px 0}summary{cursor:pointer;font-weight:600}
</style></head><body><main>
<section class="card"><h1>Structure Doctor</h1>
<p>W\u00e4hlen Sie eine Structure. Die vollst\u00e4ndige Analyse startet erst mit <strong>Structure analysieren</strong>. Ein Work-Item-Key ist optional und filtert nur die Anzeige.</p>
${catalogProblem}
<div class="grid"><div><label for="structureId">Structure</label><select id="structureId" required><option value="">Structure w\u00e4hlen</option>${options}</select></div>
<div><label for="issueKeyFilter">Work-Item-Key (optional)</label><input id="issueKeyFilter" placeholder="DEMO-123" value="${CoreSupport.html(analysis?.issueKeyFilter ?: '')}"></div>
<div><label for="auditDays">Automation-Audit in Tagen</label><input id="auditDays" type="number" min="1" max="365" value="${analysis?.requestedAuditDays ?: 30}"></div>
<div><label for="ruleExportFile">Automation-Regeln (JSON, optional)</label><input id="ruleExportFile" type="file" accept="application/json,.json"><p class="note">Offizieller Jira-Automation-Regel-Export. Der Inhalt wird nur gelesen und niemals ausgef\u00fchrt.</p></div>
<div><label for="auditExportFile">Structure-Doctor Audit-Beleg (JSON, optional)</label><input id="auditExportFile" type="file" accept="application/json,.json"><p class="note">Optionales normalisiertes Doctor-Format f\u00fcr die zeitliche Ursachenanalyse, kein Automation-Regel-Export.</p></div></div>
<button id="analyzeButton" type="button">Structure analysieren</button>
</section>
${analysisHtml}${planHtml}
<section class="card warning"><h2>Änderungen</h2><p><strong>Automatische Reparaturen sind deaktiviert.</strong> Die Auswahl prüft die Voraussetzungen eines Plans. Die Live-Ermittlung ausführbarer Reparaturen ist noch nicht angebunden. Die Jira-Hierarchie und Automation-Regeln werden niemals verändert.</p><button disabled>Ausgewählte Reparaturen anwenden</button></section>
<script>${browserScript()}</script>
</main></body></html>"""
    }

    private static String renderAnalysis(DoctorAnalysis analysis) {
        DoctorReportSupport report = new DoctorReportSupport(analysis)
        DoctorFindingRenderer findings = new DoctorFindingRenderer(report)
        List<Finding> visibleHierarchy = (analysis.hierarchy?.findings ?: []).findAll {
            Finding finding -> analysis.displayIssueId == null ||
                finding.issueId == analysis.displayIssueId
        }
        List<DuplicateGroup> visibleDuplicates = (analysis.duplicates?.groups ?: []).findAll {
            DuplicateGroup group -> analysis.displayIssueId == null ||
                group.issueId == analysis.displayIssueId
        }
        Set<String> visibleFindingIds = new LinkedHashSet<String>()
        visibleFindingIds.addAll(visibleHierarchy*.id)
        visibleFindingIds.addAll((analysis.duplicates?.findings ?: []).findAll {
            analysis.displayIssueId == null || it.issueId == analysis.displayIssueId
        }*.id)
        String hierarchyItems = visibleHierarchy.collect { Finding finding ->
            findings.hierarchy(finding)
        }.join('\n')
        String duplicateItems = visibleDuplicates.collect { DuplicateGroup group ->
            findings.duplicate(group)
        }.join('\n')
        String automationItems = (analysis.automation?.findings ?: []).collect {
            AutomationFinding finding ->
                '<li><strong>' + CoreSupport.html(finding.type.name()) + '</strong>: ' +
                    CoreSupport.html(finding.summary) + ' (Regeln ' +
                    CoreSupport.html(finding.ruleIds.join(', ')) + ')</li>'
        }.join('\n')
        String automationStatus
        if (!report.sourceComplete('automation-rules')) {
            automationStatus = '<p><strong>Nicht geprüft: Automation-Regeln.</strong> Aus fehlenden Daten folgt nicht, dass keine Konflikte existieren. Ein offizieller JSON-Regel-Export kann oben zur Analyse ergänzt werden.</p>'
        } else if (analysis.automation == null || (analysis.automation.blockers ?: []).any {
            !(it in ['automation-audit', 'automation-audit-coverage'])
        }) {
            automationStatus = '<p><strong>Automation-Regeln nicht vollständig ausgewertet.</strong> Vorhandene Hinweise sind ein Teilergebnis, keine Entwarnung.</p>'
        } else {
            automationStatus = '<p>Regelkonfiguration gelesen. ' + (automationItems ? 'Hinweise siehe unten.' : 'Keine Konflikte nach den implementierten Regelprüfungen erkannt.') + '</p>'
        }
        if (!report.sourceComplete('automation-audit')) automationStatus +=
            '<p><strong>Ausführungen nicht vollständig geprüft.</strong> Ob und wann eine Regel diese Vorgänge verändert hat, ist nicht belegt. Das Auditfenster ist eine Anfrage, kein Nachweis vollständiger Protokolle.</p>'
        String claims = (analysis.causalClaims ?: []).findAll { CausalClaim claim ->
            visibleFindingIds.contains(claim.findingId)
        }.collect { CausalClaim claim ->
            Finding related = ((analysis.hierarchy?.findings ?: []) + (analysis.duplicates?.findings ?: [])).find { it.id == claim.findingId }
            '<li>' + (related == null ? '' : report.issue(related.issueId) + ': ') + '<strong>' +
                CoreSupport.html([CONFIGURATION_CONFLICT: 'Konfigurationskonflikt', POSSIBLE_CAUSE: 'Mögliche Ursache, nicht nachgewiesen',
                    PROBABLE_CAUSE: 'Wahrscheinliche Ursache, nicht bestätigt', CONFIRMED_CAUSE: 'Bestätigte Ursache'][claim.grade.name()]) +
                '</strong>; fehlende Belege: ' +
                CoreSupport.html(claim.missingEvidence.join(', ')) + '</li>'
        }.join('\n')
        """<section class="card" data-snapshot-id="${CoreSupport.html(analysis.snapshotId)}">
<h2>Analyse der Structure #${analysis.structureId}</h2>
<p><strong>${analysis.complete ? 'Alle vorgesehenen Prüfbereiche vollständig ausgewertet.' : 'Teilergebnis: Nicht alle Prüfbereiche konnten ausgewertet werden.'}</strong> Angefragtes Auditfenster: ${analysis.requestedAuditDays} Tage.</p>
<p>${visibleHierarchy.size()} angezeigte Hierarchie-Hinweise · ${visibleDuplicates.size()} angezeigte Duplikatgruppen. Gelesene Structure-Vorkommen: ${analysis.snapshot?.occurrences?.size() ?: 0}.</p>
<p class="note">Ein Hinweis ist keine bestätigte Ursache. Die Lesestatus unten zeigen, welche Daten verfügbar waren, nicht ob die Structure fehlerfrei ist. „Soll“ bezeichnet die Ausrichtung an der gelesenen Jira-Elternbeziehung, keine automatische Änderungsfreigabe.</p>
${analysis.issueKeyFilter ? '<p>Anzeigefilter: ' + CoreSupport.html(analysis.issueKeyFilter) + '</p>' : ''}
${blockers(analysis.blockers)}
<details open><summary>Datenquellen und Prüfgrenzen</summary>${report.coverageTable()}</details>
<details open><summary>Hierarchie-Hinweise</summary>${analysis.hierarchy?.complete ? '' : '<p class="blockers">Hierarchie nicht vollständig geprüft. Aufgeführte Hinweise sind ein Teilergebnis.</p>'}<div>${hierarchyItems ?: '<p>Keine Hinweise in dieser Anzeige. Bei unvollständiger Prüfung ist das keine Entwarnung.</p>'}</div></details>
<details open><summary>Duplikate: Vorkommen vergleichen und Auswahl treffen</summary>${analysis.duplicates?.complete ? '' : '<p class="blockers">Duplikate nicht vollständig geprüft. Die Anzeige kann unvollständig sein.</p>'}<div>${duplicateItems ?: '<p>Keine Duplikatgruppen in dieser Anzeige. Der Anzeigefilter und die Lesedeckung sind zu beachten.</p>'}</div></details>
<details open><summary>Automation-Prüfung</summary>${automationStatus}<ul>${automationItems}</ul></details>
<details><summary>Ursachen und Beleglage</summary><ul>${claims ?: '<li>Keine vollständige Ursachenkette belegt.</li>'}</ul></details>
${visibleDuplicates ? '<button id="planButton" type="button">Duplikatauswahl prüfen</button>' : ''}</section>"""
    }

    private static String renderPlan(ProposalPlan plan) {
        String packages = (plan.packages ?: []).collect { RepairPackage item ->
            '<article><h3>' + CoreSupport.html(item.strategy.name()) + '</h3><p>' +
                CoreSupport.html(item.explanation) + '</p><p>Wirkung: Work Items ' +
                CoreSupport.html(item.affectedIssueIds.join(', ')) + '; Generatoren ' +
                CoreSupport.html(item.generatorIds.join(', ')) + '</p><p>Warnungen: ' +
                CoreSupport.html(item.warnings.join(' ')) + '</p>' + blockers(item.blockers) +
                '</article>'
        }.join('\n')
        '<section class="card"><h2>Reparaturplan</h2>' + blockers(plan.blockers) +
            (packages ?: '<p>Keine ausf\u00fchrbare Reparatur ausgew\u00e4hlt.</p>') + '</section>'
    }

    private static String blockers(List<String> values) {
        DoctorReportSupport.blockers(values)
    }

    private static String message(String title, String body) {
        '<section class="card error"><strong>' + CoreSupport.html(title) +
            '</strong><p>' + CoreSupport.html(body) + '</p></section>'
    }

    private static String browserScript() {
        '''
const post = async (endpoint, payload) => {
  const response = await fetch(window.location.pathname.replace(/structureIssueDoctor(?:Analyze|Plan)?\\/?$/, endpoint), {
    method: 'POST', credentials: 'same-origin',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(payload)
  });
  const text = await response.text();
  if (!response.ok) throw new Error(text);
  document.open(); document.write(text); document.close();
};
const readExport = async id => {
  const file = document.getElementById(id).files[0];
  if (!file) return null;
  if (file.size > 5242880) throw new Error('Die JSON-Datei ist gr\u00f6sser als 5 MiB.');
  return file.text();
};
document.getElementById('analyzeButton').addEventListener('click', async () => {
  const button = document.getElementById('analyzeButton');
  button.disabled = true;
  try {
    await post('structureIssueDoctorAnalyze', {
    structureId: document.getElementById('structureId').value,
    issueKeyFilter: document.getElementById('issueKeyFilter').value || null,
    requestedAuditDays: Number(document.getElementById('auditDays').value),
    ruleExportRef: null, auditExportRef: null,
    ruleExportJson: await readExport('ruleExportFile'),
    auditExportJson: await readExport('auditExportFile')
    });
  } catch (error) {
    window.alert(error.message || String(error));
    button.disabled = false;
  }
});
const planButton = document.getElementById('planButton');
document.querySelectorAll('.duplicate-card').forEach(group => {
  const enabled = group.querySelector('.finding');
  const update = () => {
    const active = enabled.checked && !enabled.disabled;
    const retained = group.querySelector('input[type=radio]:checked');
    group.querySelectorAll('input[type=radio],.permanent-row').forEach(input => {
      input.disabled = !active || (input.classList.contains('permanent-row') && retained && input.dataset.occurrence === retained.value);
      if (input.disabled) input.checked = false;
    });
  };
  group.addEventListener('change', update);
  update();
});
if (planButton) planButton.addEventListener('click', async () => {
  planButton.disabled = true;
  try {
  const snapshotId = document.querySelector('[data-snapshot-id]').dataset.snapshotId;
  const selected = Array.from(document.querySelectorAll('.finding:checked:not(:disabled)'));
  if (!selected.length) throw new Error('Bitte mindestens eine Duplikatgruppe aktivieren.');
  const findingGroupIds = selected.map(x => x.value);
  const selectedPermanentRowIds = [];
  const retainOccurrenceByGroup = {};
  selected.forEach(input => {
    const group = input.closest('.duplicate-card');
    const chosen = group.querySelector('input[type=radio]:checked:not(:disabled)');
    if (!chosen) throw new Error('Für jede aktivierte Gruppe ein Vorkommen zum Behalten wählen.');
    retainOccurrenceByGroup[input.value] = chosen.value;
    group.querySelectorAll('.permanent-row:checked:not(:disabled)').forEach(row => selectedPermanentRowIds.push(row.value));
  });
  await post('structureIssueDoctorPlan', {
    snapshotId, findingGroupIds, retainOccurrenceByGroup, selectedPermanentRowIds
  });
  } catch (error) {
    window.alert(error.message || String(error));
    planButton.disabled = false;
  }
});
'''
    }
}
