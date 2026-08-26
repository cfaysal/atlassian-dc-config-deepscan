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

import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationLinkRequestFactory
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.ApplicationType
import com.atlassian.applinks.api.CredentialsRequiredException

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
import com.opensymphony.workflow.loader.ResultDescriptor
import com.opensymphony.workflow.loader.StepDescriptor

import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.sal.api.net.Request

import com.adaptavist.hapi.jira.users.Users
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import org.codehaus.groovy.runtime.InvokerHelper

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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

    /* The alias ManageIssueTypeSchemes is evidenced in actions.xml, so the screen
     * itself can be linked. What could NOT be evidenced anywhere in jira-core or in
     * the shipped plugins is a parameter that preselects one scheme on it.
     *
     * The link therefore goes to the list, and its text says "issue type schemes"
     * rather than "open in Jira". A reader who lands on a list after clicking a
     * label that promised one scheme concludes the report pointed at the wrong
     * thing; the imprecise half of a link belongs in the text somebody clicks, not
     * in a footnote underneath it. */
    String issueTypeSchemes() {
        return admin("ManageIssueTypeSchemes.jspa")
    }

    String issueTypeSchemeUnavailableNote() {
        return "Administration > Issues > Issue type schemes. No evidenced URL parameter " +
            "addresses a single scheme, so this link opens the list."
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
    /* What the link is called. Left unset it reads "open in Jira" on a section and
     * "open" on a node, which is right when the link lands exactly on the item.
     * When it can only land nearby, that difference belongs in the text a reader
     * clicks. */
    String linkLabel

    Nd linkAs(String label) {
        this.linkLabel = Pc.text(label)
        return this
    }

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
        if (linkLabel != null) {
            out.put("linkLabel", linkLabel)
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

    static String html(Report report, Map<String, Object> activeParams, boolean expandAll) {
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
        out.append(toolbar(activeParams, expandAll))
        out.append(exportCard(report))
        out.append(diagnosticsCard(report))
        for (Nd node : report.sections) {
            out.append(section(node, expandAll))
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
        /* The base URL is what identifies the instance once the report has left it.
         * An exported page or a printed PDF that names a title and a version but not
         * the address describes some Jira, not this one. */
        String baseUrl = Pc.text(report.instanceBaseUrl)
        out.append("<div><strong>Address</strong> ")
        if (baseUrl == null) {
            out.append("<span class=\"state state-unreadable\">could not be read</span>")
        } else {
            out.append("<a href=\"").append(Pc.html(baseUrl))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">").append(Pc.html(baseUrl)).append("</a>")
        }
        out.append("</div>")
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

    private static String toolbar(Map<String, Object> activeParams, boolean expandAll) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"actions\">")
        /* Expanding and collapsing happens in the page. The link below is the
         * bookmarkable variant of the same thing, for a URL that should open fully
         * expanded for somebody else. */
        out.append("<button class=\"button\" type=\"button\" onclick=\"expandAll(true)\">Expand all</button>")
        out.append("<button class=\"button\" type=\"button\" onclick=\"expandAll(false)\">Collapse all</button>")
        out.append("<button id=\"viewTree\" class=\"button on\" type=\"button\" onclick=\"setView('tree')\">Tree</button>")
        out.append("<button id=\"viewTable\" class=\"button\" type=\"button\" onclick=\"setView('table')\">Table</button>")
        /* There is deliberately no button that turns depth=full ON. It used to be
         * one click away, and one click was enough to pin "everything expanded"
         * into the URL, into a bookmark, and into every later visit - at which
         * point the collapsed default is simply never seen again. Expanding is a
         * thing you do to the page in front of you, not a setting you carry around,
         * so Expand all above does it and leaves the URL alone. The parameter still
         * exists for a link somebody hands to somebody else, and when it is active
         * the way back out of it is right here. */
        if (expandAll) {
            out.append(button(Pc.link(activeParams, [depth: null]),
                "Leave always-expanded mode", true))
        }
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

    /* A section is closed until it is asked for. A project of any size produces
     * twelve sections and thousands of nodes, and a page that opens with all of them
     * unfolded cannot be read at all: the first thing an administrator needs is the
     * list of sections and how much sits in each. What matters while a section is
     * closed is written on its header, so a closed section is never mistaken for an
     * empty or a broken one. */
    private static String section(Nd node, boolean expandAll) {
        StringBuilder out = new StringBuilder()
        boolean open = expandAll
        out.append("<div class=\"section\">")
        out.append("<div class=\"section-head\" role=\"button\" tabindex=\"0\" aria-expanded=\"")
        out.append(open ? "true" : "false").append("\">")
        out.append("<button class=\"twisty section-twisty\" type=\"button\" tabindex=\"-1\">")
        out.append(open ? "&#9662;" : "&#9656;").append("</button>")
        out.append("<h2 class=\"section-title\">").append(Pc.html(Pc.orNa(node.label))).append("</h2>")
        if (!node.children.isEmpty()) {
            out.append("<span class=\"section-count muted\">")
            out.append(String.valueOf(node.countDescendants())).append(" items</span>")
        }
        /* A section that could not be read says so on its header, where it stays
         * visible while the section is closed. Behind the fold, a failed read turns
         * into something the reader has to go looking for. */
        if (!node.isReadable()) {
            out.append("<span class=\"state state-").append(Pc.html(node.state)).append("\">")
            out.append(Pc.html(stateLabel(node.state))).append("</span>")
        } else if (node.children.isEmpty()) {
            out.append("<span class=\"muted empty\">nothing configured</span>")
        }
        if (node.deepLink != null) {
            out.append("<a class=\"jump\" href=\"").append(Pc.html(node.deepLink))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">")
            out.append(Pc.html(node.linkLabel == null ? "open in Jira" : node.linkLabel)).append("</a>")
        }
        out.append("</div>")

        out.append("<div class=\"section-body").append(open ? "" : " hidden").append("\">")
        if (node.value != null) {
            out.append("<div class=\"section-value\">").append(valueHtml(node.value)).append("</div>")
        }
        if (node.linkNote != null) {
            out.append("<div class=\"linknote\">").append(Pc.html(node.linkNote)).append("</div>")
        }
        if (node.children.isEmpty()) {
            if (node.isReadable()) {
                out.append("<div class=\"muted empty\">Nothing configured here.</div>")
            }
        } else {
            out.append("<ul class=\"tree view-tree\">")
            for (Nd child : node.children) {
                out.append(treeNode(child, expandAll, 1))
            }
            out.append("</ul>")
            out.append(sectionTable(node))
        }
        out.append("</div>")
        out.append("</div>\n")
        return out.toString()
    }

    /* The same subtree as a flat table. A tree is the honest shape of this data and
     * stays the default, but a table is what gets scanned for one value, sorted in a
     * spreadsheet and pasted into a hand-over document. Both are rendered into the
     * page and the toggle only switches which one is shown, so neither view can go
     * stale against the other. */
    private static String sectionTable(Nd node) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"view-table hidden\"><table class=\"flat\"><thead><tr>")
        out.append("<th>Path</th><th>Item</th><th>Value</th><th>State</th><th>In Jira</th>")
        out.append("</tr></thead><tbody>")
        List<Nd> flat = new ArrayList<Nd>()
        List<String> paths = new ArrayList<String>()
        for (Nd child : node.children) {
            flattenInto(child, "", flat, paths)
        }
        for (int i = 0; i < flat.size(); i++) {
            Nd row = flat.get(i)
            out.append("<tr>")
            out.append("<td class=\"mono\">").append(Pc.html(paths.get(i))).append("</td>")
            out.append("<td>").append(Pc.html(Pc.orNa(row.label))).append("</td>")
            out.append("<td>").append(valueHtml(row.value)).append("</td>")
            out.append("<td>")
            if (!row.isReadable()) {
                out.append("<span class=\"state state-").append(Pc.html(row.state)).append("\">")
                out.append(Pc.html(stateLabel(row.state))).append("</span>")
            }
            out.append("</td><td>")
            if (row.deepLink != null) {
                out.append("<a href=\"").append(Pc.html(row.deepLink))
                out.append("\" target=\"_blank\" rel=\"noreferrer\">")
                out.append(Pc.html(row.linkLabel == null ? "open" : row.linkLabel)).append("</a>")
            } else if (row.linkNote != null) {
                out.append("<span class=\"node-nolink\" title=\"").append(Pc.html(row.linkNote))
                out.append("\">no link</span>")
            }
            out.append("</td></tr>")
        }
        out.append("</tbody></table></div>")
        return out.toString()
    }

    private static void flattenInto(Nd node, String parentPath, List<Nd> flat, List<String> paths) {
        String path = parentPath.isEmpty() ? Pc.orNa(node.label) : parentPath + " > " + Pc.orNa(node.label)
        flat.add(node)
        paths.add(path)
        for (Nd child : node.children) {
            flattenInto(child, path, flat, paths)
        }
    }

    private static String treeNode(Nd node, boolean expandAll, int level) {
        StringBuilder out = new StringBuilder()
        boolean hasChildren = !node.children.isEmpty()
        /* Collapsed is the default. A deep scan of a real project is thousands of
         * nodes, and a page that opens with all of them expanded is a wall, not a
         * report. Every twisty is one click, and Expand all is one click for the
         * whole page. */
        boolean collapse = hasChildren && !expandAll
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
        if (hasChildren) {
            /* A collapsed node that does not say how much it hides invites the
             * reader to assume it hides nothing. */
            out.append("<span class=\"node-count muted\">")
            out.append(String.valueOf(node.countDescendants())).append("</span>")
        }
        if (node.value != null) {
            out.append("<span class=\"node-value\">").append(valueHtml(node.value)).append("</span>")
        }
        if (node.id != null) {
            out.append("<span class=\"node-id mono\">id ").append(Pc.html(node.id)).append("</span>")
        }
        if (node.deepLink != null) {
            out.append("<a class=\"node-link\" href=\"").append(Pc.html(node.deepLink))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">")
            out.append(Pc.html(node.linkLabel == null ? "open" : node.linkLabel)).append("</a>")
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
                out.append(treeNode(child, expandAll, level + 1))
            }
            out.append("</ul>")
        }
        out.append("</li>")
        return out.toString()
    }

    /* A configuration value is whatever an administrator or another app put there.
     * The Scalpel scheme merger, for example, writes its whole provenance record
     * into a scheme description, participant id list included: several thousand
     * characters with no space in them. Rendered raw that has no break opportunity
     * and runs straight out of the card, taking the layout with it.
     *
     * Long values are therefore clamped into a details element. Nothing is dropped
     * - the full text is one click away and is in the JSON and the CSV either way -
     * but the page stays readable. Truncating without saying so would be the other,
     * worse answer. */
    static final int VALUE_CLAMP = 200

    static String valueHtml(Object value) {
        String text = Pc.text(value)
        if (text == null) {
            return ""
        }
        if (text.length() <= VALUE_CLAMP) {
            return Pc.html(text)
        }
        StringBuilder out = new StringBuilder()
        out.append("<details class=\"long\"><summary>")
        out.append("<span class=\"clamped\">").append(Pc.html(text.substring(0, VALUE_CLAMP)))
        out.append("&#8230;</span>")
        out.append("<span class=\"more\">show all (").append(String.valueOf(text.length()))
        out.append(" characters)</span></summary>")
        out.append("<div class=\"long-body\">").append(Pc.html(text)).append("</div>")
        out.append("</details>")
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
        out.append(projectPicker(projects, selfPath))
        if (!shell.globalDiagnostics.isEmpty()) {
            out.append("<div class=\"diag diag-warn\"><strong>Some reads were suppressed.</strong><ul>")
            for (String entry : shell.globalDiagnostics) {
                out.append("<li>").append(Pc.html(entry)).append("</li>")
            }
            out.append("</ul></div>\n")
        }
        out.append("</div>\n").append(pickerScript()).append("</body>\n</html>\n")
        return out.toString()
    }

    /* How many project rows are visible at once. An instance with several hundred
     * projects turns a dropdown into a scroll hunt, so the list is searched rather
     * than scrolled. Every project is already in the page, which is what makes the
     * search instant and keeps it from costing a request per keystroke. The cap is
     * on what is shown, and the count line always names the full population, so a
     * filtered list can never be mistaken for the whole one. */
    static final int PROJECT_ROWS = 40

    static String projectPicker(List<Map<String, String>> projects, String selfPath) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"export-card\">")
        out.append("<div class=\"export-title\">Choose a project</div>")
        out.append("<div class=\"export-grid\">")
        out.append("<label class=\"export-field\">Search by key or name")
        out.append("<input id=\"projectQuery\" class=\"wide\" type=\"search\" autocomplete=\"off\" ")
        out.append("placeholder=\"Type a project key or part of a name...\" ")
        out.append("oninput=\"filterProjects()\" onkeydown=\"pickFirstProject(event)\"></label>")
        out.append("<div class=\"export-chosen\" id=\"projectCount\">")
        out.append(Pc.html(countLine(projects.size(), projects.size())))
        out.append("</div></div>")

        out.append("<div id=\"projectResults\" class=\"export-results project-list\">")
        int shown = 0
        for (Map<String, String> project : projects) {
            String key = Pc.orNa(project.get("key"))
            String name = Pc.orNa(project.get("name"))
            boolean visible = shown < PROJECT_ROWS
            if (visible) {
                shown++
            }
            out.append("<a class=\"export-hit").append(visible ? "" : " hidden").append("\" href=\"")
            out.append(Pc.html(selfPath)).append("?project=").append(Pc.html(Pc.urlQuery(key)))
            out.append("\" data-find=\"")
            out.append(Pc.html((key + " " + name).toLowerCase(Locale.ROOT)))
            out.append("\"><strong>").append(Pc.html(key)).append("</strong> ")
            out.append(Pc.html(name)).append("</a>")
        }
        out.append("</div>")
        out.append("<div id=\"projectEmpty\" class=\"export-empty hidden\">No project matches that search.</div>")

        out.append("<div class=\"export-note\">The report reads configuration only: no issue is counted ")
        out.append("and no search is run, so the run is harmless on a production instance.</div>")
        out.append("</div>\n")
        return out.toString()
    }

    /* Rendered on the server for the first paint and recomputed in the browser on
     * every keystroke. The two must agree, so the wording lives here and the script
     * below mirrors it; the offline suite checks this one, which is the one a reader
     * without JavaScript ever sees. */
    static String countLine(int matching, int total) {
        if (matching == 0) {
            return "no match out of " + String.valueOf(total) + " projects"
        }
        String tail = matching > PROJECT_ROWS ? ", showing the first " + String.valueOf(PROJECT_ROWS) : ""
        if (matching == total) {
            return String.valueOf(total) + " projects" + tail
        }
        return String.valueOf(matching) + " of " + String.valueOf(total) + " projects match" + tail
    }

    /* The picker carries its own script rather than the report's: it has no tree to
     * fold and no export to stage, and shipping the whole thing here would mean two
     * pages sharing code only one of them can use. */
    private static String pickerScript() {
        return """<script>
var PROJECT_ROWS = ${PROJECT_ROWS};

function projectCountLine(matching, total) {
    if (matching === 0) { return 'no match out of ' + total + ' projects'; }
    var tail = matching > PROJECT_ROWS ? ', showing the first ' + PROJECT_ROWS : '';
    if (matching === total) { return total + ' projects' + tail; }
    return matching + ' of ' + total + ' projects match' + tail;
}

function filterProjects() {
    var query = (document.getElementById('projectQuery').value || '').trim().toLowerCase();
    var rows = document.querySelectorAll('#projectResults .export-hit');
    var matching = 0;
    var shown = 0;
    for (var i = 0; i < rows.length; i++) {
        var isHit = query === '' || rows[i].getAttribute('data-find').indexOf(query) >= 0;
        if (isHit) { matching++; }
        var show = isHit && shown < PROJECT_ROWS;
        if (show) { shown++; }
        rows[i].classList.toggle('hidden', !show);
    }
    document.getElementById('projectCount').textContent = projectCountLine(matching, rows.length);
    document.getElementById('projectEmpty').classList.toggle('hidden', matching !== 0);
}

/* Enter opens the first hit. Typing a key you already know should not need the
   mouse for the last step. */
function pickFirstProject(event) {
    if (event.key !== 'Enter') { return; }
    event.preventDefault();
    var first = document.querySelector('#projectResults .export-hit:not(.hidden)');
    if (first) { window.location.href = first.getAttribute('href'); }
}
</script>
"""
    }

    /* The export is staged behind its own button on purpose. Rendering the report
     * contacts nothing; the click is what lists the application links, choosing a
     * target loads that target's spaces, choosing a space opens the parent search,
     * and only then can a page be written. Each stage is one POST to this same
     * endpoint. Nothing leaves this instance until the button is pressed. */
    static String exportCard(Report report) {
        String payload = Pc.html(JsonOutput.toJson(report.toMap()))
        String defaultTitle = Pc.html(Cx.title(report.projectKey))
        return """<div class="export-card">
    <div class="export-title">Export to Confluence</div>
    <div class="export-note">
        Writes this configuration report into a Confluence page over a Jira application link and updates
        that same page on every later run. The <strong>Remark</strong> column stays untouched: it is read
        back from the existing page and carried over verbatim. If that read fails, nothing is written at
        all. A remark whose configuration item has disappeared is kept in a second table rather than
        dropped. A page that does not carry this export's marker is never overwritten. Nothing is read
        from Confluence until the button below is pressed.
    </div>
    <div class="export-grid">
        <button id="exportOpen" class="button" type="button" onclick="openExport()">Export to Confluence</button>
    </div>
    <div id="exportSettings" class="export-settings hidden">
        <div class="export-grid">
            <label class="export-field">Target Confluence
                <select id="exportTarget" onchange="targetChosen()"></select>
            </label>
            <div class="export-chosen" id="exportTargetNote">Reading the application links...</div>
        </div>
        <div id="exportSpaceStage" class="export-stage hidden">
            <div class="export-grid">
                <label class="export-field">Space - search by name or key
                    <input id="exportSpaceQuery" class="wide" type="search" autocomplete="off"
                           placeholder="Type at least ${Cx.MIN_SEARCH_CHARS} characters..." oninput="searchSpaces()"
                           onkeydown="pickFirstHit(event, 'exportSpaceResults')">
                </label>
                <div class="export-chosen" id="exportSpaceChosen">No space selected.</div>
            </div>
            <div id="exportSpaceResults" class="export-results"></div>
        </div>
        <div id="exportPageStage" class="export-stage hidden">
            <div class="export-grid">
                <label class="export-field">Parent page - search by title (optional)
                    <input id="exportParentQuery" class="wide" type="search" autocomplete="off"
                           placeholder="Type at least ${Cx.MIN_SEARCH_CHARS} characters..." oninput="parentTyped()"
                           onkeydown="pickFirstHit(event, 'exportParentResults')">
                </label>
                <div class="export-chosen" id="exportParentChosen">No parent page: the page is created at the top level of the space.</div>
            </div>
            <div id="exportParentResults" class="export-results"></div>
            <div class="export-grid">
                <label class="export-field">Page title
                    <input id="exportTitle" class="wide" type="text" value="${defaultTitle}">
                </label>
                <button id="exportRun" class="button" type="button" onclick="exportToConfluence()">Generate Confluence Page</button>
            </div>
        </div>
    </div>
    <div id="exportStatus" class="export-status muted">Not written yet.</div>
    <input id="exportPayload" type="hidden" value="${payload}">
    <input id="exportSpace" type="hidden" value="">
    <input id="exportParent" type="hidden" value="">
</div>
"""
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
.section-count { font-size: 12px; }
.node-count { font-size: 11px; color: var(--text-subtle); background: var(--surface-subtle);
    border: 1px solid var(--border-subtle); border-radius: 8px; padding: 0 6px; }
table.flat { border-collapse: collapse; width: 100%; margin-top: 10px; font-size: 13px; }
table.flat th, table.flat td { border: 1px solid var(--border-subtle); padding: 5px 8px;
    text-align: left; vertical-align: top; }
table.flat th { background: var(--surface-subtle); font-weight: 600; position: sticky; top: 0; }
table.flat tr:nth-child(even) td { background: var(--surface-subtle); }
.view-table { overflow-x: auto; }
.project-list { max-height: 460px; overflow-y: auto; margin-top: 10px; }
.project-list .export-hit { display: block; }
.project-list .export-hit strong { display: inline-block; min-width: 90px; font-family: ui-monospace,
    SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; }
.section-head { cursor: pointer; user-select: none; gap: 10px; }
.section-head:hover .section-title { color: var(--blue); }
.section-head .twisty { font-size: 13px; }
.section-title { flex: 0 1 auto; }
/* "open" must never wrap into "ope / n": the last two columns shrink to their own
   content instead of taking a share of the width. */
table.flat th:nth-child(4), table.flat td:nth-child(4),
table.flat th:nth-child(5), table.flat td:nth-child(5) { white-space: nowrap; width: 1%; }
table.flat td:nth-child(5) { text-align: right; }
table.flat td:first-child { white-space: normal; font-size: 12px; }
table.flat th:nth-child(2), table.flat td:nth-child(2) { min-width: 140px; }
.section-value, .node-value, .linknote, table.flat td, .node-diag {
    overflow-wrap: anywhere; word-break: break-word;
}
.node-line { min-width: 0; }
details.long { display: inline; }
details.long > summary { cursor: pointer; list-style: none; display: inline; }
details.long > summary::-webkit-details-marker { display: none; }
details.long .more { color: var(--blue); font-size: 12px; margin-left: 6px; white-space: nowrap; }
details.long[open] .clamped { display: none; }
details.long .long-body { margin-top: 6px; padding: 8px 10px; background: var(--surface-subtle);
    border: 1px solid var(--border-subtle); border-radius: 4px; font-size: 12px;
    max-height: 320px; overflow: auto; }
.export-card {
    background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 14px 18px; margin-bottom: 18px; box-shadow: var(--shadow);
}
.export-title { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
.export-note { color: var(--text-subtle); font-size: 13px; max-width: 1080px; }
.export-grid { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; margin-top: 12px; }
.export-field {
    display: flex; flex-direction: column; gap: 4px; color: var(--text-subtle);
    font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .035em;
}
.export-field input, .export-field select {
    height: 34px; padding: 0 10px; border: 1px solid var(--border); border-radius: 4px;
    background: var(--surface); color: var(--text); font-size: 14px; font-weight: 400;
    text-transform: none; letter-spacing: 0;
}
.export-field select { min-width: 300px; }
.export-field input.wide { min-width: 320px; }
.export-card button.button { cursor: pointer; height: 34px; }
.export-card button.button[disabled] { opacity: .55; cursor: not-allowed; }
.export-status { margin-top: 10px; font-size: 12px; }
.export-settings { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border-subtle); }
.export-stage { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border-subtle); }
.export-chosen { align-self: flex-end; padding-bottom: 8px; color: var(--text-subtle); font-size: 12px; }
.export-results { margin-top: 8px; max-width: 680px; }
.export-hit {
    display: block; width: 100%; margin-bottom: 4px; padding: 6px 10px; text-align: left;
    border: 1px solid var(--border); border-radius: 4px; background: var(--surface-subtle);
    color: var(--text); font-size: 13px; cursor: pointer;
}
.export-hit:hover { border-color: var(--blue); background: var(--blue-soft); }
.export-empty { color: var(--text-subtle); font-size: 12px; font-style: italic; }
</style>
"""
    }

    /* The only script on the page. It collapses and expands, nothing else: no
     * fetch, no external resource, no state that outlives the tab. */
    private static String script() {
        return """<script>
function toggleSection(section, force) {
    if (!section) { return; }
    var body = section.querySelector(':scope > .section-body');
    if (!body) { return; }
    var open = force === undefined ? body.classList.contains('hidden') : force;
    body.classList.toggle('hidden', !open);
    var head = section.querySelector(':scope > .section-head');
    if (head) { head.setAttribute('aria-expanded', open ? 'true' : 'false'); }
    var chevron = section.querySelector(':scope > .section-head > .twisty');
    if (chevron) { chevron.innerHTML = open ? '&#9662;' : '&#9656;'; }
}

document.addEventListener('click', function (event) {
    /* The whole section header is the hit area, which is why the link inside it has
       to be excluded by hand: clicking "open in Jira" must open Jira, not fold the
       section away underneath the click. */
    var head = event.target.closest('.section-head');
    if (head && !event.target.closest('a')) {
        toggleSection(head.closest('.section'));
        return;
    }
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

function setTwisty(item, open) {
    var list = item.querySelector(':scope > ul.tree');
    if (!list) { return; }
    list.classList.toggle('hidden', !open);
    var button = item.querySelector(':scope > .node-line > .twisty');
    if (button) {
        button.setAttribute('aria-expanded', open ? 'true' : 'false');
        button.innerHTML = open ? '&#9662;' : '&#9656;';
    }
}

document.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter' && event.key !== ' ') { return; }
    var head = event.target.closest ? event.target.closest('.section-head') : null;
    if (!head) { return; }
    event.preventDefault();
    toggleSection(head.closest('.section'));
});

function expandAll(open) {
    var sections = document.querySelectorAll('.section');
    for (var s = 0; s < sections.length; s++) { toggleSection(sections[s], open); }
    var items = document.querySelectorAll('li.node');
    for (var i = 0; i < items.length; i++) { setTwisty(items[i], open); }
}

/* Both views are already in the page. Switching only changes which one is shown,
   so the table can never drift away from the tree it was built from. */
function setView(name) {
    var wantTable = name === 'table';
    var trees = document.querySelectorAll('.view-tree');
    var tables = document.querySelectorAll('.view-table');
    for (var i = 0; i < trees.length; i++) { trees[i].classList.toggle('hidden', wantTable); }
    for (var j = 0; j < tables.length; j++) { tables[j].classList.toggle('hidden', !wantTable); }
    var treeButton = document.getElementById('viewTree');
    var tableButton = document.getElementById('viewTable');
    if (treeButton) { treeButton.classList.toggle('on', !wantTable); }
    if (tableButton) { tableButton.classList.toggle('on', wantTable); }
}

var exportSpaceList = [];
var exportPageSeq = 0;
var exportPageTimer = null;

function el(id) { return document.getElementById(id); }
function say(cssClass, text) {
    var node = el('exportStatus');
    node.className = 'export-status ' + cssClass;
    node.textContent = text;
    return node;
}
function exportPost(payload) {
    return fetch(window.location.pathname, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
        body: JSON.stringify(payload)
    }).then(function (response) {
        return response.json().then(function (parsed) { return { ok: response.ok, status: response.status, body: parsed }; });
    });
}
function hit(label, onPick) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'export-hit';
    button.textContent = label;
    button.onclick = onPick;
    return button;
}
function emptyNote(text) {
    var note = document.createElement('div');
    note.className = 'export-empty';
    note.textContent = text;
    return note;
}
/* Enter picks the first hit. The results are buttons in document order, so the
   first one in the box is the first match. Enter with no hit does nothing, the
   default is always suppressed so Enter can never submit or reload the page, and
   picking a hit with the mouse keeps working unchanged. */
function pickFirstHit(event, boxId) {
    if (event.key !== 'Enter') { return; }
    event.preventDefault();
    var first = el(boxId).querySelector('.export-hit');
    if (first) { first.click(); }
}

/* Stage 1. The first lookup of the whole report: which Confluence links exist. */
function openExport() {
    el('exportOpen').disabled = true;
    el('exportSettings').classList.remove('hidden');
    say('muted', 'Reading the Confluence application links...');
    exportPost({ action: 'links' }).then(function (result) {
        var body = result.body || {};
        var select = el('exportTarget');
        if (!result.ok || body.ok !== true) {
            el('exportSettings').classList.add('hidden');
            el('exportOpen').disabled = false;
            say('bad', body.error || 'The Confluence application links could not be read.');
            return;
        }
        var links = body.links || [];
        select.innerHTML = '';
        if (links.length > 1) { select.appendChild(new Option('Select a Confluence...', '')); }
        for (var i = 0; i < links.length; i++) {
            var label = links[i].name + (links[i].primary ? ' (primary)' : '') +
                (links[i].displayUrl ? ' - ' + links[i].displayUrl : '');
            var option = new Option(label, links[i].id);
            if (links[i].primary || links.length === 1) { option.selected = true; }
            select.appendChild(option);
        }
        el('exportTargetNote').textContent = links.length === 1
            ? 'One Confluence application link, preselected.'
            : String(links.length) + ' Confluence application links configured.';
        say('muted', 'Pick the target Confluence, then the space.');
        targetChosen();
    }).catch(function (error) {
        el('exportOpen').disabled = false;
        say('bad', 'The Confluence application links could not be read: ' + error);
    });
}

/* Stage 2. A target was picked, so that target's spaces may be listed. */
function targetChosen() {
    el('exportSpace').value = '';
    el('exportSpaceQuery').value = '';
    el('exportSpaceResults').innerHTML = '';
    el('exportSpaceChosen').textContent = 'No space selected.';
    el('exportPageStage').classList.add('hidden');
    exportSpaceList = [];
    if (!el('exportTarget').value) { el('exportSpaceStage').classList.add('hidden'); return; }
    el('exportSpaceStage').classList.remove('hidden');
    say('muted', 'Reading the spaces of the selected Confluence...');
    exportPost({ action: 'spaces', applicationLinkId: el('exportTarget').value }).then(function (result) {
        var body = result.body || {};
        if (!result.ok || body.ok !== true) {
            el('exportSpaceStage').classList.add('hidden');
            say('bad', body.error || 'The Confluence space list could not be read.');
            return;
        }
        exportSpaceList = body.spaces || [];
        say('muted', String(exportSpaceList.length) + ' space(s) available' +
            (body.truncated === true ? ', and the list is truncated - the instance has more' : '') +
            '. Type at least ${Cx.MIN_SEARCH_CHARS} characters to search by name or key.');
    }).catch(function (error) {
        el('exportSpaceStage').classList.add('hidden');
        say('bad', 'The Confluence space list could not be read: ' + error);
    });
}

/* Stage 3a. Search, not a dropdown: only matches are ever put into the page. */
function searchSpaces() {
    var query = el('exportSpaceQuery').value.trim().toLowerCase();
    var box = el('exportSpaceResults');
    box.innerHTML = '';
    if (query.length < ${Cx.MIN_SEARCH_CHARS}) { return; }
    var shown = 0;
    for (var i = 0; i < exportSpaceList.length && shown < ${Cx.SEARCH_LIMIT}; i++) {
        var space = exportSpaceList[i];
        if (space.name.toLowerCase().indexOf(query) < 0 && space.key.toLowerCase().indexOf(query) < 0) { continue; }
        box.appendChild(hit(space.name + '  (' + space.key + ')', chooseSpace(space)));
        shown++;
    }
    if (shown === 0) { box.appendChild(emptyNote('No space matches "' + query + '".')); }
}
function chooseSpace(space) {
    return function () {
        el('exportSpace').value = space.key;
        el('exportSpaceQuery').value = space.name;
        el('exportSpaceResults').innerHTML = '';
        el('exportSpaceChosen').textContent = 'Space: ' + space.name + ' (' + space.key + ')';
        /* A parent search that is still running belongs to the previous space, so
           it is discarded here as well - otherwise its answer would drop a list of
           foreign pages into the field of the space just picked. */
        if (exportPageTimer) { window.clearTimeout(exportPageTimer); }
        exportPageSeq++;
        el('exportParent').value = '';
        el('exportParentQuery').value = '';
        el('exportParentResults').innerHTML = '';
        el('exportParentChosen').textContent = 'No parent page: the page is created at the top level of the space.';
        el('exportPageStage').classList.remove('hidden');
        say('muted', 'Space ' + space.key + ' selected. Pick a parent page or leave it empty, then generate.');
    };
}

/* Stage 3b. The parent field has no button: typing is what starts the search,
   after a short idle pause rather than on every keystroke. The list that comes
   back STAYS until an entry is picked or the field falls below the minimum - a
   list that disappears while it is being read cannot confirm anything, which is
   what made the previous version unusable. Out-of-order answers are dropped, so
   a slow answer to an older term never replaces the list of the current one. */
function parentTyped() {
    /* Editing the term drops the picked parent, so a stale id can never travel
       with a title the administrator has since changed. What travels then is the
       title, and the generating run adopts or creates that page. */
    el('exportParent').value = '';
    var query = el('exportParentQuery').value.trim();
    el('exportParentChosen').textContent = query
        ? 'Parent page "' + query + '": pick it below if it is listed, otherwise it is created when the page is generated.'
        : 'No parent page: the page is created at the top level of the space.';
    if (exportPageTimer) { window.clearTimeout(exportPageTimer); }
    if (query.length < ${Cx.MIN_SEARCH_CHARS}) {
        /* Bumping the sequence here discards an answer that is still in flight,
           so an empty field never fills back up on its own. */
        exportPageSeq++;
        el('exportParentResults').innerHTML = '';
        return;
    }
    exportPageTimer = window.setTimeout(searchParents, ${Cx.SEARCH_IDLE_MS});
}
function searchParents() {
    var query = el('exportParentQuery').value.trim();
    var box = el('exportParentResults');
    if (query.length < ${Cx.MIN_SEARCH_CHARS}) { box.innerHTML = ''; return; }
    var seq = ++exportPageSeq;
    exportPost({
        action: 'pages',
        applicationLinkId: el('exportTarget').value,
        spaceKey: el('exportSpace').value,
        query: query
    }).then(function (result) {
        if (seq !== exportPageSeq) { return; }
        var body = result.body || {};
        box.innerHTML = '';
        if (!result.ok || body.ok !== true) {
            box.appendChild(emptyNote(body.error || 'The page search failed.'));
            return;
        }
        var pages = body.pages || [];
        if (pages.length === 0) {
            box.appendChild(emptyNote('Not found - will be created'));
            return;
        }
        for (var i = 0; i < pages.length; i++) {
            box.appendChild(hit(pages[i].title + '  #' + pages[i].id, chooseParent(pages[i])));
        }
        if (body.truncated === true) {
            box.appendChild(emptyNote('More pages match than are listed here. Type more of the title to narrow it down.'));
        }
    }).catch(function (error) {
        if (seq === exportPageSeq) {
            box.innerHTML = '';
            box.appendChild(emptyNote('The page search failed: ' + error));
        }
    });
}
function chooseParent(page) {
    return function () {
        el('exportParent').value = page.id;
        el('exportParentQuery').value = page.title;
        el('exportParentResults').innerHTML = '';
        el('exportParentChosen').textContent = 'Parent page: ' + page.title + ' (id ' + page.id + ')';
    };
}

/* Stage 4. The write. */
function exportToConfluence() {
    var button = el('exportRun');
    function fail(text) { say('bad', text); }
    var payload;
    try { payload = JSON.parse(el('exportPayload').value); }
    catch (error) { fail('Export payload could not be read: ' + error); return; }
    payload.applicationLinkId = el('exportTarget').value;
    payload.spaceKey = el('exportSpace').value;
    /* Either the id of a page that was picked, or the title that was typed and
       never picked - never both. The server refuses a request that carries two
       parent instructions, so the choice is made here and only here. */
    payload.parentPageId = el('exportParent').value.trim();
    payload.parentTitle = payload.parentPageId ? '' : el('exportParentQuery').value.trim();
    payload.title = el('exportTitle').value.trim();
    if (!payload.applicationLinkId) { fail('Select the target Confluence first.'); return; }
    if (!payload.spaceKey) { fail('Select a space first.'); return; }
    if (!payload.title) { fail('Enter a page title first.'); return; }
    button.disabled = true;
    say('muted', 'Writing page...');
    exportPost(payload).then(function (result) {
        button.disabled = false;
        var body = result.body || {};
        if (!result.ok || body.ok !== true) {
            fail('Nothing was written (' + result.status + '): ' + (body.error || 'unknown error'));
            return;
        }
        var version = body.pageVersion === null ? 'unknown' : body.pageVersion;
        /* Found and created are reported apart. An administrator who reads
           "found" believes the parent was already there and stops looking for
           the page this run has just made. */
        var parent = '';
        if (body.parentAction === 'created') {
            parent = ' Parent page created: "' + body.parentTitle + '" (id ' + body.parentPageId + ').';
        } else if (body.parentAction === 'found') {
            parent = ' Parent page found: "' + body.parentTitle + '" (id ' + body.parentPageId + ').';
        }
        /* A parent that was named and not applied is said plainly, and the line
           stops reading as a plain success. A silent mismatch is the worst outcome
           here: the run looks like it worked and the report is not where it was
           put. The three states are compared as strings on purpose - "unknown" is
           not a failure and is never reported as one. */
        var tone = 'good';
        if (body.parentApplied === 'false') {
            tone = 'bad';
            parent += ' PARENT NOT APPLIED. ' +
                (body.parentAppliedReason || 'The page was not moved under the parent page.');
        } else if (body.parentApplied === 'unknown') {
            tone = 'warn';
            parent += ' PARENT NOT CONFIRMED. ' +
                (body.parentAppliedReason || 'The position could not be read back.');
        }
        var status = say(tone, 'Page ' + body.action + ': "' + body.title + '" in ' + body.spaceKey +
            ' (version ' + version + '). Remark read: ' + body.remarkRead +
            ', carried over: ' + body.remarksCarried + ' of ' + body.remarksRead +
            ', without a matching item: ' + body.orphanedRemarks + '.' + parent);
        if (body.pageUrl) {
            var link = document.createElement('a');
            link.href = body.pageUrl;
            link.target = '_blank';
            link.rel = 'noopener';
            link.textContent = ' Open the page';
            status.appendChild(link);
        }
    }).catch(function (error) {
        button.disabled = false;
        fail('Request failed, nothing was written: ' + error);
    });
}
</script>
</script>
"""
    }
}

/* =============================================================================
 * Confluence page export - storage format and remark carry-over
 *
 * The report can be written into a Confluence page and that same page is updated
 * on every later run. Two rules make that safe enough to point at a production
 * space:
 *
 *   A page is only ever updated when it carries the marker below. A page this
 *   export did not create is never rewritten, whatever its title.
 *
 *   The Remark column belongs to the administrator, not to this export. It is
 *   read back from the existing page and carried over verbatim, and if that read
 *   fails for any reason NOTHING is written. A remark that cannot be read is a
 *   remark that must not be overwritten.
 *
 * Everything here is a pure function of its input, so the whole export is
 * exercised by the offline suite without a Confluence instance in sight.
 * ========================================================================== */

class Cx {

    /* Bumping this string orphans every existing page, which is the point: a page
     * written by an older, differently shaped export is not silently adopted. */
    static final String MARKER = "cfcon-project-config-export/1"

    static final String COL_PATH = "Path"
    static final String COL_ITEM = "Item"
    static final String COL_VALUE = "Value"
    static final String COL_STATE = "State"
    static final String COL_LINK = "In Jira"
    static final String COL_REMARK = "Remark"

    static final String DEFAULT_TITLE_PREFIX = "Jira project configuration - "

    static final int MAX_PAYLOAD_CHARS = 4000000
    static final int MAX_TITLE_CHARS = 255

    /* A deep scan of a large project runs into five figures of rows. Past this cap
     * the page would stop being readable and would start failing to save, so the
     * table is cut - and the cut is stated in the page itself, because a shortened
     * table that looks complete is worse than no table. */
    static final int MAX_ROWS = 5000

    static String title(String projectKey) {
        String key = projectKey == null ? "" : projectKey.trim()
        String candidate = DEFAULT_TITLE_PREFIX + (key.isEmpty() ? "unknown project" : key)
        return candidate.length() > MAX_TITLE_CHARS ? candidate.substring(0, MAX_TITLE_CHARS) : candidate
    }

    /* A request carries either the id of a picked parent or the title of one to
     * be created, never both. The refusal text is a constant so the offline
     * suite can assert on the contract rather than on a copy of the sentence. */
    static final String PARENT_BOTH = "The request carries a parent page id and a parent page title at the same time. " +
        "Exactly one of them is expected: the id of a page that was picked, or the title of a page to create. " +
        "Nothing is written."

    static String parentProblem(String parentId, String parentTitle, String reportTitle) {
        String id = parentId == null ? "" : parentId.trim()
        String parent = parentTitle == null ? "" : parentTitle.trim()
        String report = reportTitle == null ? "" : reportTitle.trim()
        if (!id.isEmpty() && !parent.isEmpty()) {
            return PARENT_BOTH
        }
        if (parent.length() > MAX_TITLE_CHARS) {
            return "The parent page title exceeds " + String.valueOf(MAX_TITLE_CHARS) + " characters."
        }
        /* A page cannot be its own parent, and Confluence titles are unique per
         * space, so the two titles being equal has no outcome that works. Caught
         * here rather than halfway through: otherwise the container page is
         * created first and the report write then fails on the duplicate title,
         * leaving a page behind that nothing was ever filed under. */
        if (!parent.isEmpty() && parent.equalsIgnoreCase(report)) {
            return "The parent page and the report page carry the same title \"" + parent +
                "\". A page cannot be its own parent. Nothing is written."
        }
        return ""
    }

    /* ---- Parent position ---------------------------------------------------- */

    /* What this run does about the position of the report page.
     *
     * A parent named in THIS run - picked from the search or created from a typed
     * title - is an instruction, and it is carried out even when the report page
     * already exists. That is the defect this replaces: the parent was applied on
     * the create branch only, so a second run rewrote the report and left it
     * wherever it was, while the response still reported the parent.
     *
     * The protection the old guard was built for is kept, narrowed to the case it
     * actually covers: a run that names no parent does not touch the position, so
     * a page an administrator moved by hand stays moved. */
    static final String MOVE_REQUESTED = "move"
    static final String MOVE_NOT_REQUESTED = "not-requested"
    static final String MOVE_ALREADY_THERE = "already-there"

    /* Pure decision, no instance needed, so the offline suite checks the rule and
     * not a run that happened to behave. An unknown current position resolves to
     * "move": carrying out the instruction is the safe direction, and only a
     * positive match skips. The skip exists so an unchanged repeat run does not
     * rewrite the page into the position it already holds. */
    static String moveDecision(String requestedParentId, String currentParentId) {
        String requested = requestedParentId == null ? "" : requestedParentId.trim()
        if (requested.isEmpty()) {
            return MOVE_NOT_REQUESTED
        }
        String current = currentParentId == null ? "" : currentParentId.trim()
        if (!current.isEmpty() && current.equals(requested)) {
            return MOVE_ALREADY_THERE
        }
        return MOVE_REQUESTED
    }

    /* Three states and no fourth. They are strings rather than a JSON boolean with
     * a special case, because a browser that writes if (!body.parentApplied) reads
     * a mixed boolean-or-string field as a success - which is exactly the silent
     * mismatch this measurement exists to prevent. */
    static final String PARENT_APPLIED_TRUE = "true"
    static final String PARENT_APPLIED_FALSE = "false"
    static final String PARENT_APPLIED_UNKNOWN = "unknown"

    /* The direct parent named by a Confluence content response, kept apart from the
     * case where no ancestors arrived at all. Ancestors run from the root of the
     * space downwards, so the direct parent is the last entry that names an id.
     *
     * measured=true with a null parentId means the response carried an ancestor
     * array and it was empty, so the page sits at the top level of the space - a
     * real measurement. measured=false means the response carried no ancestor
     * array at all, which measures nothing and must never be read as "the page has
     * no parent". rowsOf is deliberately not used here: it answers an absent key
     * and an empty array with the same empty list, and that is the one distinction
     * this method exists to make. */
    static Map<String, Object> innermostAncestor(Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("measured", Boolean.FALSE)
        out.put("parentId", null)
        if (content == null) {
            return out
        }
        Object node = content.get("ancestors")
        if (!(node instanceof List)) {
            return out
        }
        out.put("measured", Boolean.TRUE)
        List<Object> rows = (List<Object>) node
        for (int i = rows.size() - 1; i >= 0; i--) {
            Object row = rows.get(i)
            if (!(row instanceof Map)) {
                continue
            }
            Object id = ((Map<String, Object>) row).get("id")
            if (id != null && !String.valueOf(id).trim().isEmpty()) {
                out.put("parentId", String.valueOf(id).trim())
                return out
            }
        }
        return out
    }

    /* The verdict on the position, and it is a measurement or it is nothing.
     *
     * "true"    - the read-back answered and named the requested parent.
     * "false"   - the read-back answered and named something else, or nothing.
     * "unknown" - the read-back itself did not answer.
     *
     * A failed or empty read is never reported as a successful move, and never as
     * a failed one either: neither was measured, so neither is claimed. applied
     * stays null when this run named no parent, because then there is no question
     * to answer and the position was deliberately left alone.
     *
     * A move call that returned without throwing is a report about itself and is
     * deliberately not an input here. moveError only sharpens the wording of a
     * verdict that was measured either way. */
    static Map<String, Object> parentOutcome(String requestedParentId, boolean readBackOk,
                                             String actualParentId, String moveError) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("applied", null)
        out.put("reason", null)

        String requested = requestedParentId == null ? "" : requestedParentId.trim()
        if (requested.isEmpty()) {
            return out
        }

        String failure = moveError == null ? "" : moveError.trim()

        if (!readBackOk) {
            out.put("applied", PARENT_APPLIED_UNKNOWN)
            out.put("reason", "The report page was written, but its position could not be read back" +
                (failure.isEmpty() ? "" : " and the move reported \"" + failure + "\"") +
                ", so whether it sits under the parent page was not measured. Open the parent page and " +
                "check before relying on this run.")
            return out
        }

        String actual = actualParentId == null ? "" : actualParentId.trim()
        if (actual.equals(requested)) {
            out.put("applied", PARENT_APPLIED_TRUE)
            return out
        }

        out.put("applied", PARENT_APPLIED_FALSE)
        out.put("reason", "The report page was written, but it does not sit under the parent page that was " +
            "requested: it sits " + (actual.isEmpty() ? "at the top level of the space" : "under page " + actual) +
            "." + (failure.isEmpty() ? "" : " The move reported \"" + failure + "\"."))
        return out
    }

    /* Body of a parent page this export creates. Minimal on purpose: it says what
     * the page is for and where it came from, and it holds no report data, which
     * lives on the child page and is rewritten on every run. */
    static final String PARENT_BODY = "<p>Container page for the Jira project configuration export. " +
        "It was created by that export because the chosen parent page did not exist yet. " +
        "The report itself is the child page below; this page carries no report data and is never rewritten.</p>"

    /* Space picker paging. 20 pages of 200 covers every instance we have seen;
     * past that the picker reports itself truncated rather than showing a short
     * list that looks complete. */
    static final int SPACE_PAGE_SIZE = 200
    static final int MAX_SPACE_PAGES = 20

    /* Search stages. The page search asks Confluence for at most this many titles
     * and refuses a shorter term, so a single keystroke never pulls a whole space
     * back. The space list is filtered against the same minimum in the browser. */
    static final int SEARCH_LIMIT = 25
    static final int MIN_SEARCH_CHARS = 2

    /* Idle pause before a typed title is searched for. The parent field has no
     * button, so the search is what typing does - but not once per keystroke:
     * that is a call per character and a list that is rebuilt faster than it can
     * be read. */
    static final int SEARCH_IDLE_MS = 300

    /* A CQL string literal ends at a quote, and CQL documents "*" and "?" as
     * wildcards and "~" as an operator character, but documents no escaping rule
     * for literals. Everything that could change the meaning of a query is
     * therefore removed rather than escaped, and the caller appends the one
     * wildcard it wants - which also makes a leading wildcard impossible, as the
     * CQL text-search documentation requires. */
    static final String CQL_STRIP = "\"\\*?~\n\r"

    static String cqlTerm(String value) {
        if (value == null) {
            return ""
        }
        StringBuilder out = new StringBuilder()
        for (int i = 0; i < value.length(); i++) {
            String character = value.substring(i, i + 1)
            out.append(CQL_STRIP.contains(character) ? " " : character)
        }
        return out.toString().trim()
    }

    /* A space key is an identifier, not a search term, and must never go through
     * cqlTerm(): that sanitiser drops "~", so the personal space "~cfaysal"
     * silently became the key "cfaysal", which exists nowhere. Confluence then
     * answers zero hits and no error, and the mistake is invisible. The key is
     * therefore checked instead of cleaned, and a key that fails the check is
     * refused by name rather than searched for in a mangled form.
     *
     * The set below is a whitelist. A leading "~" marks a personal space; after
     * it stands the user key, which is not documented to be alphanumeric, so the
     * punctuation user keys are known to carry is admitted as well. Nothing in
     * the set can end a CQL string literal or act as a wildcard, which is what
     * cqlTerm() was protecting against in the first place. */
    static final String SPACE_KEY_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghijklmnopqrstuvwxyz" + "0123456789" + "_-.@"

    /* Empty on success, otherwise the reason in words. A reason rather than a
     * boolean, so the caller can name the offending value AND say what is wrong
     * with it - "invalid space key" sends an administrator guessing. */
    static String spaceKeyProblem(String value) {
        String key = value == null ? "" : value.trim()
        if (key.isEmpty()) {
            return "it is empty"
        }
        String body = key.startsWith("~") ? key.substring(1) : key
        if (body.isEmpty()) {
            return "a personal space key carries the user key after the tilde, \"~\" on its own is not a key"
        }
        for (int i = 0; i < body.length(); i++) {
            String character = body.substring(i, i + 1)
            if (SPACE_KEY_CHARS.contains(character)) {
                continue
            }
            if (character == "~") {
                return "only a leading tilde is allowed, and it marks a personal space"
            }
            if (character.trim().isEmpty()) {
                return "a space key contains no whitespace"
            }
            return "the character \"" + character + "\" is not allowed in a space key"
        }
        return ""
    }

    /* Confluence hands empty cells back self-closed after an editor round trip,
     * so both forms are matched. The self-closing alternative has to come first,
     * otherwise <td/> is consumed by the open-tag branch and swallows the row. */
    static final java.util.regex.Pattern TBODY = java.util.regex.Pattern.compile("(?s)<tbody[^>]*>(.*?)</tbody>")
    static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>")
    static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("(?s)<t[hd][^>]*/>|<t[hd][^>]*>(.*?)</t[hd]>")
    static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("<[^>]+>")

    static String plainText(String cellHtml) {
        if (cellHtml == null) {
            return ""
        }
        String value = TAG.matcher(cellHtml).replaceAll(" ")
        value = value.replace("&nbsp;", " ").replace("&#160;", " ").replace("\u00A0", " ")
        value = value.replace("&lt;", "<").replace("&gt;", ">")
        value = value.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        value = value.replace("&amp;", "&")
        return value.replaceAll("\\s+", " ").trim()
    }

    /* Whitespace, a non-breaking space and the wrappers an editor leaves behind
     * carry no remark. Anything else that is still a tag does: a status
     * lozenge, an image, an emoticon, a link. */
    static final java.util.regex.Pattern LAYOUT_TAG =
        java.util.regex.Pattern.compile("(?i)</?(?:p|br|div|span)(?:\\s[^>]*)?/?>")

    /* A cell is empty only when it holds neither text nor element content.
     * Deciding that on the plain text alone dropped every cell whose markup
     * carries no text node, and the row was not even counted as read. */
    static boolean isEmptyCell(String cellHtml) {
        if (cellHtml == null) {
            return true
        }
        if (!plainText(cellHtml).isEmpty()) {
            return false
        }
        String rest = LAYOUT_TAG.matcher(cellHtml).replaceAll("")
        rest = rest.replace("&nbsp;", "").replace("&#160;", "").replace("\u00A0", "")
        return rest.trim().isEmpty()
    }

    /* The placeholder written into a row that carries no remark: a grey status
     * lozenge the administrator edits instead of building one. The Confluence
     * status macro takes a colour and a title and carries no body. */
    static final String REMARK_SEED =
        "<ac:structured-macro ac:name=\"status\" ac:schema-version=\"1\">" +
        "<ac:parameter ac:name=\"colour\">Grey</ac:parameter>" +
        "<ac:parameter ac:name=\"title\">TBD</ac:parameter>" +
        "</ac:structured-macro>"

    static final java.util.regex.Pattern MACRO_ID =
        java.util.regex.Pattern.compile("\\s+ac:macro-id=\"[^\"]*\"")

    /* The seed is this export's own markup, never an administrator's note, so it
     * reads back as no remark. An editor round trip stamps a macro-id onto
     * every macro and may wrap the cell in a paragraph, so the comparison is made
     * on the normalised form. Change the colour or the title and it is a remark
     * again, carried over verbatim like any other. */
    static boolean isRemarkSeed(String cellHtml) {
        if (cellHtml == null) {
            return false
        }
        String value = cellHtml.trim()
        if (value.startsWith("<p>") && value.endsWith("</p>")) {
            value = value.substring(3, value.length() - 4).trim()
        }
        value = MACRO_ID.matcher(value).replaceAll("")
        return value.replaceAll(">\\s+<", "><").trim() == REMARK_SEED
    }

    static List<String> cellsOf(String rowHtml) {
        List<String> cells = new ArrayList<String>()
        if (rowHtml == null) {
            return cells
        }
        java.util.regex.Matcher matcher = CELL.matcher(rowHtml)
        while (matcher.find()) {
            String inner = matcher.group(1)
            cells.add(inner == null ? "" : inner)
        }
        return cells
    }

    static int headerIndex(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (plainText(header.get(i)).equalsIgnoreCase(name)) {
                return i
            }
        }
        return -1
    }

    /* Exception class plus message. Both the remark read and the write path
     * report a failure in exactly this wording. */
    static String errorDetail(Throwable error) {
        String detail = error.getClass().getSimpleName()
        String message = error.getMessage()
        if (message != null && !message.trim().isEmpty()) {
            detail = detail + ": " + message.trim()
        }
        return detail
    }

    /* Reads every remark table on the page, not just the first one: the
     * orphaned-remark table is a second table and its notes have to survive
     * as well. Anything unexpected is FAILED, never an empty success. */
    static RemarkRead parseRemarks(String storage) {
        RemarkRead read = new RemarkRead()

        try {
            if (storage == null || storage.trim().isEmpty()) {
                return read.fail("The existing page has an empty body. It was not produced by this export, so it is not overwritten.")
            }
            if (!storage.contains(MARKER)) {
                return read.fail("The existing page does not carry the export marker \"" + MARKER + "\". It was not produced by this export, so it is not overwritten.")
            }

            int tablesMatched = 0
            java.util.regex.Matcher bodyMatcher = TBODY.matcher(storage)

            while (bodyMatcher.find()) {
                List<String> rows = new ArrayList<String>()
                java.util.regex.Matcher rowMatcher = ROW.matcher(bodyMatcher.group(1))
                while (rowMatcher.find()) {
                    rows.add(rowMatcher.group(1))
                }
                if (rows.isEmpty()) {
                    continue
                }

                List<String> header = cellsOf(rows.get(0))
                int keyIndex = headerIndex(header, COL_PATH)
                int remarkIndex = headerIndex(header, COL_REMARK)
                if (keyIndex < 0 || remarkIndex < 0) {
                    continue
                }
                tablesMatched++

                int required = Math.max(keyIndex, remarkIndex) + 1
                for (int i = 1; i < rows.size(); i++) {
                    List<String> cells = cellsOf(rows.get(i))
                    if (cells.isEmpty()) {
                        continue
                    }
                    if (cells.size() < required) {
                        return read.fail("Row " + String.valueOf(i) + " of a remark table carries " + String.valueOf(cells.size()) +
                            " cell(s) where " + String.valueOf(required) + " are needed. The table structure was changed; nothing is written.")
                    }

                    String key = plainText(cells.get(keyIndex))
                    String remarkHtml = cells.get(remarkIndex).trim()
                    if (key.isEmpty() || isEmptyCell(remarkHtml) || isRemarkSeed(remarkHtml)) {
                        continue
                    }
                    if (read.remarks.containsKey(key)) {
                        return read.fail("Path \"" + key + "\" carries more than one remark on the existing page. That is ambiguous; nothing is written.")
                    }
                    read.remarks.put(key, remarkHtml)
                }
            }

            if (tablesMatched == 0) {
                return read.fail("No table with the columns \"" + COL_PATH + "\" and \"" + COL_REMARK +
                    "\" was found on the existing page. The read is inconclusive; nothing is written.")
            }

            read.outcome = RemarkRead.PARSED
            return read
        } catch (Throwable error) {
            return read.fail("The remark read failed (" + errorDetail(error) + "); nothing is written.")
        }
    }

    /* ---- Payload accessors (the POST body is JSON, so nothing is assumed) --- */

    static String str(Map<String, Object> source, String key, String fallback) {
        Object raw = source == null ? null : source.get(key)
        if (raw == null) {
            return fallback
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? fallback : value
    }

    static long lng(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        if (raw instanceof Number) {
            return ((Number) raw).longValue()
        }
        if (raw == null) {
            return 0L
        }
        try {
            return Long.parseLong(raw.toString().trim())
        } catch (NumberFormatException ignored) {
            return 0L
        }
    }

    /* A payload figure, formatted the way the report itself formats it. */
    static String numberOf(Map<String, Object> source, String key, Locale locale) {
        return Pc.number(Long.valueOf(lng(source, key)), locale)
    }

    static boolean flag(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue()
        }
        return raw != null && raw.toString().trim().equalsIgnoreCase("true")
    }

    static Map<String, Object> sub(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        return raw instanceof Map ? copyMap((Map<?, ?>) raw) : new LinkedHashMap<String, Object>()
    }

    static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        if (source == null) {
            return result
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object rawKey = entry.getKey()
            if (rawKey != null) {
                result.put(rawKey.toString(), entry.getValue())
            }
        }
        return result
    }

    static List<Map<String, Object>> rowsOf(Map<String, Object> source, String key) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>()
        Object raw = source == null ? null : source.get(key)
        if (!(raw instanceof List)) {
            return result
        }
        for (Object element : (List<?>) raw) {
            if (element instanceof Map) {
                result.add(copyMap((Map<?, ?>) element))
            }
        }
        return result
    }

    /* ---- Rendering ---------------------------------------------------------- */

    /* The payload is the report the GET branch serialised for this run, so the page
     * shows exactly the tree the administrator saw. It travels through the browser,
     * which means an administrator could tamper with it - the same administrator
     * who may edit any page they can reach anyway. Everything is escaped on the way
     * into storage format, and the remark carry-over is unaffected by it: remarks
     * come from the existing page and are read there. */
    static ExportOutcome render(Map<String, Object> request, RemarkRead read) {
        ExportOutcome outcome = new ExportOutcome()
        outcome.remarksRead = read == null ? 0 : read.remarks.size()

        Map<String, Object> project = sub(request, "project")
        Map<String, Object> instance = sub(request, "instance")
        Map<String, Object> totals = sub(request, "totals")

        StringBuilder out = new StringBuilder()

        out.append("<p>Complete configuration of project <strong>")
        out.append(esc(str(project, "name", "unknown"))).append("</strong> (")
        out.append(esc(str(project, "key", "?"))).append(") on ")
        out.append(esc(str(instance, "title", "this instance")))
        out.append(", Jira ").append(esc(str(instance, "jiraVersion", "unknown version")))
        out.append(". Generated ").append(esc(str(request, "generatedAt", "unknown")))
        out.append(" by the project configuration report v")
        out.append(esc(str(request, "reportVersion", "?"))).append(".</p>")

        out.append("<p>")
        out.append(esc(str(totals, "nodes", "0"))).append(" configuration items, ")
        out.append(esc(str(totals, "unreadable", "0"))).append(" of them unreadable, ")
        out.append(esc(str(totals, "unlinked", "0"))).append(" without a deep link. ")
        out.append("An unreadable item is not an empty one: it is an item whose configuration ")
        out.append("could not be read, and it is marked as such in the State column.</p>")

        out.append("<p>The <strong>").append(esc(COL_REMARK)).append("</strong> column belongs to you. ")
        out.append("It is read back and carried over on every later run of this export. ")
        out.append("Everything else on this page is overwritten each time.</p>")

        /* Rows first, so the truncation notice can be placed above the table it
         * applies to rather than below it, where it would be read too late. */
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>()
        for (Map<String, Object> section : rowsOf(request, "sections")) {
            flatten(section, "", rows)
        }

        boolean truncated = rows.size() > MAX_ROWS
        if (truncated) {
            outcome.warnings.add("The table was cut at " + String.valueOf(MAX_ROWS) + " of " +
                String.valueOf(rows.size()) + " rows.")
            out.append("<p><strong>This table is not complete.</strong> It carries the first ")
            out.append(String.valueOf(MAX_ROWS)).append(" of ").append(String.valueOf(rows.size()))
            out.append(" configuration items. The rest is in the report itself and in its ")
            out.append("CSV and JSON output; it is missing here, not missing from the project.</p>")
            rows = rows.subList(0, MAX_ROWS)
        }

        Set<String> used = new LinkedHashSet<String>()
        out.append("<table><tbody>")
        out.append(headerRow([COL_PATH, COL_ITEM, COL_VALUE, COL_STATE, COL_LINK, COL_REMARK]))
        for (Map<String, Object> row : rows) {
            String path = str(row, "path", "")
            String remark = read == null ? null : read.remarks.get(path)
            if (remark != null) {
                used.add(path)
                outcome.remarksCarried++
            }
            out.append("<tr>")
            out.append(cell(esc(path)))
            out.append(cell(esc(str(row, "label", ""))))
            out.append(cell(esc(str(row, "value", ""))))
            out.append(cell(esc(stateText(str(row, "state", "read")))))
            out.append(cell(linkCell(str(row, "deepLink", null), str(row, "linkNote", null))))
            out.append(cell(remark == null ? REMARK_SEED : remark))
            out.append("</tr>")
        }
        out.append("</tbody></table>")

        /* A remark whose row is gone is not deleted. The configuration it commented
         * on may come back, and even if it does not, an administrator's own words
         * are not this export's to discard. */
        List<String> orphans = new ArrayList<String>()
        if (read != null) {
            for (Map.Entry<String, String> entry : read.remarks.entrySet()) {
                if (!used.contains(entry.getKey())) {
                    orphans.add(entry.getKey())
                }
            }
        }
        if (!orphans.isEmpty()) {
            outcome.orphanKeys.addAll(orphans)
            out.append("<h2>Remarks without a matching item</h2>")
            out.append("<p>These remarks were carried over from the previous version of this page, ")
            out.append("but the configuration item they belong to is no longer in the project. ")
            out.append("They are kept here rather than dropped. Delete a row to be rid of it.</p>")
            out.append("<table><tbody>")
            out.append(headerRow([COL_PATH, COL_REMARK]))
            for (String key : orphans) {
                out.append("<tr>").append(cell(esc(key)))
                out.append(cell(read.remarks.get(key))).append("</tr>")
            }
            out.append("</tbody></table>")
        }

        List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>()
        Object rawDiagnostics = request == null ? null : request.get("diagnostics")
        if (rawDiagnostics instanceof List && !((List) rawDiagnostics).isEmpty()) {
            out.append("<h2>Suppressed reads</h2>")
            out.append("<p>Each entry is a read that failed. It is not an absence of configuration.</p><ul>")
            for (Object entry : (List) rawDiagnostics) {
                out.append("<li>").append(esc(String.valueOf(entry))).append("</li>")
            }
            out.append("</ul>")
        }

        out.append("<p><em>").append(esc(MARKER)).append("</em></p>")

        outcome.storage = out.toString()
        return outcome
    }

    /* The path is the carry-over key, so it has to be stable across runs. It is
     * built from labels rather than from ids because an administrator who renames a
     * scheme expects the remark to follow the name they see, and because the same
     * report has to work on an instance where ids differ. */
    static void flatten(Map<String, Object> node, String parentPath, List<Map<String, Object>> rows) {
        if (node == null) {
            return
        }
        String label = str(node, "label", "")
        String path = parentPath.isEmpty() ? label : parentPath + " > " + label
        Map<String, Object> row = new LinkedHashMap<String, Object>()
        row.put("path", path)
        row.put("label", label)
        row.put("value", str(node, "value", ""))
        row.put("state", str(node, "state", "read"))
        row.put("deepLink", str(node, "deepLink", null))
        row.put("linkNote", str(node, "linkNote", null))
        rows.add(row)
        for (Map<String, Object> child : rowsOf(node, "children")) {
            flatten(child, path, rows)
        }
    }

    static String stateText(String state) {
        if ("unreadable".equals(state)) {
            return "could not be read"
        }
        if ("absent".equals(state)) {
            return "not configured"
        }
        if ("truncated".equals(state)) {
            return "shortened"
        }
        return ""
    }

    static String linkCell(String deepLink, String linkNote) {
        if (deepLink != null && !deepLink.trim().isEmpty()) {
            return "<a href=\"" + esc(deepLink) + "\">open</a>"
        }
        if (linkNote != null && !linkNote.trim().isEmpty()) {
            return esc(linkNote)
        }
        return ""
    }

    static String headerRow(List<String> names) {
        StringBuilder out = new StringBuilder("<tr>")
        for (String name : names) {
            out.append("<th>").append(esc(name)).append("</th>")
        }
        return out.append("</tr>").toString()
    }

    static String cell(String html) {
        return "<td>" + (html == null || html.isEmpty() ? "" : html) + "</td>"
    }

    /* Storage format is XHTML, so an unescaped angle bracket from a scheme name is
     * not a cosmetic problem: it produces a page Confluence refuses to save. */
    static String esc(Object value) {
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
}

/* What a remark read found, and whether writing is allowed at all. */
class RemarkRead {

    static final String NONE = "none"
    static final String PARSED = "parsed"
    static final String FAILED = "failed"

    String outcome = NONE
    String reason
    String pageId
    int pageVersion

    Map<String, String> remarks = new LinkedHashMap<String, String>()

    /* The single gate every write path has to pass. FAILED never gets through. */
    boolean isWriteAllowed() {
        return outcome == NONE || outcome == PARSED
    }

    RemarkRead fail(String why) {
        outcome = FAILED
        reason = why
        remarks.clear()
        return this
    }

    Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        result.put("outcome", outcome)
        result.put("reason", reason)
        result.put("remarks", Integer.valueOf(remarks.size()))
        result.put("pageId", pageId)
        result.put("pageVersion", Integer.valueOf(pageVersion))
        return result
    }
}

/* Rendered storage format plus what happened to the carried-over remarks. */
class ExportOutcome {
    String storage
    int remarksRead
    int remarksCarried
    List<String> orphanKeys = new ArrayList<String>()
    List<String> warnings = new ArrayList<String>()
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
        Nd node = Nd.of("issueTypeScheme", "Issue types")
        /* The one link shape in this report that could not be evidenced. Rather
         * than inventing a parameter for ManageIssueTypeSchemes, the node stays
         * unlinked and says so, and its issue types link to the project page that
         * does have an evidenced address. */
        node.link(links.issueTypeSchemes(), links.issueTypeSchemeUnavailableNote())
            .linkAs("open issue type schemes")
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
            self.label = "Issue types: " + Pc.orNa(scheme.getName())
            self.ident(scheme.getId())
            if (Pc.text(scheme.getDescription()) != null) {
                self.val(scheme.getDescription())
            }

            IssueType defaultType = null
            try {
                defaultType = manager.getDefaultIssueType(project)
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
        Nd node = Nd.of("issueTypeScreenScheme", "Screens")
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
            self.label = "Screens: " + Pc.orNa(scheme.getName())
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
        Nd node = Nd.of("fieldConfigurationScheme", "Fields")
        node.link(links.projectFields(project.getKey()), null)
        return guard(node) { Nd self ->
            FieldLayoutManager manager = ComponentAccessor.getComponent(FieldLayoutManager)
            if (manager == null) {
                self.failed("FieldLayoutManager is not available in this instance.")
                return
            }
            FieldConfigurationScheme scheme = manager.getFieldConfigurationScheme(project)
            if (scheme == null) {
                self.label = "Fields: System Default Field Configuration"
                self.val("No scheme is associated, so every issue type uses the system default.")
            } else {
                self.label = "Fields: " + Pc.orNa(scheme.getName())
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
        Nd node = Nd.of("customFields", "Custom fields")
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
        Nd node = Nd.of("workflowScheme", "Workflows")
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
            self.label = "Workflows: " + Pc.orNa(scheme.getName())
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
                /* The mapping carries the default workflow under a null issue type
                 * id. That is the same layer the block above already reported by
                 * name, so rendering it again would produce a second, nameless
                 * "issue type null" row for a layer that is neither nameless nor a
                 * second one. */
                if (issueTypeId == null) {
                    continue
                }
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
            Status status = workflow.getLinkedStatus((StepDescriptor) step)
            return status == null ? null : status.getName()
        } catch (Exception ignored) {
            return null
        }
    }

    /* ---- 6. permission scheme --------------------------------------------- */

    Nd permissionScheme() {
        Nd node = Nd.of("permissionScheme", "Permissions")
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
            self.label = "Permissions: " + Pc.orNa(scheme.getName())
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
        Nd node = Nd.of("notificationScheme", "Notifications")
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
            self.label = "Notifications: " + Pc.orNa(scheme.getName())
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
        Nd node = Nd.of("issueSecurityScheme", "Issue security")
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
            self.label = "Issue security: " + Pc.orNa(scheme.getName())
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
                /* HAPI rather than ComponentAccessor, which is what the ScriptRunner
                 * editor asks for here. Both getByKey and getByName return the same
                 * ApplicationUser, so this is a straight substitution and not a change
                 * of behaviour: a scheme grant stores either a user key or a user
                 * name depending on its age, so both are still tried, in that order. */
                ApplicationUser user = Users.getByKey(parameter)
                if (user == null) {
                    user = Users.getByName(parameter)
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
        Nd node = Nd.of("projectRoles", "Roles")
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
        /* No address for the Service Management project settings could be evidenced
         * out of the shipped plugins, so this node names the path rather than
         * pointing at a URL that may not exist on a given version. */
        node.link(null, "Project settings > Service Management, inside the project itself.")
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
    boolean expandAll = depth == "full"

    Map<String, Object> activeParams = [
        project: projectKey,
        format: format == "html" ? null : format,
        depth: expandAll ? "full" : null,
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

    Nd details = report.section("projectDetails", "Details")
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
    return Http.ok(responseClass, Render.html(report, activeParams, expandAll), Http.HTML)
}

/* =============================================================================
 * Confluence application link transport
 *
 * The only outbound path in this file. A Jira JVM holds no Confluence type, so
 * the space list, the existence check and the write all travel over the Jira
 * application link. Every helper here returns a result map and never throws:
 * a failed call must arrive at the caller as a failure, never as an empty
 * answer that a later branch could mistake for "nothing there".
 * ========================================================================== */

/* The concrete Confluence application type, resolved at runtime instead of being
 * imported. The applinks API documents
 * com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType as a
 * public interface extending ApplicationType since applinks 3.0, and that is what
 * makes the typed getApplicationLinks(Class) and getPrimaryApplicationLink(Class)
 * usable here instead of a class-name string comparison. Whether ScriptRunner
 * exposes that sub-package on a given instance is NOT documented, so a missing
 * class degrades to the old simple-name match rather than breaking the endpoint -
 * the same runtime resolution the JAX-RS Response class uses above. */
Class<? extends ApplicationType> confluenceApplicationType() {
    try {
        return (Class<? extends ApplicationType>)
            Class.forName("com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType")
    } catch (Throwable ignored) {
        return null
    }
}

/* Reading one link, each getter guarded on its own: a half-configured link must
 * shorten the label, not abort the list. */
String confluenceLinkId(ApplicationLink link) {
    try {
        return link == null || link.getId() == null ? null : link.getId().get()
    } catch (Exception ignored) {
        return null
    }
}

String confluenceLinkName(ApplicationLink link) {
    try {
        String name = link == null ? null : link.getName()
        return name == null || name.trim().isEmpty() ? "Confluence" : name
    } catch (Exception ignored) {
        return "Confluence"
    }
}

String confluenceLinkUrl(ApplicationLink link) {
    try {
        Object url = link == null ? null : link.getDisplayUrl()
        return url == null ? null : url.toString()
    } catch (Exception ignored) {
        return null
    }
}

/* Every Confluence application link this Jira has, primary first, then by name.
 *
 * This used to return the first link whose type simple-name matched, silently. On
 * an instance with two Confluence links the export could write to the wrong site
 * with no way to tell, so the administrator now picks the target and the picked id
 * travels with every later stage of the same export.
 *
 * The ApplicationId is passed inside one export cycle only and is NEVER persisted.
 * The applinks documentation states on getId() that the id changes when an
 * administrator upgrades the remote application to use Unified Application Links,
 * and that a plugin storing the id has to listen for ApplicationLinksIDChangedEvent.
 * Nothing here outlives the request, so no listener is needed - do not start
 * storing this id without adding that listener. */
Map<String, Object> confluenceApplicationLinks() {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    List<ApplicationLink> links = new ArrayList<ApplicationLink>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("links", links)
    result.put("primaryId", null)
    result.put("typed", Boolean.FALSE)
    result.put("seen", Integer.valueOf(-1))
    result.put("seenTypes", "")

    ApplicationLinkService service = null
    try {
        service = ComponentLocator.getComponent(ApplicationLinkService)
    } catch (Exception error) {
        result.put("error", "The application link service could not be read (" + Cx.errorDetail(error) + ").")
        return result
    }
    if (service == null) {
        result.put("error", "The application link service is not available in this Jira instance.")
        return result
    }

    Class<? extends ApplicationType> type = confluenceApplicationType()
    result.put("typed", Boolean.valueOf(type != null))
    String primaryId = null

    try {
        if (type != null) {
            for (ApplicationLink link : service.getApplicationLinks(type)) {
                if (link != null) {
                    links.add(link)
                }
            }
            try {
                primaryId = confluenceLinkId(service.getPrimaryApplicationLink(type))
            } catch (Exception ignored) {
                primaryId = null
            }
        }

        /* The typed lookup only works if this script and the applinks plugin resolve
         * the very same ConfluenceApplicationType class. Across OSGi class loaders
         * that is not guaranteed, and a mismatch returns an EMPTY LIST rather than an
         * error, which reads as "no link configured" on an instance that plainly has
         * one. Measured on jira-test 2026-08-22. So whenever the typed lookup finds
         * nothing, fall back to the untyped scan that confluence-addon-analysis.groovy
         * has used in production for years. The reported flag follows the path that
         * actually produced the list, not the one that was attempted. */
        if (links.isEmpty()) {
            result.put("typed", Boolean.FALSE)
            primaryId = null
            int seen = 0
            Set<String> seenTypes = new TreeSet<String>()
            for (ApplicationLink link : service.getApplicationLinks()) {
                if (link == null) {
                    continue
                }
                seen++
                String typeName = link.getType() == null ? "unknown" : link.getType().getClass().getSimpleName()
                seenTypes.add(typeName)
                if (typeName == "ConfluenceApplicationTypeImpl" || typeName.contains("Confluence")) {
                    links.add(link)
                }
            }
            /* What the instance actually offered, so a refusal is a measurement and
             * not a dead end. Without this the administrator is told to create a link
             * that may already exist. */
            result.put("seen", Integer.valueOf(seen))
            result.put("seenTypes", seenTypes.isEmpty() ? "none" : seenTypes.join(", "))
        }
    } catch (Exception error) {
        result.put("error", "The Confluence application links could not be listed (" +
            Cx.errorDetail(error) + ").")
        return result
    }

    /* Without the typed lookup the primary comes from the link itself, which the
     * applinks API documents as isPrimary(). */
    if (primaryId == null) {
        for (ApplicationLink link : links) {
            boolean primary = false
            try {
                primary = link.isPrimary()
            } catch (Exception ignored) {
                primary = false
            }
            if (primary) {
                primaryId = confluenceLinkId(link)
                break
            }
        }
    }

    final String preselected = primaryId
    links.sort { ApplicationLink a, ApplicationLink b ->
        boolean aPrimary = preselected != null && preselected == confluenceLinkId(a)
        boolean bPrimary = preselected != null && preselected == confluenceLinkId(b)
        if (aPrimary != bPrimary) {
            return aPrimary ? -1 : 1
        }
        return confluenceLinkName(a).compareToIgnoreCase(confluenceLinkName(b))
    }

    result.put("ok", Boolean.TRUE)
    result.put("primaryId", primaryId)
    return result
}

/* The target the administrator picked, plus its request factory. The id is matched
 * inside the Confluence list rather than handed to getApplicationLink(ApplicationId),
 * so a request naming a link of any other type resolves to nothing instead of to a
 * foreign target. Every stage - space search, page search and write - resolves the
 * same way, so they cannot disagree about where they are pointing. */
Map<String, Object> confluenceTarget(List<ApplicationLink> links, String applicationLinkId) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("link", null)
    result.put("factory", null)

    if (applicationLinkId == null || applicationLinkId.trim().isEmpty()) {
        result.put("error", "No target Confluence was selected, so there is nowhere to look and nothing is written.")
        return result
    }

    ApplicationLink link = null
    for (ApplicationLink candidate : links) {
        String id = confluenceLinkId(candidate)
        if (id != null && id == applicationLinkId) {
            link = candidate
            break
        }
    }
    if (link == null) {
        result.put("error", "The selected Confluence application link is not among this instance's Confluence links. " +
            "Reopen the export and pick the target again. Nothing is written.")
        return result
    }

    ApplicationLinkRequestFactory factory = null
    try {
        factory = link.createAuthenticatedRequestFactory()
    } catch (Exception error) {
        result.put("error", "The Confluence application link \"" + confluenceLinkName(link) + "\" did not hand out an " +
            "authenticated request factory (" + Cx.errorDetail(error) + "). Nothing is written.")
        return result
    }
    if (factory == null) {
        result.put("error", "The Confluence application link \"" + confluenceLinkName(link) + "\" did not hand out an " +
            "authenticated request factory. Nothing is written.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("link", link)
    result.put("factory", factory)
    return result
}

/* Browse URL of a written page, built from the link's own address. */
String confluencePageUrl(ApplicationLink link, String pageId) {
    if (link == null || pageId == null || pageId.trim().isEmpty()) {
        return null
    }
    Object base = null
    try {
        base = link.getDisplayUrl()
    } catch (Exception ignored) {
        base = null
    }
    if (base == null) {
        try {
            base = link.getRpcUrl()
        } catch (Exception ignored) {
            return null
        }
    }
    if (base == null) {
        return null
    }
    String prefix = base.toString()
    while (prefix.endsWith("/")) {
        prefix = prefix.substring(0, prefix.length() - 1)
    }
    return prefix + "/pages/viewpage.action?pageId=" + pageId
}

/* One authenticated call. The three failure modes an administrator actually
 * meets are separated: no factory, no authorisation for the impersonated user,
 * and a refusal from Confluence itself. */
Map<String, Object> confluenceCall(ApplicationLinkRequestFactory factory, Request.MethodType method, String url, String jsonBody) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("json", null)

    if (factory == null) {
        result.put("error", "The Confluence application link exists but did not hand out an authenticated request factory.")
        return result
    }

    String raw = null
    try {
        def request = factory.createRequest(method, url)
        request.addHeader("Accept", "application/json")
        if (jsonBody != null && !jsonBody.isEmpty()) {
            request.addHeader("Content-Type", "application/json")
            request.setRequestBody(jsonBody)
        }
        raw = request.execute()
    } catch (CredentialsRequiredException ignored) {
        result.put("error", "Confluence did not accept the impersonated call: this Jira user has not authorised the " +
            "Confluence application link yet. Authorise it once from a page that offers the link's authentication " +
            "prompt, then run the export again.")
        return result
    } catch (Exception error) {
        String detail = Cx.errorDetail(error)
        String message = "The call to Confluence failed for " + url + " (" + detail + ")."
        /* A 401 over an application link is almost never a wrong path. This factory
         * impersonates the calling Jira user on the Confluence side, so the usual
         * cause is that this user has no account there, or none with permission.
         * Measured on a customer instance 2026-08-22: link found and preselected,
         * space call 401, user did not exist in Confluence. Saying so turns a dead
         * end into the next step. */
        if (detail != null && detail.contains("401")) {
            message = message + " A 401 here means Confluence refused the call, not that the address was wrong. " +
                "The export calls Confluence as the Jira user who runs it, so check that this user exists in " +
                "Confluence and may read spaces there."
        }
        result.put("error", message)
        return result
    }

    if (raw == null || raw.trim().isEmpty()) {
        result.put("error", "Confluence returned an empty response for " + url + ".")
        return result
    }

    Object parsed = null
    try {
        parsed = new JsonSlurper().parseText(raw)
    } catch (Exception error) {
        result.put("error", "Confluence returned a response that is not JSON for " + url +
            " (" + Cx.errorDetail(error) + ").")
        return result
    }
    if (!(parsed instanceof Map)) {
        result.put("error", "Confluence returned a JSON value that is not an object for " + url + ".")
        return result
    }

    /* A Confluence REST refusal carries statusCode and message in the body. It is
     * not documented that execute() throws on a 4xx, so the body is inspected as
     * well - a refusal must never pass as a successful empty answer. */
    Map<String, Object> json = Cx.copyMap((Map<?, ?>) parsed)
    Object statusCode = json.get("statusCode")
    if (statusCode instanceof Number && ((Number) statusCode).intValue() >= 400) {
        result.put("error", "Confluence refused the call to " + url + " with HTTP " +
            String.valueOf(((Number) statusCode).intValue()) + ": " + Cx.str(json, "message", "no message"))
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("json", json)
    return result
}

/* Every current space, paged. GET /rest/api/space is documented with start and
 * limit; the loop stops when a page comes back shorter than the page size, and
 * the page cap keeps a changed paging contract from turning into an endless
 * loop. A truncated list says so rather than looking complete. */
Map<String, Object> confluenceSpaces(ApplicationLinkRequestFactory factory) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    List<Map<String, Object>> spaces = new ArrayList<Map<String, Object>>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("spaces", spaces)
    result.put("truncated", Boolean.FALSE)

    Set<String> seen = new HashSet<String>()
    int start = 0

    for (int page = 0; page < Cx.MAX_SPACE_PAGES; page++) {
        String url = "/rest/api/space?status=current&limit=" + String.valueOf(Cx.SPACE_PAGE_SIZE) +
            "&start=" + String.valueOf(start)
        Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET, url, null)
        if (call.get("ok") != Boolean.TRUE) {
            result.put("error", call.get("error"))
            return result
        }

        List<Map<String, Object>> rows = Cx.rowsOf((Map<String, Object>) call.get("json"), "results")
        for (Map<String, Object> row : rows) {
            String key = Cx.str(row, "key", "")
            if (key.isEmpty() || !seen.add(key)) {
                continue
            }
            Map<String, Object> space = new LinkedHashMap<String, Object>()
            space.put("key", key)
            space.put("name", Cx.str(row, "name", key))
            spaces.add(space)
        }

        if (rows.size() < Cx.SPACE_PAGE_SIZE) {
            result.put("ok", Boolean.TRUE)
            return result
        }
        start += Cx.SPACE_PAGE_SIZE
    }

    result.put("ok", Boolean.TRUE)
    result.put("truncated", Boolean.TRUE)
    return result
}

/* The parent page, resolved and located. A parent that does not exist or sits in
 * another space is refused before anything is written. */
Map<String, Object> confluenceParentPage(ApplicationLinkRequestFactory factory, String parentId) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("spaceKey", null)
    result.put("title", null)

    Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET,
        "/rest/api/content/" + parentId + "?expand=space", null)
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", "The parent page " + parentId + " could not be read from Confluence: " +
            String.valueOf(call.get("error")))
        return result
    }

    Map<String, Object> json = (Map<String, Object>) call.get("json")
    String id = Cx.str(json, "id", "")
    if (id.isEmpty()) {
        result.put("error", "There is no Confluence page with the ID " + parentId + ".")
        return result
    }

    String parentSpace = Cx.str(Cx.sub(json, "space"), "key", "")
    if (parentSpace.isEmpty()) {
        result.put("error", "The space of the parent page " + parentId + " could not be read, so its location " +
            "cannot be confirmed. Nothing is written.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("spaceKey", parentSpace)
    result.put("title", Cx.str(json, "title", ""))
    return result
}

/* Parent page candidates, searched by title inside one space, so the administrator
 * never has to look up a raw page id. GET /rest/api/content/search takes a cql
 * query; the CQL reference documents the fields type, space and title, documents
 * "~" (CONTAINS) on title, and documents "*" as the multi-character wildcard that
 * must not be the first character of a term. The answer is the same paginated
 * content collection the existence check already reads, so results is parsed the
 * same way. An empty result set is an empty result set; every failure carries its
 * reason instead. */
Map<String, Object> confluenceSearchPages(ApplicationLinkRequestFactory factory, String spaceKey, String query) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("pages", pages)
    result.put("truncated", Boolean.FALSE)

    /* The space key is validated, not sanitised. It used to run through cqlTerm()
     * exactly like the search term below, which cost every personal space its
     * leading tilde and turned the search into one over a space that does not
     * exist - answered with zero hits and no error. Only the title is a search
     * term and only the title is still cleaned. */
    String space = spaceKey == null ? "" : spaceKey.trim()
    String spaceProblem = Cx.spaceKeyProblem(space)
    if (!spaceProblem.isEmpty()) {
        result.put("error", "The space key \"" + String.valueOf(spaceKey) + "\" cannot be searched in: " +
            spaceProblem + ".")
        return result
    }
    String term = Cx.cqlTerm(query)
    if (term.isEmpty()) {
        result.put("error", "The search term holds nothing that can be searched for.")
        return result
    }

    String cql = "type=page and space=\"" + space + "\" and title~\"" + term + "*\""
    String url = "/rest/api/content/search?limit=" + String.valueOf(Cx.SEARCH_LIMIT) +
        "&cql=" + URLEncoder.encode(cql, "UTF-8")
    Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET, url, null)
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", "The page search in \"" + spaceKey + "\" failed: " + String.valueOf(call.get("error")))
        return result
    }

    List<Map<String, Object>> rows = Cx.rowsOf((Map<String, Object>) call.get("json"), "results")
    /* The request carries a limit, so a full page of hits means there may be more.
     * Saying so is the point: a silently cut list reads as "that is everything". */
    if (rows.size() >= Cx.SEARCH_LIMIT) {
        result.put("truncated", Boolean.TRUE)
    }
    for (Map<String, Object> row : rows) {
        String id = Cx.str(row, "id", "")
        String title = Cx.str(row, "title", "")
        if (id.isEmpty() || title.isEmpty()) {
            continue
        }
        Map<String, Object> page = new LinkedHashMap<String, Object>()
        page.put("id", id)
        page.put("title", title)
        pages.add(page)
    }
    pages.sort { Map<String, Object> a, Map<String, Object> b ->
        return Cx.str(a, "title", "").compareToIgnoreCase(Cx.str(b, "title", ""))
    }

    result.put("ok", Boolean.TRUE)
    return result
}

/* The parent page named by a title: adopted when it already exists, created when
 * it does not. There is no Create button - this runs as part of the generating
 * request, which is the only moment at which the answer is still current.
 *
 * The exact-title check sits here, immediately before the create, and not only in
 * the search the browser ran earlier. That covers the page somebody else created
 * in between and the administrator who saw a hit, did not click it and generated
 * anyway. Neither produces a second page carrying the same title.
 *
 * created=true is set on the create path only, so the caller can report finding
 * and creating apart. A failed lookup carries its reason and never degrades into
 * "no such page", which the caller would answer by creating a duplicate. The
 * 401 hint about a Jira user without a Confluence account arrives through
 * confluenceCall, the same path the export itself uses. */
Map<String, Object> confluenceParentByTitle(ApplicationLinkRequestFactory factory, String spaceKey, String title) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("id", null)
    result.put("created", Boolean.FALSE)

    String lookupUrl = "/rest/api/content?type=page&spaceKey=" + URLEncoder.encode(spaceKey, "UTF-8") +
        "&title=" + URLEncoder.encode(title, "UTF-8") + "&limit=2"
    Map<String, Object> lookup = confluenceCall(factory, Request.MethodType.GET, lookupUrl, null)
    if (lookup.get("ok") != Boolean.TRUE) {
        result.put("error", "The parent page \"" + title + "\" could not be looked up in \"" + spaceKey + "\": " +
            String.valueOf(lookup.get("error")) + " That is a failed read, not a space without that page, so " +
            "nothing was created.")
        return result
    }

    List<Map<String, Object>> rows = Cx.rowsOf((Map<String, Object>) lookup.get("json"), "results")
    if (!rows.isEmpty()) {
        String existingId = Cx.str(rows.get(0), "id", "")
        if (existingId.isEmpty()) {
            result.put("error", "Confluence named a page \"" + title + "\" in \"" + spaceKey +
                "\" but gave no id for it, so it cannot be used as a parent.")
            return result
        }
        result.put("ok", Boolean.TRUE)
        result.put("id", existingId)
        return result
    }

    Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
    spacePayload.put("key", spaceKey)

    Map<String, Object> storageBody = new LinkedHashMap<String, Object>()
    storageBody.put("value", Cx.PARENT_BODY)
    storageBody.put("representation", "storage")

    Map<String, Object> bodyPayload = new LinkedHashMap<String, Object>()
    bodyPayload.put("storage", storageBody)

    Map<String, Object> payload = new LinkedHashMap<String, Object>()
    payload.put("type", "page")
    payload.put("title", title)
    payload.put("space", spacePayload)
    payload.put("body", bodyPayload)

    Map<String, Object> call = confluenceCall(factory, Request.MethodType.POST, "/rest/api/content",
        JsonOutput.toJson(payload))
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", "The parent page \"" + title + "\" could not be created in \"" + spaceKey + "\": " +
            String.valueOf(call.get("error")))
        return result
    }

    String createdId = Cx.str((Map<String, Object>) call.get("json"), "id", "")
    if (createdId.isEmpty()) {
        result.put("error", "Confluence accepted the parent page \"" + title + "\" but returned no page id, so the " +
            "report has no confirmed place to go.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("id", createdId)
    result.put("created", Boolean.TRUE)
    return result
}

/* The existence check. found=false only when Confluence answered and the result
 * set was empty; every other outcome is ok=false with a reason. storageRead
 * stays false when the body did not arrive, which the caller treats as a failed
 * read - not as a page without remarks. */
Map<String, Object> confluenceFindPage(ApplicationLinkRequestFactory factory, String spaceKey, String title) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("found", Boolean.FALSE)
    result.put("id", null)
    result.put("version", Integer.valueOf(0))
    result.put("storage", null)
    result.put("storageRead", Boolean.FALSE)
    /* Where the page sits today, so the write below can tell a move it has to make
     * from one it does not. It rides along with the existence check and costs no
     * extra call. parentMeasured stays false when no ancestor array came back,
     * which is not a measurement and never means "the page has no parent". */
    result.put("parentId", null)
    result.put("parentMeasured", Boolean.FALSE)

    String url = "/rest/api/content?type=page&spaceKey=" + URLEncoder.encode(spaceKey, "UTF-8") +
        "&title=" + URLEncoder.encode(title, "UTF-8") + "&expand=body.storage,version,ancestors&limit=2"
    Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET, url, null)
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", call.get("error"))
        return result
    }

    List<Map<String, Object>> rows = Cx.rowsOf((Map<String, Object>) call.get("json"), "results")
    result.put("ok", Boolean.TRUE)
    if (rows.isEmpty()) {
        return result
    }

    Map<String, Object> page = rows.get(0)
    result.put("found", Boolean.TRUE)
    result.put("id", Cx.str(page, "id", null))
    result.put("version", Integer.valueOf((int) Cx.lng(Cx.sub(page, "version"), "number")))

    Map<String, Object> chain = Cx.innermostAncestor(page)
    result.put("parentMeasured", chain.get("measured"))
    result.put("parentId", chain.get("parentId"))

    Map<String, Object> storage = Cx.sub(Cx.sub(page, "body"), "storage")
    Object value = storage.get("value")
    if (value != null) {
        result.put("storage", value.toString())
        result.put("storageRead", Boolean.TRUE)
    }
    return result
}

/* Create or update. The update path sends version number + 1 with a message, the
 * documented way to write a new version.
 *
 * Ancestors now travel with the write when this run named a parent, on update as
 * well as on create. Whether a PUT that carries ancestors actually moves a page in
 * Confluence Data Center 10 is NOT VERIFIED: the Atlassian REST reference renders
 * its content with JavaScript and hands back only navigation, and no REST resource
 * jar was available to read the annotations from. Community summaries claim it
 * works. That is hearsay and nothing here asserts it. It is sent, the position is
 * then measured by the read-back below, and the caller reports the measurement.
 *
 * Being unverified, it is also not allowed to cost the report. A PUT that carries
 * ancestors and is rejected is retried once without them, so the report is written
 * where it already was and the verdict says the parent was not applied.
 *
 * A run that names no parent sends no ancestors on update at all, so a page an
 * administrator moved by hand keeps its place. */
Map<String, Object> confluenceWritePage(ApplicationLinkRequestFactory factory, String spaceKey, String title,
                                        String storage, String parentId, String existingId, int existingVersion,
                                        String moveDecision) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("id", existingId)
    result.put("version", null)
    result.put("parentMeasured", Boolean.FALSE)
    result.put("actualParentId", null)
    result.put("parentSendError", null)

    Map<String, Object> storageBody = new LinkedHashMap<String, Object>()
    storageBody.put("value", storage)
    storageBody.put("representation", "storage")

    Map<String, Object> bodyPayload = new LinkedHashMap<String, Object>()
    bodyPayload.put("storage", storageBody)

    Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
    spacePayload.put("key", spaceKey)

    Map<String, Object> payload = new LinkedHashMap<String, Object>()
    payload.put("type", "page")
    payload.put("title", title)
    payload.put("space", spacePayload)
    payload.put("body", bodyPayload)

    List<Map<String, Object>> ancestors = null
    if (parentId != null && !parentId.trim().isEmpty()) {
        Map<String, Object> ancestor = new LinkedHashMap<String, Object>()
        ancestor.put("id", parentId.trim())
        ancestors = new ArrayList<Map<String, Object>>()
        ancestors.add(ancestor)
    }

    String writeUrl = "/rest/api/content/" + existingId
    Map<String, Object> call = null
    if (existingId == null || existingId.trim().isEmpty()) {
        if (ancestors != null) {
            payload.put("ancestors", ancestors)
        }
        call = confluenceCall(factory, Request.MethodType.POST, "/rest/api/content", JsonOutput.toJson(payload))
    } else {
        Map<String, Object> version = new LinkedHashMap<String, Object>()
        version.put("number", Integer.valueOf(existingVersion + 1))
        version.put("message", "Jira App Footprint Analysis export")

        payload.put("id", existingId)
        payload.put("version", version)

        /* Only a move this run actually has to make. A page that already sits
         * directly under the named parent is written without ancestors, so an
         * unchanged repeat run does not send a reparent it does not need. */
        boolean sentAncestors = ancestors != null && Cx.MOVE_REQUESTED.equals(moveDecision)
        if (sentAncestors) {
            payload.put("ancestors", ancestors)
        }
        call = confluenceCall(factory, Request.MethodType.PUT, writeUrl, JsonOutput.toJson(payload))

        if (call.get("ok") != Boolean.TRUE && sentAncestors) {
            /* The report matters more than its position, and the ancestors on this
             * PUT are unverified. A rejected write is retried once without them
             * rather than losing the report to an experiment. The retry reuses the
             * same version number on purpose: if the first PUT did change the page
             * after all, the retry fails on the version conflict and the caller
             * reports a failed write instead of writing a second version. */
            result.put("parentSendError", String.valueOf(call.get("error")))
            payload.remove("ancestors")
            call = confluenceCall(factory, Request.MethodType.PUT, writeUrl, JsonOutput.toJson(payload))
        }
    }

    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", call.get("error"))
        return result
    }

    Map<String, Object> json = (Map<String, Object>) call.get("json")
    String writtenId = Cx.str(json, "id", existingId)
    if (writtenId == null || writtenId.trim().isEmpty()) {
        result.put("error", "Confluence accepted the write but returned no page ID, so the result cannot be confirmed.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("id", writtenId)

    /* The version and the position are both read back rather than assumed. An
     * accepted write is the server reporting on itself; it is not a measurement of
     * the page, and for the position it is not even a documented one. If the
     * read-back does not answer, the version stays null and parentMeasured stays
     * false, and the caller says so for each - the page is written either way, but
     * an unconfirmed number is never invented and an unmeasured position is
     * reported as unknown rather than as a move that worked. */
    Map<String, Object> verify = confluenceCall(factory, Request.MethodType.GET,
        "/rest/api/content/" + writtenId + "?expand=version,ancestors", null)
    if (verify.get("ok") == Boolean.TRUE) {
        Map<String, Object> verified = (Map<String, Object>) verify.get("json")
        Map<String, Object> chain = Cx.innermostAncestor(verified)
        result.put("parentMeasured", chain.get("measured"))
        result.put("actualParentId", chain.get("parentId"))
        long confirmed = Cx.lng(Cx.sub(verified, "version"), "number")
        if (confirmed > 0L) {
            result.put("version", Integer.valueOf((int) confirmed))
            return result
        }
    }

    long fromWrite = Cx.lng(Cx.sub(json, "version"), "number")
    if (fromWrite > 0L) {
        result.put("version", Integer.valueOf((int) fromWrite))
    }
    return result
}

/* =============================================================================
 * REST Endpoint - Confluence page export (POST)
 * ========================================================================== */

/* Same endpoint name as the report with a different httpMethod. The Adaptavist
 * documentation states that several closures with the same name and different
 * verbs may live in one file, so the report page can POST to its own URL without
 * knowing the REST base path.
 *
 * CSRF - UNVERIFIED. The Custom REST Endpoint documentation does not say whether
 * these endpoints sit behind the Jira XSRF filter, so the report page sends
 * X-Atlassian-Token: no-check, which is required if the filter applies and
 * harmless if it does not. Reading that header back would need the three-argument
 * HttpServletRequest closure form, and the servlet package this ScriptRunner
 * version passes (javax or jakarta) is exactly what this file avoids depending
 * on, so no header check is attempted here. What IS enforced on the server: the
 * jira-administrators gate, the Confluence permissions of the impersonated user
 * on the far side of the application link, and the rule that only a page carrying
 * the export marker is ever updated - a forged request can neither replace a
 * foreign page nor drop a remark. TO CONFIRM before relying on more than that:
 * whether the XSRF filter covers ScriptRunner endpoints, and which
 * HttpServletRequest type is passed, so an explicit header check can be added. */
projectConfig(
    httpMethod: "POST",
    groups: ["jira-administrators"]
) { queryParams, body ->

    long started = System.currentTimeMillis()

    /* ---- JAX-RS Response, resolved at runtime (javax / jakarta neutral) --- */

    Class responseClass = Http.resolveResponseClass()

    def refuse = { int status, String stage, String message ->
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.FALSE)
        payload.put("written", Boolean.FALSE)
        payload.put("stage", stage)
        payload.put("error", message)
        payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.build(responseClass, status,
            JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Http.JSON, null)
    }

    /* ---- Request ----------------------------------------------------------- */

    String requestBody = body == null ? null : body.toString()

    if (requestBody == null || requestBody.trim().isEmpty()) {
        return refuse(400, "request", "The request body is empty. The export payload is expected as JSON.")
    }
    if (requestBody.length() > Cx.MAX_PAYLOAD_CHARS) {
        return refuse(413, "request", "The export payload exceeds " + String.valueOf(Cx.MAX_PAYLOAD_CHARS) + " characters.")
    }

    Object parsed = null
    try {
        parsed = new JsonSlurper().parseText(requestBody)
    } catch (Exception error) {
        return refuse(400, "request", "The request body is not valid JSON: " + error.getClass().getSimpleName())
    }
    if (!(parsed instanceof Map)) {
        return refuse(400, "request", "The request body must be a JSON object.")
    }

    /* The payload is the report model the GET branch serialised for this run, so
     * the page shows exactly the figures the administrator saw. It travels through
     * the browser, which means an administrator could tamper with it - the same
     * administrator who may edit any page they can reach anyway. Everything is
     * escaped on the way into storage format, and the remark carry-over below is
     * unaffected by it: remarks come from the existing page and are read here. */
    Map<String, Object> request = Cx.copyMap((Map<?, ?>) parsed)

    /* ---- Staged lookups ---------------------------------------------------- */

    /* Rendering the report reads nothing. Everything the export form needs arrives
     * here on demand, one stage per request, discriminated by "action":
     * links -> spaces -> pages -> write. A body without an action is the write, so
     * the write path below keeps the shape and the order it always had. */
    String action = Cx.str(request, "action", "write").toLowerCase(Locale.ROOT)

    def answer = { Map<String, Object> data ->
        data.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(data)), Http.JSON)
    }

    if (action == "links" || action == "spaces" || action == "pages") {
        Map<String, Object> lookup = confluenceApplicationLinks()
        if (lookup.get("ok") != Boolean.TRUE) {
            return refuse(500, "link", String.valueOf(lookup.get("error")))
        }
        List<ApplicationLink> confluenceLinks = (List<ApplicationLink>) lookup.get("links")
        if (confluenceLinks.isEmpty()) {
            return refuse(500, "link", "No Confluence application link was found in this Jira instance, so there " +
                "is nowhere to write. Seen: " + String.valueOf(lookup.get("seen")) + " application link(s), type(s): " +
                String.valueOf(lookup.get("seenTypes")) + ". If a Confluence link does exist, report those two values. " +
                "Otherwise create the link under Administration > Applications > Application links, then press " +
                "Export to Confluence again.")
        }

        if (action == "links") {
            String primaryId = lookup.get("primaryId") == null ? null : lookup.get("primaryId").toString()
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>()
            for (ApplicationLink link : confluenceLinks) {
                String id = confluenceLinkId(link)
                if (id == null) {
                    continue
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>()
                row.put("id", id)
                row.put("name", confluenceLinkName(link))
                row.put("displayUrl", confluenceLinkUrl(link))
                row.put("primary", Boolean.valueOf(primaryId != null && primaryId == id))
                rows.add(row)
            }
            if (rows.isEmpty()) {
                return refuse(500, "link", "The Confluence application links of this instance carry no readable id, " +
                    "so no target can be picked.")
            }
            /* Exactly one link is still offered as a list of one, preselected by the
             * browser, so the administrator always sees where the page will land. */
            Map<String, Object> linkPayload = new LinkedHashMap<String, Object>()
            linkPayload.put("ok", Boolean.TRUE)
            linkPayload.put("action", "links")
            linkPayload.put("typedLookup", lookup.get("typed"))
            linkPayload.put("links", rows)
            return answer(linkPayload)
        }

        Map<String, Object> target = confluenceTarget(confluenceLinks, Cx.str(request, "applicationLinkId", ""))
        if (target.get("ok") != Boolean.TRUE) {
            return refuse(500, "link", String.valueOf(target.get("error")))
        }
        ApplicationLink targetLink = (ApplicationLink) target.get("link")
        ApplicationLinkRequestFactory targetFactory = (ApplicationLinkRequestFactory) target.get("factory")

        if (action == "spaces") {
            Map<String, Object> spaceResult = confluenceSpaces(targetFactory)
            if (spaceResult.get("ok") != Boolean.TRUE) {
                return refuse(500, "spaces", "The space list could not be read over the application link \"" +
                    confluenceLinkName(targetLink) + "\": " + String.valueOf(spaceResult.get("error")))
            }
            List<Map<String, Object>> spaceRows = (List<Map<String, Object>>) spaceResult.get("spaces")
            if (spaceRows.isEmpty()) {
                return refuse(500, "spaces", "Confluence answered over the application link \"" +
                    confluenceLinkName(targetLink) + "\" but returned no space this user may see, so no space can be picked.")
            }
            spaceRows.sort { Map<String, Object> a, Map<String, Object> b ->
                int byName = Cx.str(a, "name", "").compareToIgnoreCase(Cx.str(b, "name", ""))
                if (byName != 0) {
                    return byName
                }
                return Cx.str(a, "key", "").compareToIgnoreCase(Cx.str(b, "key", ""))
            }
            /* GET /rest/api/space documents no substring parameter, so the whole
             * current-space list is read once per target and the search runs over
             * it. What reaches the page is the matches, never the full list. */
            Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
            spacePayload.put("ok", Boolean.TRUE)
            spacePayload.put("action", "spaces")
            spacePayload.put("target", confluenceLinkName(targetLink))
            spacePayload.put("spaces", spaceRows)
            spacePayload.put("truncated", spaceResult.get("truncated"))
            return answer(spacePayload)
        }

        String searchSpace = Cx.str(request, "spaceKey", "")
        String searchQuery = Cx.str(request, "query", "")
        if (searchSpace.isEmpty()) {
            return refuse(400, "pages", "No space was selected, so there is nothing to search in.")
        }
        if (searchQuery.trim().length() < Cx.MIN_SEARCH_CHARS) {
            return refuse(400, "pages", "Type at least " + String.valueOf(Cx.MIN_SEARCH_CHARS) +
                " characters of the page title.")
        }
        Map<String, Object> found = confluenceSearchPages(targetFactory, searchSpace, searchQuery)
        if (found.get("ok") != Boolean.TRUE) {
            return refuse(500, "pages", String.valueOf(found.get("error")))
        }
        Map<String, Object> pagePayload = new LinkedHashMap<String, Object>()
        pagePayload.put("ok", Boolean.TRUE)
        pagePayload.put("action", "pages")
        pagePayload.put("spaceKey", searchSpace)
        pagePayload.put("pages", found.get("pages"))
        pagePayload.put("truncated", found.get("truncated"))
        return answer(pagePayload)
    }

    /* ---- Page export: validate --------------------------------------------- */

    Map<String, Object> options = Cx.sub(request, "options")

    String spaceKey = Cx.str(request, "spaceKey", "")
    String title = Cx.str(request, "title", "")
    String parentRaw = Cx.str(request, "parentPageId", "").trim()
    String parentTitleRaw = Cx.str(request, "parentTitle", "").trim()

    if (spaceKey.isEmpty()) {
        return refuse(400, "validate", "No space was selected.")
    }
    String spaceProblem = Cx.spaceKeyProblem(spaceKey)
    if (!spaceProblem.isEmpty()) {
        return refuse(400, "validate", "The space key \"" + spaceKey + "\" cannot be used: " + spaceProblem +
            ". Nothing is written.")
    }
    if (title.isEmpty()) {
        return refuse(400, "validate", "No page title was given.")
    }
    /* Exactly one parent instruction, never two. A picked page and a typed title
     * can disagree, and guessing which one the administrator meant is how a
     * report lands somewhere nobody looks. The request is refused instead. */
    String parentProblem = Cx.parentProblem(parentRaw, parentTitleRaw, title)
    if (!parentProblem.isEmpty()) {
        return refuse(400, "validate", parentProblem)
    }
    if (title.length() > Cx.MAX_TITLE_CHARS) {
        return refuse(400, "validate", "The page title exceeds " + String.valueOf(Cx.MAX_TITLE_CHARS) + " characters.")
    }
    if (Cx.rowsOf(request, "apps").isEmpty()) {
        return refuse(400, "validate", "The export payload carries no apps. Nothing is written.")
    }

    /* ---- Application link -------------------------------------------------- */

    /* The target is the one the administrator picked in the form, resolved by its
     * ApplicationId inside the Confluence links of this instance. The link is no
     * longer guessed as "the first Confluence link that matched", which on an
     * instance with two Confluence links could write to the wrong site silently. */
    Map<String, Object> writeLookup = confluenceApplicationLinks()
    if (writeLookup.get("ok") != Boolean.TRUE) {
        return refuse(500, "link", String.valueOf(writeLookup.get("error")) + " Nothing is written.")
    }
    List<ApplicationLink> writeLinks = (List<ApplicationLink>) writeLookup.get("links")
    if (writeLinks.isEmpty()) {
        return refuse(500, "link", "No Confluence application link was found in this Jira instance, so there is " +
            "nowhere to write. Seen: " + String.valueOf(writeLookup.get("seen")) + " application link(s), type(s): " +
            String.valueOf(writeLookup.get("seenTypes")) + ". Nothing is written.")
    }

    Map<String, Object> writeTarget = confluenceTarget(writeLinks, Cx.str(request, "applicationLinkId", ""))
    if (writeTarget.get("ok") != Boolean.TRUE) {
        return refuse(500, "link", String.valueOf(writeTarget.get("error")))
    }
    ApplicationLink link = (ApplicationLink) writeTarget.get("link")
    ApplicationLinkRequestFactory factory = (ApplicationLinkRequestFactory) writeTarget.get("factory")

    /* ---- Parent page ------------------------------------------------------- */

    /* Three outcomes, kept apart in the response: no parent, a parent that was
     * found, and a parent this run created. Creating is never reported as
     * finding - an administrator who reads "found" believes the page was already
     * there and stops looking for the one that was just made. */
    String parentId = null
    String parentTitle = null
    String parentAction = "none"

    if (!parentRaw.isEmpty()) {
        try {
            Long.parseLong(parentRaw)
        } catch (NumberFormatException ignored) {
            return refuse(400, "validate", "The parent page ID \"" + parentRaw + "\" is not a number.")
        }
        Map<String, Object> parent = confluenceParentPage(factory, parentRaw)
        if (parent.get("ok") != Boolean.TRUE) {
            return refuse(400, "validate", String.valueOf(parent.get("error")))
        }
        String parentSpace = String.valueOf(parent.get("spaceKey"))
        if (!spaceKey.equalsIgnoreCase(parentSpace)) {
            return refuse(400, "validate", "The parent page " + parentRaw + " sits in space \"" + parentSpace +
                "\", not in \"" + spaceKey + "\". Nothing is written.")
        }
        parentId = parentRaw
        parentTitle = parent.get("title") == null ? null : parent.get("title").toString()
        parentAction = "found"
    }

    /* ---- Decision read ----------------------------------------------------- */

    Map<String, Object> existing = confluenceFindPage(factory, spaceKey, title)
    if (existing.get("ok") != Boolean.TRUE) {
        return refuse(409, "read", "The existing page could not be looked up in Confluence (" +
            String.valueOf(existing.get("error")) + "). Nothing is written, so no remark can be lost.")
    }

    RemarkRead read = new RemarkRead()
    boolean pageExists = existing.get("found") == Boolean.TRUE

    if (pageExists) {
        if (existing.get("storageRead") != Boolean.TRUE) {
            return refuse(409, "read", "A page with this title already exists in \"" + spaceKey + "\" but its body did " +
                "not arrive over the application link. Nothing is written, so no remark can be lost.")
        }
        read = Cx.parseRemarks(String.valueOf(existing.get("storage")))
        read.pageId = existing.get("id") == null ? null : existing.get("id").toString()
        read.pageVersion = ((Number) existing.get("version")).intValue()
    }

    /* Fail closed. This is the only path to a write and a FAILED read never passes
     * it: no create, no update, reported to the caller as a failure. A page that
     * would lose administrator notes is never produced. */
    if (!read.isWriteAllowed()) {
        Map<String, Object> refusal = new LinkedHashMap<String, Object>()
        refusal.put("ok", Boolean.FALSE)
        refusal.put("written", Boolean.FALSE)
        refusal.put("stage", "read")
        refusal.put("error", read.reason)
        refusal.put("remarkRead", read.outcome)
        refusal.put("remarkReadDetail", read.asMap())
        refusal.put("spaceKey", spaceKey)
        refusal.put("title", title)
        refusal.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.build(responseClass, 409,
            JsonOutput.prettyPrint(JsonOutput.toJson(refusal)), Http.JSON, null)
    }

    /* ---- Parent page from a typed title ------------------------------------ */

    /* There is no Create button: a title that was typed and never picked is
     * resolved by the generating run. It sits AFTER the fail-closed remark read
     * on purpose - a run that is about to be refused with a 409 must not leave a
     * container page behind that nothing was ever filed under. */
    if (parentId == null && !parentTitleRaw.isEmpty()) {
        Map<String, Object> parent = confluenceParentByTitle(factory, spaceKey, parentTitleRaw)
        if (parent.get("ok") != Boolean.TRUE) {
            /* No fallback to the top level of the space. A report filed where
             * nobody expects it is worse than a run that stops and says why. */
            return refuse(500, "parent", String.valueOf(parent.get("error")) + " Nothing is written.")
        }
        parentId = String.valueOf(parent.get("id"))
        parentTitle = parentTitleRaw
        parentAction = parent.get("created") == Boolean.TRUE ? "created" : "found"
    }

    /* ---- Write ------------------------------------------------------------- */

    ExportOutcome outcome = Cx.render(request, read)

    /* What this run does about the position. A parent named in this run is carried
     * out even when the report page already exists - that is the defect this fixes,
     * a typed parent title that produced the parent page and then filed nothing
     * under it. A run that names no parent leaves the position alone.
     *
     * The current parent came back with the existence check above at no extra call.
     * When it was not readable the decision resolves to "move": carrying out the
     * instruction is the safe direction, and only a positive match skips. */
    String currentParentId = existing.get("parentMeasured") == Boolean.TRUE && existing.get("parentId") != null
        ? existing.get("parentId").toString()
        : null
    String moveDecision = Cx.moveDecision(parentId, currentParentId)

    Map<String, Object> written = confluenceWritePage(factory, spaceKey, title, outcome.storage,
        parentId, read.pageId, read.pageVersion, moveDecision)
    if (written.get("ok") != Boolean.TRUE) {
        return refuse(500, "write", "The Confluence page could not be written: " + String.valueOf(written.get("error")))
    }

    String pageId = written.get("id") == null ? null : written.get("id").toString()
    Object pageVersion = written.get("version")
    if (pageVersion == null) {
        outcome.warnings.add("The page was written, but its new version number could not be read back from Confluence.")
    }

    /* The verdict on the position, and it is the read-back that decides it, not the
     * accepted write. This matters more here than on the Confluence endpoint: the
     * move rides on an ancestors array in a PUT whose reparenting behaviour on
     * Confluence Data Center 10 no primary source could confirm. Measuring it is
     * the whole reason the field exists, and an unmeasured position is reported as
     * unknown rather than as a success. */
    Map<String, Object> parentVerdict = Cx.parentOutcome(parentId,
        written.get("parentMeasured") == Boolean.TRUE,
        written.get("actualParentId") == null ? null : written.get("actualParentId").toString(),
        written.get("parentSendError") == null ? null : written.get("parentSendError").toString())
    if (parentVerdict.get("reason") != null) {
        outcome.warnings.add(parentVerdict.get("reason").toString())
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>()
    response.put("ok", Boolean.TRUE)
    response.put("written", Boolean.TRUE)
    response.put("action", pageExists ? "updated" : "created")
    response.put("target", confluenceLinkName(link))
    response.put("spaceKey", spaceKey)
    response.put("title", title)
    response.put("pageId", pageId)
    response.put("pageVersion", pageVersion)
    response.put("pageUrl", confluencePageUrl(link, pageId))
    response.put("parentPageId", parentId)
    response.put("parentAction", parentAction)
    response.put("parentTitle", parentTitle)
    response.put("parentPageUrl", parentId == null ? null : confluencePageUrl(link, parentId))
    response.put("parentMove", moveDecision)
    response.put("parentApplied", parentVerdict.get("applied"))
    response.put("parentAppliedReason", parentVerdict.get("reason"))
    response.put("remarkRead", read.outcome)
    response.put("remarkReadDetail", read.asMap())
    response.put("remarksRead", Integer.valueOf(outcome.remarksRead))
    response.put("remarksCarried", Integer.valueOf(outcome.remarksCarried))
    response.put("orphanedRemarks", Integer.valueOf(outcome.orphanKeys.size()))
    response.put("orphanedKeys", outcome.orphanKeys)
    response.put("warnings", outcome.warnings)
    response.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))

    return Http.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(response)), Http.JSON)
}
