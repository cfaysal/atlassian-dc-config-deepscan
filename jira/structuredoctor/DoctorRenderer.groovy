package structuredoctor

final class DoctorRenderer {
    String render(ReadResult<List<StructureChoice>> structures,
                  DoctorAnalysis analysis,
                  ProposalPlan plan) {
        String options = (structures?.value ?: []).collect { StructureChoice item ->
            '<option value="' + item.id + '">' + CoreSupport.html(item.name) +
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
label{display:block;font-weight:600;margin:10px 0 5px}select,input{box-sizing:border-box;width:100%;padding:9px;border:1px solid #7a869a;border-radius:3px}
button{margin-top:14px;background:#0052cc;color:#fff;border:0;border-radius:3px;padding:10px 14px;font-weight:600}button:disabled{background:#6b778c}
table{width:100%;border-collapse:collapse}th,td{border:1px solid #dfe1e6;padding:7px;text-align:left;vertical-align:top}
.warning{border-left:5px solid #ffab00}.error{border-left:5px solid #de350b}.note{color:#44546f}.blockers{color:#ae2a19}
code{word-break:break-all}details{margin:10px 0}summary{cursor:pointer;font-weight:600}
</style></head><body><main>
<section class="card"><h1>Structure Doctor</h1>
<p>W\u00e4hlen Sie eine Structure. Die vollst\u00e4ndige Analyse startet erst mit <strong>Structure analysieren</strong>. Ein Work-Item-Key ist optional und filtert nur die Anzeige.</p>
${catalogProblem}
<div class="grid"><div><label for="structureId">Structure</label><select id="structureId" required><option value="">Structure w\u00e4hlen</option>${options}</select></div>
<div><label for="issueKeyFilter">Work-Item-Key (optional)</label><input id="issueKeyFilter" placeholder="DEMO-123"></div>
<div><label for="auditDays">Automation-Audit in Tagen</label><input id="auditDays" type="number" min="1" max="365" value="30"></div>
<div><label for="ruleExportFile">Automation-Regeln (JSON, optional)</label><input id="ruleExportFile" type="file" accept="application/json,.json"><p class="note">Offizieller Jira-Automation-Regel-Export. Der Inhalt wird nur gelesen und niemals ausgef\u00fchrt.</p></div>
<div><label for="auditExportFile">Structure-Doctor Audit-Beleg (JSON, optional)</label><input id="auditExportFile" type="file" accept="application/json,.json"><p class="note">Optionales normalisiertes Doctor-Format f\u00fcr die zeitliche Ursachenanalyse, kein Automation-Regel-Export.</p></div></div>
<button id="analyzeButton" type="button">Structure analysieren</button>
</section>
${analysisHtml}${planHtml}
<section class="card warning"><h2>\u00c4nderungen</h2><p><strong>Apply is disabled.</strong> Diese Version analysiert und plant nur. Die Jira-Hierarchie und Automation-Regeln werden niemals ver\u00e4ndert.</p><button disabled>Ausgew\u00e4hlte Reparaturen anwenden</button></section>
<script>${browserScript()}</script>
</main></body></html>"""
    }

    private static String renderAnalysis(DoctorAnalysis analysis) {
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
        visibleFindingIds.addAll(visibleDuplicates*.id)
        String coverageRows = analysis.coverage.collect { SourceCoverage item ->
            '<tr><td>' + CoreSupport.html(item.source) + '</td><td>' + item.state +
                '</td><td>' + CoreSupport.html(item.provider) + '</td><td>' +
                CoreSupport.html(item.reason ?: 'vollstaendig') + '</td></tr>'
        }.join('\n')
        String hierarchyItems = visibleHierarchy.collect { Finding finding ->
            findingItem(finding.id, finding.type.name(), finding.summary, finding.blockers)
        }.join('\n')
        String duplicateItems = visibleDuplicates.collect { DuplicateGroup group ->
            String choices = group.occurrences.collect { DuplicateOccurrence occurrence ->
                String permanent = occurrence.provenance == 'PERMANENT' ?
                    '<label><input class="permanent-row" type="checkbox" value="' +
                        CoreSupport.html(occurrence.rowId) + '"> Dauerhafte Zeile f\u00fcr eine Entfernung freigeben</label>' : ''
                '<label><input type="radio" name="retain-' + CoreSupport.html(group.id) +
                    '" value="' + CoreSupport.html(occurrence.occurrenceId) + '"> ' +
                    'Vorkommen ' + occurrence.ordinal + ', Pfad ' +
                    CoreSupport.html(occurrence.parentPath.join(' / ')) + ', Quelle ' +
                    CoreSupport.html(occurrence.provenance) + ', Jira-Parent #' +
                    CoreSupport.html(occurrence.parentIssueId) + ', Hierarchie g\u00fcltig: ' +
                    occurrence.hierarchyValid + ', native Relation: ' + occurrence.nativeHierarchy +
                    (occurrence.recommended ? ' (empfohlen)' : '') + '</label>' + permanent
            }.join('\n')
            '<article><label><input class="finding" type="checkbox" value="' +
                CoreSupport.html(group.id) + '"> Duplicate-Gruppe fuer Work Item #' +
                group.issueId + '</label><p>' + CoreSupport.html(group.explanations.join(' ')) +
                '</p>' + choices + blockers(group.blockers) + '</article>'
        }.join('\n')
        String automationItems = (analysis.automation?.findings ?: []).collect {
            AutomationFinding finding ->
                '<li><strong>' + CoreSupport.html(finding.type.name()) + '</strong>: ' +
                    CoreSupport.html(finding.summary) + ' (Regeln ' +
                    CoreSupport.html(finding.ruleIds.join(', ')) + ')</li>'
        }.join('\n')
        String claims = (analysis.causalClaims ?: []).findAll { CausalClaim claim ->
            visibleFindingIds.contains(claim.findingId)
        }.collect { CausalClaim claim ->
            '<li><strong>' + CoreSupport.html(claim.grade.name()) + '</strong>: ' +
                CoreSupport.html(claim.findingId) + '; fehlende Belege: ' +
                CoreSupport.html(claim.missingEvidence.join(', ')) + '</li>'
        }.join('\n')
        """<section class="card" data-snapshot-id="${CoreSupport.html(analysis.snapshotId)}">
<h2>Analyse der Structure #${analysis.structureId}</h2>
<p>Status: <strong>${analysis.complete ? 'vollst\u00e4ndig' : 'unvollst\u00e4ndig'}</strong>. Auditfenster: ${analysis.requestedAuditDays} Tage.</p>
${analysis.issueKeyFilter ? '<p>Anzeigefilter: ' + CoreSupport.html(analysis.issueKeyFilter) + '</p>' : ''}
${blockers(analysis.blockers)}
<details open><summary>Quelldeckung</summary><table><thead><tr><th>Quelle</th><th>Status</th><th>Provider</th><th>Erl\u00e4uterung</th></tr></thead><tbody>${coverageRows}</tbody></table></details>
<details open><summary>Hierarchie-Befunde</summary><div>${hierarchyItems ?: '<p>Keine belegten Befunde.</p>'}</div></details>
<details open><summary>Duplicate-Gruppen und Retain-Auswahl</summary><div>${duplicateItems ?: '<p>Keine Duplicate-Gruppen.</p>'}</div></details>
<details><summary>Automation-Konflikte</summary><ul>${automationItems ?: '<li>Keine belegten Konflikte.</li>'}</ul></details>
<details><summary>Ursachen und Evidenzgrade</summary><ul>${claims ?: '<li>Keine vollstaendige Ursachenkette belegt.</li>'}</ul></details>
<button id="planButton" type="button">Auswahl planen</button></section>"""
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

    private static String findingItem(String id, String type, String summary,
                                      List<String> itemBlockers) {
        '<article><label><input class="finding" type="checkbox" value="' +
            CoreSupport.html(id) + '"> ' + CoreSupport.html(type) + '</label><p>' +
            CoreSupport.html(summary) + '</p>' + blockers(itemBlockers) + '</article>'
    }

    private static String blockers(List<String> values) {
        values ? '<p class="blockers">Blockiert durch: ' +
            CoreSupport.html(values.join(', ')) + '</p>' : ''
    }

    private static String message(String title, String body) {
        '<section class="card error"><strong>' + CoreSupport.html(title) +
            '</strong><p>' + CoreSupport.html(body) + '</p></section>'
    }

    private static String browserScript() {
        '''
const post = async (endpoint, payload) => {
  const response = await fetch(window.location.pathname.replace(/structureIssueDoctor$/, endpoint), {
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
if (planButton) planButton.addEventListener('click', () => {
  const snapshotId = document.querySelector('[data-snapshot-id]').dataset.snapshotId;
  const findingGroupIds = Array.from(document.querySelectorAll('.finding:checked')).map(x => x.value);
  const selectedPermanentRowIds = Array.from(document.querySelectorAll('.permanent-row:checked')).map(x => x.value);
  const retainOccurrenceByGroup = {};
  findingGroupIds.forEach(id => {
    const chosen = document.querySelector(`input[name="retain-${id}"]:checked`);
    if (chosen) retainOccurrenceByGroup[id] = chosen.value;
  });
  post('structureIssueDoctorPlan', {
    snapshotId, findingGroupIds, retainOccurrenceByGroup, selectedPermanentRowIds
  });
});
'''
    }
}
