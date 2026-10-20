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

    // Standard Jira descriptor evidence: jira-gadgets-plugin/src/main/resources/gadgets/
    // piechart-gadget.xml and stats-gadget.xml use projectOrFilterId;
    // two-dimensional-stats-gadget.xml and filter-results-gadget.xml use filterId.
    // The descriptors also support jql- sources. Inspected in the Jira source mirror at
    // github.com/turgaycelik/mysource, commit 44f29e397a6a2da9340a79b8a3f61b3d51e331d1,
    // under jira-project/jira-components/jira-plugins/jira-gadgets-plugin/.
    // Unknown plugin preferences remain unverified; arbitrary numbers are never filter IDs.
    static Map sources(Map preferences) {
        Set<Long> ids = new LinkedHashSet<>()
        Set<Long> projects = new LinkedHashSet<>()
        Set<String> queries = new LinkedHashSet<>()
        List<String> warnings = []
        List<Map> sourcePreferences = []
        preferences.each { key, value ->
            String name = key.toString().trim().toLowerCase(Locale.ROOT)
            String text = value?.toString()?.trim() ?: ''
            if (!(name in ['filterid', 'filter', 'filterids', 'projectorfilterid'])) return
            sourcePreferences.add([name: key.toString(), value: value?.toString() ?: ''])
            if (!text) return
            if (text.startsWith('jql-')) {
                queries.add(text.substring(4))
            } else if (name == 'projectorfilterid' && text.startsWith('project-')) {
                try { projects.add(id(text.substring(8))) }
                catch (IllegalArgumentException ignored) { warnings.add('Ung\u00fcltige Projektquelle in ' + key + '.') }
            } else if (name != 'projectorfilterid' || text.startsWith('filter-')) {
                text.split(/[,;\s]+/).findAll { it }.each { token ->
                    try { ids.add(id(token.replaceFirst(/^filter-/, ''))) }
                    catch (IllegalArgumentException ignored) { warnings.add('Ung\u00fcltige Filterreferenz in ' + key + '.') }
                }
            } else {
                warnings.add('Datenquelle in ' + key + ' nicht erkannt.')
            }
        }
        [filterIds: ids, projectIds: projects, directJql: queries, warnings: warnings.unique(),
            sourcePreferences: sourcePreferences]
    }

    static Set<Long> references(Map preferences) {
        sources(preferences).filterIds as Set<Long>
    }

    static String gadgetName(String type) {
        // Friendly labels for the descriptor filenames evidenced above; the original
        // type remains visible and is never used as a URL to fetch gadget metadata.
        Map names = ['piechart-gadget.xml': 'Kreisdiagramm',
            'two-dimensional-stats-gadget.xml': 'Zweidimensionale Filterstatistik',
            'createdvsresolved-gadget.xml': 'Erstellt vs. erledigt',
            'filter-results-gadget.xml': 'Filterergebnisse', 'stats-gadget.xml': 'Vorgangsstatistik']
        List parts = (type ?: '').replaceFirst(/[?#].*$/, '').tokenize('/')
        String filename = parts ? parts.last() : ''
        names[filename] ?: (type ?: 'Unbekanntes Gadget')
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
    static final String VERSION = '1.2.0'
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
            String type = gadget.getString('gadgetXml') ?: gadget.getString('portletId')
            Map item = [id: gadget.getLong('id').toString(), type: type,
                name: DashboardFilterSupport.gadgetName(type), filterIds: [], projectIds: [], directJql: [],
                sourcePreferences: [], preferenceNames: []]
            try {
                Map prefs = [:]
                db.findByAnd('GadgetUserPreference', [portletconfiguration: gadget.getLong('id')]).each { pref ->
                    prefs[pref.getString('userprefkey')] = pref.getString('userprefvalue')
                }
                Map sources = DashboardFilterSupport.sources(prefs)
                item.sourcePreferences = sources.sourcePreferences
                item.preferenceNames = prefs.keySet().collect { it.toString() }.sort()
                Set<Long> ids = sources.filterIds
                item.filterIds = ids.collect { it.toString() }
                item.projectIds = sources.projectIds.collect { it.toString() }
                item.directJql = sources.directJql.toList()
                List notes = []
                if (ids) notes.add('Gespeicherte Filter erkannt.')
                if (item.projectIds) notes.add('Projektquelle ohne gespeicherten Filter.')
                if (item.directJql) notes.add('Direkte JQL im Gadget hinterlegt.')
                if (!ids && !item.projectIds && !item.directJql) notes.add('Keine Standard-Datenquelle erkannt; Gadget ungepr\u00fcft.')
                notes.addAll(sources.warnings)
                item.note = notes.join(' ')
                ids.each { filterId ->
                    String key = filterId.toString()
                    if (!filters.containsKey(key)) {
                        try {
                            def filter = ComponentAccessor.getComponent(SearchRequestManager).getSearchRequestById(filterId)
                            filters[key] = filter ? [id: key, name: filter.name, owner: filter.owner?.displayName,
                                jql: ComponentAccessor.getComponent(SearchService).getJqlString(filter.query), gadgets: []] :
                                [id: key, error: 'Filter fehlt oder konnte nicht geladen werden.', gadgets: []]
                        } catch (Exception failure) {
                            filters[key] = [id: key, error: 'Filter konnte nicht gelesen werden: ' + failure.class.simpleName, gadgets: []]
                        }
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
.sources{width:100%;border-collapse:collapse;margin:16px 0}.sources th,.sources td{text-align:left;vertical-align:top;padding:12px;border:1px solid #ccd3dd;overflow-wrap:anywhere}.sources th{background:#f4f5f7}.sources small{margin-top:6px}.sources pre{white-space:pre-wrap}article{scroll-margin-top:16px}a{color:#0052cc}
.jql-state{display:flex;align-items:center;gap:8px;margin:10px 0;white-space:pre-wrap}.jql-state::before{content:"?";display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;width:22px;height:22px;border-radius:50%;background:#6b778c;color:white;font-weight:bold}.jql-state.valid::before{content:"\u2713";background:#00875a}.jql-state.invalid::before{content:"!";background:#de350b}.jql-state.checking::before{content:"\u2026";background:#0052cc}</style><h1>Dashboard-Filter bearbeiten</h1>
<p>Version <strong>__VERSION__</strong> &middot; Jira 11</p>
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
 const gadgets=data.gadgets || [], filtersById=new Map(data.filters.map(filter=>[filter.id,filter]));
 node('h3','Charts und verwendete Filter',dashboard);
 node('p','Pro Chart oder Gadget siehst du den gespeicherten Filter oder eine andere erkannte Datenquelle. Ein Filtername f\u00fchrt zur JQL weiter unten.',dashboard);
 if(!gadgets.length) node('p','Keine Gadgets auf diesem Dashboard gefunden.',dashboard);
 else {
 const table=node('table',undefined,dashboard); table.className='sources';
 const head=node('tr',undefined,node('thead',undefined,table)); node('th','Chart / Gadget',head); node('th','Verwendete Filter / Datenquelle',head);
 const rows=node('tbody',undefined,table);
 gadgets.forEach(g=>{
 const row=node('tr',undefined,rows), gadgetCell=node('td',undefined,row), sourceCell=node('td',undefined,row);
 node('strong',g.name || g.type || 'Unbekanntes Gadget',gadgetCell); node('small','Gadget-ID '+g.id,gadgetCell);
 (g.filterIds || []).forEach(filterId=>{
 const filter=filtersById.get(filterId), line=node('p',undefined,sourceCell);
 if(filter) {const link=node('a',(filter.name || 'Filter')+' \u00b7 ID '+filterId,line);link.href='#filter-'+filterId;if(filter.error)node('small',filter.error,line);}
 else node('span','Filter-ID '+filterId+' konnte nicht geladen werden.',line);
 });
 (g.projectIds || []).forEach(projectId=>node('p','Projekt-ID '+projectId+' (ohne gespeicherten Filter)',sourceCell));
 (g.directJql || []).forEach(jql=>{const details=node('details',undefined,sourceCell);node('summary','Direkte JQL im Gadget',details);node('pre',jql,details);});
 node('small',g.note || 'Datenquelle nicht erkannt; Gadget ungepr\u00fcft.',sourceCell);
 const details=node('details',undefined,sourceCell);node('summary','Datenquelle pr\u00fcfen',details);
 if(g.type) node('small','Gadget-Typ: '+g.type,details);
 const preferences=g.sourcePreferences || [];
 if(!preferences.length) node('p','Keine Standard-Quellpr\u00e4ferenz vorhanden.',details);
 preferences.forEach(pref=>{node('strong',pref.name,details);node('pre',pref.value || '(leer)',details);});
 if(g.preferenceNames && g.preferenceNames.length) node('small','Vorhandene Einstellungsnamen: '+g.preferenceNames.join(', '),details);
 });
 }
 node('h3','Gespeicherte Filter bearbeiten',dashboard);
 node('p','Speichern \u00e4ndert den bestehenden Filter \u00fcberall, auch in anderen Dashboards und Abonnements. Eigent\u00fcmer und Freigaben bleiben erhalten.',dashboard);
 if(!data.filters.length) node('p','Keine gespeicherten Filter erkannt. Datenquellen der Charts und Gadgets oben pr\u00fcfen.',dashboard);
 data.filters.forEach(filter => {
 const card=node('article',undefined,dashboard); card.id='filter-'+filter.id; node('h3',(filter.name || 'Filter')+' \u00b7 ID '+filter.id,card);
 const usages=filter.gadgets.map(id=>{const g=gadgets.find(g=>g.id===id);return g?(g.name || g.type)+' (ID '+id+')':'Gadget '+id;});
 node('small','Eigent\u00fcmer: '+(filter.owner || '\u2014')+' \u00b7 Verwendet von: '+usages.join(', '),card);
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
 node('p','Ausgelesen werden Standard-Filterpr\u00e4ferenzen. App-Gadgets mit eigener Ablage und in JQL verschachtelte Filter sind nicht automatisch abgedeckt.',dashboard);
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
