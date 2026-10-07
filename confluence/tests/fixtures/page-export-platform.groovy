// Synthetic persistence lifecycle, not an implementation of Confluence/Hibernate.
// Manager calls join the outer unit of work. Without it, a post-commit mutation
// leaves the request's cached parent stale, as described in Atlassian's guidance.
class Space { String key }
class Page implements Cloneable {
    long id
    int version
    long hibernateVersion
    Space space
    String title
    String body = ''
    Page parentPage
    List<Page> ancestors = []
    List<Page> children = []
    String getIdAsString() { id.toString() }
    String getSpaceKey() { space.key }
    String getBodyAsString() { body }
    void setBodyAsString(String value) { body = value }
    void setBodyContent(BodyContent value) { body = value.body }
    void setCreator(Object ignored) {}
    void addChild(Page child) { children.add(child) }
    Page getParent() { parentPage }
    Object clone() {
        new Page(id: id, version: version, hibernateVersion: hibernateVersion,
            space: space, title: title, body: body, parentPage: parentPage,
            ancestors: new ArrayList<Page>(ancestors))
    }
}
class BodyContent {
    String body
    BodyContent(Page page, String body, Object type) { this.body = body }
}
class BodyType { static final String XHTML = 'xhtml' }
class DefaultSaveContext { static final String SUPPRESS_NOTIFICATIONS = 'suppress' }
class AuthenticatedUserThreadLocal { static Object get() { null } }
class StaleObjectStateException extends RuntimeException {
    StaleObjectStateException() {
        super('OptimisticLockException: actual row count: 0; expected: 1; CONTENTID=? and HIBERNATEVERSION=?')
    }
}
class Store {
    Map<Long, Page> pages = [:]
    Map<Long, Page> cache = [:]
    Set<Long> newParents = [] as Set
    long nextId = 10
    boolean inTransaction = false
    boolean rollbackOnly = false
    boolean failCommit = false
    boolean conflictOnOverview = false
    boolean hideNewParent = false
    List<Map> writes = []
    int commits = 0
    int rollbacks = 0
    Page byTitle(String key, String title) {
        Page found = pages.values().find { it.space.key == key && it.title == title }
        found == null ? null : byId(found.id)
    }
    Page byId(long id) {
        if (hideNewParent && newParents.contains(id)) { return null }
        if (!cache.containsKey(id) && pages.containsKey(id)) { cache[id] = (Page) pages[id].clone() }
        cache[id]
    }
    void save(Page page, Page original = null) {
        boolean overview = page.body.contains('cfcon-overview-confluence-start')
        if (page.id != 0 && (page.hibernateVersion != pages[page.id].hibernateVersion ||
                (overview && conflictOnOverview))) {
            rollbackOnly = true
            throw new StaleObjectStateException()
        }
        boolean creating = page.id == 0
        if (creating) {
            page.id = nextId++
            if (page.body.contains('Container page')) { newParents.add(page.id) }
        } else {
            page.version++
            page.hibernateVersion++
        }
        pages[page.id] = (Page) page.clone()
        cache[page.id] = page
        writes.add([id: page.id, overview: overview, transaction: inTransaction])
        // Deferred platform work after a manager's own commit changes the newly
        // created parent in storage but not the request cache. An outer unit of
        // work defers it until all related mutations have completed.
        if (!inTransaction && creating && page.parentPage != null && newParents.contains(page.parentPage.id)) {
            pages[page.parentPage.id].hibernateVersion++
        }
    }
    Object transaction(Closure body) {
        assert !inTransaction
        Map<Long, Page> before = pages.collectEntries { id, page -> [(id): page.clone()] }
        inTransaction = true
        rollbackOnly = false
        try {
            Object result = body.call()
            if (rollbackOnly || failCommit) { throw new IllegalStateException('Transaction commit failed') }
            commits++
            return result
        } catch (Throwable error) {
            pages = before
            cache.clear()
            rollbacks++
            throw error
        } finally { inTransaction = false }
    }
    void nextRequest() { cache.clear(); newParents.clear() }
}
class PageManager {
    Store store
    void saveContentEntity(Page page, Object context) { store.save(page) }
    void saveContentEntity(Page page, Page original, Object context) { store.save(page, original) }
    void movePageAsChild(Page page, Page parent) {
        page.parentPage = parent
        page.ancestors = parent.ancestors + [parent]
        store.save(page)
    }
}
class PageService {
    Store store
    Object getTitleAndSpaceKeyPageLocator(String key, String title) { [getPage: { -> store.byTitle(key, title) }] }
    Object getIdPageLocator(long id) { [getPage: { -> store.byId(id) }] }
}
class SpaceService {
    Space space = new Space(key: 'DOC')
    Object getKeySpaceLocator(String key) { [getSpace: { -> key == space.key ? space : null }] }
}
class ComponentLocator {
    static Map<Class, Object> components = [:]
    static <T> T getComponent(Class<T> type) { (T) components[type] }
}
class Cw {
    static Map instanceIdentity() { [baseUrl: 'https://example.invalid', title: 'Synthetic', confluenceVersion: 'synthetic'] }
    static String pageUrl(String base, String id) { base + '/pages/' + id }
}
class Db { static String why(Throwable error) { error.message } }
class TestResponse extends LinkedHashMap {
    int getStatus() { containsKey('status') ? ((Number) get('status')).intValue() : 200 }
}
class Http {
    static final String JSON = 'json'
    static Object build(Class ignored, int status, String body, String contentType, Object headers) {
        new TestResponse(new groovy.json.JsonSlurper().parseText(body) + [status: status])
    }
}
