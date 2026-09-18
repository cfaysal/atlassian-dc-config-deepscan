import com.almworks.jira.structure.api.StructureComponents
import com.almworks.jira.structure.api.forest.ForestSpec
import com.almworks.jira.structure.api.forest.raw.Forest
import com.almworks.jira.structure.api.generator.CoreGeneratorParameters
import com.almworks.jira.structure.api.generator.CoreStructureGenerators
import com.almworks.jira.structure.api.item.CoreIdentities
import com.almworks.jira.structure.api.item.ItemIdentity
import com.almworks.jira.structure.api.permissions.PermissionLevel
import com.almworks.jira.structure.api.row.StructureRow
import com.almworks.jira.structure.api.row.TransientRow
import com.almworks.jira.structure.api.structure.Structure
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.permission.ProjectPermissions
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.security.PermissionManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.util.ErrorCollection
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import org.codehaus.groovy.runtime.InvokerHelper

class ParentUpdateCheck {
    boolean valid
    IssueService.UpdateValidationResult validationResult
    Map<String, Object> errors = [:]
    List<Map<String, Object>> attempts = []
}

class InspectionResult {
    int status
    Map<String, Object> payload
}

/**
 * Jira Data Center / ScriptRunner Custom REST Endpoint
 *
 * Browser UI:
 *   GET /rest/scriptrunner/latest/custom/structureIssueDoctor
 *
 * JSON analysis:
 *   GET /rest/scriptrunner/latest/custom/structureIssueDoctor
 *       ?structureId=123&issueKey=ABC-123&format=json
 *
 * Fix (called by the browser UI; may also be called directly):
 *   POST /rest/scriptrunner/latest/custom/structureIssueDoctorFix
 *   Content-Type: application/json
 *   X-Atlassian-Token: no-check
 *   {"structureId":123,"issueKey":"ABC-123","confirm":"SET_PARENT_LINK"}
 *
 * Source of truth for the only automatic fix in this version:
 *   "Strategische Ziele KPI" -> Advanced Roadmaps Parent Link
 *
 * Safety properties:
 * - Analysis is read-only.
 * - A fix is only offered when the selected structure uses the Advanced
 *   Roadmaps children extender, the strategic parent is already visible in
 *   that structure, and Jira validates the update for the logged-in user.
 * - The field is updated with IssueService and an ISSUE_UPDATED event.
 * - setSkipScreenCheck(true) allows the update even if Parent Link is not on
 *   the Edit Screen. Field context, edit permission, workflow editability and
 *   Advanced Roadmaps hierarchy validation are not bypassed.
 */

@BaseScript CustomEndpointDelegate delegate

@WithPlugin('com.almworks.jira.structure')
@PluginModule
StructureComponents structureComponents

final String SOURCE_FIELD_NAME = 'Strategische Ziele KPI'
final String PARENT_LINK_TYPE_KEY = 'com.atlassian.jpo:jpo-custom-field-parent'
final String FIX_CONFIRMATION = 'SET_PARENT_LINK'

IssueService issueService = ComponentAccessor.getIssueService()
IssueManager issueManager = ComponentAccessor.getIssueManager()
CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
PermissionManager permissionManager = ComponentAccessor.getPermissionManager()
JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext()

Closure<Class<?>> responseClass = {
    try {
        return this.class.classLoader.loadClass('jakarta.ws.rs.core.Response')
    } catch (ClassNotFoundException ignored) {
        // ScriptRunner 8/9 still uses javax; ScriptRunner 10+ uses jakarta.
        return this.class.classLoader.loadClass('javax.ws.rs.core.Response')
    }
}

Closure<Object> respond = { int status, String entity, String contentType ->
    Object builder = InvokerHelper.invokeMethod(responseClass.call(), 'status', status)
    builder = InvokerHelper.invokeMethod(builder, 'entity', entity)
    builder = InvokerHelper.invokeMethod(builder, 'type', contentType)
    builder = InvokerHelper.invokeMethod(builder, 'header', ['Cache-Control', 'no-store'] as Object[])
    InvokerHelper.invokeMethod(builder, 'build', null)
}

Closure<Object> respondJson = { int status, Object payload ->
    respond.call(status, JsonOutput.prettyPrint(JsonOutput.toJson(payload)), 'application/json;charset=UTF-8')
}

Closure<String> html = { Object value ->
    String.valueOf(value == null ? '' : value)
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
        .replace("'", '&#39;')
}

Closure<Map<String, Object>> errorDetails = { ErrorCollection errors ->
    if (errors == null) return [messages: [], fields: [:]]
    List<String> messages = []
    Collection<?> rawMessages = errors.getErrorMessages()
    if (rawMessages != null) {
        for (Object message : rawMessages) {
            messages.add(String.valueOf(message))
        }
    }
    Map<String, String> fields = [:]
    Map<?, ?> rawFields = errors.getErrors()
    if (rawFields != null) {
        for (Map.Entry<?, ?> entry : rawFields.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()))
        }
    }
    [messages: messages, fields: fields]
}

Closure<Object> jsonSafe
jsonSafe = { Object value ->
    if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
        return value
    }
    if (value instanceof Map) {
        Map<String, Object> safeMap = [:]
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            safeMap.put(String.valueOf(entry.getKey()), jsonSafe.call(entry.getValue()))
        }
        return safeMap
    }
    if (value instanceof Iterable) {
        List<Object> safeItems = []
        for (Object nested : (Iterable<?>) value) {
            safeItems.add(jsonSafe.call(nested))
        }
        return safeItems
    }
    if (value.getClass().isArray()) {
        List<Object> safeItems = []
        for (Object nested : (Object[]) value) {
            safeItems.add(jsonSafe.call(nested))
        }
        return safeItems
    }
    String.valueOf(value)
}

Closure<String> queryValue = { Object queryParams, String name ->
    Object value = queryParams == null ? null : InvokerHelper.invokeMethod(queryParams, 'getFirst', name)
    value == null ? null : String.valueOf(value).trim()
}

Closure<List<Issue>> issueValues = { Object raw ->
    List<Issue> values = []
    Collection<?> items = raw instanceof Collection ? (Collection<?>) raw : (raw == null ? [] : [raw])
    for (Object item : items) {
        if (item instanceof Issue) {
            values.add((Issue) item)
        } else if (item != null) {
            Issue resolved = issueManager.getIssueObject(String.valueOf(item))
            if (resolved != null) values.add(resolved)
        }
    }
    values.unique { Issue candidate -> candidate.getId() } as List<Issue>
}

Closure<Map<String, Object>> findSourceField = { Issue issue ->
    List<CustomField> named = (customFieldManager.getCustomFieldObjectsByName(SOURCE_FIELD_NAME) ?: []) as List<CustomField>
    List<CustomField> relevant = named.findAll { CustomField field ->
        try {
            field.getRelevantConfig(issue) != null
        } catch (Exception ignored) {
            false
        }
    }
    [field: relevant.size() == 1 ? relevant.first() : null,
     namedCount: named.size(), relevantCount: relevant.size(),
     candidates: relevant.collect { CustomField field -> [id: field.getId(), name: field.getName()] }]
}

Closure<Map<String, Object>> findParentLinkField = { Issue issue ->
    List<CustomField> typed = customFieldManager.getCustomFieldObjects().findAll { CustomField field ->
        try {
            field.getCustomFieldType()?.getKey() == PARENT_LINK_TYPE_KEY
        } catch (Exception ignored) {
            false
        }
    } as List<CustomField>
    List<CustomField> relevant = typed.findAll { CustomField field ->
        try {
            field.getRelevantConfig(issue) != null
        } catch (Exception ignored) {
            false
        }
    }
    [field: relevant.size() == 1 ? relevant.first() : null,
     typedCount: typed.size(), relevantCount: relevant.size(),
     candidates: relevant.collect { CustomField field -> [id: field.getId(), name: field.getName()] }]
}

Closure<Map<String, Object>> jqlMatch = { String jql, Issue issue ->
    if (!jql) return null
    try {
        // Official HAPI path for checking one issue against JQL. It adds the
        // issue-key constraint internally and respects the current user's permissions.
        boolean matches = Issues.getByKey(issue.getKey()).matches(jql)
        return [valid: true, matches: matches, errors: [messages: [], fields: [:]]]
    } catch (Exception failure) {
        return [valid: false, matches: null,
                errors: [messages: [failure.getClass().getSimpleName() + ': ' +
                    (failure.getMessage() ?: 'JQL execution failed')], fields: [:]]]
    }
}

Closure<ParentUpdateCheck> validateParentUpdate = {
        Issue issue, CustomField parentField, Issue target, ApplicationUser user ->
    if (issue == null || parentField == null || target == null || user == null) {
        return new ParentUpdateCheck(
            valid: false,
            errors: [messages: ['Update prerequisites are incomplete.'], fields: [:]]
        )
    }

    // Parent Link normally accepts the issue key. The ID fallback covers
    // installations whose field implementation expects the numeric ID.
    List<String> candidateValues = []
    candidateValues.add(String.valueOf(target.getKey()))
    String targetId = String.valueOf(target.getId())
    if (!candidateValues.contains(targetId)) candidateValues.add(targetId)
    List<Map<String, Object>> attempts = []
    for (String candidateValue : candidateValues) {
        IssueInputParameters input = issueService.newIssueInputParameters()
        input.setSkipScreenCheck(true)
        input.addCustomFieldValue(parentField.getId(), candidateValue)
        IssueService.UpdateValidationResult validation =
            issueService.validateUpdate(user, issue.getId(), input)
        if (validation.isValid()) {
            return new ParentUpdateCheck(
                valid: true,
                validationResult: validation,
                errors: [messages: [], fields: [:]],
                attempts: attempts
            )
        }
        attempts.add([
            inputValue: candidateValue,
            errors: errorDetails.call(validation.getErrorCollection())
        ])
    }
    Map<String, Object> lastErrors = attempts.isEmpty() ?
        [messages: ['Jira rejected the Parent Link update.'], fields: [:]] :
        (Map<String, Object>) attempts.get(attempts.size() - 1).get('errors')
    new ParentUpdateCheck(valid: false, errors: lastErrors, attempts: attempts)
}

Closure<InspectionResult> inspectStructure = { Long structureId, String rawIssueKey ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) {
        return new InspectionResult(status: 401, payload: [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    }

    String issueKey = rawIssueKey?.trim()?.toUpperCase(Locale.ROOT)
    if (!issueKey || !(issueKey ==~ /[A-Z][A-Z0-9_]*-[0-9]+/)) {
        return new InspectionResult(status: 400, payload: [ok: false, error: 'INVALID_ISSUE_KEY',
            message: 'Please enter a valid issue key.'])
    }

    IssueService.IssueResult issueResult = issueService.getIssue(user, issueKey)
    if (!issueResult.isValid() || issueResult.getIssue() == null) {
        return new InspectionResult(status: 404, payload: [ok: false,
            error: 'ISSUE_NOT_FOUND_OR_NOT_VISIBLE',
            details: errorDetails.call(issueResult.getErrorCollection())])
    }
    Issue issue = issueResult.getIssue()

    Structure structure
    try {
        structure = structureComponents.getStructureManager().getStructure(structureId, PermissionLevel.VIEW)
    } catch (Exception failure) {
        return new InspectionResult(status: 404, payload: [ok: false,
            error: 'STRUCTURE_NOT_FOUND_OR_NOT_VISIBLE',
            message: failure.getMessage() ?: failure.getClass().getSimpleName()])
    }

    Forest forest
    try {
        forest = structureComponents.getForestService()
            .getForestSource(ForestSpec.structure(structureId))
            .getLatest()
            .getForest()
    } catch (Exception failure) {
        return new InspectionResult(status: 500, payload: [ok: false,
            error: 'STRUCTURE_FOREST_READ_FAILED',
            message: failure.getMessage() ?: failure.getClass().getSimpleName()])
    }

    Set<Long> visibleIssueIds = new LinkedHashSet<Long>()
    Set<Long> generatorIds = new LinkedHashSet<Long>()
    List<Map<String, Object>> issueOccurrenceRows = []
    int issueOccurrences = 0
    for (int index = 0; index < forest.size(); index++) {
        long rowId = forest.getRow(index)
        StructureRow row = structureComponents.getRowManager().getRow(rowId)
        ItemIdentity identity = row.getItemId()
        if (CoreIdentities.isIssue(identity)) {
            Long id = identity.getLongId()
            visibleIssueIds.add(id)
            if (id == issue.getId()) {
                issueOccurrences++
                int parentIndex = forest.getParentIndex(index)
                Long parentRowId = parentIndex >= 0 ? forest.getRow(parentIndex) : null
                String parentType = 'root'
                String parentLabel = 'ROOT'
                Long parentIssueId = null
                Long parentGeneratorId = null
                if (parentIndex >= 0) {
                    StructureRow parentRow = structureComponents.getRowManager().getRow(parentRowId)
                    ItemIdentity parentIdentity = parentRow.getItemId()
                    if (CoreIdentities.isIssue(parentIdentity)) {
                        parentType = 'issue'
                        parentIssueId = parentIdentity.getLongId()
                        Issue parentIssue = issueManager.getIssueObject(parentIssueId)
                        parentLabel = parentIssue == null ?
                            "Issue ID ${parentIssueId}".toString() : parentIssue.getKey()
                    } else if (CoreIdentities.isGenerator(parentIdentity)) {
                        parentType = 'generator'
                        parentGeneratorId = parentIdentity.getLongId()
                        parentLabel = "Generator ${parentGeneratorId}".toString()
                    } else {
                        parentType = 'other'
                        parentLabel = String.valueOf(parentIdentity)
                    }
                }

                long creatorId = 0L
                long originalRowId = 0L
                try {
                    creatorId = TransientRow.getCreatorId(row)
                    originalRowId = TransientRow.getOriginalId(row)
                } catch (Exception ignored) {
                    // Permanent rows and older Structure versions may not expose provenance.
                }
                issueOccurrenceRows.add([
                    rowId           : rowId,
                    forestIndex     : index,
                    depth           : forest.getDepth(index),
                    parentRowId      : parentRowId,
                    parentType       : parentType,
                    parentLabel      : parentLabel,
                    parentIssueId    : parentIssueId,
                    parentGeneratorId: parentGeneratorId,
                    creatorId        : creatorId > 0L ? creatorId : null,
                    originalRowId    : originalRowId > 0L ? originalRowId : null
                ])
            }
        } else if (CoreIdentities.isGenerator(identity)) {
            generatorIds.add(identity.getLongId())
        }
    }

    List<Map<String, Object>> generators = []
    generatorIds.each { Long generatorId ->
        try {
            Object generator = structureComponents.getGeneratorManager().getGenerator(generatorId)
            String moduleKey = String.valueOf(InvokerHelper.getProperty(generator, 'moduleKey'))
            Object rawParameters = InvokerHelper.getProperty(generator, 'parameters')
            Map<Object, Object> parameters = rawParameters instanceof Map ?
                (Map<Object, Object>) rawParameters : [:]
            String jql = parameters.get(CoreGeneratorParameters.JQL)?.toString()
            if (!jql) {
                Map.Entry<Object, Object> jqlEntry = null
                for (Map.Entry<Object, Object> entry : parameters.entrySet()) {
                    if (String.valueOf(entry.getKey()).equalsIgnoreCase('jql')) {
                        jqlEntry = entry
                        break
                    }
                }
                jql = jqlEntry == null ? null : String.valueOf(jqlEntry.getValue())
            }
            String normalizedModuleKey = moduleKey.toLowerCase(Locale.ROOT)
            String kind = normalizedModuleKey.contains('duplicate') ? 'duplicates-filter' :
                moduleKey == CoreStructureGenerators.EXTENDER_PORTFOLIO_CHILDREN ? 'advanced-roadmaps-children' :
                (moduleKey == CoreStructureGenerators.INSERTER_JQL || moduleKey.contains('inserter-jql')) ? 'jql-inserter' :
                (moduleKey == CoreStructureGenerators.FILTER_JQL || moduleKey.contains('filter-jql')) ? 'jql-filter' :
                moduleKey.contains('extender') ? 'extender' : 'other'
            generators.add([
                id        : generatorId,
                moduleKey : moduleKey,
                kind      : kind,
                parameters: jsonSafe.call(parameters),
                jql       : jql,
                issueMatch: jql ? jqlMatch.call(jql, issue) : null
            ])
        } catch (Exception failure) {
            generators.add([id: generatorId, kind: 'unreadable',
                            error: failure.getClass().getSimpleName() + ': ' +
                                (failure.getMessage() ?: 'Generator could not be read')])
        }
    }

    Map<Long, Map<String, Object>> generatorsById = [:]
    for (Map<String, Object> generator : generators) {
        Object rawGeneratorId = generator.get('id')
        if (rawGeneratorId instanceof Number) {
            generatorsById.put(((Number) rawGeneratorId).longValue(), generator)
        }
    }

    Set<Long> occurrenceCreatorIds = new LinkedHashSet<Long>()
    Set<String> occurrenceCreatorKinds = new LinkedHashSet<String>()
    Set<String> occurrenceParentPaths = new LinkedHashSet<String>()
    boolean hasRootOccurrence = false
    boolean hasChildOccurrence = false
    boolean hasOccurrenceWithoutResolvedCreator = false
    for (Map<String, Object> occurrence : issueOccurrenceRows) {
        Long creatorId = occurrence.get('creatorId') instanceof Number ?
            ((Number) occurrence.get('creatorId')).longValue() : null
        Map<String, Object> creator = creatorId == null ? null : generatorsById.get(creatorId)
        if (creator != null) {
            String creatorKind = String.valueOf(creator.get('kind'))
            occurrenceCreatorIds.add(creatorId)
            occurrenceCreatorKinds.add(creatorKind)
            occurrence.put('creatorGeneratorId', creatorId)
            occurrence.put('creatorKind', creatorKind)
            occurrence.put('creatorModuleKey', creator.get('moduleKey'))
            occurrence.put('sourceLabel', "${creatorKind} #${creatorId}".toString())
        } else if (creatorId != null) {
            hasOccurrenceWithoutResolvedCreator = true
            occurrence.put('sourceLabel', "Unresolved generator or transform #${creatorId}".toString())
        } else {
            hasOccurrenceWithoutResolvedCreator = true
            occurrence.put('sourceLabel', 'Permanent row or provenance unavailable')
        }

        String parentType = String.valueOf(occurrence.get('parentType'))
        String parentLabel = String.valueOf(occurrence.get('parentLabel'))
        occurrenceParentPaths.add("${parentType}:${parentLabel}".toString())
        if (parentType == 'root') {
            hasRootOccurrence = true
        } else {
            hasChildOccurrence = true
        }
    }

    boolean duplicateDetected = issueOccurrences > 1
    boolean hasDuplicatesFilter = generators.any { Map<String, Object> generator ->
        generator.get('kind') == 'duplicates-filter'
    }
    boolean hasInserterCreator = occurrenceCreatorKinds.contains('jql-inserter')
    boolean hasExtenderCreator = occurrenceCreatorKinds.contains('advanced-roadmaps-children') ||
        occurrenceCreatorKinds.contains('extender')
    List<Map<String, Object>> duplicateReasons = []
    if (duplicateDetected && hasRootOccurrence && hasChildOccurrence &&
            hasInserterCreator && hasExtenderCreator) {
        duplicateReasons.add([
            code: 'INSERT_EXTEND_OVERLAP',
            message: 'The same issue was created through both an insertion path and an extension path: at least one occurrence is at the root and at least one is below another row.',
            evidence: [creatorKinds: new ArrayList<String>(occurrenceCreatorKinds),
                       parentPaths: new ArrayList<String>(occurrenceParentPaths)]
        ])
    } else if (duplicateDetected && hasRootOccurrence && hasChildOccurrence) {
        duplicateReasons.add([
            code: 'ROOT_AND_CHILD_PATHS',
            message: 'The issue exists once as a root item and at least once below another row. This proves that separate structure paths add or retain the same Jira issue.',
            evidence: [parentPaths: new ArrayList<String>(occurrenceParentPaths)]
        ])
    }
    if (duplicateDetected && occurrenceParentPaths.size() > 1) {
        duplicateReasons.add([
            code: 'MULTIPLE_HIERARCHY_PATHS',
            message: 'The occurrences have different immediate parents. Structure permits the same Jira issue in multiple locations when it represents more than one hierarchy relationship.',
            evidence: [parentPaths: new ArrayList<String>(occurrenceParentPaths)]
        ])
    }
    if (duplicateDetected && occurrenceCreatorIds.size() > 1) {
        duplicateReasons.add([
            code: 'MULTIPLE_GENERATORS',
            message: 'Different generators created occurrences of the same Jira issue. Their scopes overlap for this issue.',
            evidence: [generatorIds: new ArrayList<Long>(occurrenceCreatorIds),
                       creatorKinds: new ArrayList<String>(occurrenceCreatorKinds)]
        ])
    }
    if (duplicateDetected && hasOccurrenceWithoutResolvedCreator && !occurrenceCreatorIds.isEmpty()) {
        duplicateReasons.add([
            code: 'MIXED_ROW_PROVENANCE',
            message: 'At least one occurrence has a known generator source and at least one is permanent or has no resolvable generator provenance.',
            evidence: [resolvedGeneratorIds: new ArrayList<Long>(occurrenceCreatorIds)]
        ])
    }
    if (duplicateDetected && !hasDuplicatesFilter) {
        duplicateReasons.add([
            code: 'NO_DUPLICATES_FILTER',
            message: 'No recognized Inserter/Extender Duplicates Filter was detected in this structure, so identical rows produced by insertion and extension rules are not automatically removed.',
            evidence: [detected: false]
        ])
    }
    if (duplicateDetected && duplicateReasons.isEmpty()) {
        duplicateReasons.add([
            code: 'MULTIPLE_STRUCTURE_ROWS',
            message: 'Structure contains multiple distinct rows for the same Jira issue. The available row provenance does not identify a more specific cause for every occurrence.',
            evidence: [rowIds: issueOccurrenceRows.collect { Map<String, Object> occurrence ->
                occurrence.get('rowId')
            }]
        ])
    }

    String duplicateSummary = duplicateDetected ?
        "${issue.getKey()} appears ${issueOccurrences} times in the selected structure.".toString() :
        "${issue.getKey()} does not have duplicate occurrences in the selected structure.".toString()
    String duplicateRecommendation = null
    if (duplicateDetected && occurrenceParentPaths.size() > 1) {
        duplicateRecommendation = 'Review whether every hierarchy path is intentional. A duplicates filter can retain occurrences under different parents because they represent different relationships.'
    } else if (duplicateDetected && hasRootOccurrence && hasChildOccurrence && !hasDuplicatesFilter) {
        duplicateRecommendation = 'Add an Inserter/Extender Duplicates Filter after the relevant inserter and extender generators, then verify its scope.'
    } else if (duplicateDetected) {
        duplicateRecommendation = 'Review the listed generator sources and parent paths. Narrow overlapping generator scopes or verify the existing duplicates filter configuration.'
    }

    boolean present = issueOccurrences > 0
    boolean hasRoadmapsChildren = generators.any { Map<String, Object> generator ->
        generator.get('kind') == 'advanced-roadmaps-children'
    }
    Map<String, Object> sourceLookup = findSourceField.call(issue)
    Map<String, Object> parentLookup = findParentLinkField.call(issue)
    CustomField sourceField = (CustomField) sourceLookup.get('field')
    CustomField parentField = (CustomField) parentLookup.get('field')

    List<Issue> strategicParents = sourceField == null ? [] :
        issueValues.call(issue.getCustomFieldValue(sourceField))
    Issue strategicParent = strategicParents.size() == 1 ? strategicParents.first() : null
    List<Issue> currentParents = parentField == null ? [] :
        issueValues.call(issue.getCustomFieldValue(parentField))
    Issue currentParent = currentParents ? currentParents.first() : null
    boolean strategicParentInStructure = strategicParent != null &&
        visibleIssueIds.contains(strategicParent.getId())
    boolean parentMatches = strategicParent != null && currentParent?.getId() == strategicParent.getId()
    boolean canEdit = issueService.isEditable(issue, user) &&
        permissionManager.hasPermission(ProjectPermissions.EDIT_ISSUES, issue, user)
    boolean targetVisible = strategicParent != null &&
        permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, strategicParent, user)

    List<Map<String, Object>> diagnosis = []
    String primaryCode
    if (sourceField == null) {
        primaryCode = 'SOURCE_FIELD_NOT_UNIQUE_OR_NOT_IN_CONTEXT'
        diagnosis.add([severity: 'error', code: primaryCode,
                       message: "The '${SOURCE_FIELD_NAME}' field could not be resolved unambiguously for this issue or is outside the field context."])
    } else if (strategicParents.isEmpty()) {
        primaryCode = 'SOURCE_FIELD_EMPTY'
        diagnosis.add([severity: 'error', code: primaryCode,
                       message: "${SOURCE_FIELD_NAME} is empty. No Advanced Roadmaps parent can be derived."])
    } else if (strategicParents.size() > 1) {
        primaryCode = 'SOURCE_FIELD_AMBIGUOUS'
        diagnosis.add([severity: 'error', code: primaryCode,
                       message: "${SOURCE_FIELD_NAME} contains multiple issues. An automatic Parent Link fix would be ambiguous."])
    } else if (!hasRoadmapsChildren) {
        primaryCode = 'NO_ADVANCED_ROADMAPS_EXTENDER'
        diagnosis.add([severity: 'warning', code: primaryCode,
                       message: 'The selected structure does not contain a recognized Advanced Roadmaps children extender. A Parent Link fix would therefore not explain inclusion in the structure.'])
    } else if (!strategicParentInStructure) {
        primaryCode = 'STRATEGIC_PARENT_NOT_IN_STRUCTURE'
        diagnosis.add([severity: 'error', code: primaryCode,
                       message: "The strategic parent ${strategicParent.getKey()} is not present in the selected structure. The extender therefore has no starting point."])
    } else if (parentField == null) {
        primaryCode = 'PARENT_LINK_FIELD_NOT_UNIQUE_OR_NOT_IN_CONTEXT'
        diagnosis.add([severity: 'error', code: primaryCode,
                       message: 'The Advanced Roadmaps Parent Link field could not be resolved unambiguously for this issue or is outside the field context.'])
    } else if (!parentMatches) {
        primaryCode = 'PARENT_LINK_MISMATCH'
        diagnosis.add([severity: 'error', code: primaryCode,
                       message: present ?
                           "${issue.getKey()} is present in the structure, but its hierarchy is inconsistent: Parent Link is ${currentParent?.getKey() ?: 'empty'}, while '${SOURCE_FIELD_NAME}' points to ${strategicParent.getKey()}." :
                           "${issue.getKey()} is missing because Parent Link is ${currentParent?.getKey() ?: 'empty'}, while '${SOURCE_FIELD_NAME}' points to ${strategicParent.getKey()}. The Advanced Roadmaps extender follows Parent Link."])
    } else if (present) {
        primaryCode = 'ISSUE_PRESENT'
        diagnosis.add([severity: 'ok', code: primaryCode,
                       message: "${issue.getKey()} is present in the selected structure (${issueOccurrences} occurrence(s)), and Parent Link matches '${SOURCE_FIELD_NAME}'."])
    } else {
        primaryCode = 'RELATION_CORRECT_BUT_ISSUE_ABSENT'
        diagnosis.add([severity: 'warning', code: primaryCode,
                       message: 'Parent Link and the strategic goal match. The likely cause is generator scope, a filter, permissions, or a structure calculation that has not refreshed yet.'])
    }

    generators.findAll { Map<String, Object> generator ->
        Map<String, Object> issueMatch = (Map<String, Object>) generator.get('issueMatch')
        generator.get('kind') == 'jql-filter' && issueMatch != null &&
            Boolean.TRUE == issueMatch.get('valid') && Boolean.FALSE == issueMatch.get('matches')
    }.each { Map<String, Object> generator ->
        diagnosis.add([severity: 'warning', code: 'JQL_FILTER_DOES_NOT_MATCH',
                       generatorId: generator.get('id'),
                       message: "The issue does not match the JQL of filter ${generator.get('id')}. Depending on filter mode and scope, this generator may hide the issue."])
    }

    if (duplicateDetected) {
        diagnosis.add([severity: 'warning', code: 'DUPLICATE_OCCURRENCES',
                       message: duplicateSummary])
    }

    boolean prerequisitesForValidation = hasRoadmapsChildren && strategicParentInStructure &&
        strategicParent != null && parentField != null && !parentMatches && targetVisible && canEdit &&
        strategicParent.getId() != issue.getId()
    ParentUpdateCheck validation = prerequisitesForValidation ?
        validateParentUpdate.call(issue, parentField, strategicParent, user) :
        new ParentUpdateCheck(valid: false, errors: [messages: [], fields: [:]])

    List<String> fixBlockers = []
    if (!hasRoadmapsChildren) fixBlockers.add('No Advanced Roadmaps children extender was detected.')
    if (strategicParent == null) fixBlockers.add("${SOURCE_FIELD_NAME} does not provide an unambiguous parent.".toString())
    if (strategicParent != null && !strategicParentInStructure) fixBlockers.add('The strategic parent is not present in the structure.')
    if (parentField == null) fixBlockers.add('The Parent Link field is ambiguous or outside the field context.')
    if (parentMatches) fixBlockers.add('Parent Link is already correct.')
    if (!targetVisible) fixBlockers.add('The target parent is not visible to the current user.')
    if (!canEdit) fixBlockers.add('The issue is not editable by the current user.')
    if (strategicParent?.getId() == issue.getId()) fixBlockers.add('An issue cannot be its own parent.')
    if (prerequisitesForValidation && !validation.isValid()) fixBlockers.add('Jira validation rejected the update.')

    Map<String, Object> sourceLookupReport = [:]
    for (Map.Entry<String, Object> entry : sourceLookup.entrySet()) {
        if (entry.getKey() != 'field') sourceLookupReport.put(entry.getKey(), entry.getValue())
    }
    Map<String, Object> parentLookupReport = [:]
    for (Map.Entry<String, Object> entry : parentLookup.entrySet()) {
        if (entry.getKey() != 'field') parentLookupReport.put(entry.getKey(), entry.getValue())
    }
    Map<String, Object> payload = [
        ok       : true,
        checkedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"),
        user     : [key: user.getKey(), displayName: user.getDisplayName()],
        structure: [id: structure.getId(), name: structure.getName()],
        issue    : [id: issue.getId(), key: issue.getKey(), summary: issue.getSummary(),
                    issueType: issue.getIssueType().getName(), status: issue.getStatus().getName(),
                    present: present, occurrences: issueOccurrences],
        fields   : [
            source: [name: SOURCE_FIELD_NAME, id: sourceField?.getId(),
                     value: strategicParents.collect { Issue parent -> parent.getKey() },
                     lookup: sourceLookupReport],
            parentLink: [name: parentField?.getName() ?: 'Parent Link', id: parentField?.getId(),
                         value: currentParents.collect { Issue parent -> parent.getKey() },
                         lookup: parentLookupReport]
        ],
        structureEvidence: [advancedRoadmapsChildrenExtender: hasRoadmapsChildren,
                            strategicParent: strategicParent?.getKey(),
                            strategicParentInStructure: strategicParentInStructure,
                            visibleIssueCount: visibleIssueIds.size(),
                            generatorCount: generators.size()],
        primaryDiagnosis: primaryCode,
        diagnosis: diagnosis,
        duplicates: [
            detected: duplicateDetected,
            occurrenceCount: issueOccurrences,
            duplicateFilterDetected: hasDuplicatesFilter,
            summary: duplicateSummary,
            reasons: duplicateReasons,
            recommendedAction: duplicateRecommendation,
            occurrences: issueOccurrenceRows
        ],
        fix: [
            available: prerequisitesForValidation && validation.isValid(),
            action: 'SET_PARENT_LINK',
            from: currentParent?.getKey(),
            to: strategicParent?.getKey(),
            fieldId: parentField?.getId(),
            skipsEditScreenCheck: true,
            permissionsAndFieldValidationRemainActive: true,
            validationErrors: validation.isValid() ? [messages: [], fields: [:]] : validation.getErrors(),
            blockers: fixBlockers
        ],
        generators: generators
    ]
    new InspectionResult(status: 200, payload: payload)
}

Closure<String> renderPage = {
        List<Structure> structures, Map<String, Object> analysis,
        Long selectedStructureId, String enteredIssueKey ->
    String options = structures.collect { Structure structure ->
        boolean selected = selectedStructureId != null && structure.getId() == selectedStructureId
        ("<option value=\"${html.call(structure.getId())}\"${selected ? ' selected' : ''}>" +
            "${html.call(structure.getName())} (#${html.call(structure.getId())})</option>").toString()
    }.join('\n')

    String result = ''
    if (analysis != null) {
        if (Boolean.FALSE == analysis.get('ok')) {
            result = "<section class=\"card error\"><h2>Analysis failed</h2><pre>" +
                "${html.call(JsonOutput.prettyPrint(JsonOutput.toJson(analysis)))}</pre></section>"
        } else {
            Map<String, Object> issueData = (Map<String, Object>) analysis.get('issue')
            Map<String, Object> structureData = (Map<String, Object>) analysis.get('structure')
            Map<String, Object> fieldsData = (Map<String, Object>) analysis.get('fields')
            Map<String, Object> sourceData = (Map<String, Object>) fieldsData.get('source')
            Map<String, Object> parentData = (Map<String, Object>) fieldsData.get('parentLink')
            Map<String, Object> evidenceData = (Map<String, Object>) analysis.get('structureEvidence')
            Map<String, Object> duplicateData = (Map<String, Object>) analysis.get('duplicates')
            Map<String, Object> fixData = (Map<String, Object>) analysis.get('fix')
            List<Map<String, Object>> findingsData =
                (List<Map<String, Object>>) (analysis.get('diagnosis') ?: [])

            String findings = findingsData.collect { Map<String, Object> finding ->
                ("<li class=\"${html.call(finding.get('severity'))}\"><strong>" +
                    "${html.call(finding.get('code'))}</strong><br>${html.call(finding.get('message'))}</li>").toString()
            }.join('\n')
            String fixButton = Boolean.TRUE == fixData.get('available') ? """
                <button id="fixButton" type="button">Set Parent Link to ${html.call(fixData.get('to'))}</button>
                <span id="fixStatus"></span>
                <script>
                document.getElementById('fixButton').addEventListener('click', async function () {
                  const button = this;
                  const status = document.getElementById('fixStatus');
                  if (!window.confirm('Set Parent Link of ${html.call(issueData.get('key'))} to ${html.call(fixData.get('to'))}?')) return;
                  button.disabled = true;
                  status.textContent = ' Applying fix ...';
                  try {
                    const url = window.location.pathname.replace(/structureIssueDoctor\$/, 'structureIssueDoctorFix');
                    const response = await fetch(url, {
                      method: 'POST',
                      credentials: 'same-origin',
                      headers: {'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check'},
                      body: ${JsonOutput.toJson(JsonOutput.toJson([structureId: structureData.get('id'), issueKey: issueData.get('key'), confirm: FIX_CONFIRMATION]))}
                    });
                    const data = await response.json();
                    if (!response.ok || !data.ok) throw new Error(data.message || data.error || 'Fix failed');
                    status.textContent = ' Fix completed. Refreshing analysis ...';
                    window.setTimeout(() => window.location.reload(), 800);
                  } catch (error) {
                    status.textContent = ' ' + error.message;
                    button.disabled = false;
                  }
                });
                </script>
            """.toString() : "<p><strong>No automatic fix is available.</strong> " +
                "${html.call(((List<?>) (fixData.get('blockers') ?: [])).join(' '))}</p>"

            List<Map<String, Object>> generatorData =
                (List<Map<String, Object>>) (analysis.get('generators') ?: [])
            String generatorRows = generatorData.collect { Map<String, Object> generator ->
                Map<String, Object> issueMatch = (Map<String, Object>) generator.get('issueMatch')
                String match = issueMatch == null ? '' : Boolean.TRUE == issueMatch.get('valid') ?
                    String.valueOf(issueMatch.get('matches')) : 'Error'
                ("<tr><td>${html.call(generator.get('id'))}</td><td>${html.call(generator.get('kind'))}</td>" +
                    "<td><code>${html.call(generator.get('moduleKey'))}</code></td><td>${html.call(match)}</td></tr>").toString()
            }.join('\n')

            String duplicateSection = ''
            if (duplicateData != null && Boolean.TRUE == duplicateData.get('detected')) {
                List<Map<String, Object>> duplicateReasonData =
                    (List<Map<String, Object>>) (duplicateData.get('reasons') ?: [])
                List<Map<String, Object>> occurrenceData =
                    (List<Map<String, Object>>) (duplicateData.get('occurrences') ?: [])
                String duplicateReasonItems = duplicateReasonData.collect { Map<String, Object> reason ->
                    ("<li><strong>${html.call(reason.get('code'))}</strong><br>" +
                        "${html.call(reason.get('message'))}</li>").toString()
                }.join('\n')
                String occurrenceRows = occurrenceData.collect { Map<String, Object> occurrence ->
                    ("<tr><td>${html.call(occurrence.get('rowId'))}</td>" +
                        "<td>${html.call(occurrence.get('depth'))}</td>" +
                        "<td>${html.call(occurrence.get('parentLabel'))}</td>" +
                        "<td>${html.call(occurrence.get('sourceLabel'))}</td></tr>").toString()
                }.join('\n')
                duplicateSection = """
                    <section class="card duplicate">
                      <h3>Duplicate analysis</h3>
                      <p><strong>${html.call(duplicateData.get('summary'))}</strong></p>
                      <ul class="findings">${duplicateReasonItems}</ul>
                      <table>
                        <thead><tr><th>Row ID</th><th>Depth</th><th>Immediate parent</th><th>Source</th></tr></thead>
                        <tbody>${occurrenceRows}</tbody>
                      </table>
                      <p><strong>Recommended action:</strong> ${html.call(duplicateData.get('recommendedAction'))}</p>
                      <p class="note">Duplicate removal is not offered automatically because occurrences under different parents can represent intentional hierarchy relationships.</p>
                    </section>
                """.toString()
            }

            List<?> sourceValues = (List<?>) (sourceData.get('value') ?: ['(empty)'])
            List<?> parentValues = (List<?>) (parentData.get('value') ?: ['(empty)'])

            result = """
                <section class="card">
                  <h2>${html.call(issueData.get('key'))} in ${html.call(structureData.get('name'))}</h2>
                  <dl>
                    <dt>In structure</dt><dd>${Boolean.TRUE == issueData.get('present') ? 'YES' : 'NO'}</dd>
                    <dt>Occurrences</dt><dd>${html.call(issueData.get('occurrences'))}</dd>
                    <dt>${html.call(SOURCE_FIELD_NAME)}</dt><dd>${html.call(sourceValues.join(', '))}</dd>
                    <dt>${html.call(parentData.get('name'))}</dt><dd>${html.call(parentValues.join(', '))}</dd>
                    <dt>Advanced Roadmaps extender</dt><dd>${Boolean.TRUE == evidenceData.get('advancedRoadmapsChildrenExtender') ? 'YES' : 'NO'}</dd>
                  </dl>
                  <h3>Diagnosis</h3>
                  <ul class="findings">${findings}</ul>
                  <div class="fix">${fixButton}</div>
                </section>
                ${duplicateSection}
                <details class="card"><summary>Generators and technical details</summary>
                  <table><thead><tr><th>ID</th><th>Type</th><th>Module Key</th><th>Issue matches JQL</th></tr></thead>
                  <tbody>${generatorRows}</tbody></table>
                  <pre>${html.call(JsonOutput.prettyPrint(JsonOutput.toJson(analysis)))}</pre>
                </details>
            """.toString()
        }
    }

    return """<!doctype html>
    <html lang="en"><head><meta charset="utf-8"><title>Structure Issue Doctor</title>
    <style>
      body{font:14px Arial,sans-serif;color:#172b4d;background:#f4f5f7;margin:0;padding:28px}
      main{max-width:1100px;margin:auto}.card{background:white;border:1px solid #dfe1e6;border-radius:6px;padding:20px;margin:0 0 18px}
      h1{margin-top:0}label{display:block;font-weight:600;margin:12px 0 5px}select,input{box-sizing:border-box;width:100%;max-width:650px;padding:9px;border:1px solid #7a869a;border-radius:3px}
      button{margin-top:16px;background:#0052cc;color:white;border:0;border-radius:3px;padding:10px 14px;font-weight:600;cursor:pointer}button:disabled{opacity:.55}
      dl{display:grid;grid-template-columns:220px 1fr;gap:7px}dt{font-weight:600}dd{margin:0}.findings{padding-left:22px}.findings li{margin:9px 0}.error{color:#ae2a19}.warning{color:#7f5f01}.ok{color:#216e4e}
      table{border-collapse:collapse;width:100%;margin-top:14px}th,td{border:1px solid #dfe1e6;padding:7px;text-align:left}pre{white-space:pre-wrap;word-break:break-word;background:#f4f5f7;padding:12px;max-height:420px;overflow:auto}
      summary{cursor:pointer;font-weight:600}.note{color:#44546f}.fix{border-top:1px solid #dfe1e6;margin-top:16px;padding-top:4px}
    </style></head><body><main>
      <section class="card">
        <h1>Structure Issue Doctor</h1>
        <p class="note">Select a structure, enter an issue key, and run the analysis. Changes are only made through an explicitly offered fix.</p>
        <form method="get">
          <label for="structureId">Structure</label>
          <select id="structureId" name="structureId" required><option value="">Select a structure</option>${options}</select>
          <label for="issueKey">Issue key</label>
          <input id="issueKey" name="issueKey" value="${html.call(enteredIssueKey)}" placeholder="ABC-123" required pattern="[A-Za-z][A-Za-z0-9_]*-[0-9]+">
          <button type="submit">Analyze</button>
        </form>
      </section>
      ${result}
    </main></body></html>""".toString()
}

structureIssueDoctor(httpMethod: 'GET', groups: ["jira-administrators"]) { Object queryParams, Object ignoredBody ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])

    List<Structure> structures
    try {
        structures = structureComponents.getStructureManager().getAllStructures(PermissionLevel.VIEW) as List<Structure>
    } catch (Exception failure) {
        return respondJson.call(500, [ok: false, error: 'STRUCTURE_LIST_FAILED',
            message: failure.getMessage() ?: failure.getClass().getSimpleName()])
    }

    String structureIdText = queryValue.call(queryParams, 'structureId')
    String issueKey = queryValue.call(queryParams, 'issueKey')
    String format = queryValue.call(queryParams, 'format')
    Long structureId = null
    if (structureIdText) {
        try {
            structureId = Long.valueOf(structureIdText)
        } catch (NumberFormatException ignored) {
            return respondJson.call(400, [ok: false, error: 'INVALID_STRUCTURE_ID'])
        }
    }

    InspectionResult inspection = null
    if (structureId != null || issueKey) {
        if (structureId == null || !issueKey) {
            inspection = new InspectionResult(status: 400, payload: [ok: false,
                error: 'STRUCTURE_AND_ISSUE_REQUIRED',
                message: 'Structure and issue key must be provided together.'])
        } else {
            inspection = inspectStructure.call(structureId, issueKey)
        }
    }

    if ('json'.equalsIgnoreCase(format)) {
        return inspection == null ?
            respondJson.call(200, [ok: true, structures: structures.collect { Structure item ->
                [id: item.getId(), name: item.getName()]
            }]) :
            respondJson.call(inspection.getStatus(), inspection.getPayload())
    }

    String page = renderPage.call(structures, inspection?.getPayload(), structureId, issueKey ?: '')
    respond.call(inspection == null ? 200 : inspection.getStatus(), page, 'text/html;charset=UTF-8')
}

structureIssueDoctorFix(httpMethod: 'POST', groups: ["jira-administrators"]) { Object ignoredQueryParams, String body ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])

    Map<String, Object> request
    try {
        request = body?.trim() ? (Map<String, Object>) new JsonSlurper().parseText(body) : [:]
    } catch (Exception ignored) {
        return respondJson.call(400, [ok: false, error: 'INVALID_JSON'])
    }

    if (String.valueOf(request.get('confirm')) != FIX_CONFIRMATION) {
        return respondJson.call(400, [ok: false, error: 'CONFIRMATION_REQUIRED',
                                      message: "confirm must equal ${FIX_CONFIRMATION}"])
    }

    Long structureId
    try {
        structureId = Long.valueOf(String.valueOf(request.get('structureId')))
    } catch (Exception ignored) {
        return respondJson.call(400, [ok: false, error: 'INVALID_STRUCTURE_ID'])
    }
    String issueKey = String.valueOf(request.get('issueKey') ?: '').trim().toUpperCase(Locale.ROOT)

    // Re-run the complete diagnosis server-side. No field IDs or target issue
    // supplied by the browser are trusted.
    InspectionResult inspection = inspectStructure.call(structureId, issueKey)
    Map<String, Object> before = inspection.getPayload()
    if (inspection.getStatus() != 200 || Boolean.FALSE == before.get('ok')) {
        return respondJson.call(inspection.getStatus(), before)
    }
    Map<String, Object> beforeFix = (Map<String, Object>) before.get('fix')
    if (Boolean.TRUE != beforeFix.get('available') ||
            before.get('primaryDiagnosis') != 'PARENT_LINK_MISMATCH') {
        return respondJson.call(409, [ok: false, error: 'FIX_NOT_AVAILABLE',
                                      message: 'The current analysis does not allow a safe automatic fix.',
                                      diagnosis: before])
    }

    IssueService.IssueResult issueResult = issueService.getIssue(user, issueKey)
    Issue issue = issueResult.getIssue()
    if (!issueResult.isValid() || issue == null) {
        return respondJson.call(409, [ok: false, error: 'FIX_PRECONDITION_CHANGED'])
    }
    Map<String, Object> sourceLookup = findSourceField.call(issue)
    Map<String, Object> parentLookup = findParentLinkField.call(issue)
    CustomField sourceField = (CustomField) sourceLookup.get('field')
    CustomField parentField = (CustomField) parentLookup.get('field')
    List<Issue> strategicParents = sourceField == null ? [] :
        issueValues.call(issue.getCustomFieldValue(sourceField))
    Issue target = strategicParents.size() == 1 ? strategicParents.first() : null
    if (parentField == null || target == null) {
        return respondJson.call(409, [ok: false, error: 'FIX_PRECONDITION_CHANGED'])
    }

    ParentUpdateCheck validation = validateParentUpdate.call(issue, parentField, target, user)
    if (!validation.isValid()) {
        return respondJson.call(409, [ok: false, error: 'UPDATE_VALIDATION_FAILED',
                                      details: validation.getErrors()])
    }

    String oldParent = (String) beforeFix.get('from')
    IssueService.IssueResult updateResult = issueService.update(
        user, validation.getValidationResult(), EventDispatchOption.ISSUE_UPDATED, false)
    if (!updateResult.isValid()) {
        return respondJson.call(500, [ok: false, error: 'UPDATE_FAILED',
                                      details: errorDetails.call(updateResult.getErrorCollection())])
    }

    Issue refreshed = issueManager.getIssueObject(issue.getId())
    List<Issue> refreshedParents = issueValues.call(refreshed.getCustomFieldValue(parentField))
    boolean verified = refreshedParents.size() == 1 &&
        refreshedParents.first().getId() == target.getId()
    if (!verified) {
        return respondJson.call(500, [ok: false, error: 'UPDATE_COULD_NOT_BE_VERIFIED',
                                      expected: target.getKey(),
                                      actual: refreshedParents.collect { Issue parent -> parent.getKey() }])
    }

    log.warn("Structure Issue Doctor fix by ${user.getKey()}: structure=${structureId}, issue=${issue.getKey()}, " +
        "field=${parentField.getId()}, oldParent=${oldParent ?: '(empty)'}, newParent=${target.getKey()}")

    InspectionResult afterInspection = inspectStructure.call(structureId, issue.getKey())
    respondJson.call(200, [
        ok: true,
        action: 'SET_PARENT_LINK',
        issueKey: issue.getKey(),
        fieldId: parentField.getId(),
        oldParent: oldParent,
        newParent: target.getKey(),
        verified: true,
        mailSent: false,
        eventDispatched: 'ISSUE_UPDATED',
        structureRefreshMayBeAsynchronous: true,
        analysisAfter: afterInspection.getPayload()
    ])
}
