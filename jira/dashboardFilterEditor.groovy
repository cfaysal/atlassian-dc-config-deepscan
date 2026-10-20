/* ScriptRunner file endpoint: dashboardFilterEditor. See docs/jira-dashboard-filter-editor.md.
 * Administrator maintenance tool: updates existing shared filters, never dashboard preferences.
 */
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.issue.search.SearchService
import com.adaptavist.hapi.jira.users.Users
import com.atlassian.jira.issue.search.SearchRequestManager
import com.atlassian.jira.issue.search.SearchRequest
import com.atlassian.query.Query
import com.atlassian.jira.permission.GlobalPermissionKey
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import groovy.transform.BaseScript
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode

@BaseScript CustomEndpointDelegate delegate

@TypeChecked(TypeCheckingMode.SKIP)
class DashboardFilterSupport {
    static String html(Object value) {
        (value == null ? '' : value.toString()).replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;')
    }

    static Long id(Object value) {
        String text = value?.toString()?.trim()
        if (!(text ==~ /[1-9][0-9]{0,18}/)) throw new IllegalArgumentException('Ung\u00fcltige ID.')
        try { return Long.valueOf(text) } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException('Ung\u00fcltige ID.')
        }
    }

    // Standard Jira gadget preferences, including projectOrFilterId = filter-123.
    // Unknown plugin preferences are reported as unverified; arbitrary numbers are never filter IDs.
    static Set<Long> references(Map preferences) {
        Set<Long> ids = new LinkedHashSet<>()
        preferences.each { key, value ->
            String name = key.toString().toLowerCase(Locale.ROOT)
            String text = value?.toString()?.trim() ?: ''
            if (name in ['filterid', 'filter', 'filterids']) {
                text.split(/[,;\s]+/).findAll { it }.each { token ->
                    ids.add(id(token.replaceFirst(/^filter-/, '')))
                }
            } else if (name == 'projectorfilterid' && text.startsWith('filter-')) {
                ids.add(id(text.substring(7)))
            }
        }
        ids
    }

    static Map save(Map input, Closure read, Closure parse, Closure validate, Closure persist, Closure serialize) {
        Long filterId = id(input.filterId)
        if (!(input.jql instanceof String) || !input.jql.trim() || input.jql.length() > 32000 ||
            !(input.originalJql instanceof String)) {
            throw new IllegalArgumentException('JQL fehlt oder ist zu lang.')
        }
        def filter = read(filterId)
        if (!filter) return [status: 404, error: 'Filter nicht gefunden.']
        if (serialize(filter.query) != input.originalJql) {
            return [status: 409, error: 'Der Filter wurde inzwischen ge\u00e4ndert. Dashboard neu laden.']
        }
        def parsed = parse(input.jql)
        if (!parsed.valid) return [status: 400, error: parsed.errors.toString()]
        def errors = validate(parsed.query)
        if (errors.hasAnyErrors()) return [status: 400, error: errors.toString()]
        filter.setQuery(parsed.query)
        persist(filter)
        [status: 200, jql: serialize(parsed.query), message: 'Filter gespeichert.']
    }
}

// Jira/ScriptRunner bridges use dynamic dispatch for platform services.
@TypeChecked(TypeCheckingMode.SKIP)
class DashboardFilterLive {
    static final String VERSION = '1.1.0'
    static def response(int status, Object body, String type = 'application/json') {
        Class responseClass
        try { responseClass = Class.forName('jakarta.ws.rs.core.Response') }
        catch (ClassNotFoundException ignored) { responseClass = Class.forName('javax.ws.rs.core.Response') }
        responseClass.status(status).type(type + '; charset=UTF-8')
            .header('X-Dashboard-Filter-Editor-Version', VERSION).header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff')
            .entity(type == 'application/json' ? JsonOutput.toJson(body) : body).build()
    }

    static boolean admin() {
        def user = Users.getLoggedInUser()
        user != null && ComponentAccessor.globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)
    }

    static List dashboards(String query) {
        if (!query?.trim()) return []
        String needle = query.trim().toLowerCase(Locale.ROOT)
        // Jira entity fields: PortalPage.id, pagename, username. Read-only entity access.
        ComponentAccessor.ofBizDelegator.findAll('PortalPage').findAll { page ->
            page.getLong('id').toString() == needle ||
                (page.getString('pagename') ?: '').toLowerCase(Locale.ROOT).contains(needle)
        }.sort { a, b -> a.getLong('id') <=> b.getLong('id') }.collect { page ->
            [id: page.getLong('id').toString(), name: page.getString('pagename'), owner: page.getString('username')]
        }
    }

    static Map dashboard(Long dashboardId) {
        def db = ComponentAccessor.ofBizDelegator
        def page = db.findByAnd('PortalPage', [id: dashboardId])
        if (!page) return [status: 404, error: 'Dashboard nicht gefunden.']
        Map<String, Map> filters = [:]
        List gadgets = []
        db.findByAnd('PortletConfiguration', [portalpage: dashboardId]).each { gadget ->
            Map item = [id: gadget.getLong('id').toString(), type: gadget.getString('gadgetXml') ?: gadget.getString('portletId')]
            try {
                Map prefs = [:]
                db.findByAnd('GadgetUserPreference', [portletconfiguration: gadget.getLong('id')]).each { pref ->
                    prefs[pref.getString('userprefkey')] = pref.getString('userprefvalue')
                }
                Set<Long> ids = DashboardFilterSupport.references(prefs)
                item.filterIds = ids.collect { it.toString() }
                item.note = ids ? 'Filterreferenzen erkannt.' : 'Keine Standard-Filterreferenz erkannt; Gadget ungepr\u00fcft.'
                ids.each { filterId ->
                    String key = filterId.toString()
                    if (!filters.containsKey(key)) {
                        def filter = ComponentAccessor.getComponent(SearchRequestManager).getSearchRequestById(filterId)
                        filters[key] = filter ? [id: key, name: filter.name, owner: filter.owner?.displayName,
                            jql: ComponentAccessor.getComponent(SearchService).getJqlString(filter.query), gadgets: []] :
                            [id: key, error: 'Filter fehlt oder konnte nicht geladen werden.', gadgets: []]
                    }
                    filters[key].gadgets.add(item.id)
                }
            } catch (Exception failure) {
                item.note = 'Gadget konnte nicht vollst\u00e4ndig gelesen werden: ' + failure.class.simpleName
            }
            gadgets.add(item)
        }
        [status: 200, id: dashboardId.toString(), name: page[0].getString('pagename'), filters: filters.values().toList(), gadgets: gadgets]
    }

    static Map validateJql(String jql) {
        if (!jql?.trim()) return [valid: false, errors: ['Bitte JQL eingeben.']]
        if (jql.length() > 32000) throw new IllegalArgumentException('JQL ist zu lang.')
        SearchService search = ComponentAccessor.getComponent(SearchService)
        def user = Users.getLoggedInUser()
        def parsed = search.parseQuery(user, jql)
        if (!parsed.valid) return [valid: false, errors: parsed.errors.errorMessages.toList()]
        def errors = search.validateQuery(user, (Query) parsed.query)
        [valid: !errors.hasAnyErrors(), errors: errors.errorMessages.toList()]
    }

    static String page(String token) {
        '''<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Dashboard-Filter bearbeiten</title><style>
body{font:16px system-ui;margin:32px auto;max-width:1100px;padding:0 20px;color:#172b4d}input,button,textarea{font:inherit;padding:8px}textarea{display:block;width:96%;min-height:110px;margin:10px 0}article{border:1px solid #ccd3dd;border-radius:6px;padding:18px;margin:16px 0}button{cursor:pointer;margin:4px}#status,.result{white-space:pre-wrap}small{display:block}li{margin:8px 0}
.jql-state{display:flex;align-items:center;gap:8px;margin:10px 0;white-space:pre-wrap}.jql-state::before{content:"?";display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;width:22px;height:22px;border-radius:50%;background:#6b778c;color:white;font-weight:bold}.jql-state.valid::before{content:"\u2713";background:#00875a}.jql-state.invalid::before{content:"!";background:#de350b}.jql-state.checking::before{content:"\u2026";background:#0052cc}</style><h1>Dashboard-Filter bearbeiten</h1>
<p>Version <strong>__VERSION__</strong> &middot; Jira 11 &middot; JQL-Serializer</p>
<form id="search"><label>Dashboard-Name oder ID <input id="query" required maxlength="200"></label><button>Suchen</button></form>
<p id="status" role="status"></p><div id="choices"></div><div id="dashboard"></div>
<script>
'use strict';
const csrf = '__TOKEN__';
const endpoint = location.pathname;
const status = document.getElementById('status');
const choices = document.getElementById('choices');
const dashboard = document.getElementById('dashboard');
let generation = 0;
function node(tag, text, parent) { const el = document.createElement(tag); if(text !== undefined) el.textContent=text; if(parent) parent.append(el); return el; }
async function api(url, options) {
 const response = await fetch(url, {credentials:'same-origin', ...options});
 let data; try { data = await response.json(); } catch (_) { throw new Error('Keine JSON-Antwort; Anmeldung und Serverprotokoll pr\u00fcfen.'); }
 if(!response.ok) throw new Error(data.error || 'Anfrage fehlgeschlagen.');
 return data;
}
document.getElementById('search').onsubmit = async event => {
 event.preventDefault(); const current = ++generation; status.textContent='Suche l\u00e4uft \u2026'; choices.replaceChildren(); dashboard.replaceChildren();
 try { const data = await api(endpoint+'?action=search&q='+encodeURIComponent(document.getElementById('query').value));
 if(current !== generation) return;
 status.textContent=data.dashboards.length+' Dashboard(s) gefunden.';
 data.dashboards.forEach(item => { const b=node('button',item.name+' \u00b7 ID '+item.id+' \u00b7 '+(item.owner || ''),choices); b.onclick=()=>load(item.id); });
 } catch(e) { if(current===generation) status.textContent=e.message; }
};
async function load(id) {
 const current=++generation; dashboard.replaceChildren(); status.textContent='Dashboard wird gelesen \u2026';
 try { const data=await api(endpoint+'?action=dashboard&id='+encodeURIComponent(id)); if(current!==generation)return;
 status.textContent=''; node('h2',data.name+' \u00b7 ID '+data.id,dashboard);
 node('p','Speichern \u00e4ndert den bestehenden Filter \u00fcberall, auch in anderen Dashboards und Abonnements. Eigent\u00fcmer und Freigaben bleiben erhalten.',dashboard);
 if(!data.filters.length) node('p','Keine Standard-Filterreferenzen gefunden. Gadget-Abdeckung unten pr\u00fcfen.',dashboard);
 data.filters.forEach(filter => {
 const card=node('article',undefined,dashboard); node('h3',(filter.name || 'Filter')+' \u00b7 ID '+filter.id,card);
 node('small','Eigent\u00fcmer: '+(filter.owner || '\u2014')+' \u00b7 Gadgets: '+filter.gadgets.join(', '),card);
 if(filter.error){node('p',filter.error,card);return;}
 const label=node('label','JQL',card); const area=node('textarea',undefined,label); area.value=filter.jql; area.maxLength=32000;
 const validity=node('p','Noch nicht gepr\u00fcft.',card); validity.className='jql-state'; validity.setAttribute('role','status'); validity.setAttribute('aria-live','polite');
 const button=node('button','JQL speichern',card); const result=node('p','',card); result.className='result'; result.setAttribute('role','status');
 let validationVersion=0, validationTimer;
 function showValidity(state, text) { validity.className='jql-state '+state; validity.textContent=text; area.setAttribute('aria-invalid',state==='invalid'?'true':'false'); }
 async function validateJql() {
 const version=++validationVersion, jql=area.value;
 if(!jql.trim()){showValidity('invalid','Bitte JQL eingeben.');return;}
 showValidity('checking','JQL wird gepr\u00fcft ...');
 try { const validation=await api(endpoint+'Save?action=validate',{method:'POST',headers:{'Content-Type':'application/json','X-Dashboard-CSRF':csrf},body:JSON.stringify({jql})});
 if(version!==validationVersion || area.value!==jql || current!==generation)return;
 showValidity(validation.valid?'valid':'invalid',validation.valid?'JQL ist g\u00fcltig.':(validation.errors || ['JQL ist ung\u00fcltig.']).join('\\n'));
 }catch(e){if(version===validationVersion && area.value===jql && current===generation)showValidity('unknown','Pr\u00fcfung fehlgeschlagen: '+e.message);}
 }
 area.oninput=()=>{++validationVersion;clearTimeout(validationTimer);showValidity('unknown','Noch nicht gepr\u00fcft.');result.textContent='';validationTimer=setTimeout(validateJql,500);};
 validateJql();
 button.onclick=async()=>{button.disabled=true; result.textContent='Wird gepr\u00fcft und gespeichert \u2026';
 try { const saved=await api(endpoint+'Save',{method:'POST',headers:{'Content-Type':'application/json','X-Dashboard-CSRF':csrf},body:JSON.stringify({dashboardId:id,filterId:filter.id,originalJql:filter.jql,jql:area.value})});
 filter.jql=saved.jql; area.value=saved.jql; ++validationVersion; clearTimeout(validationTimer); showValidity('valid','JQL ist g\u00fcltig.'); result.textContent=saved.message;
 }catch(e){result.textContent=e.message;}finally{button.disabled=false;}};
 });
 node('h3','Gadget-Abdeckung',dashboard); node('p','Ausgelesen werden Standard-Filterpr\u00e4ferenzen. App-Gadgets mit eigener Ablage und in JQL verschachtelte Filter sind nicht automatisch abgedeckt.',dashboard);
 const list=node('ul',undefined,dashboard); data.gadgets.forEach(g=>node('li','Gadget '+g.id+' \u00b7 '+g.type+' \u00b7 '+g.note,list));
 }catch(e){if(current===generation)status.textContent=e.message;}
}
</script></html>'''.replace('__TOKEN__', token).replace('__VERSION__', VERSION)
    }
}

// ScriptRunner registers these routes through methodMissing. Request types target Jira 11 (Jakarta).
@TypeChecked(TypeCheckingMode.SKIP)
void registerDashboardFilterEndpoints() {
dashboardFilterEditor(httpMethod: 'GET', groups: ['jira-administrators']) { MultivaluedMap<String, String> params, String body, HttpServletRequest request ->
    if (!DashboardFilterLive.admin()) return DashboardFilterLive.response(403, [error: 'Jira-Administrator erforderlich.'])
    try {
        String action = params.getFirst('action') ?: ''
        if (action == 'search') {
            String query = params.getFirst('q') ?: ''
            if (query.length() > 200) throw new IllegalArgumentException('Suchtext zu lang.')
            return DashboardFilterLive.response(200, [dashboards: DashboardFilterLive.dashboards(query)])
        }
        if (action == 'dashboard') {
            Map result = DashboardFilterLive.dashboard(DashboardFilterSupport.id(params.getFirst('id')))
            return DashboardFilterLive.response(result.status as int, result)
        }
        if (action) throw new IllegalArgumentException('Unbekannte Aktion.')
        HttpSession session = request.getSession(true)
        String token
        synchronized (session) {
            token = (String) session.getAttribute('dashboardFilterEditor.csrf')
            if (!token) { token = UUID.randomUUID().toString(); session.setAttribute('dashboardFilterEditor.csrf', token) }
        }
        DashboardFilterLive.response(200, DashboardFilterLive.page(token), 'text/html')
    } catch (IllegalArgumentException failure) {
        DashboardFilterLive.response(400, [error: failure.message])
    } catch (Exception failure) {
        log.warn('Dashboard filter read failed', failure)
        DashboardFilterLive.response(500, [error: 'Dashboard konnte nicht gelesen werden. Serverprotokoll pr\u00fcfen.'])
    }
}

dashboardFilterEditorSave(httpMethod: 'POST', groups: ['jira-administrators']) { MultivaluedMap<String, String> params, String body, HttpServletRequest request ->
    if (!DashboardFilterLive.admin()) return DashboardFilterLive.response(403, [error: 'Jira-Administrator erforderlich.'])
    HttpSession session = request.getSession(false)
    String token = (String) session?.getAttribute('dashboardFilterEditor.csrf')
    if (!token || token != request.getHeader('X-Dashboard-CSRF')) {
        return DashboardFilterLive.response(403, [error: 'Sitzung abgelaufen. Seite neu laden.'])
    }
    if (!(request.contentType ?: '').toLowerCase(Locale.ROOT).startsWith('application/json') || !body || body.length() > 100000) {
        return DashboardFilterLive.response(400, [error: 'JSON erforderlich; maximal 100000 Zeichen.'])
    }
    try {
        Object parsedInput
        try { parsedInput = new JsonSlurper().parseText(body) }
        catch (groovy.json.JsonException ignored) { throw new IllegalArgumentException('Ung\u00fcltiges JSON.') }
        if (!(parsedInput instanceof Map)) throw new IllegalArgumentException('JSON-Objekt erforderlich.')
        Map input = (Map) parsedInput
        if (params.getFirst('action') == 'validate') {
            if (!(input.get('jql') instanceof String)) throw new IllegalArgumentException('JQL als Text erforderlich.')
            return DashboardFilterLive.response(200, DashboardFilterLive.validateJql((String) input.get('jql')))
        }
        Long filterId = DashboardFilterSupport.id(input.filterId)
        Long dashboardId = DashboardFilterSupport.id(input.dashboardId)
        // Serialize writes from this endpoint across Data Center nodes.
        def lockService = ComponentAccessor.getComponent(com.atlassian.beehive.ClusterLockService)
        def lock = lockService.getLockForName('dashboardFilterEditor.filter.' + filterId)
        if (!lock.tryLock()) return DashboardFilterLive.response(409, [error: 'Filter wird gerade bearbeitet. Erneut versuchen.'])
        try {
            Map dashboard = DashboardFilterLive.dashboard(dashboardId)
            if (dashboard.status != 200 || !((List<Map>) dashboard.get('filters')).any { Map entry ->
                    entry.get('id') == filterId.toString() && !entry.get('error')
                }) {
                return DashboardFilterLive.response(409, [error: 'Filter geh\u00f6rt nicht mehr zum geladenen Dashboard oder ist nicht lesbar. Neu laden.'])
            }
            def user = Users.getLoggedInUser()
            def manager = ComponentAccessor.getComponent(SearchRequestManager)
            def search = ComponentAccessor.getComponent(SearchService)
            Map result = DashboardFilterSupport.save(input,
                { Long key -> manager.getSearchRequestById(key) },
                { String jql -> def parsed = search.parseQuery(user, jql); [valid: parsed.valid, query: parsed.query, errors: parsed.errors] },
                { Query query -> search.validateQuery(user, query) },
                { SearchRequest filter -> manager.update(user, filter) },
                { Query query -> search.getJqlString(query) })
            if (result.status == 200) log.info('Dashboard filter JQL updated: actor=' + user.key + ', dashboard=' + dashboardId + ', filter=' + filterId)
            return DashboardFilterLive.response(result.status as int, result)
        } finally { lock.unlock() }
    } catch (IllegalArgumentException failure) {
        DashboardFilterLive.response(400, [error: failure.message])
    } catch (Exception failure) {
        log.warn('Dashboard filter request failed', failure)
        if (params.getFirst('action') == 'validate') {
            return DashboardFilterLive.response(500, [error: 'JQL-Pruefung fehlgeschlagen (' + failure.class.simpleName + '). Serverprotokoll pruefen.'])
        }
        DashboardFilterLive.response(500, [error: 'Speichern fehlgeschlagen. Aktuellen Filter neu laden und Serverprotokoll pr\u00fcfen.'])
    }
}

}

registerDashboardFilterEndpoints()
