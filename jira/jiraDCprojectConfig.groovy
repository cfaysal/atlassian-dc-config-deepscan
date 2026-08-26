/* =============================================================================
 * Jira Data Center - Project Configuration Deep Scan
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only; the only
 * write is the opt-in Confluence page export described below.
 *
 * Version
 *   Declared once as Pc.VERSION below and printed by every output channel: the
 *   HTML report, the JSON, the CSV and the generated Confluence page. The number
 *   lives in exactly one place, so this header cannot drift away from the code.
 *
 * Purpose
 *   One project in, its complete configuration out. Not a list of the scheme
 *   names a project happens to use - every configuration item is expanded down
 *   to its own inner configuration, and every node carries a deep link to the
 *   exact administration screen where it is maintained.
 *
 *   Issue type scheme down to its issue types. Issue type screen scheme down to
 *   issue type, operation, screen, tab and field. Field configuration scheme down
 *   to the behaviour of each field. Custom field contexts that actually apply to
 *   this project, with their options and defaults. Workflow scheme with every
 *   layer and that layer's workflow, its statuses and transitions. Permission,
 *   notification and issue security schemes down to the resolved grant. Roles
 *   with their actors, versions, components.
 *
 * What this endpoint deliberately does NOT do
 *   No issue counting, no issue search, no JQL. This report answers "how is this
 *   project configured", never "how much data is in it". That keeps the run cheap
 *   enough to be harmless on a production instance and removes the time budget
 *   the sibling app-footprint endpoint needs.
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameter is untyped, so no jakarta.* or javax.* import is present.
 *   The namespace a ScriptRunner script needs follows the SCRIPTRUNNER version,
 *   not the Jira version: ScriptRunner 10.x and above use jakarta.ws.rs.*, 8.x to
 *   9.x use javax.ws.rs.*. This file runs on either line without being edited.
 *
 * Parameters
 *   project=<KEY>             none      the project to report on. Without it the
 *                                       endpoint renders the project picker.
 *   format=html|json|csv      default html
 *   depth=full|top            default full   top shows only the first level of
 *                                            every section
 *   includeInactive=true|false default true   archived versions, inactive
 *                                             workflows, hidden fields
 *   numbers=de|en             default de      thousands separator style
 *
 * Reporting discipline
 *   A failed read is never rendered as an empty child list. A node with no
 *   children and a node whose children could not be read look different in the
 *   report, and the reason travels inside the node rather than only in the log.
 *
 *   A deep link that is not backed by primary evidence is never guessed. Such a
 *   node carries no link and states the navigation path in plain words instead.
 *   The provenance of every link shape used here is recorded in Dl below.
 * ========================================================================== */

import com.atlassian.jira.bc.project.component.ProjectComponent
import com.atlassian.jira.bc.project.component.ProjectComponentManager
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.properties.ApplicationProperties
import com.atlassian.jira.event.type.EventType
import com.atlassian.jira.event.type.EventTypeManager
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.fields.config.FieldConfigScheme
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme
import com.atlassian.jira.issue.fields.layout.field.FieldLayout
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager
import com.atlassian.jira.issue.fields.screen.FieldScreen
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem
import com.atlassian.jira.issue.fields.screen.FieldScreenTab
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager
import com.atlassian.jira.issue.issuetype.IssueType
import com.atlassian.jira.issue.operation.IssueOperations
import com.atlassian.jira.issue.operation.ScreenableIssueOperation
import com.atlassian.jira.issue.security.IssueSecurityLevel
import com.atlassian.jira.issue.security.IssueSecurityLevelManager
import com.atlassian.jira.issue.security.IssueSecuritySchemeManager
import com.atlassian.jira.issue.security.IssueSecurityTypeManager
import com.atlassian.jira.issue.status.Status
import com.atlassian.jira.notification.NotificationSchemeManager
import com.atlassian.jira.notification.NotificationTypeManager
import com.atlassian.jira.permission.PermissionSchemeManager
import com.atlassian.jira.permission.PermissionTypeManager
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectCategory
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.project.type.ProjectType
import com.atlassian.jira.project.type.ProjectTypeKey
import com.atlassian.jira.project.type.ProjectTypeManager
import com.atlassian.jira.project.version.Version
import com.atlassian.jira.project.version.VersionManager
import com.atlassian.jira.scheme.Scheme
import com.atlassian.jira.scheme.SchemeEntity
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.security.roles.ProjectRole
import com.atlassian.jira.security.roles.ProjectRoleActors
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.security.roles.RoleActor
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.util.BuildUtilsInfo
import com.atlassian.jira.util.I18nHelper
import com.atlassian.jira.workflow.AssignableWorkflowScheme
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowSchemeManager

import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.FunctionDescriptor
import com.opensymphony.workflow.loader.ResultDescriptor
import com.opensymphony.workflow.loader.StepDescriptor
import com.opensymphony.workflow.loader.ValidatorDescriptor

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import org.codehaus.groovy.runtime.InvokerHelper

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@BaseScript CustomEndpointDelegate delegate

/* =============================================================================
 * Utility - deliberately free of any Jira type so it stays unit-testable
 * ========================================================================== */

class Pc {

    static final String NA = "—"

    /* The single place the report version lives. The file header points here and
     * every output channel prints this constant, so a report always names the
     * build that produced it. */
    static final String VERSION = "0.1"

    /* Node states. A node is not just present or absent: it can be present but
     * unreadable, and the report has to keep those apart. */
    static final String READ = "read"
    static final String UNREADABLE = "unreadable"
    static final String ABSENT = "absent"
    static final String TRUNCATED = "truncated"

    /* ---- escaping and formatting --------------------------------------- */

    static String html(Object value) {
        if (value == null) {
            return ""
        }
        return value.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    static String csv(Object value) {
        if (value == null) {
            return "\"\""
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\""
    }

    static String number(Number value, Locale loc) {
        if (value == null) {
            return NA
        }
        return String.format(loc == null ? Locale.ENGLISH : loc, "%,d", value.longValue())
    }

    static String text(Object value) {
        if (value == null) {
            return null
        }
        String out = value.toString().trim()
        return out.isEmpty() ? null : out
    }

    static String orNa(Object value) {
        String out = text(value)
        return out == null ? NA : out
    }

    /* URL-encoding for a path segment. A project key is uppercase alphanumeric in
     * practice, but the key of an archived or imported project is not guaranteed
     * to be, and a raw key in a path is how a link silently breaks. */
    static String urlPath(String value) {
        if (value == null) {
            return ""
        }
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (Exception ignored) {
            return value
        }
    }

    static String urlQuery(String value) {
        if (value == null) {
            return ""
        }
        try {
            return URLEncoder.encode(value, "UTF-8")
        } catch (Exception ignored) {
            return value
        }
    }

    /* The base URL without its trailing slash, so every caller can concatenate a
     * path that starts with one and no link ends up with a double slash. */
    static String trimBase(String baseUrl) {
        String out = text(baseUrl)
        if (out == null) {
            return ""
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1)
        }
        return out
    }

    /* ---- query parameters ---------------------------------------------- */

    /* queryParams is the JAX-RS MultivaluedMap, and naming that type would drag
     * either javax.ws.rs or jakarta.ws.rs into this file - the one dependency this
     * endpoint exists without, so that a single file runs on ScriptRunner 8 and
     * on ScriptRunner 10. The call is therefore made through the invoker, which is
     * both namespace-neutral and resolvable by the static type checker. */
    static String stringParam(Object queryParams, String name, String defaultValue) {
        Object raw = queryParams == null ? null : duck(queryParams, "getFirst", name)
        if (raw == null) {
            return defaultValue
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? defaultValue : value
    }

    static boolean booleanParam(Object queryParams, String name, boolean defaultValue) {
        String value = stringParam(queryParams, name, null)
        if (value == null) {
            return defaultValue
        }
        value = value.toLowerCase(Locale.ROOT)
        if (value in ["true", "1", "yes", "on"]) {
            return true
        }
        if (value in ["false", "0", "no", "off"]) {
            return false
        }
        return defaultValue
    }

    /* Self-referencing report link. Keys with a null value drop out, so the URL
     * only ever carries the parameters that differ from the defaults. */
    static String link(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(base)
        if (overrides != null) {
            merged.putAll(overrides)
        }
        StringBuilder out = new StringBuilder("?")
        boolean first = true
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (entry.value == null) {
                continue
            }
            if (!first) {
                out.append("&")
            }
            out.append(urlQuery(entry.key)).append("=").append(urlQuery(entry.value.toString()))
            first = false
        }
        return out.length() == 1 ? "?" : out.toString()
    }

    /* The assignee type is stored as a number. The constants are
     * com.atlassian.jira.project.AssigneeTypes, verified against jira-api 10.3.19
     * and 11.3.8: PROJECT_DEFAULT 0, COMPONENT_LEAD 1, PROJECT_LEAD 2,
     * UNASSIGNED 3. An unknown number is reported as itself, never guessed into
     * one of the four. */
    static String assigneeType(Long value) {
        if (value == null) {
            return NA
        }
        switch (value.longValue()) {
            case 0L: return "Project default"
            case 1L: return "Component lead"
            case 2L: return "Project lead"
            case 3L: return "Unassigned"
            default: return "Unknown (" + value.toString() + ")"
        }
    }

    static String flag(boolean value) {
        return value ? "yes" : "no"
    }

    static String dateText(Date value) {
        return value == null ? NA : new java.text.SimpleDateFormat("yyyy-MM-dd").format(value)
    }

    /* Reading a bean property that may not exist on this Jira line. Everything
     * the report shows about scheme grants comes out of Jira's own type registry
     * rather than out of a hard-coded table of type strings, and the shape of
     * that registry differs between versions: what is an interface on one line is
     * an enum on another. Asking the object whether it answers a method, instead
     * of declaring which interface it must implement, is what lets one file serve
     * both. A miss returns null and the caller falls back to the raw value. */
    static Object duck(Object target, String method, Object argument) {
        return duckAll(target, method, argument == null ? new Object[0] : ([argument] as Object[]))
    }

    /* Written against InvokerHelper rather than as target."$name"() on purpose.
     * A dynamic method name is invisible to the static type checker and shows up
     * as an error in the ScriptRunner editor, which is the one place an
     * administrator reads this file before running it. This form does the same
     * thing and stays checkable. */
    static Object duckAll(Object target, String method, Object[] arguments) {
        if (target == null) {
            return null
        }
        try {
            return InvokerHelper.invokeMethod(target, method, arguments)
        } catch (MissingMethodException ignored) {
            return null
        } catch (Exception ignored) {
            return null
        }
    }

    static String timestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    }
}

/* =============================================================================
 * The HTTP response, built without naming a JAX-RS namespace
 *
 * The namespace a ScriptRunner script needs follows the ScriptRunner version, not
 * the Jira version: 10.x and above use jakarta.ws.rs.*, 8.x to 9.x use
 * javax.ws.rs.*. Importing either one would tie this file to one of the two lines.
 * The class is therefore resolved at runtime and the builder chain is driven
 * through the invoker.
 *
 * Keeping that in one class rather than at each of the four call sites has a
 * second effect that matters more than the tidiness: the whole thing becomes
 * testable off-instance, because a fake response class is enough to prove the
 * chain is built in the right order with the right arguments.
 * ========================================================================== */

class Http {

    static final String HTML = "text/html; charset=UTF-8"
    static final String JSON = "application/json; charset=UTF-8"
    static final String CSV = "text/csv; charset=UTF-8"

    static Class resolveResponseClass() {
        try {
            return Class.forName("jakarta.ws.rs.core.Response")
        } catch (ClassNotFoundException ignored) {
            return Class.forName("javax.ws.rs.core.Response")
        }
    }

    /* status 200 goes through ok(entity), anything else through
     * status(code).entity(entity), because that is the shape JAX-RS offers. */
    static Object build(Class responseClass, int status, String entity,
                        String contentType, Map<String, String> headers) {
        Object builder
        if (status == 200) {
            builder = Pc.duckAll(responseClass, "ok", [entity] as Object[])
        } else {
            builder = Pc.duckAll(responseClass, "status", [Integer.valueOf(status)] as Object[])
            builder = Pc.duckAll(builder, "entity", [entity] as Object[])
        }
        builder = Pc.duckAll(builder, "type", [contentType] as Object[])
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder = Pc.duckAll(builder, "header",
                    [header.getKey(), header.getValue()] as Object[])
            }
        }
        return Pc.duckAll(builder, "build", new Object[0])
    }

    static Object ok(Class responseClass, String entity, String contentType) {
        return build(responseClass, 200, entity, contentType, null)
    }
}

/* =============================================================================
 * Deep links
 *
 * Every shape below is backed by primary evidence read on 2026-08-26, not by
 * recollection. Two sources were used and both are named per entry:
 *
 *   actions.xml + action class setters
 *       jira-core 10.3.19. The alias in actions.xml gives the path segment, and
 *       the URL parameters of an XWork action ARE the setters of its action
 *       class, so `javap` on the class is the parameter evidence.
 *
 *   shipped plugin descriptors and templates
 *       read out of a running Jira 11.3.8, 681 plugin jars scanned for the
 *       literal URL. A literal that Jira itself emits is stronger evidence than
 *       any documentation page.
 *
 * A shape that could not be evidenced does not get invented here. The builder
 * returns null and the caller states the navigation path in words instead.
 * ========================================================================== */

class Dl {

    /* Kept out of the constructors so the whole class stays a pure function of
     * its inputs and the test suite can drive it without a Jira instance. */
    String base

    Dl(String baseUrl) {
        this.base = Pc.trimBase(baseUrl)
    }

    private String admin(String tail) {
        return base + "/secure/admin/" + tail
    }

    private String projectConfig(String projectKey, String tail) {
        String key = Pc.urlPath(projectKey)
        if (key.isEmpty()) {
            return null
        }
        return base + "/plugins/servlet/project-config/" + key + (tail == null ? "" : "/" + tail)
    }

    /* ---- project pages -------------------------------------------------- */
    /* Evidence: com.atlassian.jira.jira-admin-project-config-plugin-11.3.8.jar,
     * atlassian-plugin.xml, web-item URLs. The plugin emits exactly these tails. */

    String projectSummary(String key) { return projectConfig(key, "summary") }
    String projectFields(String key) { return projectConfig(key, "fields") }
    String projectScreens(String key) { return projectConfig(key, "screens") }
    String projectWorkflows(String key) { return projectConfig(key, "workflows") }
    String projectPermissions(String key) { return projectConfig(key, "permissions") }
    String projectNotifications(String key) { return projectConfig(key, "notifications") }
    String projectIssueSecurity(String key) { return projectConfig(key, "issuesecurity") }
    String projectRoles(String key) { return projectConfig(key, "roles") }
    String projectPriorities(String key) { return projectConfig(key, "priorities") }
    String projectVersions(String key) { return projectConfig(key, "administer-versions") }
    String projectComponents(String key) { return projectConfig(key, "administer-components") }

    /* Evidence: literal /plugins/servlet/project-config/${project.key}/issuetypes/${issueType.id}
     * found in a shipped template of the running instance. */
    String projectIssueType(String key, Object issueTypeId) {
        String id = Pc.text(issueTypeId)
        if (id == null) {
            return null
        }
        return projectConfig(key, "issuetypes/" + Pc.urlPath(id))
    }

    /* ---- scheme and configuration pages --------------------------------- */

    /* Evidence: alias ConfigureFieldScreen, setter setId(Long). */
    String screen(Object id) {
        String value = Pc.text(id)
        return value == null ? null : admin("ConfigureFieldScreen.jspa?id=" + Pc.urlQuery(value))
    }

    /* Evidence: alias ConfigureFieldScreenScheme, setId(Long) on
     * AbstractFieldScreenSchemeAction. */
    String screenScheme(Object id) {
        String value = Pc.text(id)
        return value == null ? null : admin("ConfigureFieldScreenScheme.jspa?id=" + Pc.urlQuery(value))
    }

    /* Evidence: literal ConfigureIssueTypeScreenScheme.jspa?id, 21 occurrences in
     * the shipped plugins of the running instance. */
    String issueTypeScreenScheme(Object id) {
        String value = Pc.text(id)
        return value == null ? null : admin("ConfigureIssueTypeScreenScheme.jspa?id=" + Pc.urlQuery(value))
    }

    /* Evidence: alias ConfigureFieldLayout, setId(Long) on
     * AbstractConfigureFieldLayout. This is a field configuration. */
    String fieldConfiguration(Object id) {
        String value = Pc.text(id)
        return value == null ? null : admin("ConfigureFieldLayout.jspa?id=" + Pc.urlQuery(value))
    }

    /* Evidence: alias ConfigureFieldLayoutScheme, setId(Long). */
    String fieldConfigurationScheme(Object id) {
        String value = Pc.text(id)
        return value == null ? null : admin("ConfigureFieldLayoutScheme.jspa?id=" + Pc.urlQuery(value))
    }

    /* Evidence: alias ConfigureCustomField, setCustomFieldId(Long) and
     * setFieldConfigSchemeId(Long). The second parameter opens the context. */
    String customField(Object numericId) {
        String value = Pc.text(numericId)
        return value == null ? null : admin("ConfigureCustomField.jspa?customFieldId=" + Pc.urlQuery(value))
    }

    String customFieldContext(Object numericId, Object fieldConfigSchemeId) {
        String field = Pc.text(numericId)
        String context = Pc.text(fieldConfigSchemeId)
        if (field == null || context == null) {
            return null
        }
        return admin("ConfigureCustomField.jspa?customFieldId=" + Pc.urlQuery(field) +
            "&fieldConfigSchemeId=" + Pc.urlQuery(context))
    }

    /* Evidence: alias EditPermissions on class admin.permission.ProjectPermissions,
     * setter setSchemeId(long). */
    String permissionScheme(Object schemeId) {
        String value = Pc.text(schemeId)
        return value == null ? null : admin("EditPermissions.jspa?schemeId=" + Pc.urlQuery(value))
    }

    /* Evidence: alias EditNotifications, setSchemeId(Long) inherited from
     * com.atlassian.jira.scheme.AbstractSchemeAwareAction. */
    String notificationScheme(Object schemeId) {
        String value = Pc.text(schemeId)
        return value == null ? null : admin("EditNotifications!default.jspa?schemeId=" + Pc.urlQuery(value))
    }

    /* Evidence: alias EditIssueSecurities, setSchemeId(Long) inherited, plus the
     * class's own setLevelId(Long). */
    String issueSecurityScheme(Object schemeId) {
        String value = Pc.text(schemeId)
        return value == null ? null : admin("EditIssueSecurities!default.jspa?schemeId=" + Pc.urlQuery(value))
    }

    String issueSecurityLevel(Object schemeId, Object levelId) {
        String scheme = Pc.text(schemeId)
        String level = Pc.text(levelId)
        if (scheme == null || level == null) {
            return null
        }
        return admin("EditIssueSecurities!default.jspa?schemeId=" + Pc.urlQuery(scheme) +
            "&levelId=" + Pc.urlQuery(level))
    }

    /* Evidence: literal EditWorkflowScheme.jspa?schemeId={$schemeId} in a shipped
     * soy template of the running instance. */
    String workflowScheme(Object schemeId) {
        String value = Pc.text(schemeId)
        return value == null ? null : admin("workflows/EditWorkflowScheme.jspa?schemeId=" + Pc.urlQuery(value))
    }

    /* Evidence: literal ViewWorkflowSteps.jspa?workflowName=${wfName}${urlPost} in
     * a jira-core template. workflowMode selects live or draft; "live" is the
     * published workflow, which is the one a configuration report describes. */
    String workflow(String workflowName, boolean draft) {
        String value = Pc.text(workflowName)
        if (value == null) {
            return null
        }
        return admin("workflows/ViewWorkflowSteps.jspa?workflowName=" + Pc.urlQuery(value) +
            "&workflowMode=" + (draft ? "draft" : "live"))
    }

    /* NOT EVIDENCED. The alias ManageIssueTypeSchemes exists in actions.xml, but no
     * parameter for addressing one specific scheme could be evidenced anywhere in
     * jira-core or in the shipped plugins. Rather than guess one, the issue type
     * scheme node links to the project's own issue type page, and this method
     * stays here to record the gap instead of hiding it. */
    String issueTypeSchemeUnavailableNote() {
        return "Administration > Issues > Issue type schemes. No evidenced URL parameter " +
            "addresses a single scheme, so this node is not linked."
    }
}

/* =============================================================================
 * The report tree
 *
 * One recursive node type carries the whole report. Everything the renderers
 * need is on it, so a new section is a new subtree and never a new renderer.
 * ========================================================================== */

class Nd {

    /* Machine-readable node type, e.g. "issueTypeScheme", "screen", "field". Used
     * by the JSON consumer and by the CSV path column, never shown as a label. */
    String kind = "node"

    /* What the administrator reads. */
    String label

    /* Optional scalar value of this node, e.g. "required" or a date. */
    String value

    /* The Jira-internal id, when the object has one. Printed because it is what
     * an administrator needs when they go looking in the database. */
    String id

    /* Absolute URL, or null. */
    String deepLink

    /* Set exactly when deepLink is null and the node could have had one. Says in
     * plain words where the item is maintained. */
    String linkNote

    /* read, unreadable, absent or truncated. A node whose children could not be
     * read is NOT the same as a node without children, and this field is the only
     * thing keeping those apart. */
    String state = Pc.READ

    /* Suppressed read errors at this node. They travel with the node, so a
     * failure surfaces exactly where it happened rather than in a global list
     * nobody reads. */
    List<String> diagnostics = new ArrayList<String>()

    List<Nd> children = new ArrayList<Nd>()

    Nd(String kind, String label) {
        this.kind = kind
        this.label = label
    }

    static Nd of(String kind, String label) {
        return new Nd(kind, label)
    }

    Nd val(Object value) {
        this.value = Pc.text(value)
        return this
    }

    Nd ident(Object id) {
        this.id = Pc.text(id)
        return this
    }

    /* A link is attached with the note that applies when it is absent, so the two
     * can never drift apart: passing a null link without a note is what produces
     * an unexplained missing link. */
    Nd link(String url, String noteIfMissing) {
        String candidate = Pc.text(url)
        if (candidate == null) {
            this.deepLink = null
            this.linkNote = Pc.text(noteIfMissing)
        } else {
            this.deepLink = candidate
            this.linkNote = null
        }
        return this
    }

    Nd add(Nd child) {
        if (child != null) {
            children.add(child)
        }
        return this
    }

    Nd addAll(Collection<Nd> items) {
        if (items != null) {
            for (Nd item : items) {
                add(item)
            }
        }
        return this
    }

    /* The failure reason belongs to the node, not only to the log. */
    Nd failed(String reason) {
        this.state = Pc.UNREADABLE
        if (Pc.text(reason) != null) {
            diagnostics.add(reason)
        }
        return this
    }

    Nd absent(String reason) {
        this.state = Pc.ABSENT
        if (Pc.text(reason) != null) {
            this.value = reason
        }
        return this
    }

    boolean isReadable() {
        return Pc.READ.equals(state)
    }

    int countDescendants() {
        int total = children.size()
        for (Nd child : children) {
            total += child.countDescendants()
        }
        return total
    }

    /* Every diagnostic in the subtree, prefixed with the path it sits on, so the
     * report can show one honest list without losing where each entry came from. */
    List<String> collectDiagnostics(String path) {
        String here = path == null || path.isEmpty() ? Pc.orNa(label) : path + " > " + Pc.orNa(label)
        List<String> out = new ArrayList<String>()
        for (String entry : diagnostics) {
            out.add(here + ": " + entry)
        }
        for (Nd child : children) {
            out.addAll(child.collectDiagnostics(here))
        }
        return out
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("kind", kind)
        out.put("label", label)
        if (value != null) {
            out.put("value", value)
        }
        if (id != null) {
            out.put("id", id)
        }
        if (deepLink != null) {
            out.put("deepLink", deepLink)
        }
        if (linkNote != null) {
            out.put("linkNote", linkNote)
        }
        out.put("state", state)
        if (!diagnostics.isEmpty()) {
            out.put("diagnostics", new ArrayList<String>(diagnostics))
        }
        if (!children.isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<Map<String, Object>>()
            for (Nd child : children) {
                kids.add(child.toMap())
            }
            out.put("children", kids)
        }
        return out
    }
}

/* =============================================================================
 * The report
 * ========================================================================== */

class Report {

    String version = Pc.VERSION
    String generatedAt = Pc.timestamp()
    long executionMs = 0L

    String instanceTitle
    String instanceBaseUrl
    String jiraVersion
    String jiraBuild

    String projectKey
    String projectName
    String projectId

    /* Top-level sections, each the root of one subtree. */
    List<Nd> sections = new ArrayList<Nd>()

    /* Failures that could not be attached to any single node, e.g. a component
     * that could not be obtained at all. Deliberately small: a diagnostic that
     * belongs to a node lives on that node. */
    List<String> globalDiagnostics = new ArrayList<String>()

    Nd section(String kind, String label) {
        Nd node = Nd.of(kind, label)
        sections.add(node)
        return node
    }

    int nodeCount() {
        int total = 0
        for (Nd node : sections) {
            total += 1 + node.countDescendants()
        }
        return total
    }

    int unreadableCount() {
        int total = 0
        for (Nd node : sections) {
            total += countUnreadable(node)
        }
        return total
    }

    private int countUnreadable(Nd node) {
        int total = node.isReadable() ? 0 : 1
        for (Nd child : node.children) {
            total += countUnreadable(child)
        }
        return total
    }

    int unlinkedCount() {
        int total = 0
        for (Nd node : sections) {
            total += countUnlinked(node)
        }
        return total
    }

    private int countUnlinked(Nd node) {
        int total = node.linkNote != null ? 1 : 0
        for (Nd child : node.children) {
            total += countUnlinked(child)
        }
        return total
    }

    List<String> allDiagnostics() {
        List<String> out = new ArrayList<String>(globalDiagnostics)
        for (Nd node : sections) {
            out.addAll(node.collectDiagnostics(""))
        }
        return out
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("reportVersion", version)
        out.put("generatedAt", generatedAt)
        out.put("executionMs", Long.valueOf(executionMs))
        Map<String, Object> instance = new LinkedHashMap<String, Object>()
        instance.put("title", instanceTitle)
        instance.put("baseUrl", instanceBaseUrl)
        instance.put("jiraVersion", jiraVersion)
        instance.put("jiraBuild", jiraBuild)
        out.put("instance", instance)
        Map<String, Object> project = new LinkedHashMap<String, Object>()
        project.put("key", projectKey)
        project.put("name", projectName)
        project.put("id", projectId)
        out.put("project", project)
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>()
        for (Nd node : sections) {
            nodes.add(node.toMap())
        }
        out.put("sections", nodes)
        out.put("diagnostics", allDiagnostics())
        Map<String, Object> totals = new LinkedHashMap<String, Object>()
        totals.put("nodes", Integer.valueOf(nodeCount()))
        totals.put("unreadable", Integer.valueOf(unreadableCount()))
        totals.put("unlinked", Integer.valueOf(unlinkedCount()))
        out.put("totals", totals)
        return out
    }
}

/* =============================================================================
 * Renderers - pure functions of the report, so the suite can drive them offline
 * ========================================================================== */

class Render {

    static String json(Report report) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(report.toMap()))
    }

    /* One row per node. A tree in CSV is never pretty, but this is the shape that
     * goes into a spreadsheet and into an audit record. */
    static String csv(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("path;kind;label;value;id;state;deepLink;linkNote;diagnostics\n")
        for (Nd node : report.sections) {
            csvNode(out, node, "")
        }
        return out.toString()
    }

    private static void csvNode(StringBuilder out, Nd node, String parentPath) {
        String path = parentPath.isEmpty() ? Pc.orNa(node.label) : parentPath + " > " + Pc.orNa(node.label)
        out.append(Pc.csv(path)).append(";")
        out.append(Pc.csv(node.kind)).append(";")
        out.append(Pc.csv(node.label)).append(";")
        out.append(Pc.csv(node.value)).append(";")
        out.append(Pc.csv(node.id)).append(";")
        out.append(Pc.csv(node.state)).append(";")
        out.append(Pc.csv(node.deepLink)).append(";")
        out.append(Pc.csv(node.linkNote)).append(";")
        out.append(Pc.csv(node.diagnostics.isEmpty() ? null : node.diagnostics.join(" | "))).append("\n")
        for (Nd child : node.children) {
            csvNode(out, child, path)
        }
    }

    /* ---- HTML ----------------------------------------------------------- */

    static String html(Report report, Map<String, Object> activeParams, boolean topOnly) {
        StringBuilder out = new StringBuilder()
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        out.append("<meta charset=\"UTF-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>Project configuration - ").append(Pc.html(report.projectKey)).append("</title>\n")
        out.append(style())
        out.append("</head>\n<body>\n<div class=\"page\">\n")
        out.append(header(report))
        out.append(instanceCard(report))
        out.append(summaryCards(report))
        out.append(toolbar(activeParams, topOnly))
        out.append(diagnosticsCard(report))
        for (Nd node : report.sections) {
            out.append(section(node, topOnly))
        }
        out.append(footer(report))
        out.append("</div>\n").append(script()).append("</body>\n</html>\n")
        return out.toString()
    }

    private static String header(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"page-header\"><div>")
        out.append("<h1 class=\"page-title\">").append(Pc.html(report.projectName))
        out.append(" <span class=\"muted\">(").append(Pc.html(report.projectKey)).append(")</span></h1>")
        out.append("<div class=\"page-subtitle\">Complete configuration of this project. ")
        out.append("Every item is expanded to its own configuration and linked to the screen where it is maintained.")
        out.append("</div></div></div>\n")
        return out.toString()
    }

    private static String instanceCard(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"instance\">")
        out.append("<div><strong>Instance</strong> ").append(Pc.html(Pc.orNa(report.instanceTitle))).append("</div>")
        out.append("<div><strong>Jira</strong> ").append(Pc.html(Pc.orNa(report.jiraVersion)))
        if (Pc.text(report.jiraBuild) != null) {
            out.append(" (build ").append(Pc.html(report.jiraBuild)).append(")")
        }
        out.append("</div>")
        out.append("<div><strong>Report</strong> v").append(Pc.html(report.version)).append("</div>")
        out.append("<div><strong>Generated</strong> ").append(Pc.html(report.generatedAt)).append("</div>")
        out.append("<div><strong>Runtime</strong> ").append(Pc.html(String.valueOf(report.executionMs))).append(" ms</div>")
        out.append("</div>\n")
        return out.toString()
    }

    private static String summaryCards(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"summary-grid\">")
        out.append(card(String.valueOf(report.sections.size()), "sections"))
        out.append(card(String.valueOf(report.nodeCount()), "configuration items"))
        out.append(card(String.valueOf(report.unreadableCount()), "could not be read"))
        out.append(card(String.valueOf(report.unlinkedCount()), "without a deep link"))
        out.append("</div>\n")
        return out.toString()
    }

    private static String card(String value, String label) {
        return "<div class=\"summary-card\"><div class=\"summary-value\">" + Pc.html(value) +
            "</div><div class=\"summary-label\">" + Pc.html(label) + "</div></div>"
    }

    private static String toolbar(Map<String, Object> activeParams, boolean topOnly) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"actions\">")
        out.append(button(Pc.link(activeParams, [depth: topOnly ? null : "top"]),
            topOnly ? "Expand everything" : "First level only", false))
        out.append(button(Pc.link(activeParams, [format: "json"]), "JSON", false))
        out.append(button(Pc.link(activeParams, [format: "csv"]), "CSV", false))
        out.append(button(Pc.link([:], [:]), "Pick another project", false))
        out.append("</div>\n")
        return out.toString()
    }

    private static String button(String href, String label, boolean on) {
        return "<a class=\"button" + (on ? " on" : "") + "\" href=\"" + Pc.html(href) + "\">" +
            Pc.html(label) + "</a>"
    }

    private static String diagnosticsCard(Report report) {
        List<String> entries = report.allDiagnostics()
        if (entries.isEmpty()) {
            return ""
        }
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"diag diag-warn\"><strong>")
        out.append(String.valueOf(entries.size()))
        out.append(entries.size() == 1 ? " read was suppressed" : " reads were suppressed")
        out.append(".</strong> Each one is also marked at the item it belongs to. ")
        out.append("A suppressed read is not a measured absence.<ul>")
        for (String entry : entries) {
            out.append("<li>").append(Pc.html(entry)).append("</li>")
        }
        out.append("</ul></div>\n")
        return out.toString()
    }

    private static String section(Nd node, boolean topOnly) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"section\">")
        out.append("<div class=\"section-head\">")
        out.append("<h2 class=\"section-title\">").append(Pc.html(Pc.orNa(node.label))).append("</h2>")
        if (node.deepLink != null) {
            out.append("<a class=\"jump\" href=\"").append(Pc.html(node.deepLink))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">open in Jira</a>")
        }
        out.append("</div>")
        if (node.value != null) {
            out.append("<div class=\"section-value\">").append(Pc.html(node.value)).append("</div>")
        }
        if (node.linkNote != null) {
            out.append("<div class=\"linknote\">").append(Pc.html(node.linkNote)).append("</div>")
        }
        if (!node.isReadable()) {
            out.append("<div class=\"state state-").append(Pc.html(node.state)).append("\">")
            out.append(Pc.html(stateLabel(node.state))).append("</div>")
        }
        if (node.children.isEmpty()) {
            if (node.isReadable()) {
                out.append("<div class=\"muted empty\">Nothing configured here.</div>")
            }
        } else {
            out.append("<ul class=\"tree\">")
            for (Nd child : node.children) {
                out.append(treeNode(child, topOnly, 1))
            }
            out.append("</ul>")
        }
        out.append("</div>\n")
        return out.toString()
    }

    private static String treeNode(Nd node, boolean topOnly, int level) {
        StringBuilder out = new StringBuilder()
        boolean hasChildren = !node.children.isEmpty()
        boolean collapse = topOnly && level >= 1 && hasChildren
        out.append("<li class=\"node node-").append(Pc.html(node.kind)).append("\">")
        out.append("<div class=\"node-line\">")
        if (hasChildren) {
            out.append("<button class=\"twisty\" type=\"button\" aria-expanded=\"")
            out.append(collapse ? "false" : "true").append("\">")
            out.append(collapse ? "&#9656;" : "&#9662;").append("</button>")
        } else {
            out.append("<span class=\"twisty-spacer\"></span>")
        }
        out.append("<span class=\"node-label\">").append(Pc.html(Pc.orNa(node.label))).append("</span>")
        if (node.value != null) {
            out.append("<span class=\"node-value\">").append(Pc.html(node.value)).append("</span>")
        }
        if (node.id != null) {
            out.append("<span class=\"node-id mono\">id ").append(Pc.html(node.id)).append("</span>")
        }
        if (node.deepLink != null) {
            out.append("<a class=\"node-link\" href=\"").append(Pc.html(node.deepLink))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">open</a>")
        } else if (node.linkNote != null) {
            out.append("<span class=\"node-nolink\" title=\"").append(Pc.html(node.linkNote))
            out.append("\">no link</span>")
        }
        if (!node.isReadable()) {
            out.append("<span class=\"state state-").append(Pc.html(node.state)).append("\">")
            out.append(Pc.html(stateLabel(node.state))).append("</span>")
        }
        out.append("</div>")
        for (String entry : node.diagnostics) {
            out.append("<div class=\"node-diag\">").append(Pc.html(entry)).append("</div>")
        }
        if (hasChildren) {
            out.append("<ul class=\"tree").append(collapse ? " hidden" : "").append("\">")
            for (Nd child : node.children) {
                out.append(treeNode(child, topOnly, level + 1))
            }
            out.append("</ul>")
        }
        out.append("</li>")
        return out.toString()
    }

    static String stateLabel(String state) {
        if (Pc.UNREADABLE.equals(state)) {
            return "could not be read"
        }
        if (Pc.ABSENT.equals(state)) {
            return "not configured"
        }
        if (Pc.TRUNCATED.equals(state)) {
            return "shortened"
        }
        return "read"
    }

    private static String footer(Report report) {
        return "<div class=\"footer muted\">Project configuration report v" + Pc.html(report.version) +
            ". Read-only: producing this report changes nothing and contacts nothing outside this instance.</div>\n"
    }

    /* The picker. Rendered by the same endpoint when no project was named, so the
     * administrator never has to know a project key by heart. */
    static String picker(Report shell, List<Map<String, String>> projects, String selfPath) {
        StringBuilder out = new StringBuilder()
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>Project configuration</title>\n").append(style())
        out.append("</head>\n<body>\n<div class=\"page\">\n")
        out.append("<div class=\"page-header\"><div>")
        out.append("<h1 class=\"page-title\">Project configuration</h1>")
        out.append("<div class=\"page-subtitle\">Pick a project. The report expands every configuration ")
        out.append("item of that project and links each one to the screen where it is maintained.</div>")
        out.append("</div></div>\n")
        out.append(instanceCard(shell))
        out.append("<div class=\"export-card\"><form method=\"get\" action=\"")
        out.append(Pc.html(selfPath)).append("\">")
        out.append("<div class=\"export-grid\">")
        out.append("<label class=\"export-field\">Project<select name=\"project\" required>")
        out.append("<option value=\"\">Choose a project</option>")
        for (Map<String, String> project : projects) {
            out.append("<option value=\"").append(Pc.html(project.get("key"))).append("\">")
            out.append(Pc.html(project.get("name"))).append(" (").append(Pc.html(project.get("key"))).append(")")
            out.append("</option>")
        }
        out.append("</select></label>")
        out.append("<button class=\"button on\" type=\"submit\">OK</button>")
        out.append("</div></form>")
        out.append("<div class=\"export-note\">")
        out.append(String.valueOf(projects.size()))
        out.append(" projects. The report reads configuration only: no issue is counted and no search is run, ")
        out.append("so the run is harmless on a production instance.</div>")
        out.append("</div>\n")
        if (!shell.globalDiagnostics.isEmpty()) {
            out.append("<div class=\"diag diag-warn\"><strong>Some reads were suppressed.</strong><ul>")
            for (String entry : shell.globalDiagnostics) {
                out.append("<li>").append(Pc.html(entry)).append("</li>")
            }
            out.append("</ul></div>\n")
        }
        out.append("</div>\n</body>\n</html>\n")
        return out.toString()
    }

    private static String style() {
        return """<style>
:root {
    --page-bg: #f7f8f9;
    --surface: #ffffff;
    --surface-subtle: #f7f8f9;
    --text: #172b4d;
    --text-subtle: #626f86;
    --border: #dcdfe4;
    --border-subtle: #ebecf0;
    --blue: #0c66e4;
    --blue-soft: #e9f2ff;
    --green: #216e4e;
    --yellow: #7f5f01;
    --yellow-soft: #fff7d6;
    --yellow-border: #f5cd47;
    --red: #ae2e24;
    --red-soft: #ffeceb;
    --red-border: #ffd5d2;
    --purple-border: #b8acf6;
    --shadow: 0 1px 2px rgba(9, 30, 66, .08), 0 1px 3px rgba(9, 30, 66, .06);
}
* { box-sizing: border-box; }
body {
    margin: 0;
    background: var(--page-bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 14px;
    line-height: 1.45;
}
.page { max-width: 1580px; margin: 0 auto; padding: 28px 32px 40px; }
.mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size: 12px; }
.muted { color: var(--text-subtle); }
.hidden { display: none !important; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 20px; }
.page-title { margin: 0 0 4px; font-size: 22px; font-weight: 600; letter-spacing: -.01em; }
.page-subtitle { color: var(--text-subtle); font-size: 13px; max-width: 900px; }
.instance {
    background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 10px 14px; margin: 12px 0 20px; box-shadow: var(--shadow);
    display: flex; flex-wrap: wrap; gap: 6px 28px;
}
.instance div { font-size: 13px; }
.instance strong { font-weight: 600; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
.button {
    display: inline-block; padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border);
    background: var(--surface); color: var(--text); text-decoration: none; font-size: 13px; font-weight: 500;
    cursor: pointer;
}
.button:hover { background: var(--surface-subtle); }
.button.on { background: var(--blue-soft); border-color: var(--blue); color: var(--blue); }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 16px; }
.summary-card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 14px 16px; box-shadow: var(--shadow); }
.summary-value { font-size: 24px; font-weight: 600; letter-spacing: -.02em; }
.summary-label { color: var(--text-subtle); font-size: 12px; margin-top: 2px; }
.diag { border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; font-size: 13px;
    background: var(--blue-soft); border: 1px solid var(--purple-border); }
.diag-warn { background: var(--yellow-soft); border-color: var(--yellow-border); }
.diag ul { margin: 8px 0 0; padding-left: 20px; }
.diag li { margin: 2px 0; }
.export-card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 14px 18px; margin-bottom: 18px; box-shadow: var(--shadow); }
.export-grid { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; }
.export-field { display: flex; flex-direction: column; gap: 4px; color: var(--text-subtle);
    font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .035em; }
.export-field select { height: 34px; padding: 0 10px; border: 1px solid var(--border);
    border-radius: 4px; min-width: 340px; font-size: 13px; color: var(--text); background: var(--surface); }
.export-note { color: var(--text-subtle); font-size: 13px; max-width: 1080px; margin-top: 10px; }
.section { background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 14px 18px 16px; margin-bottom: 16px; box-shadow: var(--shadow); }
.section-head { display: flex; align-items: baseline; gap: 12px; }
.section-title { margin: 0; font-size: 16px; font-weight: 600; }
.section-value { color: var(--text-subtle); font-size: 13px; margin-top: 2px; }
.jump, .node-link { font-size: 12px; color: var(--blue); text-decoration: none; }
.jump:hover, .node-link:hover { text-decoration: underline; }
.linknote { color: var(--text-subtle); font-size: 12px; margin-top: 4px; font-style: italic; }
.empty { font-size: 13px; margin-top: 8px; }
ul.tree { list-style: none; margin: 8px 0 0; padding-left: 0; }
ul.tree ul.tree { margin: 0; padding-left: 20px; border-left: 1px solid var(--border-subtle); }
.node-line { display: flex; align-items: baseline; gap: 8px; padding: 2px 0; flex-wrap: wrap; }
.node-label { font-weight: 500; }
.node-value { color: var(--text-subtle); font-size: 13px; }
.node-id { color: var(--text-subtle); }
.node-nolink { font-size: 12px; color: var(--yellow); border-bottom: 1px dotted var(--yellow); cursor: help; }
.node-diag { font-size: 12px; color: var(--red); background: var(--red-soft);
    border: 1px solid var(--red-border); border-radius: 4px; padding: 2px 8px; margin: 2px 0 2px 22px; }
.state { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .03em; }
.state-unreadable { color: var(--red); }
.state-absent { color: var(--text-subtle); }
.state-truncated { color: var(--yellow); }
.twisty { border: 0; background: transparent; cursor: pointer; color: var(--text-subtle);
    font-size: 12px; padding: 0; width: 14px; }
.twisty-spacer { display: inline-block; width: 14px; }
.footer { font-size: 12px; margin-top: 24px; }
</style>
"""
    }

    /* The only script on the page. It collapses and expands, nothing else: no
     * fetch, no external resource, no state that outlives the tab. */
    private static String script() {
        return """<script>
document.addEventListener('click', function (event) {
    var button = event.target.closest('.twisty');
    if (!button) { return; }
    var item = button.closest('li.node');
    if (!item) { return; }
    var list = item.querySelector(':scope > ul.tree');
    if (!list) { return; }
    var open = list.classList.toggle('hidden') === false;
    button.setAttribute('aria-expanded', open ? 'true' : 'false');
    button.innerHTML = open ? '&#9662;' : '&#9656;';
});
</script>
"""
    }
}

/* =============================================================================
 * END OF THE JIRA-FREE BLOCK
 *
 * Everything above this line is free of Jira types on purpose: CI cuts exactly
 * that block out of this file and compiles it together with the test suite, so
 * the suite always exercises the shipped source instead of a copy that can drift.
 * Everything below touches Jira and can only be verified on an instance.
 * ========================================================================== */

/* =============================================================================
 * The scan
 *
 * One method per section of the report. Every method returns a node, never
 * throws, and never turns a failed read into an empty result: the reason is
 * attached to the node it belongs to, which is what lets the reader tell
 * "nothing is configured here" from "this could not be read".
 * ========================================================================== */

class Scan {

    Project project
    Dl links
    I18nHelper i18n

    Scan(Project project, Dl links, I18nHelper i18n) {
        this.project = project
        this.links = links
        this.i18n = i18n
    }

    /* The single place a read is allowed to fail. A caller that writes its own
     * try/catch will eventually write one that swallows, and a swallowed read is
     * how a report starts claiming an absence it never measured. */
    private static Nd guard(Nd node, Closure body) {
        try {
            body.call(node)
        } catch (Exception error) {
            node.failed(describe(error))
        }
        return node
    }

    private static String describe(Throwable error) {
        String message = Pc.text(error.getMessage())
        return "Read failed: " + error.getClass().getSimpleName() +
            (message == null ? "" : " - " + message)
    }

    private static String constantName(Object constant) {
        if (constant == null) {
            return Pc.NA
        }
        Object name = Pc.duck(constant, "getName", null)
        return name == null ? constant.toString() : name.toString()
    }

    private static String userLabel(ApplicationUser user) {
        if (user == null) {
            return null
        }
        String display = Pc.text(user.getDisplayName())
        String name = Pc.text(user.getName())
        if (display == null) {
            return name
        }
        return name == null ? display : display + " (" + name + ")"
    }

    /* ---- 1. issue type scheme -------------------------------------------- */

    Nd issueTypeScheme() {
        Nd node = Nd.of("issueTypeScheme", "Issue type scheme")
        /* The one link shape in this report that could not be evidenced. Rather
         * than inventing a parameter for ManageIssueTypeSchemes, the node stays
         * unlinked and says so, and its issue types link to the project page that
         * does have an evidenced address. */
        node.link(null, links.issueTypeSchemeUnavailableNote())
        return guard(node) { Nd self ->
            IssueTypeSchemeManager manager = ComponentAccessor.getComponent(IssueTypeSchemeManager)
            if (manager == null) {
                self.failed("IssueTypeSchemeManager is not available in this instance.")
                return
            }
            FieldConfigScheme scheme = manager.getConfigScheme(project)
            if (scheme == null) {
                self.absent("No issue type scheme is associated with this project.")
                return
            }
            self.label = "Issue type scheme: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            IssueType defaultType = null
            try {
                defaultType = manager.getDefaultValue(project.getGenericValue())
            } catch (Exception ignored) {
                /* The default issue type is a convenience, not the scheme. If the
                 * accessor is not available on this line the scheme still reports
                 * in full, and the reader is told which single fact is missing. */
            }
            Nd defaultNode = Nd.of("issueTypeDefault", "Default issue type")
            if (defaultType == null) {
                defaultNode.absent("No default issue type, or it could not be read")
            } else {
                defaultNode.val(defaultType.getName()).ident(defaultType.getId())
                defaultNode.link(links.projectIssueType(project.getKey(), defaultType.getId()), null)
            }
            self.add(defaultNode)

            Collection<IssueType> types = manager.getIssueTypesForProject(project)
            if (types == null || types.isEmpty()) {
                self.add(Nd.of("issueType", "Issue types").absent("The scheme contains no issue type."))
                return
            }
            for (IssueType type : types) {
                Nd typeNode = Nd.of("issueType", type.getName())
                typeNode.ident(type.getId())
                typeNode.val(type.isSubTask() ? "sub-task" : "standard")
                typeNode.link(links.projectIssueType(project.getKey(), type.getId()), null)
                if (Pc.text(type.getDescription()) != null) {
                    typeNode.add(Nd.of("issueTypeDescription", "Description").val(type.getDescription()))
                }
                self.add(typeNode)
            }
        }
    }

    /* ---- 2. issue type screen scheme, down to the field ------------------- */

    Nd issueTypeScreenScheme() {
        Nd node = Nd.of("issueTypeScreenScheme", "Issue type screen scheme")
        node.link(links.projectScreens(project.getKey()), null)
        return guard(node) { Nd self ->
            IssueTypeScreenSchemeManager manager =
                ComponentAccessor.getComponent(IssueTypeScreenSchemeManager)
            if (manager == null) {
                self.failed("IssueTypeScreenSchemeManager is not available in this instance.")
                return
            }
            IssueTypeScreenScheme scheme = manager.getIssueTypeScreenScheme(project)
            if (scheme == null) {
                self.absent("No issue type screen scheme is associated with this project.")
                return
            }
            self.label = "Issue type screen scheme: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            self.link(links.issueTypeScreenScheme(scheme.getId()), null)
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            Collection<IssueTypeScreenSchemeEntity> entities = scheme.getEntities()
            if (entities == null || entities.isEmpty()) {
                self.add(Nd.of("issueTypeScreenSchemeEntry", "Entries")
                    .absent("The scheme has no entry, not even a default."))
                return
            }

            /* The entry whose issue type id is null is the default entry, the one
             * that catches every issue type without an explicit row. Sorting it to
             * the front is how the report reads the way the administration screen
             * reads. */
            List<IssueTypeScreenSchemeEntity> ordered = new ArrayList<IssueTypeScreenSchemeEntity>(entities)
            ordered.sort { IssueTypeScreenSchemeEntity left, IssueTypeScreenSchemeEntity right ->
                String a = left.getIssueTypeId()
                String b = right.getIssueTypeId()
                if (a == null && b == null) { return 0 }
                if (a == null) { return -1 }
                if (b == null) { return 1 }
                return a.compareTo(b)
            }

            for (IssueTypeScreenSchemeEntity entry : ordered) {
                String label = "Default (every other issue type)"
                Object entryId = null
                try {
                    IssueType type = entry.getIssueTypeObject()
                    if (type != null) {
                        label = "Issue type: " + type.getName()
                        entryId = type.getId()
                    }
                } catch (Exception error) {
                    label = "Issue type " + Pc.orNa(entry.getIssueTypeId())
                }
                Nd entryNode = Nd.of("issueTypeScreenSchemeEntry", label)
                if (entryId != null) {
                    entryNode.link(links.projectIssueType(project.getKey(), entryId), null)
                }
                guard(entryNode) { Nd inner ->
                    FieldScreenScheme screenScheme = entry.getFieldScreenScheme()
                    if (screenScheme == null) {
                        inner.absent("The entry references no screen scheme.")
                        return
                    }
                    inner.add(screenSchemeNode(screenScheme))
                }
                self.add(entryNode)
            }
        }
    }

    Nd screenSchemeNode(FieldScreenScheme screenScheme) {
        Nd node = Nd.of("screenScheme", "Screen scheme: " + Pc.orNa(screenScheme.getName()))
        node.ident(screenScheme.getId())
        node.link(links.screenScheme(screenScheme.getId()), null)
        return guard(node) { Nd self ->
            if (Pc.text(screenScheme.getDescription()) != null) {
                self.val(screenScheme.getDescription())
            }
            Collection<FieldScreenSchemeItem> items = screenScheme.getFieldScreenSchemeItems()
            if (items == null || items.isEmpty()) {
                self.add(Nd.of("screenSchemeItem", "Operations")
                    .absent("The screen scheme maps no operation."))
                return
            }
            List<FieldScreenSchemeItem> ordered = new ArrayList<FieldScreenSchemeItem>(items)
            ordered.sort { FieldScreenSchemeItem left, FieldScreenSchemeItem right ->
                return operationRank(left) <=> operationRank(right)
            }
            for (FieldScreenSchemeItem item : ordered) {
                Nd operationNode = Nd.of("screenSchemeItem", "Operation: " + operationLabel(item))
                guard(operationNode) { Nd inner ->
                    FieldScreen screen = item.getFieldScreen()
                    if (screen == null) {
                        inner.absent("The operation maps to no screen.")
                        return
                    }
                    inner.add(screenNode(screen))
                }
                self.add(operationNode)
            }
        }
    }

    /* The three screenable operations have stable ids on IssueOperations, which is
     * what this compares against. The default entry, whose operation is null, sorts
     * first because that is where the administration screen puts it. */
    private static int operationRank(FieldScreenSchemeItem item) {
        ScreenableIssueOperation operation = null
        try {
            operation = item.getIssueOperation()
        } catch (Exception ignored) {
            return 9
        }
        if (operation == null) {
            return 0
        }
        Long id = operation.getId()
        if (id == null) {
            return 8
        }
        if (id == IssueOperations.CREATE_ISSUE_OPERATION.getId()) { return 1 }
        if (id == IssueOperations.EDIT_ISSUE_OPERATION.getId()) { return 2 }
        if (id == IssueOperations.VIEW_ISSUE_OPERATION.getId()) { return 3 }
        return 7
    }

    private static String operationLabel(FieldScreenSchemeItem item) {
        ScreenableIssueOperation operation = null
        try {
            operation = item.getIssueOperation()
        } catch (Exception ignored) {
            return "unknown"
        }
        if (operation == null) {
            return "Default (every other operation)"
        }
        Long id = operation.getId()
        if (id != null) {
            if (id == IssueOperations.CREATE_ISSUE_OPERATION.getId()) { return "Create" }
            if (id == IssueOperations.EDIT_ISSUE_OPERATION.getId()) { return "Edit" }
            if (id == IssueOperations.VIEW_ISSUE_OPERATION.getId()) { return "View" }
        }
        /* An operation this file does not know is named by whatever Jira calls it
         * rather than folded into one of the three above. */
        String name = null
        try {
            name = Pc.text(item.getIssueOperationName())
        } catch (Exception ignored) {
            name = null
        }
        return name == null ? ("operation " + String.valueOf(id)) : name
    }

    Nd screenNode(FieldScreen screen) {
        Nd node = Nd.of("screen", "Screen: " + Pc.orNa(screen.getName()))
        node.ident(screen.getId())
        node.link(links.screen(screen.getId()), null)
        return guard(node) { Nd self ->
            if (Pc.text(screen.getDescription()) != null) {
                self.val(screen.getDescription())
            }
            List<FieldScreenTab> tabs = screen.getTabs()
            if (tabs == null || tabs.isEmpty()) {
                self.add(Nd.of("screenTab", "Tabs").absent("The screen has no tab."))
                return
            }
            for (FieldScreenTab tab : tabs) {
                Nd tabNode = Nd.of("screenTab", "Tab: " + Pc.orNa(tab.getName()))
                tabNode.ident(tab.getId())
                guard(tabNode) { Nd inner ->
                    List<FieldScreenLayoutItem> layoutItems = tab.getFieldScreenLayoutItems()
                    if (layoutItems == null || layoutItems.isEmpty()) {
                        inner.absent("The tab carries no field.")
                        return
                    }
                    for (FieldScreenLayoutItem layoutItem : layoutItems) {
                        Nd fieldNode = Nd.of("screenField", fieldLabel(layoutItem))
                        fieldNode.val(layoutItem.getFieldId())
                        inner.add(fieldNode)
                    }
                }
                self.add(tabNode)
            }
        }
    }

    private static String fieldLabel(FieldScreenLayoutItem layoutItem) {
        try {
            Object field = layoutItem.getOrderableField()
            Object name = Pc.duck(field, "getName", null)
            if (name != null) {
                return name.toString()
            }
        } catch (Exception ignored) {
            /* A field whose module is gone still has an id, and the id is the thing
             * an administrator needs in order to find what left it behind. */
        }
        return Pc.orNa(layoutItem.getFieldId())
    }

    /* ---- 3. field configuration scheme ------------------------------------ */

    Nd fieldConfigurationScheme() {
        Nd node = Nd.of("fieldConfigurationScheme", "Field configuration scheme")
        node.link(links.projectFields(project.getKey()), null)
        return guard(node) { Nd self ->
            FieldLayoutManager manager = ComponentAccessor.getComponent(FieldLayoutManager)
            if (manager == null) {
                self.failed("FieldLayoutManager is not available in this instance.")
                return
            }
            FieldConfigurationScheme scheme = manager.getFieldConfigurationScheme(project)
            if (scheme == null) {
                self.label = "Field configuration scheme: System Default Field Configuration"
                self.val("No scheme is associated, so every issue type uses the system default.")
            } else {
                self.label = "Field configuration scheme: " + Pc.orNa(scheme.getName())
                self.ident(scheme.getId())
                self.link(links.fieldConfigurationScheme(scheme.getId()), null)
                if (Pc.text(scheme.getDescription()) != null) {
                    self.val(scheme.getDescription())
                }
            }

            Collection<IssueType> types = projectIssueTypes()
            if (types.isEmpty()) {
                self.add(Nd.of("fieldConfiguration", "Field configurations")
                    .failed("The issue types of this project could not be read, " +
                        "so no field configuration could be resolved."))
                return
            }

            /* Several issue types usually share one field configuration. Printing
             * the configuration once per issue type would repeat hundreds of field
             * rows and hide the actual grouping, so the issue types are collected
             * under the configuration they resolve to. */
            Map<String, List<IssueType>> byLayout = new LinkedHashMap<String, List<IssueType>>()
            Map<String, FieldLayout> layouts = new LinkedHashMap<String, FieldLayout>()
            for (IssueType type : types) {
                try {
                    FieldLayout layout = manager.getFieldLayout(project, type.getId())
                    String key = layout == null ? "none" : String.valueOf(layout.getId())
                    if (!byLayout.containsKey(key)) {
                        byLayout.put(key, new ArrayList<IssueType>())
                        layouts.put(key, layout)
                    }
                    byLayout.get(key).add(type)
                } catch (Exception error) {
                    self.diagnostics.add("The field configuration for issue type " +
                        Pc.orNa(type.getName()) + " could not be resolved: " + describe(error))
                }
            }

            for (Map.Entry<String, List<IssueType>> entry : byLayout.entrySet()) {
                FieldLayout layout = layouts.get(entry.getKey())
                List<String> names = new ArrayList<String>()
                for (IssueType type : entry.getValue()) {
                    names.add(type.getName())
                }
                Nd layoutNode = fieldConfigurationNode(layout)
                layoutNode.add(Nd.of("appliesTo", "Applies to issue types").val(names.join(", ")))
                self.add(layoutNode)
            }
        }
    }

    Nd fieldConfigurationNode(FieldLayout layout) {
        if (layout == null) {
            return Nd.of("fieldConfiguration", "Field configuration")
                .absent("No field configuration resolved for these issue types.")
        }
        String name = Pc.text(layout.getName())
        Nd node = Nd.of("fieldConfiguration",
            "Field configuration: " + (name == null ? "System Default Field Configuration" : name))
        node.ident(layout.getId())
        node.link(links.fieldConfiguration(layout.getId()),
            layout.getId() == null
                ? "Administration > Issues > Field configurations. The system default has no id to link to."
                : null)
        return guard(node) { Nd self ->
            if (Pc.text(layout.getDescription()) != null) {
                self.val(layout.getDescription())
            }
            List<FieldLayoutItem> items = layout.getFieldLayoutItems()
            if (items == null || items.isEmpty()) {
                self.add(Nd.of("fieldBehaviour", "Fields").absent("The configuration lists no field."))
                return
            }
            List<FieldLayoutItem> ordered = new ArrayList<FieldLayoutItem>(items)
            ordered.sort { FieldLayoutItem left, FieldLayoutItem right ->
                String a = fieldLayoutItemName(left)
                String b = fieldLayoutItemName(right)
                return a.compareToIgnoreCase(b)
            }
            for (FieldLayoutItem item : ordered) {
                Nd fieldNode = Nd.of("fieldBehaviour", fieldLayoutItemName(item))
                guard(fieldNode) { Nd inner ->
                    List<String> parts = new ArrayList<String>()
                    parts.add(item.isRequired() ? "required" : "optional")
                    parts.add(item.isHidden() ? "hidden" : "visible")
                    String renderer = Pc.text(item.getRendererType())
                    if (renderer != null) {
                        parts.add("renderer " + renderer)
                    }
                    inner.val(parts.join(", "))
                    String description = Pc.text(item.getRawFieldDescription())
                    if (description != null) {
                        inner.add(Nd.of("fieldDescription", "Description").val(description))
                    }
                }
                self.add(fieldNode)
            }
        }
    }

    private static String fieldLayoutItemName(FieldLayoutItem item) {
        Object field = null
        try {
            field = item.getOrderableField()
        } catch (Exception ignored) {
            return "unknown field"
        }
        Object name = Pc.duck(field, "getName", null)
        if (name != null) {
            return name.toString()
        }
        Object id = Pc.duck(field, "getId", null)
        return id == null ? "unknown field" : id.toString()
    }

    /* ---- 4. custom field contexts that apply to this project -------------- */

    Nd customFields() {
        Nd node = Nd.of("customFields", "Custom fields in this project")
        node.link(links.projectFields(project.getKey()), null)
        return guard(node) { Nd self ->
            CustomFieldManager manager = ComponentAccessor.getCustomFieldManager()
            OptionsManager optionsManager = ComponentAccessor.getComponent(OptionsManager)
            if (manager == null) {
                self.failed("CustomFieldManager is not available in this instance.")
                return
            }
            List<String> issueTypeIds = new ArrayList<String>()
            for (IssueType type : projectIssueTypes()) {
                issueTypeIds.add(type.getId())
            }
            List<CustomField> fields = manager.getCustomFieldObjects(project.getId(), issueTypeIds)
            if (fields == null || fields.isEmpty()) {
                self.absent("No custom field is in scope for this project.")
                return
            }
            self.val(String.valueOf(fields.size()) + " custom fields are in scope for this project")
            List<CustomField> ordered = new ArrayList<CustomField>(fields)
            ordered.sort { CustomField left, CustomField right ->
                String a = left.getName() == null ? "" : left.getName()
                String b = right.getName() == null ? "" : right.getName()
                return a.compareToIgnoreCase(b)
            }
            for (CustomField field : ordered) {
                self.add(customFieldNode(field, optionsManager))
            }
        }
    }

    Nd customFieldNode(CustomField field, OptionsManager optionsManager) {
        Nd node = Nd.of("customField", Pc.orNa(field.getName()))
        node.ident(field.getId())
        node.link(links.customField(field.getIdAsLong()), null)
        return guard(node) { Nd self ->
            Object type = field.getCustomFieldType()
            String typeName = Pc.text(Pc.duck(type, "getName", null))
            self.val(typeName == null ? field.getId() : typeName)

            Object searcher = field.getCustomFieldSearcher()
            String searcherName = Pc.text(Pc.duck(searcher, "getDescriptor", null) == null
                ? null : Pc.duck(Pc.duck(searcher, "getDescriptor", null), "getName", null))
            self.add(Nd.of("customFieldSearcher", "Searcher")
                .val(searcherName == null ? "not set or not readable" : searcherName))

            if (Pc.text(field.getDescription()) != null) {
                self.add(Nd.of("customFieldDescription", "Description").val(field.getDescription()))
            }

            List<FieldConfigScheme> schemes = field.getConfigurationSchemes()
            if (schemes == null || schemes.isEmpty()) {
                self.add(Nd.of("customFieldContext", "Contexts")
                    .absent("The field has no context, so it applies nowhere."))
                return
            }
            int shown = 0
            for (FieldConfigScheme scheme : schemes) {
                if (!appliesToProject(scheme)) {
                    continue
                }
                shown++
                self.add(customFieldContextNode(field, scheme, optionsManager))
            }
            if (shown == 0) {
                /* The field is in scope for the project but none of its contexts
                 * claims the project by id. That is a real state worth naming
                 * rather than an empty list. */
                self.add(Nd.of("customFieldContext", "Contexts")
                    .absent("No context of this field names this project, although the field " +
                        "is reported as in scope. Check the context configuration."))
            }
        }
    }

    private boolean appliesToProject(FieldConfigScheme scheme) {
        try {
            if (scheme.isGlobal() || scheme.isAllProjects()) {
                return true
            }
            List<Long> ids = scheme.getAssociatedProjectIds()
            return ids != null && ids.contains(project.getId())
        } catch (Exception ignored) {
            /* When the association cannot be read, the context is shown rather than
             * hidden: an unreadable association must not silently remove a context
             * from the report. */
            return true
        }
    }

    Nd customFieldContextNode(CustomField field, FieldConfigScheme scheme, OptionsManager optionsManager) {
        Nd node = Nd.of("customFieldContext", "Context: " + Pc.orNa(scheme.getName()))
        node.ident(scheme.getId())
        node.link(links.customFieldContext(field.getIdAsLong(), scheme.getId()), null)
        return guard(node) { Nd self ->
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            Nd scopeNode = Nd.of("contextScope", "Applies to projects")
            if (scheme.isGlobal() || scheme.isAllProjects()) {
                scopeNode.val("every project")
            } else {
                List<Project> projects = scheme.getAssociatedProjectObjects()
                List<String> keys = new ArrayList<String>()
                if (projects != null) {
                    for (Project associated : projects) {
                        keys.add(associated.getKey())
                    }
                }
                scopeNode.val(keys.isEmpty() ? "no project" : keys.join(", "))
            }
            self.add(scopeNode)

            Nd typeNode = Nd.of("contextIssueTypes", "Applies to issue types")
            if (scheme.isAllIssueTypes()) {
                typeNode.val("every issue type")
            } else {
                Collection<IssueType> types = scheme.getAssociatedIssueTypes()
                List<String> names = new ArrayList<String>()
                if (types != null) {
                    for (IssueType type : types) {
                        if (type != null) {
                            names.add(type.getName())
                        }
                    }
                }
                typeNode.val(names.isEmpty() ? "no issue type" : names.join(", "))
            }
            self.add(typeNode)

            Map<String, FieldConfig> configs = scheme.getConfigs()
            if (configs == null || configs.isEmpty()) {
                self.add(Nd.of("contextConfig", "Configuration")
                    .absent("The context carries no configuration."))
                return
            }
            Set<Long> seen = new LinkedHashSet<Long>()
            for (Map.Entry<String, FieldConfig> entry : configs.entrySet()) {
                FieldConfig config = entry.getValue()
                if (config == null || !seen.add(config.getId())) {
                    continue
                }
                self.add(fieldConfigNode(config, optionsManager))
            }
        }
    }

    Nd fieldConfigNode(FieldConfig config, OptionsManager optionsManager) {
        Nd node = Nd.of("contextConfig", "Configuration: " + Pc.orNa(config.getName()))
        node.ident(config.getId())
        return guard(node) { Nd self ->
            if (Pc.text(config.getDescription()) != null) {
                self.val(config.getDescription())
            }
            if (optionsManager == null) {
                self.add(Nd.of("contextOptions", "Options")
                    .failed("OptionsManager is not available, so options could not be read."))
                return
            }
            Object options = null
            try {
                options = optionsManager.getOptions(config)
            } catch (Exception error) {
                self.add(Nd.of("contextOptions", "Options").failed(describe(error)))
                return
            }
            if (options == null || ((Collection) options).isEmpty()) {
                /* A field type without options is the normal case, not a defect.
                 * Saying so beats an empty node the reader has to interpret. */
                self.add(Nd.of("contextOptions", "Options")
                    .absent("This field type carries no option list."))
                return
            }
            Nd optionsNode = Nd.of("contextOptions", "Options")
            optionsNode.val(String.valueOf(((Collection) options).size()) + " options")
            for (Object raw : (Collection) options) {
                optionsNode.add(optionNode((Option) raw))
            }
            self.add(optionsNode)
        }
    }

    private Nd optionNode(Option option) {
        Nd node = Nd.of("contextOption", Pc.orNa(option.getValue()))
        node.ident(option.getOptionId())
        Boolean disabled = option.getDisabled()
        if (Boolean.TRUE.equals(disabled)) {
            node.val("disabled")
        }
        List<Option> children = option.getChildOptions()
        if (children != null) {
            for (Option child : children) {
                node.add(optionNode(child))
            }
        }
        return node
    }

    /* ---- 5. workflow scheme, every layer ---------------------------------- */

    Nd workflowScheme() {
        Nd node = Nd.of("workflowScheme", "Workflow scheme")
        node.link(links.projectWorkflows(project.getKey()), null)
        return guard(node) { Nd self ->
            WorkflowSchemeManager schemeManager = ComponentAccessor.getComponent(WorkflowSchemeManager)
            WorkflowManager workflowManager = ComponentAccessor.getComponent(WorkflowManager)
            if (schemeManager == null || workflowManager == null) {
                self.failed("The workflow managers are not available in this instance.")
                return
            }
            AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project)
            if (scheme == null) {
                self.absent("No workflow scheme is associated with this project.")
                return
            }
            self.label = "Workflow scheme: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            self.link(links.workflowScheme(scheme.getId()), null)
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            /* getMappings holds the explicit rows; the default row is separate and
             * is what every issue type without a row resolves to. Both are layers,
             * so both are reported, and the default is named as such rather than
             * being silently applied to a list of issue types. */
            Map<String, String> mappings = new LinkedHashMap<String, String>()
            try {
                Map<String, String> raw = scheme.getMappings()
                if (raw != null) {
                    mappings.putAll(raw)
                }
            } catch (Exception error) {
                self.diagnostics.add("The explicit workflow layers could not be read: " + describe(error))
            }

            String defaultWorkflow = null
            try {
                defaultWorkflow = scheme.getActualDefaultWorkflow()
            } catch (Exception error) {
                self.diagnostics.add("The default workflow could not be read: " + describe(error))
            }

            Map<String, String> issueTypeNames = new LinkedHashMap<String, String>()
            for (IssueType type : projectIssueTypes()) {
                issueTypeNames.put(type.getId(), type.getName())
            }

            Nd defaultLayer = Nd.of("workflowLayer", "Layer: default (every other issue type)")
            if (defaultWorkflow == null) {
                defaultLayer.absent("No default workflow, or it could not be read")
            } else {
                defaultLayer.add(workflowNode(workflowManager, defaultWorkflow))
            }
            self.add(defaultLayer)

            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                String issueTypeId = entry.getKey()
                String name = issueTypeNames.get(issueTypeId)
                Nd layer = Nd.of("workflowLayer",
                    "Layer: issue type " + (name == null ? String.valueOf(issueTypeId) : name))
                layer.ident(issueTypeId)
                if (name == null) {
                    /* A layer for an issue type the project does not carry is not a
                     * read failure and not nothing: it is a configuration leftover,
                     * and naming it is the point of the report. */
                    layer.diagnostics.add("This layer maps an issue type that is not in the " +
                        "issue type scheme of this project.")
                } else {
                    layer.link(links.projectIssueType(project.getKey(), issueTypeId), null)
                }
                layer.add(workflowNode(workflowManager, entry.getValue()))
                self.add(layer)
            }
        }
    }

    Nd workflowNode(WorkflowManager workflowManager, String workflowName) {
        Nd node = Nd.of("workflow", "Workflow: " + Pc.orNa(workflowName))
        node.link(links.workflow(workflowName, false), null)
        return guard(node) { Nd self ->
            JiraWorkflow workflow = workflowManager.getWorkflow(workflowName)
            if (workflow == null) {
                self.failed("The workflow scheme names a workflow that could not be loaded.")
                return
            }
            List<String> facts = new ArrayList<String>()
            try {
                facts.add(workflow.isActive() ? "active" : "inactive")
            } catch (Exception ignored) {
                facts.add("active state not readable")
            }
            try {
                facts.add(workflow.isSystemWorkflow() ? "system" : "custom")
            } catch (Exception ignored) {
                facts.add("system state not readable")
            }
            try {
                if (workflow.hasDraftWorkflow()) {
                    facts.add("has an unpublished draft")
                }
            } catch (Exception ignored) {
                /* Draft state is an extra, and its absence must not cost the
                 * whole workflow node. */
            }
            self.val(facts.join(", "))
            if (Pc.text(workflow.getDescription()) != null) {
                self.add(Nd.of("workflowDescription", "Description").val(workflow.getDescription()))
            }

            List<Status> statuses = workflow.getLinkedStatusObjects()
            if (statuses == null || statuses.isEmpty()) {
                self.add(Nd.of("workflowStatus", "Statuses").absent("The workflow has no linked status."))
                return
            }
            for (Status status : statuses) {
                self.add(statusNode(workflow, status))
            }

            /* Global and initial transitions belong to no single status, so they
             * would vanish if only the per-status lists were printed. */
            self.add(looseTransitions(workflow))
        }
    }

    private Nd statusNode(JiraWorkflow workflow, Status status) {
        Nd node = Nd.of("workflowStatus", "Status: " + Pc.orNa(status.getName()))
        node.ident(status.getId())
        return guard(node) { Nd self ->
            Object category = status.getStatusCategory()
            String categoryName = Pc.text(Pc.duck(category, "getName", null))
            self.val(categoryName == null ? "category not readable" : "category " + categoryName)

            StepDescriptor step = workflow.getLinkedStep(status)
            if (step == null) {
                self.failed("The status is linked to no workflow step.")
                return
            }
            List actions = step.getActions()
            if (actions == null || actions.isEmpty()) {
                self.add(Nd.of("workflowTransition", "Transitions")
                    .absent("No transition leaves this status."))
                return
            }
            for (Object raw : actions) {
                self.add(transitionNode(workflow, (ActionDescriptor) raw))
            }
        }
    }

    private Nd looseTransitions(JiraWorkflow workflow) {
        Nd node = Nd.of("workflowTransitionGroup", "Global and initial transitions")
        return guard(node) { Nd self ->
            List<ActionDescriptor> loose = new ArrayList<ActionDescriptor>()
            Object descriptor = workflow.getDescriptor()
            List globals = (List) Pc.duck(descriptor, "getGlobalActions", null)
            List initials = (List) Pc.duck(descriptor, "getInitialActions", null)
            if (globals != null) {
                for (Object raw : globals) {
                    loose.add((ActionDescriptor) raw)
                }
            }
            if (initials != null) {
                for (Object raw : initials) {
                    loose.add((ActionDescriptor) raw)
                }
            }
            if (loose.isEmpty()) {
                self.absent("The workflow has no global and no initial transition.")
                return
            }
            for (ActionDescriptor action : loose) {
                self.add(transitionNode(workflow, action))
            }
        }
    }

    private Nd transitionNode(JiraWorkflow workflow, ActionDescriptor action) {
        Nd node = Nd.of("workflowTransition", "Transition: " + Pc.orNa(action.getName()))
        node.ident(action.getId())
        return guard(node) { Nd self ->
            String target = targetStatusName(workflow, action)
            self.val(target == null ? "target not readable" : "to " + target)

            /* Counting is the honest summary here. Printing every argument of every
             * post function would bury the transition, and claiming there are none
             * when the list could not be read would be worse than either. */
            self.add(countNode("workflowCondition", "Conditions", conditionCount(action)))
            self.add(countNode("workflowValidator", "Validators", listSize(action.getValidators())))
            self.add(countNode("workflowFunction", "Post functions", postFunctionCount(action)))

            Map meta = action.getMetaAttributes()
            Object screenId = meta == null ? null : meta.get(JiraWorkflow.ACTION_SCREEN_ATTRIBUTE)
            Nd screenNode = Nd.of("workflowScreen", "Transition screen")
            if (screenId == null) {
                screenNode.absent("No screen, the transition happens without a form")
            } else {
                screenNode.val("screen " + screenId.toString()).ident(screenId)
                screenNode.link(links.screen(screenId), null)
            }
            self.add(screenNode)
        }
    }

    private static Nd countNode(String kind, String label, Integer count) {
        Nd node = Nd.of(kind, label)
        if (count == null) {
            return node.failed("The list could not be read, so this is not a count of zero.")
        }
        return node.val(String.valueOf(count))
    }

    private static Integer listSize(Object list) {
        if (list == null) {
            return Integer.valueOf(0)
        }
        try {
            return Integer.valueOf(((Collection) list).size())
        } catch (Exception ignored) {
            return null
        }
    }

    /* Conditions hang under a restriction, and the accessor for that nesting is
     * not identical across osworkflow builds. Asking the object rather than
     * declaring the shape keeps one file working on both. */
    private static Integer conditionCount(ActionDescriptor action) {
        try {
            Object restriction = action.getRestriction()
            if (restriction == null) {
                return Integer.valueOf(0)
            }
            Object conditions = Pc.duck(restriction, "getConditionsDescriptor", null)
            if (conditions == null) {
                return null
            }
            Object list = Pc.duck(conditions, "getConditions", null)
            return listSize(list)
        } catch (Exception ignored) {
            return null
        }
    }

    private static Integer postFunctionCount(ActionDescriptor action) {
        try {
            int total = 0
            Integer pre = listSize(action.getPreFunctions())
            Integer post = listSize(action.getPostFunctions())
            if (pre == null || post == null) {
                return null
            }
            total += pre.intValue() + post.intValue()
            ResultDescriptor result = action.getUnconditionalResult()
            if (result != null) {
                Integer resultPre = listSize(result.getPreFunctions())
                Integer resultPost = listSize(result.getPostFunctions())
                if (resultPre == null || resultPost == null) {
                    return null
                }
                total += resultPre.intValue() + resultPost.intValue()
            }
            return Integer.valueOf(total)
        } catch (Exception ignored) {
            return null
        }
    }

    private static String targetStatusName(JiraWorkflow workflow, ActionDescriptor action) {
        try {
            ResultDescriptor result = action.getUnconditionalResult()
            if (result == null) {
                return null
            }
            int stepId = result.getStep()
            Object descriptor = workflow.getDescriptor()
            Object step = Pc.duck(descriptor, "getStep", Integer.valueOf(stepId))
            if (step == null) {
                return null
            }
            Status status = workflow.getLinkedStatusObject((StepDescriptor) step)
            return status == null ? null : status.getName()
        } catch (Exception ignored) {
            return null
        }
    }

    /* ---- 6. permission scheme --------------------------------------------- */

    Nd permissionScheme() {
        Nd node = Nd.of("permissionScheme", "Permission scheme")
        node.link(links.projectPermissions(project.getKey()), null)
        return guard(node) { Nd self ->
            PermissionSchemeManager manager = ComponentAccessor.getComponent(PermissionSchemeManager)
            if (manager == null) {
                self.failed("PermissionSchemeManager is not available in this instance.")
                return
            }
            Scheme scheme = manager.getSchemeFor(project)
            if (scheme == null) {
                self.absent("No permission scheme is associated with this project.")
                return
            }
            self.label = "Permission scheme: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            self.link(links.permissionScheme(scheme.getId()), null)
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            Object typeManager = ComponentAccessor.getComponent(PermissionTypeManager)
            Collection<SchemeEntity> entities = scheme.getEntities()
            if (entities == null || entities.isEmpty()) {
                self.add(Nd.of("permission", "Permissions")
                    .absent("The scheme grants nothing to anybody."))
                return
            }

            /* Grouped by permission, because that is the question an administrator
             * arrives with: who can do X here. */
            Map<String, List<SchemeEntity>> byPermission = new TreeMap<String, List<SchemeEntity>>()
            for (SchemeEntity entity : entities) {
                String permission = String.valueOf(entity.getEntityTypeId())
                if (!byPermission.containsKey(permission)) {
                    byPermission.put(permission, new ArrayList<SchemeEntity>())
                }
                byPermission.get(permission).add(entity)
            }
            for (Map.Entry<String, List<SchemeEntity>> entry : byPermission.entrySet()) {
                Nd permissionNode = Nd.of("permission", entry.getKey())
                permissionNode.val(String.valueOf(entry.getValue().size()) + " grants")
                for (SchemeEntity entity : entry.getValue()) {
                    permissionNode.add(grantNode(entity, typeManager))
                }
                self.add(permissionNode)
            }
        }
    }

    /* ---- 7. notification scheme ------------------------------------------- */

    Nd notificationScheme() {
        Nd node = Nd.of("notificationScheme", "Notification scheme")
        node.link(links.projectNotifications(project.getKey()), null)
        return guard(node) { Nd self ->
            NotificationSchemeManager manager = ComponentAccessor.getComponent(NotificationSchemeManager)
            if (manager == null) {
                self.failed("NotificationSchemeManager is not available in this instance.")
                return
            }
            Scheme scheme = manager.getSchemeFor(project)
            if (scheme == null) {
                self.absent("No notification scheme is associated with this project, " +
                    "so this project sends no notification.")
                return
            }
            self.label = "Notification scheme: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            self.link(links.notificationScheme(scheme.getId()), null)
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            Object typeManager = ComponentAccessor.getComponent(NotificationTypeManager)
            EventTypeManager eventTypeManager = ComponentAccessor.getComponent(EventTypeManager)
            Collection<SchemeEntity> entities = scheme.getEntities()
            if (entities == null || entities.isEmpty()) {
                self.add(Nd.of("event", "Events").absent("The scheme notifies nobody about anything."))
                return
            }
            Map<String, List<SchemeEntity>> byEvent = new LinkedHashMap<String, List<SchemeEntity>>()
            for (SchemeEntity entity : entities) {
                String event = eventLabel(eventTypeManager, entity.getEntityTypeId())
                if (!byEvent.containsKey(event)) {
                    byEvent.put(event, new ArrayList<SchemeEntity>())
                }
                byEvent.get(event).add(entity)
            }
            for (Map.Entry<String, List<SchemeEntity>> entry : byEvent.entrySet()) {
                Nd eventNode = Nd.of("event", entry.getKey())
                eventNode.val(String.valueOf(entry.getValue().size()) + " recipients")
                for (SchemeEntity entity : entry.getValue()) {
                    eventNode.add(grantNode(entity, typeManager))
                }
                self.add(eventNode)
            }
        }
    }

    private static String eventLabel(EventTypeManager manager, Object entityTypeId) {
        if (entityTypeId == null) {
            return "unknown event"
        }
        if (manager == null) {
            return "event " + entityTypeId.toString()
        }
        try {
            EventType type = manager.getEventType(Long.valueOf(entityTypeId.toString()))
            return type == null ? ("event " + entityTypeId.toString()) : type.getName()
        } catch (Exception ignored) {
            return "event " + entityTypeId.toString()
        }
    }

    /* ---- 8. issue security scheme ----------------------------------------- */

    Nd issueSecurityScheme() {
        Nd node = Nd.of("issueSecurityScheme", "Issue security scheme")
        node.link(links.projectIssueSecurity(project.getKey()), null)
        return guard(node) { Nd self ->
            IssueSecuritySchemeManager manager = ComponentAccessor.getComponent(IssueSecuritySchemeManager)
            if (manager == null) {
                self.failed("IssueSecuritySchemeManager is not available in this instance.")
                return
            }
            Scheme scheme = manager.getSchemeFor(project)
            if (scheme == null) {
                self.absent("No issue security scheme is associated, " +
                    "so no issue in this project carries a security level.")
                return
            }
            self.label = "Issue security scheme: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            self.link(links.issueSecurityScheme(scheme.getId()), null)
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            Object typeManager = ComponentAccessor.getComponent(IssueSecurityTypeManager)
            IssueSecurityLevelManager levelManager =
                ComponentAccessor.getComponent(IssueSecurityLevelManager)

            Map<String, List<SchemeEntity>> byLevel = new LinkedHashMap<String, List<SchemeEntity>>()
            Collection<SchemeEntity> entities = scheme.getEntities()
            if (entities != null) {
                for (SchemeEntity entity : entities) {
                    String level = String.valueOf(entity.getEntityTypeId())
                    if (!byLevel.containsKey(level)) {
                        byLevel.put(level, new ArrayList<SchemeEntity>())
                    }
                    byLevel.get(level).add(entity)
                }
            }

            List<IssueSecurityLevel> levels = null
            if (levelManager != null) {
                try {
                    levels = levelManager.getIssueSecurityLevels(scheme.getId().longValue())
                } catch (Exception error) {
                    self.diagnostics.add("The security levels could not be read: " + describe(error))
                }
            }
            if (levels == null || levels.isEmpty()) {
                self.add(Nd.of("issueSecurityLevel", "Levels")
                    .absent("The scheme defines no level, or the levels could not be read."))
                return
            }
            for (IssueSecurityLevel level : levels) {
                Nd levelNode = Nd.of("issueSecurityLevel", "Level: " + Pc.orNa(level.getName()))
                levelNode.ident(level.getId())
                levelNode.link(links.issueSecurityLevel(scheme.getId(), level.getId()), null)
                if (Pc.text(level.getDescription()) != null) {
                    levelNode.val(level.getDescription())
                }
                List<SchemeEntity> members = byLevel.get(String.valueOf(level.getId()))
                if (members == null || members.isEmpty()) {
                    levelNode.add(Nd.of("issueSecurityMember", "Members")
                        .absent("Nobody is granted this level."))
                } else {
                    for (SchemeEntity entity : members) {
                        levelNode.add(grantNode(entity, typeManager))
                    }
                }
                self.add(levelNode)
            }
        }
    }

    /* ---- the grant, resolved through Jira's own type registry -------------- */

    /* No table of type strings lives in this file. The scheme type registry is
     * asked for the display name of the type and for the display form of the
     * parameter, which is exactly what the administration screen shows. When the
     * registry cannot answer, the raw type and parameter are printed rather than
     * being dropped or guessed at. */
    private Nd grantNode(SchemeEntity entity, Object typeManager) {
        String rawType = Pc.orNa(entity.getType())
        String parameter = Pc.text(entity.getParameter())

        Object schemeType = null
        if (typeManager != null) {
            schemeType = Pc.duck(typeManager, "getSchemeType", entity.getType())
            if (schemeType == null) {
                schemeType = Pc.duck(typeManager, "getNotificationType", entity.getType())
            }
        }

        String typeLabel = Pc.text(Pc.duck(schemeType, "getDisplayName", null))
        Nd node = Nd.of("grant", typeLabel == null ? rawType : typeLabel)
        node.ident(entity.getId())

        if (parameter == null) {
            return node
        }
        String rendered = Pc.text(Pc.duck(schemeType, "getArgumentDisplay", parameter))
        if (rendered == null) {
            rendered = resolveParameter(entity.getType(), parameter)
        }
        node.val(rendered == null ? parameter : rendered)
        if (typeLabel == null) {
            node.diagnostics.add("The scheme type registry did not know the type '" + rawType +
                "', so the raw type and parameter are shown.")
        }
        return node
    }

    /* The three parameter kinds this report can resolve on its own when the
     * registry declines. Anything else keeps its raw value: an unresolved id is
     * still true, an invented name would not be. */
    private String resolveParameter(String type, String parameter) {
        if (type == null) {
            return null
        }
        try {
            if (type.toLowerCase(Locale.ROOT).contains("role")) {
                ProjectRoleManager roleManager = ComponentAccessor.getComponent(ProjectRoleManager)
                if (roleManager != null) {
                    ProjectRole role = roleManager.getProjectRole(Long.valueOf(parameter))
                    if (role != null) {
                        return "project role " + role.getName()
                    }
                }
            }
            if (type.toLowerCase(Locale.ROOT).contains("user")) {
                ApplicationUser user = ComponentAccessor.getUserManager().getUserByKey(parameter)
                if (user == null) {
                    user = ComponentAccessor.getUserManager().getUserByName(parameter)
                }
                if (user != null) {
                    return userLabel(user)
                }
            }
        } catch (Exception ignored) {
            /* Falling through to the raw parameter is the correct outcome here. */
        }
        return null
    }

    /* ---- 9. project roles -------------------------------------------------- */

    Nd projectRoles() {
        Nd node = Nd.of("projectRoles", "Project roles")
        node.link(links.projectRoles(project.getKey()), null)
        return guard(node) { Nd self ->
            ProjectRoleManager manager = ComponentAccessor.getComponent(ProjectRoleManager)
            if (manager == null) {
                self.failed("ProjectRoleManager is not available in this instance.")
                return
            }
            Collection<ProjectRole> roles = manager.getProjectRoles()
            if (roles == null || roles.isEmpty()) {
                self.absent("This instance defines no project role.")
                return
            }
            for (ProjectRole role : roles) {
                Nd roleNode = Nd.of("projectRole", Pc.orNa(role.getName()))
                roleNode.ident(role.getId())
                if (Pc.text(role.getDescription()) != null) {
                    roleNode.val(role.getDescription())
                }
                guard(roleNode) { Nd inner ->
                    ProjectRoleActors actors = manager.getProjectRoleActors(role, project)
                    Set<RoleActor> roleActors = actors == null ? null : actors.getRoleActors()
                    if (roleActors == null || roleActors.isEmpty()) {
                        inner.absent("Nobody holds this role in this project.")
                        return
                    }
                    for (RoleActor actor : roleActors) {
                        Nd actorNode = Nd.of("roleActor", Pc.orNa(actor.getDescriptor()))
                        actorNode.val(Pc.orNa(actor.getType()))
                        actorNode.ident(actor.getId())
                        if (!actor.isActive()) {
                            actorNode.diagnostics.add("This actor is marked inactive.")
                        }
                        inner.add(actorNode)
                    }
                }
                self.add(roleNode)
            }
        }
    }

    /* ---- 10. versions and components --------------------------------------- */

    Nd versions(boolean includeInactive) {
        Nd node = Nd.of("versions", "Versions")
        node.link(links.projectVersions(project.getKey()), null)
        return guard(node) { Nd self ->
            VersionManager manager = ComponentAccessor.getComponent(VersionManager)
            if (manager == null) {
                self.failed("VersionManager is not available in this instance.")
                return
            }
            Collection<Version> versions = manager.getVersions(project.getId())
            if (versions == null || versions.isEmpty()) {
                self.absent("This project has no version.")
                return
            }
            int hidden = 0
            for (Version version : versions) {
                if (!includeInactive && (version.isArchived() || version.isReleased())) {
                    hidden++
                    continue
                }
                Nd versionNode = Nd.of("version", Pc.orNa(version.getName()))
                versionNode.ident(version.getId())
                List<String> facts = new ArrayList<String>()
                facts.add(version.isReleased() ? "released" : "unreleased")
                if (version.isArchived()) {
                    facts.add("archived")
                }
                if (version.getStartDate() != null) {
                    facts.add("starts " + Pc.dateText(version.getStartDate()))
                }
                if (version.getReleaseDate() != null) {
                    facts.add("due " + Pc.dateText(version.getReleaseDate()))
                }
                versionNode.val(facts.join(", "))
                if (Pc.text(version.getDescription()) != null) {
                    versionNode.add(Nd.of("versionDescription", "Description").val(version.getDescription()))
                }
                self.add(versionNode)
            }
            if (hidden > 0) {
                /* What a filter removed is stated, so a shortened list can never be
                 * mistaken for the whole list. */
                self.add(Nd.of("filtered", "Hidden by includeInactive=false")
                    .val(String.valueOf(hidden) + " released or archived versions")
                    .absent(String.valueOf(hidden) + " released or archived versions are not shown"))
            }
        }
    }

    Nd components() {
        Nd node = Nd.of("components", "Components")
        node.link(links.projectComponents(project.getKey()), null)
        return guard(node) { Nd self ->
            ProjectComponentManager manager = ComponentAccessor.getComponent(ProjectComponentManager)
            if (manager == null) {
                self.failed("ProjectComponentManager is not available in this instance.")
                return
            }
            Collection<ProjectComponent> components = manager.findAllForProject(project.getId())
            if (components == null || components.isEmpty()) {
                self.absent("This project has no component.")
                return
            }
            for (ProjectComponent component : components) {
                Nd componentNode = Nd.of("component", Pc.orNa(component.getName()))
                componentNode.ident(component.getId())
                guard(componentNode) { Nd inner ->
                    List<String> facts = new ArrayList<String>()
                    facts.add("default assignee " + Pc.assigneeType(Long.valueOf(component.getAssigneeType())))
                    ApplicationUser lead = component.getComponentLead()
                    facts.add(lead == null ? "no lead" : "lead " + userLabel(lead))
                    if (component.isArchived()) {
                        facts.add("archived")
                    }
                    inner.val(facts.join(", "))
                    if (Pc.text(component.getDescription()) != null) {
                        inner.add(Nd.of("componentDescription", "Description")
                            .val(component.getDescription()))
                    }
                }
                self.add(componentNode)
            }
        }
    }

    /* ---- 11. Jira Service Management, only when it is installed ------------ */

    /* Resolved at runtime rather than imported. On an instance without Service
     * Management the classes are simply absent, and that is a fact about the
     * instance, not a failure of this report - so it is stated as such and the
     * section stays. A missing optional app must never look like an empty
     * configuration. Throwable rather than Exception on purpose: a partially
     * present plugin raises linkage errors, not exceptions. */
    Nd serviceDesk() {
        Nd node = Nd.of("serviceDesk", "Jira Service Management")
        try {
            Class.forName("com.atlassian.servicedesk.api.ServiceDeskService")
        } catch (Throwable ignored) {
            node.absent("Jira Service Management is not installed on this instance.")
            return node
        }
        try {
            Object serviceDeskService = ComponentAccessor.getOSGiComponentInstanceOfType(
                Class.forName("com.atlassian.servicedesk.api.ServiceDeskService"))
            if (serviceDeskService == null) {
                node.failed("Jira Service Management is installed but its service could not be obtained.")
                return node
            }
            /* The Service Management API is reached only through duck typing. Its
             * method shapes have changed across versions, and this report will not
             * declare a signature it has not verified on the instance it runs on.
             * What cannot be read is named; nothing here is invented. */
            node.val("Jira Service Management is installed.")
            node.add(Nd.of("serviceDeskNote", "Request types, SLAs, queues and portal settings")
                .failed("Not read yet. The Service Management API surface is not verified for " +
                    "this instance, and this report does not print configuration it has not read. " +
                    "Open the project's Service Management settings for these items."))
        } catch (Throwable error) {
            node.failed("Jira Service Management is installed but could not be queried: " +
                error.getClass().getSimpleName())
        }
        return node
    }

    /* ---- shared ------------------------------------------------------------ */

    /* The issue types of this project, read once and reused. A failure here is
     * reported by every caller that needed it rather than being hidden in one. */
    private Collection<IssueType> cachedIssueTypes = null

    Collection<IssueType> projectIssueTypes() {
        if (cachedIssueTypes != null) {
            return cachedIssueTypes
        }
        try {
            IssueTypeSchemeManager manager = ComponentAccessor.getComponent(IssueTypeSchemeManager)
            Collection<IssueType> types = manager == null ? null : manager.getIssueTypesForProject(project)
            cachedIssueTypes = types == null ? new ArrayList<IssueType>() : types
        } catch (Exception ignored) {
            cachedIssueTypes = new ArrayList<IssueType>()
        }
        return cachedIssueTypes
    }
}

/* =============================================================================
 * REST Endpoint
 * ========================================================================== */

projectConfig(
    httpMethod: "GET",
    groups: ["jira-administrators"]
) { queryParams ->

    long started = System.currentTimeMillis()

    /* ---- JAX-RS Response, resolved at runtime (javax / jakarta neutral) --- */

    Class responseClass = Http.resolveResponseClass()

    /* ---- Parameters ------------------------------------------------------ */

    String projectKey = Pc.stringParam(queryParams, "project", null)
    String format = Pc.stringParam(queryParams, "format", "html").toLowerCase(Locale.ROOT)
    String depth = Pc.stringParam(queryParams, "depth", "full").toLowerCase(Locale.ROOT)
    boolean includeInactive = Pc.booleanParam(queryParams, "includeInactive", true)
    boolean topOnly = depth == "top"

    Map<String, Object> activeParams = [
        project: projectKey,
        format: format == "html" ? null : format,
        depth: topOnly ? "top" : null,
        includeInactive: includeInactive ? null : "false"
    ] as LinkedHashMap

    /* ---- Instance identity ----------------------------------------------- */

    Report report = new Report()

    try {
        ApplicationProperties applicationProperties = ComponentAccessor.getApplicationProperties()
        report.instanceBaseUrl = applicationProperties.getJiraBaseUrl()
        report.instanceTitle = applicationProperties.getString("jira.title")
    } catch (Exception error) {
        report.globalDiagnostics.add("Instance identity could not be read: " + error.getClass().getSimpleName())
    }

    try {
        BuildUtilsInfo buildInfo = ComponentAccessor.getComponent(BuildUtilsInfo)
        if (buildInfo != null) {
            report.jiraVersion = buildInfo.getVersion()
            report.jiraBuild = buildInfo.getCurrentBuildNumber()
        }
    } catch (Exception error) {
        report.globalDiagnostics.add("Jira version could not be read: " + error.getClass().getSimpleName())
    }

    Dl links = new Dl(report.instanceBaseUrl)

    ProjectManager projectManager = ComponentAccessor.getProjectManager()

    /* ---- No project named: render the picker ------------------------------ */

    if (projectKey == null) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>()
        try {
            List<Project> all = new ArrayList<Project>(projectManager.getProjectObjects())
            all.sort { Project left, Project right ->
                String a = left.getName() == null ? "" : left.getName()
                String b = right.getName() == null ? "" : right.getName()
                return a.compareToIgnoreCase(b)
            }
            for (Project project : all) {
                Map<String, String> row = new LinkedHashMap<String, String>()
                row.put("key", project.getKey())
                row.put("name", project.getName())
                rows.add(row)
            }
        } catch (Exception error) {
            /* An empty project list and a failed read must not look alike. The
             * picker says which of the two happened. */
            report.globalDiagnostics.add("The project list could not be read: " +
                error.getClass().getSimpleName() + ". This is a failed read, not an empty instance.")
        }
        report.executionMs = System.currentTimeMillis() - started
        String page = Render.picker(report, rows, "")
        return Http.ok(responseClass, page, Http.HTML)
    }

    /* ---- Resolve the project --------------------------------------------- */

    Project project = null
    try {
        project = projectManager.getProjectObjByKeyIgnoreCase(projectKey)
    } catch (Exception error) {
        report.globalDiagnostics.add("Project lookup failed: " + error.getClass().getSimpleName())
    }

    if (project == null) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.FALSE)
        payload.put("error", "No project with key " + projectKey + " was found, or it could not be read.")
        payload.put("diagnostics", report.globalDiagnostics)
        payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.build(responseClass, 404,
            JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Http.JSON, null)
    }

    report.projectKey = project.getKey()
    report.projectName = project.getName()
    report.projectId = project.getId() == null ? null : project.getId().toString()

    /* ---- Section: project details ----------------------------------------- */

    Nd details = report.section("projectDetails", "Project details")
    details.link(links.projectSummary(project.getKey()), null)

    details.add(Nd.of("projectField", "Key").val(project.getKey()))
    details.add(Nd.of("projectField", "Name").val(project.getName()))
    details.add(Nd.of("projectField", "Id").val(project.getId()))

    try {
        String original = project.getOriginalKey()
        if (Pc.text(original) != null && original != project.getKey()) {
            details.add(Nd.of("projectField", "Original key").val(original))
        }
    } catch (Exception error) {
        details.add(Nd.of("projectField", "Original key").failed(
            "Read failed: " + error.getClass().getSimpleName()))
    }

    details.add(Nd.of("projectField", "Description").val(Pc.orNa(project.getDescription())))
    details.add(Nd.of("projectField", "URL").val(Pc.orNa(project.getUrl())))

    try {
        ApplicationUser lead = project.getProjectLead()
        Nd node = Nd.of("projectField", "Lead")
        if (lead == null) {
            node.absent("No lead set")
        } else {
            node.val(lead.getDisplayName() + " (" + lead.getName() + ")")
        }
        details.add(node)
    } catch (Exception error) {
        details.add(Nd.of("projectField", "Lead").failed("Read failed: " + error.getClass().getSimpleName()))
    }

    try {
        details.add(Nd.of("projectField", "Default assignee").val(Pc.assigneeType(project.getAssigneeType())))
    } catch (Exception error) {
        details.add(Nd.of("projectField", "Default assignee").failed(
            "Read failed: " + error.getClass().getSimpleName()))
    }

    try {
        ProjectTypeKey typeKey = project.getProjectTypeKey()
        Nd node = Nd.of("projectField", "Project type")
        if (typeKey == null) {
            node.absent("No project type set")
        } else {
            String rendered = typeKey.getKey()
            try {
                ProjectTypeManager projectTypeManager = ComponentAccessor.getComponent(ProjectTypeManager)
                if (projectTypeManager != null) {
                    /* getByKey returns io.atlassian.fugue.Option. It is read through
                     * duck typing so this file needs no fugue import and keeps
                     * working if the return type changes shape. */
                    Object option = projectTypeManager.getByKey(typeKey)
                    if (option != null && option.isDefined()) {
                        ProjectType type = (ProjectType) option.get()
                        rendered = type.getFormattedKey() + " (" + typeKey.getKey() + ")"
                    }
                }
            } catch (Exception inner) {
                node.diagnostics.add("The readable project type name could not be resolved: " +
                    inner.getClass().getSimpleName() + ". The raw key is shown.")
            }
            node.val(rendered)
        }
        details.add(node)
    } catch (Exception error) {
        details.add(Nd.of("projectField", "Project type").failed(
            "Read failed: " + error.getClass().getSimpleName()))
    }

    try {
        ProjectCategory category = project.getProjectCategoryObject()
        Nd node = Nd.of("projectField", "Category")
        if (category == null) {
            node.absent("No category")
        } else {
            node.val(category.getName()).ident(category.getId())
        }
        details.add(node)
    } catch (Exception error) {
        details.add(Nd.of("projectField", "Category").failed("Read failed: " + error.getClass().getSimpleName()))
    }

    try {
        Nd node = Nd.of("projectField", "Archived").val(project.isArchived() ? "yes" : "no")
        if (project.isArchived()) {
            ApplicationUser by = project.getArchivedBy()
            Date on = project.getArchivedDate()
            node.add(Nd.of("projectField", "Archived by").val(by == null ? Pc.NA : by.getDisplayName()))
            node.add(Nd.of("projectField", "Archived on").val(on == null ? Pc.NA : on.toString()))
        }
        details.add(node)
    } catch (Exception error) {
        details.add(Nd.of("projectField", "Archived").failed("Read failed: " + error.getClass().getSimpleName()))
    }

    /* ---- The configuration itself ----------------------------------------- */

    /* Every section is independent and none of them throws, so one unreadable
     * subsystem costs exactly its own section and never the report. */

    I18nHelper i18n = null
    try {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext()
        i18n = authenticationContext == null ? null : authenticationContext.getI18nHelper()
    } catch (Exception error) {
        report.globalDiagnostics.add("The i18n helper could not be obtained: " +
            error.getClass().getSimpleName() + ". Names are shown untranslated.")
    }

    Scan scan = new Scan(project, links, i18n)

    report.sections.add(scan.issueTypeScheme())
    report.sections.add(scan.issueTypeScreenScheme())
    report.sections.add(scan.fieldConfigurationScheme())
    report.sections.add(scan.customFields())
    report.sections.add(scan.workflowScheme())
    report.sections.add(scan.permissionScheme())
    report.sections.add(scan.notificationScheme())
    report.sections.add(scan.issueSecurityScheme())
    report.sections.add(scan.projectRoles())
    report.sections.add(scan.versions(includeInactive))
    report.sections.add(scan.components())
    report.sections.add(scan.serviceDesk())

    report.executionMs = System.currentTimeMillis() - started

    /* ---- Emit -------------------------------------------------------------- */

    if (format == "json") {
        return Http.ok(responseClass, Render.json(report), Http.JSON)
    }
    if (format == "csv") {
        Map<String, String> headers = new LinkedHashMap<String, String>()
        headers.put("Content-Disposition",
            "attachment; filename=\"project-config-" + report.projectKey + ".csv\"")
        return Http.build(responseClass, 200, Render.csv(report), Http.CSV, headers)
    }
    return Http.ok(responseClass, Render.html(report, activeParams, topOnly), Http.HTML)
}
