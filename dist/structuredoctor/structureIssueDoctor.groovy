/*
 * GENERATED FILE - DO NOT EDIT.
 * Source: tools/build-structure-doctor-bundle.mjs
 * INPUT jira/structureIssueDoctor.groovy
 * INPUT jira/structuredoctor/CoreAutomationAnalyzer.groovy
 * INPUT jira/structuredoctor/CoreAutomationJsonSupport.groovy
 * INPUT jira/structuredoctor/CoreAutomationModels.groovy
 * INPUT jira/structuredoctor/CoreCanonical.groovy
 * INPUT jira/structuredoctor/CoreCausalityEngine.groovy
 * INPUT jira/structuredoctor/CoreCausalityModels.groovy
 * INPUT jira/structuredoctor/CoreDuplicateAnalyzer.groovy
 * INPUT jira/structuredoctor/CoreDuplicateModels.groovy
 * INPUT jira/structuredoctor/CoreDuplicateSupport.groovy
 * INPUT jira/structuredoctor/CoreHierarchyAnalyzer.groovy
 * INPUT jira/structuredoctor/CoreImpactSimulator.groovy
 * INPUT jira/structuredoctor/CoreModels.groovy
 * INPUT jira/structuredoctor/CoreProposalModels.groovy
 * INPUT jira/structuredoctor/CoreProposalPlanner.groovy
 * INPUT jira/structuredoctor/CoreRead.groovy
 * INPUT jira/structuredoctor/CoreRepairCoordinator.groovy
 * INPUT jira/structuredoctor/CoreRepairModels.groovy
 * INPUT jira/structuredoctor/CoreRepairPolicy.groovy
 * INPUT jira/structuredoctor/CoreSupport.groovy
 * INPUT jira/structuredoctor/DisabledRepairInfrastructure.groovy
 * INPUT jira/structuredoctor/DoctorApplication.groovy
 * INPUT jira/structuredoctor/DoctorContracts.groovy
 * INPUT jira/structuredoctor/DoctorHttpGuard.groovy
 * INPUT jira/structuredoctor/DoctorRenderer.groovy
 * INPUT jira/structuredoctor/DoctorRepairApplication.groovy
 * INPUT jira/structuredoctor/DoctorRequestModels.groovy
 * INPUT jira/structuredoctor/JsonAutomationProvider.groovy
 * INPUT jira/structuredoctor/LegacyIssueDoctor.groovy
 * INPUT jira/structuredoctor/LiveAutomationProvider.groovy
 * INPUT jira/structuredoctor/LiveConfigurationDiscovery.groovy
 * INPUT jira/structuredoctor/LiveJiraGateway.groovy
 * INPUT jira/structuredoctor/LiveRepairInfrastructure.groovy
 * INPUT jira/structuredoctor/LiveStructureGateway.groovy
 * INPUT_SHA256 a569fb65851ddd4dd525e6a3c6070a46368385687eb7f98a5792e86de9532140
 */

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
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
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
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.KnownImmutable
import java.lang.reflect.Array
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.codehaus.groovy.runtime.InvokerHelper
import static structuredoctor.CoreRepairPolicy.confirmationBlockers
import static structuredoctor.CoreRepairPolicy.freshnessBlockers
import static structuredoctor.CoreRepairPolicy.operationStatus
import static structuredoctor.CoreRepairPolicy.requestFingerprint
import static structuredoctor.CoreRepairPolicy.result
import static structuredoctor.CoreRepairPolicy.validOperationId
import static structuredoctor.CoreRepairPolicy.validate

// SOURCE: jira/structuredoctor/CoreAutomationAnalyzer.groovy
@CompileStatic
final class CoreAutomationAnalyzer {
    AutomationAnalysis analyze(ReadResult<List<AutomationRuleSnapshot>> ruleRead,
                               ReadResult<List<AutomationAuditSnapshot>> auditRead,
                               AutomationAnalysisContext context) {
        List<String> blockers = []
        if (ruleRead == null || !ruleRead.complete() || ruleRead.value == null) {
            blockers.add('automation-rules')
        }
        if (auditRead == null || !auditRead.complete()) {
            blockers.add('automation-audit-coverage')
        }
        if (context == null || context.scope == null) {
            blockers.add('analysis-scope')
        }
        List<AutomationRuleSnapshot> rules = (ruleRead?.value ?: []).findAll {
            AutomationRuleSnapshot rule -> rule.enabled && relevant(rule, context?.scope)
        }
        if (rules.any { AutomationRuleSnapshot rule -> !rule.complete }) {
            blockers.add('automation-rule-normalization')
        }
        List<AutomationRuleSnapshot> analyzableRules = rules.findAll {
            AutomationRuleSnapshot rule -> rule.complete
        }
        List<AutomationFinding> findings = []
        Map<String, List<AutomationRuleSnapshot>> writers = writerIndex(analyzableRules)
        for (Map.Entry<String, List<AutomationRuleSnapshot>> entry : writers.entrySet()) {
            List<AutomationRuleSnapshot> overlapping = overlappingWriters(entry.value)
            if (overlapping.size() > 1) {
                findings.add(finding(AutomationConflictType.MULTIPLE_WRITERS,
                    overlapping, entry.key, 'Multiple enabled rules write the same target'))
                if (sourceSets(overlapping, entry.key).size() > 1) {
                    findings.add(finding(AutomationConflictType.CONFLICTING_SOURCES,
                        overlapping, entry.key, 'Rules derive the target from different sources'))
                }
                if (overlapping.any { AutomationRuleSnapshot rule -> rule.asynchronous }) {
                    findings.add(finding(AutomationConflictType.ASYNCHRONOUS_RACE,
                        overlapping, entry.key, 'Asynchronous writers have last-writer ambiguity'))
                }
            }
        }
        addClearFindings(analyzableRules, findings)
        addChainFindings(analyzableRules, findings)
        addHierarchyFindings(analyzableRules, context, findings, blockers)
        addStructureConsumerFindings(analyzableRules, context, findings)
        findings.sort { AutomationFinding left, AutomationFinding right ->
            int typeOrder = left.type.name() <=> right.type.name()
            typeOrder != 0 ? typeOrder : left.target <=> right.target
        }
        new AutomationAnalysis(
            findings: findings,
            rules: rules,
            auditCoverage: auditRead?.coverage,
            complete: blockers.isEmpty(),
            blockers: blockers.unique().sort()
        )
    }

    private static void addClearFindings(List<AutomationRuleSnapshot> rules,
                                         List<AutomationFinding> findings) {
        for (AutomationRuleSnapshot rule : rules) {
            for (String target : rule.clears ?: []) {
                findings.add(finding(AutomationConflictType.CLEAR_ON_EMPTY,
                    [rule], target, 'Rule can clear a hierarchy-related target'))
            }
        }
    }

    private static void addChainFindings(List<AutomationRuleSnapshot> rules,
                                         List<AutomationFinding> findings) {
        Set<String> targets = rules.collectMany { AutomationRuleSnapshot rule -> rule.writes ?: [] } as Set<String>
        for (String target : targets) {
            List<AutomationRuleSnapshot> writers = rules.findAll {
                AutomationRuleSnapshot rule -> (rule.writes ?: []).contains(target)
            }
            List<AutomationRuleSnapshot> readers = rules.findAll {
                AutomationRuleSnapshot rule -> rule.allowOtherRuleTrigger &&
                    (rule.reads ?: []).contains(target)
            }
            if (!writers.isEmpty() && !readers.isEmpty()) {
                findings.add(finding(AutomationConflictType.POSSIBLE_CHAIN,
                    uniqueRules(writers + readers), target,
                    'One rule can feed another rule'))
            }
        }
    }

    private static void addHierarchyFindings(List<AutomationRuleSnapshot> rules,
                                             AutomationAnalysisContext context,
                                             List<AutomationFinding> findings,
                                             List<String> blockers) {
        if (context?.hierarchy == null || context.hierarchy.levels == null) {
            blockers.add('jira-hierarchy')
            return
        }
        Set<Long> knownTypes = context.hierarchy.levels.collectMany {
            HierarchyLevel level -> level.issueTypeIds ?: []
        } as Set<Long>
        Set<String> targets = (context.hierarchyTargets ?: []) as Set<String>
        for (AutomationRuleSnapshot rule : rules) {
            if (!Collections.disjoint(rule.writes ?: [], targets) &&
                (rule.issueTypeIds ?: []).any { Long id -> !knownTypes.contains(id) }) {
                findings.add(finding(AutomationConflictType.INVALID_TARGET_LEVEL,
                    [rule], ((rule.writes ?: []).find { String value -> targets.contains(value) }),
                    'Rule scope includes an issue type outside the configured hierarchy'))
            }
        }
    }

    private static void addStructureConsumerFindings(List<AutomationRuleSnapshot> rules,
                                                     AutomationAnalysisContext context,
                                                     List<AutomationFinding> findings) {
        Set<String> consumed = (context?.structureConsumedValues ?: []) as Set<String>
        for (AutomationRuleSnapshot rule : rules) {
            for (String target : (rule.writes ?: []).findAll { String value -> consumed.contains(value) }) {
                findings.add(finding(AutomationConflictType.STRUCTURE_CONSUMED_WRITE,
                    [rule], target, 'Rule writes a value consumed by the selected Structure'))
            }
        }
    }

    private static Map<String, List<AutomationRuleSnapshot>> writerIndex(
        List<AutomationRuleSnapshot> rules) {
        Map<String, List<AutomationRuleSnapshot>> values = new LinkedHashMap<>()
        for (AutomationRuleSnapshot rule : rules) {
            for (String target : rule.writes ?: []) {
                values.computeIfAbsent(target) { String ignored -> [] }.add(rule)
            }
        }
        values
    }

    private static List<AutomationRuleSnapshot> overlappingWriters(
        List<AutomationRuleSnapshot> rules) {
        rules.findAll { AutomationRuleSnapshot left ->
            rules.any { AutomationRuleSnapshot right ->
                left.ruleId != right.ruleId && scopesOverlap(left, right)
            }
        }
    }

    private static boolean scopesOverlap(AutomationRuleSnapshot left,
                                         AutomationRuleSnapshot right) {
        intersectsOrGlobal(left.projectIds, right.projectIds) &&
            intersectsOrGlobal(left.issueTypeIds, right.issueTypeIds)
    }

    private static boolean relevant(AutomationRuleSnapshot rule, AnalysisScope scope) {
        if (scope == null) {
            return true
        }
        intersectsOrGlobal(rule.projectIds, scope.projectIds) &&
            intersectsOrGlobal(rule.issueTypeIds, scope.issueTypeIds)
    }

    private static boolean intersectsOrGlobal(List<Long> left, List<Long> right) {
        left == null || left.isEmpty() || right == null || right.isEmpty() ||
            !Collections.disjoint(left, right)
    }

    private static Set<List<String>> sourceSets(List<AutomationRuleSnapshot> rules, String target) {
        rules.collect { AutomationRuleSnapshot rule ->
            (rule.sourcesByTarget?.get(target) ?: []).sort()
        } as Set<List<String>>
    }

    private static List<AutomationRuleSnapshot> uniqueRules(List<AutomationRuleSnapshot> rules) {
        Map<Long, AutomationRuleSnapshot> values = new LinkedHashMap<>()
        rules.each { AutomationRuleSnapshot rule -> values.put(rule.ruleId, rule) }
        values.values() as List<AutomationRuleSnapshot>
    }

    private static AutomationFinding finding(AutomationConflictType type,
                                             List<AutomationRuleSnapshot> rules,
                                             String target,
                                             String summary) {
        List<Long> ruleIds = rules*.ruleId.sort()
        List<EvidenceRequirement> requirements = rules.findAll {
            AutomationRuleSnapshot rule -> rule.revision != null && !rule.revision.trim().isEmpty()
        }.collect { AutomationRuleSnapshot rule ->
            new EvidenceRequirement('automation-rule:' + rule.ruleId, rule.revision, true)
        }
        new AutomationFinding(
            id: CoreCanonical.deterministicId('automation-finding', [
                type: type.name(), target: target, ruleIds: ruleIds
            ]),
            type: type,
            ruleIds: ruleIds,
            target: target,
            summary: summary,
            requirements: requirements,
            blockers: []
        )
    }
}

// SOURCE: jira/structuredoctor/CoreAutomationJsonSupport.groovy
@CompileStatic
final class CoreAutomationJsonSupport {
    private static final Set<String> RULE_KEYS = [
        'id', 'enabled', 'projects', 'issueTypes', 'reads', 'writes', 'clears',
        'sourcesByTarget', 'trigger', 'orderedComponents', 'conditions',
        'asynchronous', 'allowOtherRuleTrigger', 'actor', 'revision', 'complete'
    ] as Set<String>
    private static final Set<String> AUDIT_KEYS = [
        'ruleId', 'issueId', 'occurredAt', 'action', 'target', 'successful', 'revision'
    ] as Set<String>
    private static final Set<String> OFFICIAL_RULE_KEYS = [
        'id', 'clientKey', 'name', 'state', 'description', 'authorAccountId',
        'actorAccountId', 'created', 'updated', 'trigger', 'components', 'projects',
        'canOtherRuleTrigger', 'notifyOnError', 'labels', 'tags'
    ] as Set<String>
    private static final Set<String> OFFICIAL_REQUIRED_KEYS = [
        'id', 'state', 'updated', 'trigger', 'components', 'projects',
        'canOtherRuleTrigger'
    ] as Set<String>
    private static final Set<String> COMPONENT_KEYS = [
        'id', 'parentId', 'conditionParentId', 'component', 'schemaVersion',
        'type', 'value', 'children', 'conditions'
    ] as Set<String>
    private static final Set<String> COMPONENT_REQUIRED_KEYS = [
        'id', 'component', 'schemaVersion', 'type', 'children', 'conditions'
    ] as Set<String>

    private CoreAutomationJsonSupport() {
        throw new UnsupportedOperationException('utility class')
    }

    static AutomationRuleSnapshot rule(Map<String, Object> value, String exportVersion) {
        requireExactKeys(value, RULE_KEYS, RULE_KEYS)
        new AutomationRuleSnapshot(
            ruleId: longValue(value.id, 'id'),
            enabled: booleanValue(value.enabled, 'enabled'),
            projectIds: longList(value.projects, 'projects'),
            issueTypeIds: longList(value.issueTypes, 'issueTypes'),
            reads: stringList(value.reads, 'reads'),
            writes: stringList(value.writes, 'writes'),
            clears: stringList(value.clears, 'clears'),
            sourcesByTarget: stringListMap(value.sourcesByTarget, 'sourcesByTarget'),
            trigger: text(value.trigger, 'trigger'),
            orderedComponents: stringList(value.orderedComponents, 'orderedComponents'),
            conditions: stringList(value.conditions, 'conditions'),
            asynchronous: booleanValue(value.asynchronous, 'asynchronous'),
            allowOtherRuleTrigger: booleanValue(
                value.allowOtherRuleTrigger, 'allowOtherRuleTrigger'),
            actor: text(value.actor, 'actor'),
            revision: text(value.revision, 'revision'),
            exportVersion: exportVersion,
            complete: booleanValue(value.complete, 'complete')
        )
    }

    static AutomationAuditSnapshot audit(Map<String, Object> value) {
        requireExactKeys(value, AUDIT_KEYS, AUDIT_KEYS)
        new AutomationAuditSnapshot(
            ruleId: longValue(value.ruleId, 'ruleId'),
            issueId: longValue(value.issueId, 'issueId'),
            occurredAt: text(value.occurredAt, 'occurredAt'),
            action: text(value.action, 'action'),
            target: text(value.target, 'target'),
            successful: booleanValue(value.successful, 'successful'),
            revision: text(value.revision, 'revision')
        )
    }

    static AutomationRuleSnapshot officialRule(Map<String, Object> value) {
        requireExactKeys(value, OFFICIAL_RULE_KEYS, OFFICIAL_REQUIRED_KEYS)
        Map<String, Object> triggerNode = objectValue(value.trigger, 'trigger')
        List<Map<String, Object>> nodes = []
        flattenComponent(triggerNode, nodes)
        for (Map<String, Object> component : objectList(value.components, 'components')) {
            flattenComponent(component, nodes)
        }
        List<String> reads = officialReads(nodes)
        boolean opaqueAction = nodes.any { Map<String, Object> node ->
            node.component == 'ACTION'
        }
        Map<String, Object> triggerValue = valueMap(triggerNode.value)
        List<Long> projects = objectList(value.projects, 'projects').collect {
            Map<String, Object> project -> flexibleLong(project.projectId, 'projectId')
        }
        new AutomationRuleSnapshot(
            ruleId: flexibleLong(value.id, 'id'),
            enabled: text(value.state, 'state') == 'ENABLED',
            projectIds: projects,
            issueTypeIds: [],
            reads: reads,
            writes: [],
            clears: [],
            sourcesByTarget: [:],
            trigger: text(triggerNode.type, 'trigger.type'),
            orderedComponents: nodes*.component.collect { Object item -> String.valueOf(item) },
            conditions: nodes.findAll { Map<String, Object> node ->
                String.valueOf(node.component).startsWith('CONDITION')
            }*.type.collect { Object item -> String.valueOf(item) },
            asynchronous: triggerValue.containsKey('synchronous') &&
                !booleanValue(triggerValue.synchronous, 'synchronous'),
            allowOtherRuleTrigger: booleanValue(
                value.canOtherRuleTrigger, 'canOtherRuleTrigger'),
            actor: value.actorAccountId == null ? 'UNSPECIFIED_ACTOR' : 'CONFIGURED_ACTOR',
            revision: revision(value.updated, 'updated'),
            exportVersion: 'jira-automation-dc',
            complete: !opaqueAction
        )
    }

    static void requireExactKeys(Map<String, Object> value,
                                 Set<String> allowed,
                                 Set<String> required) {
        if (value == null || !allowed.containsAll(value.keySet()) ||
            !value.keySet().containsAll(required)) {
            throw new IllegalArgumentException('JSON object does not match schema')
        }
    }

    static List<Map<String, Object>> objectList(Object value, String name) {
        if (!(value instanceof List) || ((List<?>) value).any { Object item -> !(item instanceof Map) }) {
            throw new IllegalArgumentException(name + ' must be an object array')
        }
        (List<Map<String, Object>>) value
    }

    static long longValue(Object value, String name) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + ' must be numeric')
        }
        BigDecimal decimal = new BigDecimal(value.toString())
        try {
            decimal.longValueExact()
        } catch (ArithmeticException ignored) {
            throw new IllegalArgumentException(name + ' must be an integer')
        }
    }

    static long flexibleLong(Object value, String name) {
        if (value instanceof Number) {
            return longValue(value, name)
        }
        if (value instanceof String && value ==~ /[0-9]+/) {
            try {
                return Long.parseLong((String) value)
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException(name + ' exceeds signed long range')
            }
        }
        throw new IllegalArgumentException(name + ' must be a numeric identifier')
    }

    static boolean booleanValue(Object value, String name) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + ' must be boolean')
        }
        (Boolean) value
    }

    static String text(Object value, String name) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(name + ' must be non-blank text')
        }
        (String) value
    }

    static String revision(Object value, String name) {
        if (value instanceof Number) {
            return String.valueOf(longValue(value, name))
        }
        text(value, name)
    }

    static List<Long> longList(Object value, String name) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(name + ' must be an array')
        }
        ((List<?>) value).collect { Object item -> longValue(item, name) }
    }

    static List<String> stringList(Object value, String name) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(name + ' must be an array')
        }
        ((List<?>) value).collect { Object item -> text(item, name) }
    }

    static Map<String, List<String>> stringListMap(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(name + ' must be an object')
        }
        Map<String, List<String>> result = new LinkedHashMap<>()
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = text(entry.key, name)
            result.put(key, stringList(entry.value, name))
        }
        result
    }

    private static Map<String, Object> objectValue(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(name + ' must be an object')
        }
        (Map<String, Object>) value
    }

    private static Map<String, Object> valueMap(Object value) {
        value instanceof Map ? (Map<String, Object>) value : [:]
    }

    private static void flattenComponent(Map<String, Object> node,
                                         List<Map<String, Object>> result) {
        requireExactKeys(node, COMPONENT_KEYS, COMPONENT_REQUIRED_KEYS)
        result.add(node)
        for (Map<String, Object> child : objectList(node.children, 'children')) {
            flattenComponent(child, result)
        }
        for (Map<String, Object> condition : objectList(node.conditions, 'conditions')) {
            flattenComponent(condition, result)
        }
    }

    private static List<String> officialReads(List<Map<String, Object>> nodes) {
        LinkedHashSet<String> reads = [] as LinkedHashSet<String>
        for (Map<String, Object> node : nodes) {
            Map<String, Object> componentValue = valueMap(node.value)
            Object fields = componentValue.fields
            if (fields instanceof List) {
                for (Object field : (List<?>) fields) {
                    Map<String, Object> fieldValue = objectValue(field, 'field')
                    reads.add('field:' + text(fieldValue.value, 'field.value'))
                }
            }
            Object linkTypes = componentValue.linkTypes
            if (linkTypes instanceof List) {
                for (Object linkType : (List<?>) linkTypes) {
                    reads.add('link:' + flexibleLong(linkType, 'linkType'))
                }
            }
        }
        reads as List<String>
    }
}

// SOURCE: jira/structuredoctor/CoreAutomationModels.groovy
enum AutomationConflictType {
    MULTIPLE_WRITERS,
    CONFLICTING_SOURCES,
    CLEAR_ON_EMPTY,
    ASYNCHRONOUS_RACE,
    POSSIBLE_CHAIN,
    INVALID_TARGET_LEVEL,
    STRUCTURE_CONSUMED_WRITE
}

@Immutable(copyWith = true)
class AutomationFinding {
    String id
    AutomationConflictType type
    List<Long> ruleIds
    String target
    String summary
    List<EvidenceRequirement> requirements
    List<String> blockers
}

@Immutable(copyWith = true)
class AutomationAnalysisContext {
    AnalysisScope scope
    HierarchySnapshot hierarchy
    List<String> hierarchyTargets
    List<String> structureConsumedValues
}

@Immutable(copyWith = true)
class AutomationAnalysis {
    List<AutomationFinding> findings
    List<AutomationRuleSnapshot> rules
    Coverage auditCoverage
    boolean complete
    List<String> blockers

    List<AutomationFinding> findAll(AutomationConflictType type) {
        findings.findAll { AutomationFinding finding -> finding.type == type }
    }
}

// SOURCE: jira/structuredoctor/CoreCanonical.groovy
@CompileStatic
final class CoreCanonical {
    private CoreCanonical() {
        throw new UnsupportedOperationException('utility class')
    }

    static String canonicalJson(Object value) {
        JsonOutput.toJson(normalize(value))
    }

    static String sha256(Object value) {
        byte[] bytes = canonicalJson(value).getBytes(StandardCharsets.UTF_8)
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(bytes)
        digest.collect { byte item -> String.format('%02x', item & 0xff) }.join('')
    }

    static String deterministicId(String namespace, Object value) {
        if (namespace == null || !(namespace ==~ /[a-z][a-z0-9-]{1,40}/)) {
            throw new IllegalArgumentException('invalid deterministic ID namespace')
        }
        namespace + ':' + sha256(value)
    }

    static String planningFingerprint(StructureSnapshot snapshot) {
        if (snapshot == null || !snapshot.complete || snapshot.hierarchy == null) {
            return null
        }
        if (snapshot.generators.any { GeneratorSnapshot generator -> !generator.complete }) {
            return null
        }
        if (snapshot.occurrences.any { OccurrenceSnapshot occurrence -> !occurrence.provenanceComplete }) {
            return null
        }
        sha256(snapshotValue(snapshot))
    }

    static Object normalize(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum) {
            return String.valueOf(value)
        }
        if (value instanceof Number) {
            return normalizeNumber((Number) value)
        }
        if (value instanceof Map) {
            Map<String, Object> normalized = new TreeMap<String, Object>()
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.key)
                if (normalized.containsKey(key)) {
                    throw new IllegalArgumentException('canonical map key collision: ' + key)
                }
                normalized.put(key, normalize(entry.value))
            }
            return normalized
        }
        if (value instanceof Set) {
            List<Object> normalized = ((Set<?>) value).collect { Object item -> normalize(item) }
            normalized.sort { Object left, Object right ->
                JsonOutput.toJson(left) <=> JsonOutput.toJson(right)
            }
            return normalized
        }
        if (value instanceof Iterable) {
            return ((Iterable<?>) value).collect { Object item -> normalize(item) }
        }
        if (value.class.isArray()) {
            List<Object> normalized = []
            int length = Array.getLength(value)
            for (int index = 0; index < length; index++) {
                normalized.add(normalize(Array.get(value, index)))
            }
            return normalized
        }
        throw new IllegalArgumentException('unsupported canonical value: ' + value.class.name)
    }

    private static Object normalizeNumber(Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer ||
            number instanceof Long || number instanceof BigInteger) {
            BigInteger integral = new BigInteger(number.toString())
            if (integral < BigInteger.valueOf(Long.MIN_VALUE) || integral > BigInteger.valueOf(Long.MAX_VALUE)) {
                throw new IllegalArgumentException('integral value exceeds signed long range')
            }
            return integral.longValue()
        }
        if ((number instanceof Double && !Double.isFinite(number.doubleValue())) ||
            (number instanceof Float && !Float.isFinite(number.floatValue()))) {
            throw new IllegalArgumentException('non-finite number is not canonical')
        }
        BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros()
        if (decimal.scale() <= 0) {
            try {
                return decimal.longValueExact()
            } catch (ArithmeticException ignored) {
                throw new IllegalArgumentException('integral value exceeds signed long range')
            }
        }
        decimal
    }

    private static Map<String, Object> snapshotValue(StructureSnapshot snapshot) {
        [
            structureId: snapshot.structureId,
            revision: snapshot.revision,
            hierarchy: [
                levels: snapshot.hierarchy.levels.collect { HierarchyLevel level ->
                    [rank: level.rank, levelId: level.levelId, issueTypeIds: level.issueTypeIds]
                }
            ],
            generators: snapshot.generators.collect { GeneratorSnapshot generator ->
                [id: generator.generatorId, moduleKey: generator.moduleKey, type: generator.type,
                 order: generator.order, enabled: generator.enabled, parameters: generator.parameters,
                 revision: generator.revision]
            },
            occurrences: snapshot.occurrences.collect { OccurrenceSnapshot occurrence ->
                [id: occurrence.occurrenceId, issueId: occurrence.issueId, rowId: occurrence.rowId,
                 parentPath: occurrence.parentPath, parentIssueId: occurrence.parentIssueId,
                 depth: occurrence.depth, position: occurrence.position,
                 provenance: occurrence.provenance, creatorId: occurrence.creatorId]
            },
            relations: snapshot.relations.collect { IssueRelationSnapshot relation ->
                [issueId: relation.issueId, issueTypeId: relation.issueTypeId,
                 nativeParentId: relation.nativeParentId, leadingParentIds: relation.leadingParentIds,
                 revisions: relation.revisions]
            }
        ] as Map<String, Object>
    }
}

// SOURCE: jira/structuredoctor/CoreCausalityEngine.groovy
@CompileStatic
final class CoreCausalityEngine {
    private static final List<CausalEdgeType> CHAIN = [
        CausalEdgeType.AUTOMATION_TO_JIRA_DATA,
        CausalEdgeType.JIRA_DATA_TO_GENERATOR,
        CausalEdgeType.GENERATOR_TO_OCCURRENCE,
        CausalEdgeType.OCCURRENCE_TO_FINDING
    ].asImmutable()

    CausalClaim claim(CausalContext context) {
        if (context == null || blank(context.findingId)) {
            throw new IllegalArgumentException('findingId is required')
        }
        Map<CausalEdgeType, CausalEdge> byType = new LinkedHashMap<>()
        for (CausalEdge edge : context.edges ?: []) {
            validate(edge)
            if (byType.put(edge.type, edge) != null) {
                throw new IllegalArgumentException('duplicate causal edge type: ' + edge.type)
            }
        }

        List<String> edgeIds = []
        List<String> presentEvidence = []
        List<String> missingEvidence = []
        boolean allPresent = true
        for (CausalEdgeType type : CHAIN) {
            CausalEdge edge = byType.get(type)
            if (edge == null || !edge.present) {
                allPresent = false
                missingEvidence.add(type.name())
            } else {
                edgeIds.add(edge.id)
                presentEvidence.add(edge.evidenceId)
            }
        }

        CausalEdge automationEdge = byType.get(CausalEdgeType.AUTOMATION_TO_JIRA_DATA)
        boolean directlyExecuted = automationEdge != null &&
            automationEdge.present && automationEdge.direct
        boolean completeAudit = context.auditCoverage != null &&
            context.auditCoverage.complete()
        EvidenceGrade grade = grade(
            context.configurationConflict,
            !presentEvidence.isEmpty(),
            allPresent,
            directlyExecuted,
            completeAudit)

        List<String> blockers = new ArrayList<>(context.blockers ?: [])
        if (!completeAudit) {
            blockers.add('automation-audit-coverage')
        }
        blockers = blockers.unique().sort()
        List<EvidenceRequirement> requirements = context.requirements ?: []
        String id = CoreCanonical.deterministicId('causal-claim', [
            findingId: context.findingId,
            grade: grade.name(),
            edges: CHAIN.collect { CausalEdgeType type ->
                CausalEdge edge = byType.get(type)
                [type: type.name(), present: edge?.present ?: false,
                 direct: edge?.direct ?: false, evidenceId: edge?.evidenceId]
            },
            requirements: requirements.collect { EvidenceRequirement requirement ->
                [source: requirement.source, fingerprint: requirement.fingerprint,
                 required: requirement.required]
            }
        ])
        new CausalClaim(
            id: id,
            findingId: context.findingId,
            grade: grade,
            edgeIds: edgeIds,
            presentEvidence: presentEvidence,
            missingEvidence: missingEvidence,
            auditCoverage: context.auditCoverage,
            requirements: requirements,
            blockers: blockers
        )
    }

    private static EvidenceGrade grade(boolean configurationConflict,
                                       boolean hasEvidence,
                                       boolean allPresent,
                                       boolean directlyExecuted,
                                       boolean completeAudit) {
        if (allPresent && directlyExecuted && completeAudit) {
            return EvidenceGrade.CONFIRMED_CAUSE
        }
        if (allPresent) {
            return EvidenceGrade.PROBABLE_CAUSE
        }
        if (hasEvidence) {
            return EvidenceGrade.POSSIBLE_CAUSE
        }
        configurationConflict ? EvidenceGrade.CONFIGURATION_CONFLICT :
            EvidenceGrade.POSSIBLE_CAUSE
    }

    private static void validate(CausalEdge edge) {
        if (edge == null || edge.type == null || blank(edge.id) ||
            blank(edge.fromId) || blank(edge.toId)) {
            throw new IllegalArgumentException('causal edge identity is incomplete')
        }
        if (edge.present && blank(edge.evidenceId)) {
            throw new IllegalArgumentException('present causal edge requires evidence')
        }
        if (edge.direct && !edge.present) {
            throw new IllegalArgumentException('direct causal edge must be present')
        }
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}

// SOURCE: jira/structuredoctor/CoreCausalityModels.groovy
enum CausalEdgeType {
    AUTOMATION_TO_JIRA_DATA,
    JIRA_DATA_TO_GENERATOR,
    GENERATOR_TO_OCCURRENCE,
    OCCURRENCE_TO_FINDING
}

@Immutable(copyWith = true)
class CausalEdge {
    String id
    CausalEdgeType type
    String fromId
    String toId
    String evidenceId
    boolean present
    boolean direct
}

@Immutable(copyWith = true)
class CausalContext {
    String findingId
    List<CausalEdge> edges
    boolean configurationConflict
    Coverage auditCoverage
    List<EvidenceRequirement> requirements
    List<String> blockers
}

// SOURCE: jira/structuredoctor/CoreDuplicateAnalyzer.groovy
@CompileStatic
final class CoreDuplicateAnalyzer {
    DuplicateAnalysis analyze(StructureSnapshot snapshot) {
        if (snapshot == null || snapshot.occurrences == null) {
            return new DuplicateAnalysis(
                groups: [], findings: [], complete: false,
                blockers: ['structure-forest'])
        }

        Map<Long, List<OccurrenceSnapshot>> byIssue = new LinkedHashMap<>()
        for (OccurrenceSnapshot occurrence : snapshot.occurrences) {
            List<OccurrenceSnapshot> values = byIssue.get(occurrence.issueId)
            if (values == null) {
                values = []
                byIssue.put(occurrence.issueId, values)
            }
            values.add(occurrence)
        }

        Map<Long, IssueRelationSnapshot> relations = CoreDuplicateSupport.relationIndex(snapshot.relations)
        Map<Long, HierarchyLevel> levels = CoreDuplicateSupport.hierarchyIndex(snapshot.hierarchy)
        Map<String, GeneratorSnapshot> generators = CoreDuplicateSupport.generatorIndex(snapshot.generators)
        List<GeneratorSnapshot> duplicateFilters = (snapshot.generators ?: []).findAll {
            GeneratorSnapshot generator ->
                generator.enabled && generator.type == 'DUPLICATES_FILTER'
        }

        List<DuplicateGroup> groups = []
        List<Finding> findings = []
        for (Map.Entry<Long, List<OccurrenceSnapshot>> entry : byIssue.entrySet()) {
            if (entry.value.size() < 2) {
                continue
            }
            DuplicateGroup group = buildGroup(snapshot, entry.key, entry.value,
                relations, levels, generators, duplicateFilters)
            groups.add(group)
            findings.add(buildFinding(snapshot, group))
        }

        List<String> blockers = groups.collectMany { DuplicateGroup group -> group.blockers }
            .unique().sort()
        Set<String> evidenceBlockers = [
            'structure-snapshot',
            'structure-generators',
            'jira-hierarchy',
            'jira-relations',
            'occurrence-provenance',
            'physical-occurrence-identity'
        ] as Set<String>
        new DuplicateAnalysis(
            groups: groups,
            findings: findings,
            complete: snapshot.complete && !blockers.any {
                String blocker -> evidenceBlockers.contains(blocker)
            },
            blockers: blockers
        )
    }

    private static DuplicateGroup buildGroup(StructureSnapshot snapshot,
                                             long issueId,
                                             List<OccurrenceSnapshot> occurrences,
                                             Map<Long, IssueRelationSnapshot> relations,
                                             Map<Long, HierarchyLevel> levels,
                                             Map<String, GeneratorSnapshot> generators,
                                             List<GeneratorSnapshot> duplicateFilters) {
        List<String> blockers = []
        if (!snapshot.complete) {
            blockers.add('structure-snapshot')
        }
        if ((snapshot.generators ?: []).any { GeneratorSnapshot generator -> !generator.complete }) {
            blockers.add('structure-generators')
        }
        if (snapshot.hierarchy == null || levels.isEmpty() ||
            blank(snapshot.hierarchy.fingerprint) ||
            !CoreDuplicateSupport.validHierarchyMapping(snapshot.hierarchy)) {
            blockers.add('jira-hierarchy')
        }

        IssueRelationSnapshot relation = relations.get(issueId)
        HierarchyLevel childLevel = relation == null ? null : levels.get(relation.issueTypeId)
        if (relation == null) {
            blockers.add('jira-relations')
        } else if (childLevel == null) {
            blockers.add('jira-hierarchy')
        }

        List<DuplicateOccurrence> choices = []
        Set<String> occurrenceIds = [] as Set<String>
        int ordinal = 0
        for (OccurrenceSnapshot occurrence : occurrences) {
            ordinal++
            if (blank(occurrence.occurrenceId) || !occurrenceIds.add(occurrence.occurrenceId)) {
                blockers.add('physical-occurrence-identity')
            }
            if (!occurrence.provenanceComplete || blank(occurrence.provenance) ||
                (occurrence.provenance != 'PERMANENT' && blank(occurrence.creatorId))) {
                blockers.add('occurrence-provenance')
            }
            boolean hierarchyValid = hierarchyValid(
                occurrence, relation, childLevel, relations, levels)
            boolean nativeHierarchy = relation != null &&
                relation.nativeParentId != null &&
                relation.nativeParentId == occurrence.parentIssueId
            choices.add(new DuplicateOccurrence(
                occurrenceId: occurrence.occurrenceId,
                retainChoiceId: 'retain:' + occurrence.occurrenceId,
                ordinal: ordinal,
                rowId: occurrence.rowId,
                parentPath: occurrence.parentPath,
                parentIssueId: occurrence.parentIssueId,
                provenance: occurrence.provenance,
                creatorId: occurrence.creatorId,
                hierarchyValid: hierarchyValid,
                nativeHierarchy: nativeHierarchy,
                recommended: false,
                explanations: occurrenceExplanations(hierarchyValid, nativeHierarchy)
            ))
        }

        List<DuplicateOccurrence> valid = choices.findAll {
            DuplicateOccurrence choice -> choice.hierarchyValid
        }
        if (valid.isEmpty()) {
            blockers.add('no-hierarchy-valid-occurrence')
        }
        String recommendation = recommendation(valid)
        if (recommendation != null) {
            choices = choices.collect { DuplicateOccurrence choice ->
                choice.copyWith(recommended: choice.occurrenceId == recommendation)
            }
        }

        List<String> explanations = CoreDuplicateSupport.groupExplanations(
            occurrences, generators, duplicateFilters)
        String groupId = CoreCanonical.deterministicId('duplicate-group', [
            structureId: snapshot.structureId,
            issueId: issueId,
            occurrenceIds: choices*.occurrenceId
        ])
        new DuplicateGroup(
            id: groupId,
            issueId: issueId,
            occurrences: choices,
            recommendedOccurrenceId: recommendation,
            selectable: blockers.isEmpty(),
            explanations: explanations,
            blockers: blockers.unique().sort()
        )
    }

    private static boolean hierarchyValid(OccurrenceSnapshot occurrence,
                                          IssueRelationSnapshot relation,
                                          HierarchyLevel childLevel,
                                          Map<Long, IssueRelationSnapshot> relations,
                                          Map<Long, HierarchyLevel> levels) {
        if (relation == null || childLevel == null) {
            return false
        }
        long topRank = levels.values().collect { HierarchyLevel level -> level.rank }.max()
        if (childLevel.rank == topRank) {
            return occurrence.parentIssueId == null
        }
        if (occurrence.parentIssueId == null) {
            return false
        }
        IssueRelationSnapshot parent = relations.get(occurrence.parentIssueId)
        HierarchyLevel parentLevel = parent == null ? null : levels.get(parent.issueTypeId)
        parentLevel != null && parentLevel.rank == childLevel.rank + 1L
    }

    private static String recommendation(List<DuplicateOccurrence> valid) {
        if (valid.size() == 1) {
            return valid[0].occurrenceId
        }
        List<DuplicateOccurrence> nativeChoices = valid.findAll {
            DuplicateOccurrence choice -> choice.nativeHierarchy
        }
        nativeChoices.size() == 1 ? nativeChoices[0].occurrenceId : null
    }

    private static List<String> occurrenceExplanations(boolean valid, boolean nativeHierarchy) {
        List<String> values = [valid ? 'Path follows a configured hierarchy level' :
            'Path does not follow a configured hierarchy level']
        if (nativeHierarchy) {
            values.add('Path follows the native Jira parent relation')
        }
        values
    }

    private static Finding buildFinding(StructureSnapshot snapshot, DuplicateGroup group) {
        List<EvidenceRequirement> requirements = []
        if (snapshot.hierarchy != null && !blank(snapshot.hierarchy.fingerprint)) {
            requirements.add(new EvidenceRequirement(
                'jira-hierarchy', snapshot.hierarchy.fingerprint, true))
        }
        if (!blank(snapshot.fingerprint)) {
            requirements.add(new EvidenceRequirement(
                'structure-snapshot', snapshot.fingerprint, true))
        }
        new Finding(
            id: CoreCanonical.deterministicId('finding', [groupId: group.id]),
            type: FindingType.DUPLICATE,
            issueId: group.issueId,
            occurrenceIds: group.occurrences*.occurrenceId,
            summary: 'Work item has ' + group.occurrences.size() + ' physical occurrences',
            requirements: requirements,
            blockers: group.blockers
        )
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}

// SOURCE: jira/structuredoctor/CoreDuplicateModels.groovy
@Immutable(copyWith = true)
class DuplicateOccurrence {
    String occurrenceId
    String retainChoiceId
    int ordinal
    String rowId
    List<Long> parentPath
    Long parentIssueId
    String provenance
    String creatorId
    boolean hierarchyValid
    boolean nativeHierarchy
    boolean recommended
    List<String> explanations
}

@Immutable(copyWith = true)
class DuplicateGroup {
    String id
    long issueId
    List<DuplicateOccurrence> occurrences
    String recommendedOccurrenceId
    boolean selectable
    List<String> explanations
    List<String> blockers
}

@Immutable(copyWith = true)
class DuplicateAnalysis {
    List<DuplicateGroup> groups
    List<Finding> findings
    boolean complete
    List<String> blockers

    boolean clean() {
        complete && groups.isEmpty()
    }
}

// SOURCE: jira/structuredoctor/CoreDuplicateSupport.groovy
@CompileStatic
final class CoreDuplicateSupport {
    private CoreDuplicateSupport() {
        throw new UnsupportedOperationException('utility class')
    }

    static List<String> groupExplanations(List<OccurrenceSnapshot> occurrences,
                                          Map<String, GeneratorSnapshot> generators,
                                          List<GeneratorSnapshot> filters) {
        List<String> values = []
        Set<String> creators = occurrences*.creatorId.findAll {
            String value -> !blank(value)
        } as Set<String>
        Set<List<Long>> paths = occurrences*.parentPath as Set<List<Long>>
        Set<String> provenance = occurrences*.provenance.findAll {
            String value -> !blank(value)
        } as Set<String>
        if (creators.size() > 1) {
            values.add('Multiple generators render this work item')
        }
        if (paths.size() > 1) {
            values.add('Occurrences follow multiple paths')
        }
        if (provenance.contains('PERMANENT') && provenance.size() > 1) {
            values.add('Permanent and generated occurrences overlap')
        }
        if (provenance.contains('ADVANCED_ROADMAPS') && provenance.contains('JIRA_LINK')) {
            values.add('Native hierarchy and Jira link paths overlap')
        }
        if (filters.isEmpty()) {
            values.add('No enabled duplicates filter is present')
        } else if (filterRunsBeforeSource(creators, generators, filters)) {
            values.add('Duplicates filter runs before an occurrence source')
        }
        if (paths.size() == 1 && creators.size() <= 1) {
            values.add('Semantically identical occurrences remain separate physical rows')
        }
        values
    }

    static Map<Long, IssueRelationSnapshot> relationIndex(List<IssueRelationSnapshot> relations) {
        Map<Long, IssueRelationSnapshot> values = [:]
        for (IssueRelationSnapshot relation : relations ?: []) {
            values.put(relation.issueId, relation)
        }
        values
    }

    static Map<Long, HierarchyLevel> hierarchyIndex(HierarchySnapshot hierarchy) {
        Map<Long, HierarchyLevel> values = [:]
        for (HierarchyLevel level : hierarchy?.levels ?: []) {
            for (Long issueTypeId : level.issueTypeIds ?: []) {
                values.put(issueTypeId, level)
            }
        }
        values
    }

    static boolean validHierarchyMapping(HierarchySnapshot hierarchy) {
        Set<Long> assigned = [] as Set<Long>
        for (HierarchyLevel level : hierarchy?.levels ?: []) {
            for (Long issueTypeId : level.issueTypeIds ?: []) {
                if (issueTypeId == null || !assigned.add(issueTypeId)) {
                    return false
                }
            }
        }
        !assigned.isEmpty()
    }

    static Map<String, GeneratorSnapshot> generatorIndex(List<GeneratorSnapshot> generators) {
        Map<String, GeneratorSnapshot> values = [:]
        for (GeneratorSnapshot generator : generators ?: []) {
            values.put(String.valueOf(generator.generatorId), generator)
        }
        values
    }

    private static boolean filterRunsBeforeSource(Set<String> creators,
                                                  Map<String, GeneratorSnapshot> generators,
                                                  List<GeneratorSnapshot> filters) {
        List<Integer> sourceOrders = creators.collect {
            String creator -> generators.get(creator)?.order
        }.findAll { Integer order -> order != null }
        sourceOrders.isEmpty() ? false : filters.any { GeneratorSnapshot filter ->
            filter.order <= sourceOrders.max()
        }
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}

// SOURCE: jira/structuredoctor/CoreHierarchyAnalyzer.groovy
@CompileStatic
final class CoreHierarchyAnalyzer {
    HierarchyAnalysis analyze(StructureSnapshot snapshot) {
        List<String> blockers = readinessBlockers(snapshot)
        if (!blockers.isEmpty()) {
            return result([], blockers)
        }

        Map<Long, HierarchyLevel> levelsByIssueType = [:]
        for (HierarchyLevel level : snapshot.hierarchy.levels) {
            for (Long issueTypeId : level.issueTypeIds) {
                if (issueTypeId == null || levelsByIssueType.containsKey(issueTypeId)) {
                    blockers.add('jira-hierarchy')
                } else {
                    levelsByIssueType.put(issueTypeId, level)
                }
            }
        }
        if (!blockers.isEmpty()) {
            return result([], blockers)
        }

        Map<Long, IssueRelationSnapshot> relationsByIssue = [:]
        for (IssueRelationSnapshot relation : snapshot.relations) {
            if (relationsByIssue.put(relation.issueId, relation) != null) {
                blockers.add('jira-relations')
            }
        }
        if (!blockers.isEmpty()) {
            return result([], blockers)
        }

        long topRank = snapshot.hierarchy.levels.collect { HierarchyLevel level -> level.rank }.max()
        List<Finding> findings = []
        for (IssueRelationSnapshot relation : snapshot.relations) {
            analyzeRelation(snapshot, relation, relationsByIssue, levelsByIssueType,
                topRank, findings, blockers)
        }
        for (OccurrenceSnapshot occurrence : snapshot.occurrences) {
            analyzeOccurrence(snapshot, occurrence, relationsByIssue, findings, blockers)
        }

        findings.sort { Finding left, Finding right ->
            int issueOrder = left.issueId <=> right.issueId
            if (issueOrder != 0) {
                return issueOrder
            }
            int typeOrder = left.type.name() <=> right.type.name()
            typeOrder != 0 ? typeOrder :
                left.occurrenceIds.join(',') <=> right.occurrenceIds.join(',')
        }
        result(findings, blockers.unique().sort())
    }

    private static List<String> readinessBlockers(StructureSnapshot snapshot) {
        if (snapshot == null || !snapshot.complete || snapshot.hierarchy == null ||
            snapshot.hierarchy.levels == null || snapshot.hierarchy.levels.isEmpty() ||
            blank(snapshot.hierarchy.fingerprint)) {
            return ['jira-hierarchy']
        }
        if (snapshot.relations == null) {
            return ['jira-relations']
        }
        if (snapshot.occurrences == null) {
            return ['structure-forest']
        }
        []
    }

    private static void analyzeRelation(StructureSnapshot snapshot,
                                        IssueRelationSnapshot relation,
                                        Map<Long, IssueRelationSnapshot> relationsByIssue,
                                        Map<Long, HierarchyLevel> levelsByIssueType,
                                        long topRank,
                                        List<Finding> findings,
                                        List<String> blockers) {
        HierarchyLevel childLevel = levelsByIssueType.get(relation.issueTypeId)
        if (childLevel == null) {
            blockers.add('jira-hierarchy')
            return
        }

        List<Long> leadingParents = relation.leadingParentIds ?: []
        if (relation.nativeParentId == null) {
            if (leadingParents.size() == 1) {
                findings.add(finding(snapshot, FindingType.MISSING_PARENT,
                    relation.issueId, [], 'Native parent is missing'))
            } else if (leadingParents.size() > 1) {
                findings.add(finding(snapshot, FindingType.CONFLICTING_PARENT,
                    relation.issueId, [], 'Multiple parent relationships compete'))
            } else if (childLevel.rank < topRank) {
                findings.add(finding(snapshot, FindingType.ORPHAN,
                    relation.issueId, [], 'Work item has no parent relationship'))
            }
            return
        }

        if (!leadingParents.isEmpty() &&
            (leadingParents.size() > 1 || !leadingParents.contains(relation.nativeParentId))) {
            findings.add(finding(snapshot, FindingType.CONFLICTING_PARENT,
                relation.issueId, [], 'Configured parent relationships disagree'))
        }

        IssueRelationSnapshot parent = relationsByIssue.get(relation.nativeParentId)
        if (parent == null) {
            blockers.add('jira-relations')
            return
        }
        HierarchyLevel parentLevel = levelsByIssueType.get(parent.issueTypeId)
        if (parentLevel == null) {
            blockers.add('jira-hierarchy')
            return
        }
        if (parentLevel.rank != childLevel.rank + 1L) {
            findings.add(finding(snapshot, FindingType.INVALID_LEVEL,
                relation.issueId, [], 'Parent is on an invalid hierarchy level'))
        }
    }

    private static void analyzeOccurrence(StructureSnapshot snapshot,
                                          OccurrenceSnapshot occurrence,
                                          Map<Long, IssueRelationSnapshot> relationsByIssue,
                                          List<Finding> findings,
                                          List<String> blockers) {
        IssueRelationSnapshot relation = relationsByIssue.get(occurrence.issueId)
        if (relation == null) {
            blockers.add('jira-relations')
            return
        }
        if (relation.nativeParentId != null &&
            occurrence.parentIssueId != relation.nativeParentId) {
            findings.add(finding(snapshot, FindingType.WRONG_PATH,
                occurrence.issueId, [occurrence.occurrenceId],
                'Occurrence is rendered below a different parent'))
        }
    }

    private static Finding finding(StructureSnapshot snapshot,
                                   FindingType type,
                                   long issueId,
                                   List<String> occurrenceIds,
                                   String summary) {
        Map<String, Object> identity = [
            structureId: snapshot.structureId,
            type: type.name(),
            issueId: issueId,
            occurrences: occurrenceIds
        ]
        new Finding(
            id: CoreCanonical.deterministicId('finding', identity),
            type: type,
            issueId: issueId,
            occurrenceIds: occurrenceIds,
            summary: summary,
            requirements: [new EvidenceRequirement(
                'jira-hierarchy', snapshot.hierarchy.fingerprint, true)],
            blockers: []
        )
    }

    private static HierarchyAnalysis result(List<Finding> findings, List<String> blockers) {
        new HierarchyAnalysis(
            findings: findings,
            complete: blockers.isEmpty(),
            blockers: blockers
        )
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}

// SOURCE: jira/structuredoctor/CoreImpactSimulator.groovy
@CompileStatic
final class CoreImpactSimulator {
    ImpactResult simulate(SimulationRequest request) {
        List<String> blockers = []
        if (request == null || request.beforeSnapshot == null ||
            request.afterSnapshot == null || request.repairPackage == null) {
            return result(false, [], [], [], [], ['simulation-input'])
        }
        if (!request.capabilityAvailable) {
            blockers.add('repair-capability-unavailable')
        }
        if (!request.beforeSnapshot.complete) {
            blockers.add('incomplete-before-population')
        }
        if (!request.afterSnapshot.complete) {
            blockers.add('incomplete-preview')
        }
        if (!request.repairPackage.selectable) {
            blockers.add('repair-package-blocked')
        }
        checkFingerprints(request, blockers)

        Map<String, OccurrenceSnapshot> before = occurrenceIndex(
            request.beforeSnapshot.occurrences, 'before', blockers)
        Map<String, OccurrenceSnapshot> after = occurrenceIndex(
            request.afterSnapshot.occurrences, 'after', blockers)
        List<String> added = after.keySet().findAll { String id -> !before.containsKey(id) } as List<String>
        List<String> removed = before.keySet().findAll { String id -> !after.containsKey(id) } as List<String>
        List<String> moved = []
        List<String> unchanged = []
        for (String id : before.keySet().findAll { String value -> after.containsKey(value) }) {
            if (signature(before.get(id)) == signature(after.get(id))) {
                unchanged.add(id)
            } else {
                moved.add(id)
            }
        }

        checkRetainedOccurrences(request, after, blockers)
        checkPermanentRows(request, removed, blockers)
        checkFindingChanges(request, blockers)
        result(
            request.beforeSnapshot.complete && request.afterSnapshot.complete,
            added, removed, moved, unchanged, blockers.unique().sort())
    }

    private static void checkFingerprints(SimulationRequest request,
                                          List<String> blockers) {
        Map<String, String> current = request.currentFingerprints ?: [:]
        for (EvidenceRequirement requirement : request.repairPackage.requirements ?: []) {
            if (requirement.required && current.get(requirement.source) != requirement.fingerprint) {
                blockers.add('stale-dependency:' + requirement.source)
            }
        }
    }

    private static Map<String, OccurrenceSnapshot> occurrenceIndex(
        List<OccurrenceSnapshot> occurrences,
        String side,
        List<String> blockers) {
        if (occurrences == null) {
            blockers.add('unknown-' + side + '-population')
            return [:]
        }
        Map<String, OccurrenceSnapshot> values = new LinkedHashMap<>()
        for (OccurrenceSnapshot occurrence : occurrences) {
            if (occurrence.occurrenceId == null ||
                values.put(occurrence.occurrenceId, occurrence) != null) {
                blockers.add('invalid-' + side + '-occurrence-identity')
            }
        }
        values
    }

    private static void checkRetainedOccurrences(SimulationRequest request,
                                                 Map<String, OccurrenceSnapshot> after,
                                                 List<String> blockers) {
        Map<String, Finding> beforeFindings = (request.beforeFindings ?: []).collectEntries {
            Finding finding -> [(finding.id): finding]
        } as Map<String, Finding>
        for (String groupId : request.repairPackage.findingGroupIds ?: []) {
            Finding finding = beforeFindings.get(groupId)
            if (finding?.type != FindingType.DUPLICATE) {
                continue
            }
            String retained = request.retainOccurrenceByGroup?.get(groupId)
            if (retained == null) {
                blockers.add('retain-occurrence-required:' + groupId)
            } else if (!after.containsKey(retained)) {
                blockers.add('retained-occurrence-lost:' + retained)
            }
        }
    }

    private static void checkPermanentRows(SimulationRequest request,
                                           List<String> removed,
                                           List<String> blockers) {
        Set<String> selected = (request.selectedPermanentRowIds ?: []) as Set<String>
        for (String rowId : request.repairPackage.permanentRowIds ?: []) {
            if (removed.contains(rowId) && !selected.contains(rowId)) {
                blockers.add('permanent-row-not-selected:' + rowId)
            }
        }
    }

    private static void checkFindingChanges(SimulationRequest request,
                                            List<String> blockers) {
        Set<String> beforeIds = (request.beforeFindings ?: [])*.id as Set<String>
        Set<String> afterIds = (request.afterFindings ?: [])*.id as Set<String>
        Set<String> selected = (request.selectedFindingIds ?: []) as Set<String>
        Set<String> approvedNew = (request.approvedNewFindingIds ?: []) as Set<String>
        for (String findingId : afterIds - beforeIds) {
            if (!approvedNew.contains(findingId)) {
                blockers.add('new-unapproved-finding:' + findingId)
            }
        }
        for (String findingId : beforeIds - afterIds) {
            if (!selected.contains(findingId)) {
                blockers.add('unselected-finding-changed:' + findingId)
            }
        }
    }

    private static Map<String, Object> signature(OccurrenceSnapshot occurrence) {
        [
            rowId: occurrence.rowId,
            parentPath: occurrence.parentPath,
            parentIssueId: occurrence.parentIssueId,
            depth: occurrence.depth,
            position: occurrence.position,
            provenance: occurrence.provenance,
            creatorId: occurrence.creatorId
        ] as Map<String, Object>
    }

    private static ImpactResult result(boolean complete,
                                       List<String> added,
                                       List<String> removed,
                                       List<String> moved,
                                       List<String> unchanged,
                                       List<String> blockers) {
        new ImpactResult(
            complete: complete,
            safeToApply: blockers.isEmpty(),
            added: added,
            removed: removed,
            moved: moved,
            unchanged: unchanged,
            blockers: blockers
        )
    }
}

// SOURCE: jira/structuredoctor/CoreModels.groovy
@Immutable(copyWith = true)
class HierarchyLevel {
    long rank
    String levelId
    String name
    List<Long> issueTypeIds
}

@Immutable(copyWith = true)
class HierarchySnapshot {
    List<HierarchyLevel> levels
    String fingerprint
}

@Immutable(copyWith = true)
class GeneratorSnapshot {
    long generatorId
    String moduleKey
    String type
    int order
    boolean enabled
    Map<String, Object> parameters
    String revision
    boolean complete
}

@Immutable(copyWith = true)
class OccurrenceSnapshot {
    String occurrenceId
    long issueId
    String rowId
    List<Long> parentPath
    Long parentIssueId
    int depth
    int position
    String provenance
    String creatorId
    boolean provenanceComplete
}

@Immutable(copyWith = true)
class IssueRelationSnapshot {
    long issueId
    long issueTypeId
    Long nativeParentId
    List<Long> leadingParentIds
    Map<String, String> revisions
}

@Immutable(copyWith = true)
class StructureSnapshot {
    long structureId
    String revision
    HierarchySnapshot hierarchy
    List<GeneratorSnapshot> generators
    List<OccurrenceSnapshot> occurrences
    List<IssueRelationSnapshot> relations
    String fingerprint
    boolean complete

    String planningFingerprint() {
        CoreCanonical.planningFingerprint(this)
    }
}

@Immutable(copyWith = true)
class AnalysisScope {
    List<Long> projectIds
    List<Long> issueTypeIds
    List<String> fieldIds
    List<Long> linkTypeIds
}

@Immutable(copyWith = true)
class AutomationRuleSnapshot {
    long ruleId
    boolean enabled
    List<Long> projectIds
    List<Long> issueTypeIds
    List<String> reads
    List<String> writes
    List<String> clears
    Map<String, List<String>> sourcesByTarget
    String trigger
    List<String> orderedComponents
    List<String> conditions
    boolean asynchronous
    boolean allowOtherRuleTrigger
    String actor
    String revision
    String exportVersion
    boolean complete
}

@Immutable(copyWith = true)
class AutomationAuditSnapshot {
    long ruleId
    long issueId
    String occurredAt
    String action
    String target
    boolean successful
    String revision
}

@Immutable(copyWith = true)
class AuditRequest {
    List<Long> ruleIds
    List<Long> issueIds
    int requestedDays
}

enum FindingType {
    DUPLICATE,
    MISSING_PARENT,
    CONFLICTING_PARENT,
    INVALID_LEVEL,
    ORPHAN,
    WRONG_PATH,
    GENERATOR_OVERLAP,
    AUTOMATION_CONFLICT
}

@Immutable(copyWith = true)
class Finding {
    String id
    FindingType type
    long issueId
    List<String> occurrenceIds
    String summary
    List<EvidenceRequirement> requirements
    List<String> blockers
}

@Immutable(copyWith = true)
class HierarchyAnalysis {
    List<Finding> findings
    boolean complete
    List<String> blockers

    boolean clean() {
        complete && findings.isEmpty()
    }
}

enum EvidenceGrade {
    CONFIGURATION_CONFLICT,
    POSSIBLE_CAUSE,
    PROBABLE_CAUSE,
    CONFIRMED_CAUSE
}

@Immutable(copyWith = true)
class CausalClaim {
    String id
    String findingId
    EvidenceGrade grade
    List<String> edgeIds
    List<String> presentEvidence
    List<String> missingEvidence
    Coverage auditCoverage
    List<EvidenceRequirement> requirements
    List<String> blockers
}

// SOURCE: jira/structuredoctor/CoreProposalModels.groovy
@Immutable(copyWith = true)
class RepairSelection {
    List<String> findingGroupIds
    Map<String, String> retainOccurrenceByGroup
    List<String> selectedPermanentRowIds
}

@Immutable(copyWith = true)
class ProposalCandidate {
    String sourceId
    RepairStrategy strategy
    RepairKind kind
    List<String> findingGroupIds
    List<Long> affectedIssueIds
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
    List<String> permanentRowIds
    String explanation
    List<String> warnings
    List<EvidenceRequirement> requirements
    List<String> blockers
}

@Immutable(copyWith = true)
class ProposalPlan {
    List<RepairPackage> packages
    boolean complete
    List<String> blockers
}

@Immutable(copyWith = true)
class SimulationRequest {
    StructureSnapshot beforeSnapshot
    StructureSnapshot afterSnapshot
    RepairPackage repairPackage
    Map<String, String> retainOccurrenceByGroup
    List<String> selectedFindingIds
    List<String> approvedNewFindingIds
    List<Finding> beforeFindings
    List<Finding> afterFindings
    Map<String, String> currentFingerprints
    List<String> selectedPermanentRowIds
    boolean capabilityAvailable
}

// SOURCE: jira/structuredoctor/CoreProposalPlanner.groovy
@CompileStatic
final class CoreProposalPlanner {
    ProposalPlan plan(StructureSnapshot snapshot,
                      DuplicateAnalysis duplicates,
                      RepairSelection selection,
                      ProposalSource source) {
        List<String> blockers = validate(snapshot, duplicates, selection, source)
        if (selection == null || selection.findingGroupIds == null ||
            selection.findingGroupIds.isEmpty()) {
            return new ProposalPlan(
                packages: [], complete: blockers.isEmpty(), blockers: blockers)
        }

        Map<String, DuplicateGroup> groups = (duplicates?.groups ?: []).collectEntries {
            DuplicateGroup group -> [(group.id): group]
        } as Map<String, DuplicateGroup>
        validateSelection(selection, groups, blockers)
        if (!blockers.isEmpty()) {
            return new ProposalPlan(packages: [], complete: false,
                blockers: blockers.unique().sort())
        }

        ReadResult<List<ProposalCandidate>> candidateRead = source.readCandidates(snapshot)
        if (candidateRead == null || !candidateRead.complete() || candidateRead.value == null) {
            blockers.add('proposal-source')
            return new ProposalPlan(packages: [], complete: false,
                blockers: blockers.unique().sort())
        }

        Set<String> selected = selection.findingGroupIds as Set<String>
        List<ProposalCandidate> relevant = candidateRead.value.findAll {
            ProposalCandidate candidate ->
                !(candidate.findingGroupIds as Set<String>).intersect(selected).isEmpty()
        }
        for (String groupId : selected) {
            if (!relevant.any { ProposalCandidate candidate ->
                candidate.findingGroupIds.contains(groupId)
            }) {
                blockers.add('no-proposal:' + groupId)
            }
        }
        List<RepairPackage> packages = relevant.collect {
            ProposalCandidate candidate -> buildPackage(snapshot, candidate)
        }
        new ProposalPlan(
            packages: packages,
            complete: blockers.isEmpty(),
            blockers: blockers.unique().sort()
        )
    }

    private static List<String> validate(StructureSnapshot snapshot,
                                         DuplicateAnalysis duplicates,
                                         RepairSelection selection,
                                         ProposalSource source) {
        List<String> blockers = []
        if (snapshot == null || !snapshot.complete || snapshot.planningFingerprint() == null) {
            blockers.add('structure-snapshot')
        }
        if (duplicates == null || !duplicates.complete) {
            blockers.add('duplicate-analysis')
        }
        if (selection == null) {
            blockers.add('repair-selection')
        }
        if (source == null) {
            blockers.add('proposal-source')
        }
        blockers
    }

    private static void validateSelection(RepairSelection selection,
                                          Map<String, DuplicateGroup> groups,
                                          List<String> blockers) {
        Set<String> selected = new LinkedHashSet<>(selection.findingGroupIds ?: [])
        if (selected.size() != (selection.findingGroupIds ?: []).size()) {
            blockers.add('duplicate-finding-selection')
        }
        for (String groupId : selected) {
            DuplicateGroup group = groups.get(groupId)
            if (group == null) {
                blockers.add('unknown-finding-group:' + groupId)
                continue
            }
            if (!group.selectable) {
                blockers.add('blocked-finding-group:' + groupId)
            }
            String retained = selection.retainOccurrenceByGroup?.get(groupId)
            if (retained == null || !group.occurrences*.occurrenceId.contains(retained)) {
                blockers.add('invalid-retain-occurrence:' + groupId)
            }
        }
        for (String groupId : selection.retainOccurrenceByGroup?.keySet() ?: []) {
            if (!selected.contains(groupId)) {
                blockers.add('unexpected-retain-selection:' + groupId)
            }
        }
    }

    private static RepairPackage buildPackage(StructureSnapshot snapshot,
                                              ProposalCandidate candidate) {
        List<String> candidateBlockers = new ArrayList<>(candidate.blockers ?: [])
        List<EvidenceRequirement> requirements = dependencyRequirements(
            snapshot, candidate, candidateBlockers)
        List<Confirmation> confirmations = [Confirmation.STRUCTURE_CHANGE]
        if (candidate.kind == RepairKind.JIRA_DATA) {
            confirmations.add(Confirmation.JIRA_DATA_CHANGE)
        }
        Map<String, Object> identity = [
            structureId: snapshot.structureId,
            sourceId: candidate.sourceId,
            strategy: candidate.strategy.name(),
            kind: candidate.kind.name(),
            groups: candidate.findingGroupIds,
            issues: candidate.affectedIssueIds,
            generators: candidate.generatorIds,
            before: candidate.beforeState,
            after: candidate.afterState
        ]
        new RepairPackage(
            id: CoreCanonical.deterministicId('repair-package', identity),
            kind: candidate.kind,
            strategy: candidate.strategy,
            findingGroupIds: candidate.findingGroupIds,
            affectedIssueIds: candidate.affectedIssueIds,
            generatorIds: candidate.generatorIds,
            beforeState: candidate.beforeState,
            afterState: candidate.afterState,
            permanentRowIds: candidate.permanentRowIds,
            explanation: candidate.explanation,
            warnings: candidate.warnings,
            confirmations: confirmations,
            requirements: requirements,
            blockers: candidateBlockers,
            selectable: candidateBlockers.isEmpty()
        )
    }

    private static List<EvidenceRequirement> dependencyRequirements(
        StructureSnapshot snapshot, ProposalCandidate candidate, List<String> blockers) {
        Map<String, EvidenceRequirement> values = new LinkedHashMap<>()
        addRequirement(values, new EvidenceRequirement(
            'structure:' + snapshot.structureId,
            snapshot.planningFingerprint(), true), blockers)
        if (snapshot.hierarchy?.fingerprint) {
            addRequirement(values, new EvidenceRequirement(
                'jira-hierarchy', snapshot.hierarchy.fingerprint, true), blockers)
        } else {
            blockers.add('dependency-fingerprint:jira-hierarchy')
        }
        for (Long generatorId : candidate.generatorIds ?: []) {
            GeneratorSnapshot generator = snapshot.generators.find {
                GeneratorSnapshot item -> item.generatorId == generatorId
            }
            if (generator?.revision) {
                addRequirement(values, new EvidenceRequirement(
                    'generator:' + generatorId, generator.revision, true), blockers)
            } else {
                blockers.add('dependency-fingerprint:generator:' + generatorId)
            }
        }
        for (Long issueId : candidate.affectedIssueIds ?: []) {
            IssueRelationSnapshot relation = snapshot.relations.find {
                IssueRelationSnapshot item -> item.issueId == issueId
            }
            if (relation != null) {
                String issueFingerprint = relation.revisions?.get('issue') ?:
                    CoreCanonical.sha256(relation.revisions ?: [:])
                addRequirement(values, new EvidenceRequirement(
                    'jira-issue:' + issueId, issueFingerprint, true), blockers)
            } else {
                blockers.add('dependency-fingerprint:jira-issue:' + issueId)
            }
        }
        for (EvidenceRequirement requirement : candidate.requirements ?: []) {
            addRequirement(values, requirement, blockers)
        }
        values.values() as List<EvidenceRequirement>
    }

    private static void addRequirement(Map<String, EvidenceRequirement> values,
                                       EvidenceRequirement requirement,
                                       List<String> blockers) {
        EvidenceRequirement previous = values.get(requirement.source)
        if (previous != null && previous.fingerprint != requirement.fingerprint) {
            blockers.add('dependency-conflict:' + requirement.source)
        } else {
            values.put(requirement.source, requirement)
        }
    }
}

// SOURCE: jira/structuredoctor/CoreRead.groovy
@CompileStatic
enum ReadState {
    COMPLETE,
    INCOMPLETE,
    FAILED,
    UNAVAILABLE
}

@CompileStatic
@KnownImmutable
final class Coverage {
    final long requested
    final long actual
    final boolean capped
    final String fromInclusive
    final String toInclusive

    private Coverage(long requested, long actual, boolean capped,
                     String fromInclusive, String toInclusive) {
        if (requested < 0L) {
            throw new IllegalArgumentException('requested must not be negative')
        }
        if (actual < 0L || actual > requested) {
            throw new IllegalArgumentException('actual must be between zero and requested')
        }
        this.requested = requested
        this.actual = actual
        this.capped = capped
        this.fromInclusive = fromInclusive
        this.toInclusive = toInclusive
    }

    static Coverage bounded(long requested, long actual, boolean capped,
                            String fromInclusive, String toInclusive) {
        new Coverage(requested, actual, capped, fromInclusive, toInclusive)
    }

    boolean complete() {
        !capped && actual == requested
    }
}

@CompileStatic
final class EvidenceRequirement {
    final String source
    final String fingerprint
    final boolean required

    EvidenceRequirement(String source, String fingerprint, boolean required) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException('source is required')
        }
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException('fingerprint is required')
        }
        this.source = source
        this.fingerprint = fingerprint
        this.required = required
    }
}

@CompileStatic
final class ReadResult<T> {
    final ReadState state
    final T value
    final String reason
    final Coverage coverage

    private ReadResult(ReadState state, T value, String reason, Coverage coverage) {
        if (state == null) {
            throw new IllegalArgumentException('state is required')
        }
        if (state != ReadState.COMPLETE && (reason == null || reason.trim().isEmpty())) {
            throw new IllegalArgumentException('non-complete reads require a reason')
        }
        this.state = state
        this.value = value
        this.reason = reason
        this.coverage = coverage
    }

    static <T> ReadResult<T> complete(T value) {
        new ReadResult<T>(ReadState.COMPLETE, value, null, null)
    }

    static <T> ReadResult<T> complete(T value, Coverage coverage) {
        new ReadResult<T>(ReadState.COMPLETE, value, null, coverage)
    }

    static <T> ReadResult<T> incomplete(T partialValue, String reason) {
        new ReadResult<T>(ReadState.INCOMPLETE, partialValue, reason, null)
    }

    static <T> ReadResult<T> incomplete(T partialValue, String reason, Coverage coverage) {
        new ReadResult<T>(ReadState.INCOMPLETE, partialValue, reason, coverage)
    }

    static <T> ReadResult<T> failed(String reason) {
        new ReadResult<T>(ReadState.FAILED, null, reason, null)
    }

    static <T> ReadResult<T> unavailable(String reason) {
        new ReadResult<T>(ReadState.UNAVAILABLE, null, reason, null)
    }

    boolean complete() {
        state == ReadState.COMPLETE && (coverage == null || coverage.complete())
    }
}

// SOURCE: jira/structuredoctor/CoreRepairCoordinator.groovy
final class CoreRepairCoordinator {
    private static final Map<OperationState, Set<OperationState>> TRANSITIONS = [
        (OperationState.ANALYZED): [OperationState.PLANNED] as Set,
        (OperationState.PLANNED): [OperationState.CONFIRMED] as Set,
        (OperationState.CONFIRMED): [OperationState.APPLIED,
            OperationState.MUTATION_FAILED,
            OperationState.MANUAL_RECOVERY_REQUIRED] as Set,
        (OperationState.APPLIED): [OperationState.VERIFYING] as Set,
        (OperationState.VERIFYING): [OperationState.VERIFIED, OperationState.PENDING,
            OperationState.ROLLED_BACK, OperationState.MANUAL_RECOVERY_REQUIRED] as Set,
        (OperationState.PENDING): [OperationState.VERIFYING] as Set
    ].asImmutable()

    private final RepairInfrastructure infrastructure
    CoreRepairCoordinator(RepairInfrastructure infrastructure) {
        if (infrastructure == null) throw new IllegalArgumentException('infrastructure is required')
        this.infrastructure = infrastructure
    }
    RepairCoordinatorResult apply(RepairApplyRequest request) {
        RepairCoordinatorResult unavailable = unavailable()
        if (unavailable != null) return unavailable
        validate(request)
        String requestFingerprint = requestFingerprint(request)
        RepairOperation existing = infrastructure.find(request.operationId)
        if (existing != null) {
            RepairCoordinatorResult previous = replay(existing, requestFingerprint)
            if (existing.state != OperationState.PENDING ||
                previous.code == 'OPERATION_ID_CONFLICT') return previous
            return resume(existing.operationId, request.actorKey)
        }
        try {
            return (RepairCoordinatorResult) infrastructure.withLocks(
                request.structureId, request.affectedIssueIds ?: [], {
                    RepairOperation raced = infrastructure.find(request.operationId)
                    if (raced != null) {
                        RepairCoordinatorResult previous = replay(raced, requestFingerprint)
                        if (raced.state != OperationState.PENDING ||
                            previous.code == 'OPERATION_ID_CONFLICT') return previous
                        return resumeLocked(raced, request.actorKey)
                    }
                    RepairOperation pending = infrastructure.findPending(
                        request.structureId, request.affectedIssueIds ?: [])
                    if (pending != null && pending.operationId != request.operationId) {
                        return result(409, 'PENDING_OPERATION', pending, false,
                            [pending.operationId])
                    }
                    RepairRefresh refresh = infrastructure.refresh(request)
                    List<String> blockers = freshnessBlockers(request, refresh)
                    if (blockers.contains('repair-refresh')) {
                        return result(409, 'STALE_PLAN', null, false, blockers)
                    }
                    if (refresh?.targetPermitted != Boolean.TRUE) {
                        return result(403, 'TARGET_PERMISSION_REQUIRED', null, false, [])
                    }
                    if (!blockers.isEmpty()) {
                        return result(409, 'STALE_PLAN', null, false, blockers)
                    }
                    List<String> confirmationBlockers = confirmationBlockers(
                        request, refresh.repairPackage)
                    if (!confirmationBlockers.isEmpty()) {
                        return result(409, 'CONFIRMATION_REQUIRED', null, false,
                            confirmationBlockers)
                    }
                    return execute(request, requestFingerprint, refresh)
                } as Closure<Object>)
        } catch (RepairLockUnavailableException failure) {
            String code = failure.message in [
                'STRUCTURE_LOCK_UNAVAILABLE', 'ISSUE_LOCK_UNAVAILABLE'] ?
                failure.message : 'LOCK_UNAVAILABLE'
            return result(409, code, null, false, [code])
        }
    }

    RepairCoordinatorResult resume(String operationId, String actorKey) {
        RepairCoordinatorResult unavailable = unavailable()
        if (unavailable != null) return unavailable
        if (!validOperationId(operationId)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
        RepairOperation operation = infrastructure.find(operationId)
        if (operation == null) return result(404, 'OPERATION_NOT_FOUND', null, false, [])
        if (operation.actorKey != actorKey) return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
        if (operation.state != OperationState.PENDING) {
            return result(409, 'OPERATION_NOT_PENDING', operation, false, [])
        }
        RepairRefresh context = infrastructure.resumeContext(operation)
        if (context?.repairPackage == null) {
            return result(409, 'RECOVERY_CONTEXT_UNAVAILABLE', operation, false, [])
        }
        try {
            return (RepairCoordinatorResult) infrastructure.withLocks(
                operation.structureId, context.repairPackage.affectedIssueIds ?: [], {
                    RepairOperation current = infrastructure.find(operationId)
                    if (current == null) {
                        return result(404, 'OPERATION_NOT_FOUND', null, false, [])
                    }
                    if (current.actorKey != actorKey) {
                        return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
                    }
                    if (current.state != OperationState.PENDING) {
                        return result(200, current.state.name(), current, true, [])
                    }
                    resumeLocked(current, actorKey)
                } as Closure<Object>)
        } catch (RepairLockUnavailableException failure) {
            return result(409, failure.message ?: 'LOCK_UNAVAILABLE', operation,
                false, [failure.message ?: 'LOCK_UNAVAILABLE'])
        }
    }

    RepairCoordinatorResult status(String operationId, String actorKey) {
        RepairCoordinatorResult unavailable = unavailable()
        if (unavailable != null) return unavailable
        if (!validOperationId(operationId)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
        RepairOperation operation = infrastructure.find(operationId)
        if (operation == null) return result(404, 'OPERATION_NOT_FOUND', null, false, [])
        if (operation.actorKey != actorKey) {
            return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
        }
        result(operationStatus(operation.state), operation.state.name(),
            operation, false, [])
    }

    private RepairCoordinatorResult resumeLocked(RepairOperation operation,
                                                 String actorKey) {
        if (operation.actorKey != actorKey) {
            return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
        }
        RepairRefresh refreshed = infrastructure.resumeContext(operation)
        if (refreshed?.repairPackage == null) {
            return result(409, 'RECOVERY_CONTEXT_UNAVAILABLE', operation, false, [])
        }
        if (refreshed.targetPermitted != Boolean.TRUE) {
            return result(403, 'TARGET_PERMISSION_REQUIRED', operation, false, [])
        }
        RepairOperation verifying = advance(operation, OperationState.VERIFYING)
        infrastructure.persist(verifying, refreshed.repairPackage)
        finishVerification(verifying, refreshed, infrastructure.verify(refreshed, false))
    }

    private RepairCoordinatorResult execute(RepairApplyRequest request,
                                            String requestFingerprint,
                                            RepairRefresh refresh) {
        RepairOperation operation = new RepairOperation(
            operationId: request.operationId, structureId: request.structureId,
            repairPackageId: request.repairPackageId, fingerprint: requestFingerprint,
            actorKey: request.actorKey, state: OperationState.CONFIRMED,
            history: [OperationState.ANALYZED, OperationState.PLANNED,
                      OperationState.CONFIRMED])
        infrastructure.persist(operation, refresh.repairPackage)
        RepairMutationOutcome mutation
        try {
            mutation = infrastructure.mutate(refresh.repairPackage)
        } catch (RuntimeException ignored) {
            return finish(operation, refresh.repairPackage,
                OperationState.MANUAL_RECOVERY_REQUIRED, 500)
        }
        if (mutation == null) {
            return finish(operation, refresh.repairPackage,
                OperationState.MANUAL_RECOVERY_REQUIRED, 500)
        }
        if (mutation.disposition == MutationDisposition.NOT_APPLIED) {
            return finish(operation, refresh.repairPackage,
                OperationState.MUTATION_FAILED, 500)
        }
        operation = advance(operation, OperationState.APPLIED)
        infrastructure.persist(operation, refresh.repairPackage)
        operation = advance(operation, OperationState.VERIFYING)
        infrastructure.persist(operation, refresh.repairPackage)
        if (mutation.disposition == MutationDisposition.PARTIAL) {
            return recover(operation, refresh)
        }
        finishVerification(operation, refresh, infrastructure.verify(refresh, false))
    }

    private RepairCoordinatorResult finishVerification(RepairOperation operation,
                                                        RepairRefresh refresh,
                                                        VerificationOutcome verification) {
        if (verification?.disposition == VerificationDisposition.VERIFIED) {
            return finish(operation, refresh.repairPackage, OperationState.VERIFIED, 200)
        }
        if (verification?.disposition == VerificationDisposition.PENDING) {
            return finish(operation, refresh.repairPackage, OperationState.PENDING, 202)
        }
        recover(operation, refresh)
    }

    private RepairCoordinatorResult recover(RepairOperation operation,
                                             RepairRefresh refresh) {
        RepairMutationOutcome restored = infrastructure.restore(refresh.repairPackage)
        if (restored?.disposition == MutationDisposition.APPLIED) {
            VerificationOutcome verification = infrastructure.verify(refresh, true)
            if (verification?.disposition == VerificationDisposition.VERIFIED) {
                return finish(operation, refresh.repairPackage,
                    OperationState.ROLLED_BACK, 409)
            }
        }
        finish(operation, refresh.repairPackage,
            OperationState.MANUAL_RECOVERY_REQUIRED, 500)
    }

    private RepairCoordinatorResult finish(RepairOperation operation,
                                           RepairPackage repairPackage,
                                           OperationState target,
                                           int status) {
        RepairOperation changed = advance(operation, target)
        infrastructure.persist(changed, repairPackage)
        result(status, target.name(), changed, false, [])
    }

    private RepairCoordinatorResult unavailable() {
        RepairAvailability availability = infrastructure.availability()
        if (availability?.enabled) return null
        List<String> missing = (availability?.missing ?: [])*.name().sort()
        result(409, 'APPLY_UNAVAILABLE', null, false, missing)
    }

    private static RepairCoordinatorResult replay(RepairOperation existing,
                                                  String requestFingerprint) {
        if (existing.fingerprint != requestFingerprint) {
            return result(409, 'OPERATION_ID_CONFLICT', existing, false, [])
        }
        int status = operationStatus(existing.state)
        result(status, existing.state.name(), existing, true, [])
    }

    private static RepairOperation advance(RepairOperation operation, OperationState target) {
        if (!TRANSITIONS.get(operation.state)?.contains(target)) {
            throw new IllegalStateException('invalid repair transition')
        }
        operation.copyWith(state: target, history: operation.history + target)
    }

}

// SOURCE: jira/structuredoctor/CoreRepairModels.groovy
enum RepairKind {
    STRUCTURE,
    JIRA_DATA
}

enum Confirmation {
    STRUCTURE_CHANGE,
    JIRA_DATA_CHANGE
}

enum RepairStrategy {
    ADJUST_DUPLICATES_FILTER,
    NARROW_INSERTER,
    RESTRICT_EXTENDER,
    REORDER_GENERATORS,
    REMOVE_PERMANENT_ROW,
    REPAIR_JIRA_PARENT
}

@Immutable(copyWith = true)
class RepairPackage {
    String id
    RepairKind kind
    RepairStrategy strategy
    List<String> findingGroupIds
    List<Long> affectedIssueIds
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
    List<String> permanentRowIds
    String explanation
    List<String> warnings
    List<Confirmation> confirmations
    List<EvidenceRequirement> requirements
    List<String> blockers
    boolean selectable
}

@Immutable(copyWith = true)
class ImpactResult {
    boolean complete
    boolean safeToApply
    List<String> added
    List<String> removed
    List<String> moved
    List<String> unchanged
    List<String> blockers
}

enum OperationState {
    ANALYZED,
    PLANNED,
    CONFIRMED,
    MUTATION_FAILED,
    APPLIED,
    VERIFYING,
    VERIFIED,
    PENDING,
    ROLLED_BACK,
    MANUAL_RECOVERY_REQUIRED
}

@Immutable(copyWith = true)
class RepairOperation {
    String operationId
    long structureId
    String repairPackageId
    String fingerprint
    String actorKey
    OperationState state
    List<OperationState> history
}

@Immutable(copyWith = true)
class GeneratorMutation {
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
}

@Immutable(copyWith = true)
class JiraDataMutation {
    long issueId
    String fieldId
    String beforeValue
    String afterValue
}

@Immutable(copyWith = true)
class MutationReceipt {
    boolean applied
    String revision
    String message
}

enum RepairCapability {
    STRUCTURE_PREVIEW,
    REVISION_READ,
    TARGET_PERMISSION,
    MUTATION,
    EXACT_RESTORE,
    STRUCTURE_LOCK,
    ISSUE_COMPARE_AND_SET,
    CLUSTER_JOURNAL,
    RECALCULATION,
    TARGET_VERIFICATION
}

final class RepairAvailability {
    final boolean enabled
    final List<RepairCapability> missing

    RepairAvailability(boolean enabled, List<RepairCapability> missing) {
        this.enabled = enabled
        this.missing = new ArrayList<RepairCapability>(missing ?: [])
            .unique().sort { it.name() }.asImmutable()
    }

    static RepairAvailability enabled() {
        new RepairAvailability(true, [])
    }

    static RepairAvailability disabled(Collection<RepairCapability> missing) {
        new RepairAvailability(false, (missing ?: []) as List<RepairCapability>)
    }
}

@Immutable(copyWith = true)
class RepairApplyRequest {
    String operationId
    long structureId
    String repairPackageId
    List<Long> affectedIssueIds
    String actorKey
    Map<String, String> expectedFingerprints
    List<Confirmation> confirmations
}

@Immutable(copyWith = true)
class RepairRefresh {
    RepairPackage repairPackage
    Map<String, String> currentFingerprints
    ImpactResult impact
    Boolean targetPermitted
}

enum MutationDisposition {
    APPLIED,
    NOT_APPLIED,
    PARTIAL
}

@Immutable(copyWith = true)
class RepairMutationOutcome {
    MutationDisposition disposition
    String revision
    String message
}

enum VerificationDisposition {
    VERIFIED,
    PENDING,
    MISMATCH,
    UNAVAILABLE
}

@Immutable(copyWith = true)
class VerificationOutcome {
    VerificationDisposition disposition
    String message
}

@Immutable(copyWith = true)
class RepairCoordinatorResult {
    int status
    String code
    RepairOperation operation
    boolean replayed
    List<String> blockers
}

final class RepairLockUnavailableException extends RuntimeException {
    RepairLockUnavailableException(String code) {
        super(code)
    }
}

// SOURCE: jira/structuredoctor/CoreRepairPolicy.groovy
final class CoreRepairPolicy {
    static List<String> freshnessBlockers(RepairApplyRequest request,
                                          RepairRefresh refresh) {
        if (refresh?.repairPackage == null || refresh.impact == null) return ['repair-refresh']
        List<String> blockers = []
        if (refresh.repairPackage.id != request.repairPackageId) blockers.add('package-id')
        if (new ArrayList<Long>(refresh.repairPackage.affectedIssueIds ?: []).sort() !=
            new ArrayList<Long>(request.affectedIssueIds ?: []).sort()) {
            blockers.add('affected-issues')
        }
        Set<String> sources = new LinkedHashSet<>(request.expectedFingerprints?.keySet() ?: [])
        sources.addAll(refresh.currentFingerprints?.keySet() ?: [])
        for (String source : sources.sort()) {
            if (request.expectedFingerprints?.get(source) !=
                refresh.currentFingerprints?.get(source)) blockers.add('stale:' + source)
        }
        if (!refresh.repairPackage.selectable || !(refresh.repairPackage.blockers ?: []).isEmpty()) {
            blockers.add('repair-package')
        }
        if (!refresh.impact.complete || !refresh.impact.safeToApply) blockers.add('impact')
        blockers.unique().sort()
    }

    static List<String> confirmationBlockers(RepairApplyRequest request,
                                             RepairPackage repairPackage) {
        Set<Confirmation> supplied = (request.confirmations ?: []) as Set<Confirmation>
        List<Confirmation> required = [Confirmation.STRUCTURE_CHANGE]
        if (repairPackage.kind == RepairKind.JIRA_DATA) required.add(Confirmation.JIRA_DATA_CHANGE)
        required.findAll { !supplied.contains(it) }*.name().sort()
    }

    static String requestFingerprint(RepairApplyRequest request) {
        CoreCanonical.sha256([
            operationId: request.operationId, structureId: request.structureId,
            packageId: request.repairPackageId,
            affectedIssueIds: new ArrayList<Long>(request.affectedIssueIds ?: []).sort(),
            actorKey: request.actorKey,
            expected: request.expectedFingerprints,
            confirmations: (request.confirmations ?: []).collect {
                Confirmation confirmation -> confirmation.name()
            }.sort()
        ])
    }

    static void validate(RepairApplyRequest request) {
        if (request == null || !validOperationId(request.operationId)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
        if (request.structureId <= 0L || !request.repairPackageId || !request.actorKey ||
            request.expectedFingerprints == null || request.affectedIssueIds == null) {
            throw new IllegalArgumentException('repair request is incomplete')
        }
    }

    static boolean validOperationId(String value) {
        value != null && value ==~
            /[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/
    }

    static int operationStatus(OperationState state) {
        switch (state) {
            case OperationState.VERIFIED: return 200
            case OperationState.ROLLED_BACK: return 409
            case OperationState.MUTATION_FAILED: return 500
            case OperationState.MANUAL_RECOVERY_REQUIRED: return 500
            default: return 202
        }
    }

    static RepairCoordinatorResult result(int status, String code,
                                          RepairOperation operation,
                                          boolean replayed,
                                          List<String> blockers) {
        new RepairCoordinatorResult(status: status, code: code, operation: operation,
            replayed: replayed, blockers: blockers ?: [])
    }
}

// SOURCE: jira/structuredoctor/CoreSupport.groovy
@CompileStatic
final class CoreSupport {
    private CoreSupport() {
        throw new UnsupportedOperationException('utility class')
    }

    static String html(Object value) {
        String.valueOf(value == null ? '' : value)
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#39;')
    }

    static Object jsonSafe(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value
        }
        if (value instanceof Map) {
            Map<String, Object> safeMap = new LinkedHashMap<String, Object>()
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                safeMap.put(String.valueOf(entry.key), jsonSafe(entry.value))
            }
            return safeMap
        }
        if (value instanceof Iterable) {
            List<Object> safeItems = []
            for (Object nested : (Iterable<?>) value) {
                safeItems.add(jsonSafe(nested))
            }
            return safeItems
        }
        if (value.class.isArray()) {
            List<Object> safeItems = []
            for (Object nested : (Object[]) value) {
                safeItems.add(jsonSafe(nested))
            }
            return safeItems
        }
        String.valueOf(value)
    }

    static String queryValue(Object queryParams, String name) {
        Object value = queryParams == null ? null : InvokerHelper.invokeMethod(queryParams, 'getFirst', name)
        value == null ? null : String.valueOf(value).trim()
    }
}

// SOURCE: jira/structuredoctor/DisabledRepairInfrastructure.groovy
final class DisabledRepairInfrastructure implements RepairInfrastructure {
    private final RepairAvailability state

    DisabledRepairInfrastructure(Collection<RepairCapability> missing =
        RepairCapability.values().toList()) {
        state = RepairAvailability.disabled(missing)
    }

    RepairAvailability availability() { state }
    RepairOperation find(String ignored) { null }
    RepairOperation findPending(long ignored, List<Long> ignoredIssues) { null }
    Object withLocks(long ignored, List<Long> ignoredIssues, Closure<Object> ignoredWork) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    RepairRefresh refresh(RepairApplyRequest ignored) { null }
    RepairRefresh resumeContext(RepairOperation ignored) { null }
    void persist(RepairOperation ignored, RepairPackage ignoredPackage) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    RepairMutationOutcome mutate(RepairPackage ignored) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    VerificationOutcome verify(RepairRefresh ignored, boolean ignoredRestored) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    RepairMutationOutcome restore(RepairPackage ignored) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
}

// SOURCE: jira/structuredoctor/DoctorApplication.groovy
final class DoctorApplication {
    static final int DEFAULT_AUDIT_DAYS = 30
    private final StructureCatalogProvider catalog
    private final HierarchyProvider hierarchyProvider
    private final StructureSnapshotProvider structureProvider
    private final JiraDataProvider jiraProvider
    private final AutomationDataProvider liveAutomation
    private final AutomationDataProvider jsonAutomation
    private final ProposalSource proposals
    private final Closure<Long> issueKeyResolver
    private final DoctorAutomationEvidence automationEvidence
    private final Map<String, DoctorAnalysis> analyses =
        Collections.synchronizedMap(new LinkedHashMap<String, DoctorAnalysis>())
    DoctorApplication(StructureCatalogProvider catalog,
                      HierarchyProvider hierarchyProvider,
                      StructureSnapshotProvider structureProvider,
                      JiraDataProvider jiraProvider,
                      AutomationDataProvider liveAutomation,
                      AutomationDataProvider jsonAutomation,
                      ProposalSource proposals,
                      Closure<Long> issueKeyResolver = null) {
        this.catalog = catalog
        this.hierarchyProvider = hierarchyProvider
        this.structureProvider = structureProvider
        this.jiraProvider = jiraProvider
        this.liveAutomation = liveAutomation
        this.jsonAutomation = jsonAutomation
        this.automationEvidence = new DoctorAutomationEvidence(
            liveAutomation, jsonAutomation)
        this.proposals = proposals
        this.issueKeyResolver = issueKeyResolver
    }
    ReadResult<List<StructureChoice>> listStructures() {
        catalog == null ? ReadResult.unavailable('Structure catalog is unavailable') :
            catalog.listStructures()
    }
    DoctorAnalysis analyze(AnalyzeRequest request) {
        validateAnalyze(request)
        ReadResult<List<StructureChoice>> visibleStructures = listStructures()
        if (!visibleStructures.complete()) throw new DoctorBoundaryException(503, 'STRUCTURE_CATALOG_UNAVAILABLE')
        boolean visible = (visibleStructures.value ?: []).any { it.id == request.structureId }
        if (!visible) throw new DoctorBoundaryException(404, 'STRUCTURE_NOT_VISIBLE')
        int days = request.requestedAuditDays ?: DEFAULT_AUDIT_DAYS
        Long displayIssueId = request.issueKeyFilter ?
            issueKeyResolver?.call(request.issueKeyFilter) : null
        if (request.issueKeyFilter && displayIssueId == null) throw new DoctorBoundaryException(404, 'ISSUE_NOT_VISIBLE')
        ReadResult<HierarchySnapshot> hierarchyRead = hierarchyProvider == null ?
            ReadResult.unavailable('Jira hierarchy provider is unavailable') :
            hierarchyProvider.readHierarchy()
        ReadResult<StructureSnapshot> structureRead = structureProvider == null ?
            ReadResult.unavailable('Structure snapshot provider is unavailable') :
            structureProvider.readStructure(request.structureId)
        Set<Long> issueIds = (structureRead.value?.occurrences ?: [])*.issueId as Set<Long>
        ReadResult<List<IssueRelationSnapshot>> jiraRead = jiraProvider == null ?
            ReadResult.unavailable('Jira relationship provider is unavailable') :
            jiraProvider.readIssues(issueIds)
        StructureSnapshot snapshot = merge(structureRead, hierarchyRead, jiraRead)
        AnalysisScope scope = scope(snapshot)

        DoctorAutomationReads automationReads = automationEvidence.read(
            request, scope, issueIds, days)
        ReadResult<List<AutomationRuleSnapshot>> rules = automationReads.rules
        ReadResult<List<AutomationAuditSnapshot>> audit = automationReads.audit

        HierarchyAnalysis hierarchyAnalysis = new CoreHierarchyAnalyzer().analyze(snapshot)
        DuplicateAnalysis duplicateAnalysis = new CoreDuplicateAnalyzer().analyze(snapshot)
        AutomationAnalysis automationAnalysis = new CoreAutomationAnalyzer().analyze(
            rules, audit, new AutomationAnalysisContext(
                scope: scope, hierarchy: snapshot?.hierarchy,
                hierarchyTargets: [], structureConsumedValues: []))
        List<Finding> analyzedFindings = []
        analyzedFindings.addAll(hierarchyAnalysis.findings ?: [])
        analyzedFindings.addAll(duplicateAnalysis.findings ?: [])
        List<CausalClaim> causalClaims = analyzedFindings.collect { Finding finding ->
            new CoreCausalityEngine().claim(new CausalContext(
                findingId: finding.id, edges: [],
                configurationConflict: finding.type == FindingType.CONFLICTING_PARENT,
                auditCoverage: audit.coverage,
                requirements: finding.requirements ?: [],
                blockers: finding.blockers ?: []))
        }
        List<SourceCoverage> coverage = [
            coverage('jira-hierarchy', hierarchyRead, 'LIVE'),
            coverage('structure-snapshot', structureRead, 'LIVE'),
            coverage('jira-data', jiraRead, 'LIVE'),
            coverage('automation-rules', rules, automationReads.ruleProvider),
            coverage('automation-audit', audit, automationReads.auditProvider)
        ]
        List<String> blockers = coverage.findAll { it.state != ReadState.COMPLETE }
            .collect { SourceCoverage item -> item.source }
        blockers.addAll(hierarchyAnalysis.blockers ?: [])
        blockers.addAll(duplicateAnalysis.blockers ?: [])
        blockers.addAll(automationAnalysis.blockers ?: [])
        String dependencyFingerprint = dependencyFingerprint(snapshot, rules, audit)
        String snapshotId = 'analysis-' + CoreCanonical.sha256([
            structureId: request.structureId,
            fingerprint: dependencyFingerprint,
            states: coverage.collectEntries { [(it.source): it.state.name()] },
            auditDays: days
        ]).substring(0, 24)
        DoctorAnalysis result = new DoctorAnalysis(
            snapshotId: snapshotId, structureId: request.structureId,
            issueKeyFilter: request.issueKeyFilter, displayIssueId: displayIssueId,
            requestedAuditDays: days,
            snapshot: snapshot, dependencyFingerprint: dependencyFingerprint,
            coverage: coverage,
            hierarchy: hierarchyAnalysis, duplicates: duplicateAnalysis,
            automation: automationAnalysis, causalClaims: causalClaims,
            complete: blockers.isEmpty(), blockers: blockers.unique().sort())
        synchronized (analyses) {
            if (analyses.size() >= 100 && !analyses.containsKey(snapshotId)) {
                String removed = analyses.keySet().iterator().next()
                analyses.remove(removed)
                automationEvidence.remove(removed)
            }
            analyses.put(snapshotId, result)
            automationEvidence.remember(snapshotId, automationReads)
        }
        result
    }
    ProposalPlan plan(PlanRequest request) {
        if (request == null || !request.snapshotId) {
            return blocked('invalid-snapshot-id')
        }
        DoctorAnalysis prior = analyses.get(request.snapshotId)
        if (prior == null) {
            return blocked('unknown-snapshot')
        }
        ReadResult<HierarchySnapshot> hierarchyRead = hierarchyProvider.readHierarchy()
        ReadResult<StructureSnapshot> structureRead = structureProvider.readStructure(prior.structureId)
        Set<Long> ids = (structureRead.value?.occurrences ?: [])*.issueId as Set<Long>
        ReadResult<List<IssueRelationSnapshot>> jiraRead = jiraProvider.readIssues(ids)
        StructureSnapshot current = merge(structureRead, hierarchyRead, jiraRead)
        if (current?.planningFingerprint() == null ||
            current.planningFingerprint() != prior.snapshot?.planningFingerprint()) {
            return blocked('stale-snapshot')
        }
        AnalysisScope currentScope = scope(current)
        boolean useJsonRules = prior.coverage.find {
            it.source == 'automation-rules'
        }?.provider == 'JSON_FALLBACK'
        boolean useJsonAudit = prior.coverage.find {
            it.source == 'automation-audit'
        }?.provider == 'JSON_FALLBACK'
        DoctorAutomationReads automationReads = automationEvidence.readForPlan(
            request.snapshotId, useJsonRules, useJsonAudit, currentScope, ids,
            prior.requestedAuditDays)
        ReadResult<List<AutomationRuleSnapshot>> rules = automationReads.rules
        ReadResult<List<AutomationAuditSnapshot>> audit = automationReads.audit
        if (dependencyFingerprint(current, rules, audit) != prior.dependencyFingerprint) {
            return blocked('stale-snapshot')
        }
        DuplicateAnalysis duplicates = new CoreDuplicateAnalyzer().analyze(current)
        RepairSelection selection = new RepairSelection(
            findingGroupIds: request.findingGroupIds ?: [],
            retainOccurrenceByGroup: request.retainOccurrenceByGroup ?: [:],
            selectedPermanentRowIds: request.selectedPermanentRowIds ?: [])
        new CoreProposalPlanner().plan(current, duplicates, selection, proposals)
    }
    DoctorAnalysis analysis(String snapshotId) {
        analyses.get(snapshotId)
    }
    static AnalyzeRequest parseAnalyzeRequest(Map<String, Object> payload) {
        DoctorRequests.parseAnalyze(payload)
    }
    static PlanRequest parsePlanRequest(Map<String, Object> payload) {
        DoctorRequests.parsePlan(payload)
    }

    private static StructureSnapshot merge(ReadResult<StructureSnapshot> structureRead,
                                           ReadResult<HierarchySnapshot> hierarchyRead,
                                           ReadResult<List<IssueRelationSnapshot>> jiraRead) {
        StructureSnapshot base = structureRead?.value
        if (base == null) return null
        new StructureSnapshot(
            structureId: base.structureId, revision: base.revision,
            hierarchy: hierarchyRead?.value, generators: base.generators ?: [],
            occurrences: base.occurrences ?: [], relations: jiraRead?.value ?: [],
            fingerprint: base.fingerprint,
            complete: base.complete && structureRead.complete() && hierarchyRead?.complete() &&
                jiraRead?.complete())
    }

    private static AnalysisScope scope(StructureSnapshot snapshot) {
        new AnalysisScope(projectIds: [],
            issueTypeIds: (snapshot?.relations ?: [])*.issueTypeId.unique().sort(),
            fieldIds: [], linkTypeIds: [])
    }

    private static SourceCoverage coverage(String source, ReadResult<?> read, String provider) {
        new SourceCoverage(source: source, state: read?.state ?: ReadState.FAILED,
            reason: read?.reason, coverage: read?.coverage, provider: provider)
    }

    private static String dependencyFingerprint(
        StructureSnapshot snapshot,
        ReadResult<List<AutomationRuleSnapshot>> rules,
        ReadResult<List<AutomationAuditSnapshot>> audit) {
        String structureFingerprint = snapshot?.planningFingerprint()
        if (structureFingerprint == null || !rules?.complete() || !audit?.complete()) return null
        CoreCanonical.sha256([
            structure: structureFingerprint,
            rules: (rules.value ?: []).collect { AutomationRuleSnapshot rule ->
                [id: rule.ruleId, revision: rule.revision, complete: rule.complete]
            },
            audit: (audit.value ?: []).collect { AutomationAuditSnapshot entry ->
                [ruleId: entry.ruleId, issueId: entry.issueId, occurredAt: entry.occurredAt,
                 action: entry.action, target: entry.target, successful: entry.successful,
                 revision: entry.revision]
            },
            coverage: audit.coverage == null ? null : [
                requested: audit.coverage.requested, actual: audit.coverage.actual,
                capped: audit.coverage.capped, from: audit.coverage.fromInclusive,
                to: audit.coverage.toInclusive]
        ])
    }

    private static ProposalPlan blocked(String reason) {
        new ProposalPlan(packages: [], complete: false, blockers: [reason])
    }

    private static void validateAnalyze(AnalyzeRequest request) {
        if (request == null || request.structureId <= 0L) {
            throw new IllegalArgumentException('A positive Structure ID is required')
        }
        int days = request.requestedAuditDays ?: DEFAULT_AUDIT_DAYS
        if (days < 1 || days > 365) throw new IllegalArgumentException('Audit days must be 1 to 365')
        if (request.issueKeyFilter && !(request.issueKeyFilter ==~ /[A-Za-z][A-Za-z0-9_]*-[0-9]+/)) {
            throw new IllegalArgumentException('Issue key filter is invalid')
        }
    }

}

// SOURCE: jira/structuredoctor/DoctorContracts.groovy
interface StructureCatalogProvider {
    ReadResult<List<StructureChoice>> listStructures()
}

interface HierarchyProvider {
    ReadResult<HierarchySnapshot> readHierarchy()
}

interface StructureSnapshotProvider {
    ReadResult<StructureSnapshot> readStructure(long structureId)
}

interface JiraDataProvider {
    ReadResult<List<IssueRelationSnapshot>> readIssues(Collection<Long> issueIds)
}

interface AutomationDataProvider {
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope)
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request)
}

interface ProposalSource {
    ReadResult<List<ProposalCandidate>> readCandidates(StructureSnapshot snapshot)
}

interface StructureMutationGateway {
    MutationReceipt applyGeneratorPackage(GeneratorMutation mutation)
    MutationReceipt restoreGeneratorPackage(GeneratorMutation mutation)
}

interface JiraDataMutationGateway {
    MutationReceipt applyIssueData(JiraDataMutation mutation)
    MutationReceipt restoreIssueData(JiraDataMutation mutation)
}

interface RepairJournal {
    RepairOperation find(String operationId)
    void write(RepairOperation operation)
}

interface StructureLock {
    Object withLock(long structureId, Closure<Object> work)
}

interface DoctorClock {
    String now()
}

interface RepairInfrastructure {
    RepairAvailability availability()
    RepairOperation find(String operationId)
    RepairOperation findPending(long structureId, List<Long> issueIds)
    Object withLocks(long structureId, List<Long> issueIds, Closure<Object> work)
    RepairRefresh refresh(RepairApplyRequest request)
    RepairRefresh resumeContext(RepairOperation operation)
    void persist(RepairOperation operation, RepairPackage repairPackage)
    RepairMutationOutcome mutate(RepairPackage repairPackage)
    VerificationOutcome verify(RepairRefresh context, boolean restored)
    RepairMutationOutcome restore(RepairPackage repairPackage)
}

// SOURCE: jira/structuredoctor/DoctorHttpGuard.groovy
@Immutable(copyWith = true)
class DoctorHttpDecision {
    boolean allowed
    int status
    String error
}

final class DoctorHttpGuard {
    static final int MAX_JSON_BYTES = 65_536
    static final int MAX_ANALYZE_JSON_BYTES = 23_068_672

    static DoctorHttpDecision requireJson(String contentType, String body) {
        requireJson(contentType, body, MAX_JSON_BYTES)
    }

    static DoctorHttpDecision requireJson(String contentType, String body,
                                          int maxBytes) {
        String mediaType = contentType?.split(';', 2)?.first()?.trim()
        if (!'application/json'.equalsIgnoreCase(mediaType)) {
            return reject(415, 'UNSUPPORTED_MEDIA_TYPE')
        }
        if (maxBytes <= 0) return reject(413, 'REQUEST_TOO_LARGE')
        int size = (body ?: '').getBytes(StandardCharsets.UTF_8).length
        size > maxBytes ? reject(413, 'REQUEST_TOO_LARGE') : allow()
    }

    static DoctorHttpDecision requireQueryKeys(Collection<?> actual,
                                               Collection<String> allowed) {
        Set<String> keys = (actual ?: []).collect { String.valueOf(it) } as Set<String>
        keys.every { String key -> (allowed ?: []).contains(key) } ?
            allow() : reject(400, 'UNSUPPORTED_QUERY_PARAMETER')
    }

    static Map<String, Object> parseJsonObject(String body) {
        try {
            Object parsed = body?.trim() ? new JsonSlurper().parseText(body) : null
            if (!(parsed instanceof Map)) throw new IllegalArgumentException('JSON object required')
            (Map<String, Object>) parsed
        } catch (Exception ignored) {
            throw new DoctorBoundaryException(400, 'INVALID_JSON')
        }
    }

    private static DoctorHttpDecision allow() {
        new DoctorHttpDecision(allowed: true, status: 0, error: null)
    }

    private static DoctorHttpDecision reject(int status, String error) {
        new DoctorHttpDecision(allowed: false, status: status, error: error)
    }
}

final class DoctorBoundaryException extends IllegalArgumentException {
    final int status
    final String code

    DoctorBoundaryException(int status, String code) {
        super(code)
        this.status = status
        this.code = code
    }
}

// SOURCE: jira/structuredoctor/DoctorRenderer.groovy
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

// SOURCE: jira/structuredoctor/DoctorRepairApplication.groovy
final class DoctorRepairApplication {
    private static final Set<String> APPLY_KEYS = [
        'snapshotId', 'repairPackageId', 'operationId',
        'confirmStructureChange', 'confirmJiraDataChange'
    ] as Set<String>

    private final RepairInfrastructure infrastructure
    private final CoreRepairCoordinator coordinator
    private final Map<String, Map<String, Object>> plans =
        Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>())

    DoctorRepairApplication(RepairInfrastructure infrastructure =
        new DisabledRepairInfrastructure()) {
        this.infrastructure = infrastructure
        this.coordinator = new CoreRepairCoordinator(infrastructure)
    }

    RepairAvailability availability() {
        infrastructure.availability()
    }

    void registerPlan(DoctorAnalysis analysis, ProposalPlan plan) {
        if (analysis == null || plan == null) return
        synchronized (plans) {
            if (plans.size() >= 100 && !plans.containsKey(analysis.snapshotId)) {
                plans.remove(plans.keySet().iterator().next())
            }
            plans.put(analysis.snapshotId, [analysis: analysis, plan: plan])
        }
    }

    RepairCoordinatorResult apply(Map<String, Object> payload, String actorKey) {
        validatePayload(payload)
        RepairAvailability state = infrastructure.availability()
        if (!state.enabled) return unavailable(state)
        Map<String, Object> registration = plans.get(text(payload.snapshotId, 'snapshotId'))
        if (registration == null) return result(404, 'SNAPSHOT_NOT_FOUND', [])
        DoctorAnalysis analysis = (DoctorAnalysis) registration.analysis
        ProposalPlan plan = (ProposalPlan) registration.plan
        String packageId = text(payload.repairPackageId, 'repairPackageId')
        RepairPackage repairPackage = (plan.packages ?: []).find {
            RepairPackage item -> item.id == packageId
        }
        if (repairPackage == null) return result(404, 'REPAIR_PACKAGE_NOT_FOUND', [])
        Map<String, String> expected = new LinkedHashMap<>()
        for (EvidenceRequirement requirement : repairPackage.requirements ?: []) {
            String previous = expected.put(requirement.source, requirement.fingerprint)
            if (previous != null && previous != requirement.fingerprint) {
                return result(409, 'CONFLICTING_REQUIREMENTS', [requirement.source])
            }
        }
        List<Confirmation> confirmations = []
        if (payload.confirmStructureChange == Boolean.TRUE) {
            confirmations.add(Confirmation.STRUCTURE_CHANGE)
        }
        if (payload.confirmJiraDataChange == Boolean.TRUE) {
            confirmations.add(Confirmation.JIRA_DATA_CHANGE)
        }
        coordinator.apply(new RepairApplyRequest(
            operationId: text(payload.operationId, 'operationId'),
            structureId: analysis.structureId,
            repairPackageId: repairPackage.id,
            affectedIssueIds: repairPackage.affectedIssueIds ?: [],
            actorKey: actor(actorKey), expectedFingerprints: expected,
            confirmations: confirmations))
    }

    RepairCoordinatorResult status(String operationId, String actorKey) {
        coordinator.status(text(operationId, 'operationId'), actor(actorKey))
    }

    private static void validatePayload(Map<String, Object> payload) {
        if (payload == null || payload.keySet() != APPLY_KEYS) {
            throw new IllegalArgumentException('Apply request contains missing or unsupported keys')
        }
        if (!(payload.confirmStructureChange instanceof Boolean) ||
            !(payload.confirmJiraDataChange instanceof Boolean)) {
            throw new IllegalArgumentException('Apply confirmations must be boolean')
        }
    }

    private static String actor(String value) {
        text(value, 'authenticated actor')
    }

    private static String text(Object value, String name) {
        if (!(value instanceof CharSequence) || !value.toString().trim()) {
            throw new IllegalArgumentException(name + ' is required')
        }
        value.toString().trim()
    }

    private static RepairCoordinatorResult unavailable(RepairAvailability state) {
        new RepairCoordinatorResult(
            status: 409, code: 'APPLY_UNAVAILABLE', operation: null,
            replayed: false, blockers: state.missing*.name().sort())
    }

    private static RepairCoordinatorResult result(int status, String code,
                                                  List<String> blockers) {
        new RepairCoordinatorResult(
            status: status, code: code, operation: null,
            replayed: false, blockers: blockers ?: [])
    }
}

// SOURCE: jira/structuredoctor/DoctorRequestModels.groovy
@Immutable(copyWith = true)
class StructureChoice {
    long id
    String name
}

@Immutable(copyWith = true)
class AnalyzeRequest {
    long structureId
    String issueKeyFilter
    Integer requestedAuditDays
    String ruleExportRef
    String auditExportRef
    String ruleExportJson
    String auditExportJson
}

@Immutable(copyWith = true)
class SourceCoverage {
    String source
    ReadState state
    String reason
    Coverage coverage
    String provider
}

@Immutable(copyWith = true)
class DoctorAnalysis {
    String snapshotId
    long structureId
    String issueKeyFilter
    Long displayIssueId
    int requestedAuditDays
    StructureSnapshot snapshot
    String dependencyFingerprint
    List<SourceCoverage> coverage
    HierarchyAnalysis hierarchy
    DuplicateAnalysis duplicates
    AutomationAnalysis automation
    List<CausalClaim> causalClaims
    boolean complete
    List<String> blockers
}

@Immutable(copyWith = true)
class PlanRequest {
    String snapshotId
    List<String> findingGroupIds
    Map<String, String> retainOccurrenceByGroup
    List<String> selectedPermanentRowIds
}

final class DoctorRequests {
    private static final Set<String> ANALYZE_KEYS = [
        'structureId', 'issueKeyFilter', 'requestedAuditDays',
        'ruleExportRef', 'auditExportRef', 'ruleExportJson', 'auditExportJson'
    ] as Set<String>
    private static final Set<String> PLAN_KEYS = [
        'snapshotId', 'findingGroupIds', 'retainOccurrenceByGroup',
        'selectedPermanentRowIds'
    ] as Set<String>

    static AnalyzeRequest parseAnalyze(Map<String, Object> payload) {
        if (payload == null || payload.keySet() != ANALYZE_KEYS) {
            throw new IllegalArgumentException('Analyze request contains missing or unsupported keys')
        }
        long structureId
        int days
        try {
            structureId = Long.parseLong(String.valueOf(payload.structureId))
            days = payload.requestedAuditDays == null ? 30 :
                Integer.parseInt(String.valueOf(payload.requestedAuditDays))
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException('Analyze request has invalid numeric values')
        }
        new AnalyzeRequest(
            structureId: structureId,
            issueKeyFilter: optionalText(payload.issueKeyFilter),
            requestedAuditDays: days,
            ruleExportRef: optionalReference(payload.ruleExportRef),
            auditExportRef: optionalReference(payload.auditExportRef),
            ruleExportJson: optionalPayload(payload.ruleExportJson),
            auditExportJson: optionalPayload(payload.auditExportJson))
    }

    static PlanRequest parsePlan(Map<String, Object> payload) {
        if (payload == null || payload.keySet() != PLAN_KEYS) {
            throw new IllegalArgumentException('Plan request contains missing or unsupported keys')
        }
        if (!(payload.findingGroupIds instanceof List) ||
            !(payload.retainOccurrenceByGroup instanceof Map) ||
            !(payload.selectedPermanentRowIds instanceof List)) {
            throw new IllegalArgumentException('Plan request has invalid value types')
        }
        new PlanRequest(
            snapshotId: text(payload.snapshotId, 'snapshotId'),
            findingGroupIds: strings(payload.findingGroupIds, 'findingGroupIds'),
            retainOccurrenceByGroup: stringMap(payload.retainOccurrenceByGroup),
            selectedPermanentRowIds: strings(
                payload.selectedPermanentRowIds, 'selectedPermanentRowIds'))
    }

    private static String optionalText(Object value) {
        value == null || !value.toString().trim() ? null : value.toString().trim()
    }

    private static String optionalReference(Object value) {
        String result = optionalText(value)
        if (result != null && !(result ==~ /[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/)) {
            throw new IllegalArgumentException('Upload reference is invalid')
        }
        result
    }

    private static String optionalPayload(Object value) {
        if (value == null) return null
        if (!(value instanceof CharSequence)) {
            throw new IllegalArgumentException('Automation export must be JSON text')
        }
        String result = value.toString()
        result.trim().isEmpty() ? null : result
    }

    private static String text(Object value, String name) {
        if (!(value instanceof CharSequence) || !value.toString().trim()) {
            throw new IllegalArgumentException(name + ' is required')
        }
        value.toString().trim()
    }

    private static List<String> strings(Object value, String name) {
        ((List<?>) value).collect { Object item -> text(item, name) }
    }

    private static Map<String, String> stringMap(Object value) {
        ((Map<?, ?>) value).collectEntries { Object key, Object item ->
            [(text(key, 'retain group')): text(item, 'retain occurrence')]
        } as Map<String, String>
    }
}

// SOURCE: jira/structuredoctor/JsonAutomationProvider.groovy
@CompileStatic
final class JsonAutomationProvider implements AutomationDataProvider {
    private static final Set<String> RULE_EXPORT_KEYS =
        ['exportVersion', 'rules'] as Set<String>
    private static final Set<String> OFFICIAL_RULE_EXPORT_KEYS =
        ['cloud', 'rules'] as Set<String>
    private static final Set<String> AUDIT_EXPORT_KEYS = [
        'exportVersion', 'requestedDays', 'actualDays', 'capped',
        'fromInclusive', 'toInclusive', 'entries'
    ] as Set<String>

    private final byte[] rulePayload
    private final byte[] auditPayload
    private final int maxBytes

    JsonAutomationProvider(byte[] rulePayload, byte[] auditPayload, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException('maxBytes must be positive')
        }
        this.rulePayload = rulePayload == null ? null : rulePayload.clone()
        this.auditPayload = auditPayload == null ? null : auditPayload.clone()
        this.maxBytes = maxBytes
    }

    @Override
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope) {
        if (rulePayload == null) {
            return ReadResult.unavailable('Automation rule export was not supplied')
        }
        try {
            Map<String, Object> root = parse(rulePayload)
            List<AutomationRuleSnapshot> rules = parseRules(root)
                .findAll { AutomationRuleSnapshot rule -> overlaps(rule, scope) }
            ReadResult.complete(rules)
        } catch (RuntimeException ignored) {
            ReadResult.failed('Automation rule export was rejected')
        }
    }

    @Override
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request) {
        if (auditPayload == null) {
            return ReadResult.unavailable('Automation audit export was not supplied')
        }
        try {
            Map<String, Object> root = parse(auditPayload)
            CoreAutomationJsonSupport.requireExactKeys(
                root, AUDIT_EXPORT_KEYS, AUDIT_EXPORT_KEYS)
            requireVersion(CoreAutomationJsonSupport.text(
                root.exportVersion, 'exportVersion'))
            long actualDays = CoreAutomationJsonSupport.longValue(
                root.actualDays, 'actualDays')
            long exportedRequest = CoreAutomationJsonSupport.longValue(
                root.requestedDays, 'requestedDays')
            if (request == null || request.requestedDays <= 0 ||
                exportedRequest != request.requestedDays) {
                throw new IllegalArgumentException('audit request mismatch')
            }
            Coverage coverage = Coverage.bounded(
                request.requestedDays,
                actualDays,
                CoreAutomationJsonSupport.booleanValue(root.capped, 'capped'),
                CoreAutomationJsonSupport.text(root.fromInclusive, 'fromInclusive'),
                CoreAutomationJsonSupport.text(root.toInclusive, 'toInclusive'))
            List<AutomationAuditSnapshot> entries = CoreAutomationJsonSupport
                .objectList(root.entries, 'entries')
                .collect { Map<String, Object> value -> CoreAutomationJsonSupport.audit(value) }
                .findAll { AutomationAuditSnapshot entry -> matches(entry, request) }
            coverage.complete() ? ReadResult.complete(entries, coverage) :
                ReadResult.incomplete(entries, 'Automation audit coverage is incomplete', coverage)
        } catch (RuntimeException ignored) {
            ReadResult.failed('Automation audit export was rejected')
        }
    }

    private Map<String, Object> parse(byte[] payload) {
        if (payload.length > maxBytes) {
            throw new IllegalArgumentException('payload exceeds byte limit')
        }
        Object value = new JsonSlurper().parseText(
            new String(payload, StandardCharsets.UTF_8))
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException('root must be an object')
        }
        (Map<String, Object>) value
    }

    private static void requireVersion(String version) {
        if (version != '1') {
            throw new IllegalArgumentException('unsupported export version')
        }
    }

    private static List<AutomationRuleSnapshot> parseRules(Map<String, Object> root) {
        if (root.keySet() == RULE_EXPORT_KEYS) {
            String version = CoreAutomationJsonSupport.text(
                root.exportVersion, 'exportVersion')
            requireVersion(version)
            return CoreAutomationJsonSupport.objectList(root.rules, 'rules').collect {
                Map<String, Object> value -> CoreAutomationJsonSupport.rule(value, version)
            }
        }
        if (root.keySet() == OFFICIAL_RULE_EXPORT_KEYS && root.cloud instanceof Boolean) {
            return CoreAutomationJsonSupport.objectList(root.rules, 'rules').collect {
                Map<String, Object> value -> CoreAutomationJsonSupport.officialRule(value)
            }
        }
        throw new IllegalArgumentException('unknown Automation export schema')
    }

    private static boolean overlaps(AutomationRuleSnapshot rule, AnalysisScope scope) {
        if (scope == null) {
            return true
        }
        boolean projects = intersectsOrGlobal(rule.projectIds, scope.projectIds)
        boolean types = intersectsOrGlobal(rule.issueTypeIds, scope.issueTypeIds)
        Set<String> selected = new LinkedHashSet<>(scope.fieldIds ?: [])
        selected.addAll((scope.linkTypeIds ?: []).collect { Long id -> 'link:' + id })
        Set<String> touched = new LinkedHashSet<>(rule.reads ?: [])
        touched.addAll(rule.writes ?: [])
        touched.addAll(rule.clears ?: [])
        projects && types && (selected.isEmpty() || !Collections.disjoint(selected, touched))
    }

    private static boolean intersectsOrGlobal(List<Long> ruleValues, List<Long> selected) {
        ruleValues == null || ruleValues.isEmpty() || selected == null || selected.isEmpty() ||
            !Collections.disjoint(ruleValues, selected)
    }

    private static boolean matches(AutomationAuditSnapshot entry, AuditRequest request) {
        (request.ruleIds == null || request.ruleIds.isEmpty() || request.ruleIds.contains(entry.ruleId)) &&
            (request.issueIds == null || request.issueIds.isEmpty() ||
                request.issueIds.contains(entry.issueId))
    }
}

// SOURCE: jira/structuredoctor/LegacyIssueDoctor.groovy
final class LegacyIssueDoctor {
    static final String CONFIRMATION = 'SET_PARENT_LINK'

    private final Closure<?> inspector
    private final Closure<?> parentLinkRepair

    LegacyIssueDoctor(Closure<?> inspector, Closure<?> parentLinkRepair) {
        this.inspector = inspector
        this.parentLinkRepair = parentLinkRepair
    }

    Object analyze(long structureId, String issueKey) {
        if (inspector == null) {
            throw new IllegalStateException('Legacy issue analysis is unavailable')
        }
        inspector.call(structureId, issueKey)
    }

    Object repair(long structureId, String issueKey, String confirmation) {
        if (confirmation != CONFIRMATION) {
            throw new IllegalArgumentException('Invalid Parent Link confirmation')
        }
        if (parentLinkRepair == null) {
            throw new IllegalStateException('Legacy Parent Link repair is unavailable')
        }
        parentLinkRepair.call(structureId, issueKey, confirmation)
    }
}

// SOURCE: jira/structuredoctor/LiveAutomationProvider.groovy
final class LiveAutomationProvider implements AutomationDataProvider {
    private final Closure<ReadResult<List<AutomationRuleSnapshot>>> ruleReader
    private final Closure<ReadResult<List<AutomationAuditSnapshot>>> auditReader

    LiveAutomationProvider(
        Closure<ReadResult<List<AutomationRuleSnapshot>>> ruleReader,
        Closure<ReadResult<List<AutomationAuditSnapshot>>> auditReader) {
        this.ruleReader = ruleReader
        this.auditReader = auditReader
    }

    @Override
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope) {
        if (ruleReader == null) {
            return ReadResult.unavailable('The live Automation rule API has not been proven')
        }
        try {
            ReadResult<List<AutomationRuleSnapshot>> result = ruleReader.call(scope)
            result ?: ReadResult.failed('The Automation rule reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Automation rule read failed')
        }
    }

    @Override
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request) {
        if (auditReader == null) {
            return ReadResult.unavailable('The live Automation audit API has not been proven')
        }
        try {
            ReadResult<List<AutomationAuditSnapshot>> result = auditReader.call(request)
            result ?: ReadResult.failed('The Automation audit reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Automation audit read failed')
        }
    }
}

final class DoctorAutomationReads {
    ReadResult<List<AutomationRuleSnapshot>> rules
    ReadResult<List<AutomationAuditSnapshot>> audit
    String ruleProvider
    String auditProvider
    boolean inlineExport
}

final class DoctorAutomationEvidence {
    static final int MAX_EXPORT_BYTES = 5_242_880

    private final AutomationDataProvider live
    private final AutomationDataProvider configuredJson
    private final Map<String, DoctorAutomationReads> remembered =
        Collections.synchronizedMap(new LinkedHashMap<String, DoctorAutomationReads>())

    DoctorAutomationEvidence(AutomationDataProvider live,
                             AutomationDataProvider configuredJson) {
        this.live = live
        this.configuredJson = configuredJson
    }

    DoctorAutomationReads read(AnalyzeRequest request, AnalysisScope scope,
                               Collection<Long> issueIds, int days) {
        boolean inline = request.ruleExportJson != null || request.auditExportJson != null
        AutomationDataProvider requestJson = inline ? new JsonAutomationProvider(
            bytes(request.ruleExportJson), bytes(request.auditExportJson),
            MAX_EXPORT_BYTES) : configuredJson
        ReadResult<List<AutomationRuleSnapshot>> rules = live == null ?
            ReadResult.unavailable('Live Automation rules are unavailable') :
            live.readRules(scope)
        String ruleProvider = 'LIVE'
        if (!rules.complete() && (request.ruleExportRef || request.ruleExportJson) &&
            requestJson != null) {
            rules = requestJson.readRules(scope)
            ruleProvider = 'JSON_FALLBACK'
        }
        AuditRequest auditRequest = auditRequest(rules, issueIds, days)
        ReadResult<List<AutomationAuditSnapshot>> audit = live == null ?
            ReadResult.unavailable('Live Automation audit is unavailable') :
            live.readAudit(auditRequest)
        String auditProvider = 'LIVE'
        if (!audit.complete() && (request.auditExportRef || request.auditExportJson) &&
            requestJson != null) {
            audit = requestJson.readAudit(auditRequest)
            auditProvider = 'JSON_FALLBACK'
        }
        new DoctorAutomationReads(
            rules: rules, audit: audit, ruleProvider: ruleProvider,
            auditProvider: auditProvider, inlineExport: inline)
    }

    void remember(String snapshotId, DoctorAutomationReads reads) {
        remembered.put(snapshotId, reads)
    }

    void remove(String snapshotId) {
        remembered.remove(snapshotId)
    }

    DoctorAutomationReads readForPlan(String snapshotId,
                                      boolean useJsonRules,
                                      boolean useJsonAudit,
                                      AnalysisScope scope,
                                      Collection<Long> issueIds,
                                      int days) {
        DoctorAutomationReads prior = remembered.get(snapshotId)
        boolean inline = prior?.inlineExport == true
        AutomationDataProvider ruleSource = useJsonRules ? configuredJson : live
        AutomationDataProvider auditSource = useJsonAudit ? configuredJson : live
        ReadResult<List<AutomationRuleSnapshot>> rules = useJsonRules && inline ?
            prior.rules : (ruleSource == null ?
                ReadResult.unavailable('Automation rule provider is unavailable') :
                ruleSource.readRules(scope))
        AuditRequest auditRequest = auditRequest(rules, issueIds, days)
        ReadResult<List<AutomationAuditSnapshot>> audit = useJsonAudit && inline ?
            prior.audit : (auditSource == null ?
                ReadResult.unavailable('Automation audit provider is unavailable') :
                auditSource.readAudit(auditRequest))
        new DoctorAutomationReads(
            rules: rules, audit: audit,
            ruleProvider: useJsonRules ? 'JSON_FALLBACK' : 'LIVE',
            auditProvider: useJsonAudit ? 'JSON_FALLBACK' : 'LIVE',
            inlineExport: inline)
    }

    private static AuditRequest auditRequest(
        ReadResult<List<AutomationRuleSnapshot>> rules,
        Collection<Long> issueIds, int days) {
        new AuditRequest(ruleIds: (rules.value ?: [])*.ruleId,
            issueIds: (issueIds ?: []) as List<Long>, requestedDays: days)
    }

    private static byte[] bytes(String value) {
        value == null ? null : value.getBytes(StandardCharsets.UTF_8)
    }
}

// SOURCE: jira/structuredoctor/LiveConfigurationDiscovery.groovy
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

    static HierarchySnapshot mapHierarchy(Collection<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException('Jira hierarchy is empty')
        }
        List<HierarchyLevel> levels = records.collect { Map<String, Object> record ->
            long rank = number(record.rank, 'rank')
            String levelId = text(record.levelId, 'levelId')
            String name = text(record.name, 'name')
            List<Long> issueTypeIds = ((Collection<?>) (record.issueTypeIds ?: []))
                .collect { Object value -> number(value, 'issueTypeId') }
                .unique()
                .sort()
            if (issueTypeIds.isEmpty()) {
                throw new IllegalArgumentException('Hierarchy level has no issue types')
            }
            new HierarchyLevel(rank: rank, levelId: levelId, name: name,
                issueTypeIds: issueTypeIds)
        }.sort { HierarchyLevel left, HierarchyLevel right ->
            right.rank <=> left.rank
        }
        if (levels*.rank.unique().size() != levels.size()) {
            throw new IllegalArgumentException('Hierarchy ranks are not unique')
        }
        Map<String, Object> canonical = [levels: levels.collect { HierarchyLevel level ->
            [rank: level.rank, levelId: level.levelId, name: level.name,
             issueTypeIds: level.issueTypeIds]
        }]
        new HierarchySnapshot(levels: levels,
            fingerprint: CoreCanonical.sha256(canonical))
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number) && !(String.valueOf(value) ==~ /-?[0-9]+/)) {
            throw new IllegalArgumentException(name + ' is invalid')
        }
        Long.parseLong(String.valueOf(value))
    }

    private static String text(Object value, String name) {
        String result = value == null ? null : String.valueOf(value).trim()
        if (!result) throw new IllegalArgumentException(name + ' is required')
        result
    }
}

// SOURCE: jira/structuredoctor/LiveJiraGateway.groovy
final class LiveJiraGateway implements JiraDataProvider {
    private final Closure<ReadResult<List<IssueRelationSnapshot>>> issueReader

    LiveJiraGateway(Closure<ReadResult<List<IssueRelationSnapshot>>> issueReader) {
        this.issueReader = issueReader
    }

    @Override
    ReadResult<List<IssueRelationSnapshot>> readIssues(Collection<Long> issueIds) {
        if (issueReader == null) {
            return ReadResult.unavailable('The batched Jira relationship API has not been proven')
        }
        try {
            ReadResult<List<IssueRelationSnapshot>> result = issueReader.call(issueIds ?: [])
            result ?: ReadResult.failed('The Jira relationship reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Jira relationship read failed')
        }
    }

    static List<IssueRelationSnapshot> mapIssues(
        Collection<Map<String, Object>> records) {
        if (records == null) {
            throw new IllegalArgumentException('Jira issue records are required')
        }
        records.collect { Map<String, Object> record ->
            List<Long> parents = ((Collection<?>) (record.leadingParentIds ?: []))
                .collect { Object value -> number(value, 'leadingParentId') }
                .unique()
                .sort()
            new IssueRelationSnapshot(
                issueId: number(record.issueId, 'issueId'),
                issueTypeId: number(record.issueTypeId, 'issueTypeId'),
                nativeParentId: record.nativeParentId == null ? null :
                    number(record.nativeParentId, 'nativeParentId'),
                leadingParentIds: parents,
                revisions: ((Map<?, ?>) (record.revisions ?: [:])).collectEntries {
                    Object key, Object value ->
                        [(String.valueOf(key)): String.valueOf(value)]
                } as Map<String, String>)
        }
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number) && !(String.valueOf(value) ==~ /-?[0-9]+/)) {
            throw new IllegalArgumentException(name + ' is invalid')
        }
        Long.parseLong(String.valueOf(value))
    }
}

// SOURCE: jira/structuredoctor/LiveRepairInfrastructure.groovy
final class LiveRepairInfrastructure implements RepairInfrastructure {
    private static final Set<RepairCapability> REQUIRED =
        RepairCapability.values() as Set<RepairCapability>

    private final Map<String, Closure<?>> operations
    private final RepairAvailability state

    LiveRepairInfrastructure(Collection<RepairCapability> provenCapabilities,
                             Map<String, Closure<?>> operations) {
        this.operations = new LinkedHashMap<>(operations ?: [:])
        Set<RepairCapability> proven = new LinkedHashSet<>(provenCapabilities ?: [])
        Set<RepairCapability> missing = new LinkedHashSet<>(REQUIRED)
        missing.removeAll(proven)
        requireOperation(missing, RepairCapability.STRUCTURE_PREVIEW, 'refresh')
        requireOperation(missing, RepairCapability.REVISION_READ, 'refresh')
        requireOperation(missing, RepairCapability.TARGET_PERMISSION, 'refresh')
        requireOperation(missing, RepairCapability.MUTATION, 'mutate')
        requireOperation(missing, RepairCapability.EXACT_RESTORE, 'restore')
        requireOperation(missing, RepairCapability.STRUCTURE_LOCK, 'withLocks')
        requireOperation(missing, RepairCapability.ISSUE_COMPARE_AND_SET, 'withLocks')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'find')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'findPending')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'persist')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'resumeContext')
        requireOperation(missing, RepairCapability.RECALCULATION, 'verify')
        requireOperation(missing, RepairCapability.TARGET_VERIFICATION, 'verify')
        state = missing.isEmpty() ? RepairAvailability.enabled() :
            RepairAvailability.disabled(missing)
    }

    @Override
    RepairAvailability availability() { state }

    @Override
    RepairOperation find(String operationId) {
        (RepairOperation) invoke('find', operationId)
    }

    @Override
    RepairOperation findPending(long structureId, List<Long> issueIds) {
        (RepairOperation) invoke('findPending', structureId, issueIds)
    }

    @Override
    Object withLocks(long structureId, List<Long> issueIds, Closure<Object> work) {
        invoke('withLocks', structureId, issueIds, work)
    }

    @Override
    RepairRefresh refresh(RepairApplyRequest request) {
        (RepairRefresh) invoke('refresh', request)
    }

    @Override
    RepairRefresh resumeContext(RepairOperation operation) {
        (RepairRefresh) invoke('resumeContext', operation)
    }

    @Override
    void persist(RepairOperation operation, RepairPackage repairPackage) {
        invoke('persist', operation, repairPackage)
    }

    @Override
    RepairMutationOutcome mutate(RepairPackage repairPackage) {
        (RepairMutationOutcome) invoke('mutate', repairPackage)
    }

    @Override
    VerificationOutcome verify(RepairRefresh context, boolean restored) {
        (VerificationOutcome) invoke('verify', context, restored)
    }

    @Override
    RepairMutationOutcome restore(RepairPackage repairPackage) {
        (RepairMutationOutcome) invoke('restore', repairPackage)
    }

    private Object invoke(String name, Object... arguments) {
        Closure<?> operation = operations.get(name)
        if (operation == null) throw new IllegalStateException('missing repair operation: ' + name)
        operation.call(*arguments)
    }

    private void requireOperation(Set<RepairCapability> missing,
                                  RepairCapability capability,
                                  String operation) {
        if (!operations.containsKey(operation)) missing.add(capability)
    }
}

// SOURCE: jira/structuredoctor/LiveStructureGateway.groovy
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

// SOURCE: jira/structureIssueDoctor.groovy
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

final class DoctorLiveAccess {
    private static final String ROADMAPS_PLUGIN = 'com.atlassian.jpo'
    private static final String HIERARCHY_API =
        'com.atlassian.rm.portfolio.publicapi.hierarchy.ExportedHierarchyLevelApi'
    private static final String PARENT_LINK = 'com.atlassian.jpo:jpo-custom-field-parent'
    private static final String EPIC_LINK = 'com.pyxis.greenhopper.jira:gh-epic-link'
    private static final int MAX_HIERARCHY_LEVELS = 1000
    private static final int MAX_ISSUES = 100000

    private DoctorLiveAccess() {
        throw new UnsupportedOperationException('utility class')
    }

    @CompileDynamic
    static ReadResult<HierarchySnapshot> readHierarchy() {
        try {
            Object hierarchyApi = resolvePluginComponent(ROADMAPS_PLUGIN, HIERARCHY_API)
            if (hierarchyApi == null) {
                return ReadResult.unavailable('Advanced Roadmaps hierarchy API is unavailable')
            }
            long count = ((Number) InvokerHelper.invokeMethod(
                hierarchyApi, 'count', null)).longValue()
            if (count <= 0L || count > MAX_HIERARCHY_LEVELS) {
                return ReadResult.failed('Jira hierarchy level count is outside the supported range')
            }
            Collection<?> values = hierarchyPage(hierarchyApi, (int) count)
            if (values == null) {
                return ReadResult.incomplete(null, 'Jira hierarchy read was not complete')
            }
            List<Map<String, Object>> records = values.collect { Object value ->
                Object id = partialValue(InvokerHelper.invokeMethod(value, 'getId', null), null)
                Object title = partialValue(
                    InvokerHelper.invokeMethod(value, 'getTitle', null), null)
                Object issueTypeIds = partialValue(
                    InvokerHelper.invokeMethod(value, 'getIssueTypeIds', null), [])
                [
                    rank: id,
                    levelId: String.valueOf(id),
                    name: title,
                    issueTypeIds: ((Collection<?>) issueTypeIds).collect {
                        Object issueTypeId -> Long.parseLong(String.valueOf(issueTypeId))
                    }
                ]
            }
            ReadResult.complete(LiveConfigurationDiscovery.mapHierarchy(records))
        } catch (Throwable failure) {
            ReadResult.failed('Jira hierarchy read failed: ' + failure.class.simpleName)
        }
    }

    @CompileDynamic
    static ReadResult<StructureSnapshot> readStructure(
        StructureComponents components, long structureId) {
        try {
            components.getStructureManager().getStructure(structureId, PermissionLevel.VIEW)
            Object latest = components.getForestService()
                .getForestSource(ForestSpec.structure(structureId)).getLatest()
            Forest forest = (Forest) latest.getForest()
            List<Map<String, Object>> rows = []
            Set<Long> generatorIds = new LinkedHashSet<Long>()
            Map<Long, Integer> generatorOrder = [:]
            for (int index = 0; index < forest.size(); index++) {
                long rowId = forest.getRow(index)
                StructureRow row = components.getRowManager().getRow(rowId)
                ItemIdentity identity = row.getItemId()
                Long issueId = null
                if (CoreIdentities.isIssue(identity)) {
                    issueId = identity.getLongId()
                } else if (CoreIdentities.isGenerator(identity)) {
                    long generatorId = identity.getLongId()
                    generatorIds.add(generatorId)
                    if (!generatorOrder.containsKey(generatorId)) {
                        generatorOrder.put(generatorId, index)
                    }
                }
                Long creatorId = null
                try {
                    long rawCreator = TransientRow.getCreatorId(row)
                    if (rawCreator > 0L) {
                        creatorId = rawCreator
                        generatorIds.add(rawCreator)
                        if (!generatorOrder.containsKey(rawCreator)) {
                            generatorOrder.put(rawCreator, index)
                        }
                    }
                } catch (RuntimeException ignored) {
                    // A permanent row has no transient provenance metadata.
                }
                rows.add([
                    rowId: String.valueOf(rowId), issueId: issueId,
                    parentIndex: forest.getParentIndex(index), depth: forest.getDepth(index),
                    position: index, creatorId: creatorId
                ])
            }

            boolean complete = true
            List<Map<String, Object>> generators = []
            Map<Long, String> moduleKeys = [:]
            for (Long generatorId : generatorIds) {
                try {
                    Object generator = components.getGeneratorManager().getGenerator(generatorId)
                    String moduleKey = String.valueOf(
                        InvokerHelper.getProperty(generator, 'moduleKey'))
                    Object rawParameters = InvokerHelper.getProperty(generator, 'parameters')
                    Object safeParameters = CoreSupport.jsonSafe(rawParameters)
                    Map<String, Object> parameters = safeParameters instanceof Map ?
                        (Map<String, Object>) safeParameters : [:]
                    moduleKeys.put(generatorId, moduleKey)
                    Map<String, Object> identity = [
                        generatorId: generatorId, moduleKey: moduleKey,
                        parameters: parameters,
                        order: generatorOrder.get(generatorId) ?: 0
                    ]
                    generators.add([
                        generatorId: generatorId, moduleKey: moduleKey,
                        type: generatorType(moduleKey),
                        order: generatorOrder.get(generatorId) ?: 0,
                        enabled: true, parameters: parameters,
                        revision: CoreCanonical.sha256(identity), complete: true
                    ])
                } catch (Throwable ignored) {
                    complete = false
                }
            }
            for (Map<String, Object> row : rows) {
                Long creatorId = row.creatorId instanceof Number ?
                    ((Number) row.creatorId).longValue() : null
                if (creatorId == null) {
                    row.provenance = 'PERMANENT'
                    row.provenanceComplete = true
                } else if (moduleKeys.containsKey(creatorId)) {
                    row.provenance = provenance(moduleKeys.get(creatorId))
                    row.creatorId = String.valueOf(creatorId)
                    row.provenanceComplete = true
                } else {
                    row.provenance = 'UNKNOWN'
                    row.creatorId = String.valueOf(creatorId)
                    row.provenanceComplete = false
                }
            }
            String revision = 'forest-' + CoreCanonical.sha256([
                rows: rows, generators: generators
            ])
            StructureSnapshot snapshot = LiveStructureGateway.mapStructure(
                structureId, revision, generators, rows, complete)
            snapshot.complete ? ReadResult.complete(snapshot) :
                ReadResult.incomplete(snapshot,
                    'Structure snapshot has incomplete generator or provenance data')
        } catch (Throwable failure) {
            ReadResult.failed('Structure snapshot read failed: ' + failure.class.simpleName)
        }
    }

    @CompileDynamic
    static ReadResult<List<IssueRelationSnapshot>> readIssues(
        IssueService service, CustomFieldManager fields, ApplicationUser actor,
        Collection<Long> requestedIds) {
        if (actor == null) return ReadResult.failed('Authenticated Jira user is required')
        try {
            List<CustomField> parentFields = fields.getCustomFieldObjects().findAll {
                CustomField field ->
                    String key = field.getCustomFieldType()?.getKey()
                    key == PARENT_LINK || key == EPIC_LINK
            } as List<CustomField>
            ArrayDeque<Long> pending = new ArrayDeque<Long>()
            (requestedIds ?: []).findAll { Long id -> id != null && id > 0L }
                .unique().each { Long id -> pending.add(id) }
            Set<Long> visited = new LinkedHashSet<Long>()
            List<Map<String, Object>> records = []
            boolean complete = true
            while (!pending.isEmpty()) {
                if (visited.size() >= MAX_ISSUES) {
                    return ReadResult.incomplete(
                        LiveJiraGateway.mapIssues(records),
                        'Jira relationship read reached the safety limit')
                }
                Long issueId = pending.removeFirst()
                if (!visited.add(issueId)) continue
                Object issueResult = service.getIssue(actor, issueId)
                Issue issue = issueResult?.isValid() ? (Issue) issueResult.getIssue() : null
                if (issue == null) {
                    complete = false
                    continue
                }
                List<Long> candidates = []
                Issue builtInParent = null
                try {
                    builtInParent = (Issue) InvokerHelper.invokeMethod(
                        issue, 'getParentObject', null)
                } catch (RuntimeException ignored) {
                    // Non-sub-task work items have no built-in parent object.
                }
                if (builtInParent != null) candidates.add(builtInParent.getId())
                List<Long> parentLinkIds = []
                List<Long> epicLinkIds = []
                for (CustomField field : parentFields) {
                    Object rawParentValue = issue.getCustomFieldValue(field)
                    List<Long> resolved = resolveIssueIds(service, actor, rawParentValue)
                    if (hasParentValue(rawParentValue) && resolved.isEmpty()) {
                        complete = false
                    }
                    String typeKey = field.getCustomFieldType()?.getKey()
                    if (typeKey == PARENT_LINK) parentLinkIds.addAll(resolved)
                    if (typeKey == EPIC_LINK) epicLinkIds.addAll(resolved)
                }
                candidates.addAll(parentLinkIds)
                candidates.addAll(epicLinkIds)
                candidates = candidates.unique()
                Long nativeParentId = builtInParent?.getId() ?:
                    (parentLinkIds ? parentLinkIds.first() :
                        (epicLinkIds ? epicLinkIds.first() : null))
                candidates.each { Long parentId -> pending.add(parentId) }
                records.add([
                    issueId: issue.getId(),
                    issueTypeId: Long.parseLong(issue.getIssueType().getId()),
                    nativeParentId: nativeParentId,
                    leadingParentIds: candidates,
                    revisions: [
                        issue: String.valueOf(issue.getUpdated()?.getTime() ?: 0L),
                        parents: CoreCanonical.sha256(candidates)
                    ]
                ])
            }
            List<IssueRelationSnapshot> values = LiveJiraGateway.mapIssues(records)
            complete ? ReadResult.complete(values) :
                ReadResult.incomplete(values,
                    'One or more Jira work items were not visible to the logged-in user')
        } catch (Throwable failure) {
            ReadResult.failed('Jira relationship read failed: ' + failure.class.simpleName)
        }
    }

    @CompileDynamic
    private static Object resolvePluginComponent(String pluginKey, String className) {
        Object plugin = ComponentAccessor.getPluginAccessor().getPlugin(pluginKey)
        Object loader = plugin == null ? null :
            InvokerHelper.invokeMethod(plugin, 'getClassLoader', null)
        if (loader == null) return null
        Class<?> componentClass = (Class<?>) InvokerHelper.invokeMethod(
            loader, 'loadClass', className)
        ComponentAccessor.getOSGiComponentInstanceOfType(componentClass)
    }

    @CompileDynamic
    private static Object partialValue(Object field, Object defaultValue) {
        field == null ? defaultValue :
            InvokerHelper.invokeMethod(field, 'or', [defaultValue] as Object[])
    }

    @CompileDynamic
    private static Collection<?> hierarchyPage(Object hierarchyApi, int pageSize) {
        for (int page : [1, 0]) {
            try {
                Object value = InvokerHelper.invokeMethod(
                    hierarchyApi, 'findAll', [page, pageSize] as Object[])
                if (value instanceof Collection &&
                    ((Collection<?>) value).size() == pageSize) {
                    return (Collection<?>) value
                }
            } catch (RuntimeException ignored) {
                // Public API releases have used different first-page conventions.
            }
        }
        null
    }

    @CompileDynamic
    private static List<Long> resolveIssueIds(
        IssueService service, ApplicationUser actor, Object value) {
        Collection<?> values = value instanceof Collection ?
            (Collection<?>) value : (value == null ? [] : [value])
        List<Long> result = []
        for (Object item : values) {
            Issue issue = item instanceof Issue ? (Issue) item : null
            Object issueResult = null
            if (issue == null && item instanceof Number) {
                issueResult = service.getIssue(actor, ((Number) item).longValue())
            } else if (issue == null && item != null) {
                issueResult = service.getIssue(actor, String.valueOf(item))
            }
            if (issue == null && issueResult?.isValid()) {
                issue = (Issue) issueResult.getIssue()
            }
            if (issue != null) result.add(issue.getId())
        }
        result.unique()
    }

    private static boolean hasParentValue(Object value) {
        if (value == null) return false
        if (value instanceof Collection) return !((Collection<?>) value).isEmpty()
        !String.valueOf(value).trim().isEmpty()
    }

    private static String generatorType(String moduleKey) {
        String key = moduleKey.toLowerCase(Locale.ROOT)
        key.contains('duplicate') ? 'DUPLICATES_FILTER' :
            key.contains('inserter') ? 'INSERTER' :
            key.contains('extender') ? 'EXTENDER' :
            key.contains('filter') ? 'FILTER' : 'OTHER'
    }

    private static String provenance(String moduleKey) {
        String key = moduleKey.toLowerCase(Locale.ROOT)
        key.contains('portfolio') || key.contains('roadmap') ? 'ADVANCED_ROADMAPS' :
            key.contains('link') ? 'JIRA_LINK' : 'GENERATOR'
    }
}

/**
 * Jira Data Center / ScriptRunner Custom REST Endpoint
 *
 * Browser UI:
 *   GET /rest/scriptrunner/latest/custom/structureIssueDoctor
 *
 * Structure-wide read-only analysis and planning:
 *   POST /rest/scriptrunner/latest/custom/structureIssueDoctorAnalyze
 *   POST /rest/scriptrunner/latest/custom/structureIssueDoctorPlan
 *
 * Legacy compatibility fix (not used by the Structure-wide Core UI):
 *   POST /rest/scriptrunner/latest/custom/structureIssueDoctorFix
 *   Content-Type: application/json
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
final Set<String> FIX_KEYS = ['structureId', 'issueKey', 'confirm'] as Set<String>

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
    builder = InvokerHelper.invokeMethod(builder, 'header',
        ['Cache-Control', 'no-store, private'] as Object[])
    builder = InvokerHelper.invokeMethod(builder, 'header',
        ['X-Content-Type-Options', 'nosniff'] as Object[])
    InvokerHelper.invokeMethod(builder, 'build', null)
}

Closure<Object> respondJson = { int status, Object payload ->
    respond.call(status, JsonOutput.prettyPrint(JsonOutput.toJson(payload)), 'application/json;charset=UTF-8')
}

Closure<Object> requireJsonRequest = {
        Object httpRequest, String body, Integer maxBytes = null ->
    DoctorHttpDecision decision = maxBytes == null ?
        DoctorHttpGuard.requireJson(httpRequest?.getContentType(), body) :
        DoctorHttpGuard.requireJson(httpRequest?.getContentType(), body, maxBytes)
    decision.isAllowed() ? null : respondJson.call(
        decision.getStatus(), [ok: false, error: decision.getError()])
}

Closure<Object> requireQueryKeys = { Object queryParams, Collection<String> allowed ->
    DoctorHttpDecision decision = DoctorHttpGuard.requireQueryKeys(
        queryParams?.keySet(), allowed)
    decision.isAllowed() ? null : respondJson.call(
        decision.getStatus(), [ok: false, error: decision.getError()])
}

Closure<String> html = { Object value ->
    CoreSupport.html(value)
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
    CoreSupport.jsonSafe(value)
}

Closure<String> queryValue = { Object queryParams, String name ->
    CoreSupport.queryValue(queryParams, name)
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
                      headers: {'Content-Type': 'application/json'},
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

LiveStructureGateway doctorStructureGateway = new LiveStructureGateway({
    List<Structure> values = structureComponents.getStructureManager()
        .getAllStructures(PermissionLevel.VIEW) as List<Structure>
    ReadResult.complete(values.collect { Structure item ->
        new StructureChoice(id: item.getId(), name: item.getName())
    })
}, { long structureId ->
    DoctorLiveAccess.readStructure(structureComponents, structureId)
})
LiveConfigurationDiscovery doctorHierarchy = new LiveConfigurationDiscovery({
    DoctorLiveAccess.readHierarchy()
})
LiveJiraGateway doctorJira = new LiveJiraGateway({ Collection<Long> issueIds ->
    DoctorLiveAccess.readIssues(issueService, customFieldManager,
        authenticationContext.getLoggedInUser(), issueIds)
})
LiveAutomationProvider doctorAutomation = new LiveAutomationProvider(null, null)
ProposalSource doctorProposals = { ignored ->
    ReadResult.unavailable('Repair proposal discovery is not proven on this instance')
} as ProposalSource
DoctorApplication doctorApplication = new DoctorApplication(
    doctorStructureGateway, doctorHierarchy, doctorStructureGateway, doctorJira,
    doctorAutomation, null, doctorProposals, { String issueKey ->
        ApplicationUser actor = authenticationContext.getLoggedInUser()
        IssueService.IssueResult result = actor == null ? null :
            issueService.getIssue(actor, issueKey.toUpperCase(Locale.ROOT))
        result?.isValid() && result.getIssue() != null ? result.getIssue().getId() : null
    })
DoctorRenderer doctorRenderer = new DoctorRenderer()
DoctorRepairApplication doctorRepairApplication = new DoctorRepairApplication(
    new DisabledRepairInfrastructure())
Closure<Object> legacyParentLinkRepair = {
        Long structureId, String issueKey, String ignoredConfirmation ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])

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
        ok: true, action: 'SET_PARENT_LINK', issueKey: issue.getKey(),
        fieldId: parentField.getId(), oldParent: oldParent, newParent: target.getKey(),
        verified: true, mailSent: false, eventDispatched: 'ISSUE_UPDATED',
        structureRefreshMayBeAsynchronous: true,
        analysisAfter: afterInspection.getPayload()
    ])
}
LegacyIssueDoctor legacyIssueDoctor = new LegacyIssueDoctor(
    inspectStructure, legacyParentLinkRepair)

structureIssueDoctor(httpMethod: 'GET', groups: ["jira-administrators"]) { Object queryParams, Object ignoredBody ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    Object queryRejection = requireQueryKeys.call(queryParams, ['format'])
    if (queryRejection != null) return queryRejection

    String format = queryValue.call(queryParams, 'format')
    ReadResult<List<StructureChoice>> structures = doctorApplication.listStructures()
    if ('json'.equalsIgnoreCase(format)) {
        return respondJson.call(structures.complete() ? 200 : 503, [
            ok: structures.complete(), state: structures.getState().name(),
            reason: structures.getReason(), structures: (structures.getValue() ?: []).collect {
                StructureChoice item -> [id: item.getId(), name: item.getName()]
            }
        ])
    }
    respond.call(structures.complete() ? 200 : 503,
        doctorRenderer.render(structures, null, null), 'text/html;charset=UTF-8')
}

structureIssueDoctorAnalyze(httpMethod: 'POST', groups: ["jira-administrators"]) { Object queryParams, String body, Object httpRequest ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    Object queryRejection = requireQueryKeys.call(queryParams, [])
    if (queryRejection != null) return queryRejection
    Object requestRejection = requireJsonRequest.call(
        httpRequest, body, DoctorHttpGuard.MAX_ANALYZE_JSON_BYTES)
    if (requestRejection != null) return requestRejection
    try {
        AnalyzeRequest request = DoctorApplication.parseAnalyzeRequest(
            DoctorHttpGuard.parseJsonObject(body))
        DoctorAnalysis analysis = doctorApplication.analyze(request)
        ReadResult<List<StructureChoice>> structures = doctorApplication.listStructures()
        respond.call(200, doctorRenderer.render(structures, analysis, null),
            'text/html;charset=UTF-8')
    } catch (DoctorBoundaryException failure) {
        respondJson.call(failure.getStatus(), [ok: false, error: failure.getCode()])
    } catch (IllegalArgumentException ignored) {
        respondJson.call(400, [ok: false, error: 'INVALID_ANALYZE_REQUEST'])
    }
}

structureIssueDoctorPlan(httpMethod: 'POST', groups: ["jira-administrators"]) { Object queryParams, String body, Object httpRequest ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    Object queryRejection = requireQueryKeys.call(queryParams, [])
    if (queryRejection != null) return queryRejection
    Object requestRejection = requireJsonRequest.call(httpRequest, body)
    if (requestRejection != null) return requestRejection
    try {
        PlanRequest request = DoctorApplication.parsePlanRequest(
            DoctorHttpGuard.parseJsonObject(body))
        ProposalPlan plan = doctorApplication.plan(request)
        DoctorAnalysis analysis = doctorApplication.analysis(request.getSnapshotId())
        doctorRepairApplication.registerPlan(analysis, plan)
        ReadResult<List<StructureChoice>> structures = doctorApplication.listStructures()
        respond.call(analysis == null ? 404 : 200,
            doctorRenderer.render(structures, analysis, plan), 'text/html;charset=UTF-8')
    } catch (DoctorBoundaryException failure) {
        respondJson.call(failure.getStatus(), [ok: false, error: failure.getCode()])
    } catch (IllegalArgumentException ignored) {
        respondJson.call(400, [ok: false, error: 'INVALID_PLAN_REQUEST'])
    }
}

Closure<Map<String, Object>> repairResultPayload = { RepairCoordinatorResult result ->
    RepairOperation operation = result.getOperation()
    [
        ok: result.getStatus() < 300,
        code: result.getCode(),
        replayed: result.isReplayed(),
        blockers: result.getBlockers(),
        operation: operation == null ? null : [
            id: operation.getOperationId(),
            state: operation.getState().name(),
            history: operation.getHistory()*.name()
        ]
    ]
}

structureIssueDoctorApply(httpMethod: 'POST', groups: ["jira-administrators"]) { Object queryParams, String body, Object httpRequest ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    Object queryRejection = requireQueryKeys.call(queryParams, [])
    if (queryRejection != null) return queryRejection
    Object requestRejection = requireJsonRequest.call(httpRequest, body)
    if (requestRejection != null) return requestRejection
    try {
        RepairCoordinatorResult result = doctorRepairApplication.apply(
            DoctorHttpGuard.parseJsonObject(body), user.getKey())
        respondJson.call(result.getStatus(), repairResultPayload.call(result))
    } catch (DoctorBoundaryException failure) {
        respondJson.call(failure.getStatus(), [ok: false, error: failure.getCode()])
    } catch (IllegalArgumentException ignored) {
        respondJson.call(400, [ok: false, error: 'INVALID_APPLY_REQUEST'])
    }
}

structureIssueDoctorStatus(httpMethod: 'GET', groups: ["jira-administrators"]) { Object queryParams, Object ignoredBody ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    Object queryRejection = requireQueryKeys.call(queryParams, ['operationId'])
    if (queryRejection != null) return queryRejection
    try {
        String operationId = queryValue.call(queryParams, 'operationId')
        RepairCoordinatorResult result = doctorRepairApplication.status(
            operationId, user.getKey())
        respondJson.call(result.getStatus(), repairResultPayload.call(result))
    } catch (IllegalArgumentException ignored) {
        respondJson.call(400, [ok: false, error: 'INVALID_STATUS_REQUEST'])
    }
}

structureIssueDoctorFix(httpMethod: 'POST', groups: ["jira-administrators"]) { Object queryParams, String body, Object httpRequest ->
    ApplicationUser user = authenticationContext.getLoggedInUser()
    if (user == null) return respondJson.call(401, [ok: false, error: 'AUTHENTICATION_REQUIRED'])
    Object queryRejection = requireQueryKeys.call(queryParams, [])
    if (queryRejection != null) return queryRejection
    Object requestRejection = requireJsonRequest.call(httpRequest, body)
    if (requestRejection != null) return requestRejection

    Map<String, Object> request
    try {
        request = DoctorHttpGuard.parseJsonObject(body)
    } catch (DoctorBoundaryException failure) {
        return respondJson.call(failure.getStatus(), [ok: false, error: failure.getCode()])
    }
    if (request.keySet() != FIX_KEYS) {
        return respondJson.call(400, [ok: false, error: 'INVALID_FIX_REQUEST'])
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
    legacyIssueDoctor.repair(structureId, issueKey, FIX_CONFIRMATION)
}
