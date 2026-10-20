import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.textContent = ''; this.value = ''; this.attributes = {}; }
  append(el) { this.children.push(el); }
  replaceChildren() { this.children = []; }
  setAttribute(name,value) { this.attributes[name]=value; }
}
const all = el => [el, ...el.children.flatMap(all)];
function browserScript() {
  const source=readFileSync(process.env.DASHBOARD_EDITOR_HTML || 'jira/dashboardFilterEditor.groovy','utf8');
  const script=source.match(/<script>\n([\s\S]*?)<\/script>/)[1];
  if(process.env.DASHBOARD_EDITOR_HTML)return script;
  // Decode the shipped Groovy triple-single-quoted string in one pass. CI also
  // runs these tests against the HTML emitted by the complete compiled script.
  const escapes={'\\':'\\',"'":"'",n:'\n',r:'\r',t:'\t',b:'\b',f:'\f'};
  return script.replace(/\\(u[0-9a-fA-F]{4}|\\|'|n|r|t|b|f)/g,(_,code)=>code.startsWith('u')?String.fromCharCode(parseInt(code.slice(1),16)):escapes[code])
    .replace('__MONACO_BASE__',JSON.stringify('/jira/download/resources/fixture:monaco/vs'))
    .replace('__MONACO_FAILURE__','""');
}
const documentFor=elements=>({
  getElementById:id=>elements[id],createElement:tag=>new Element(tag),
  head:{append:script=>script.onerror()}
});
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function monacoFixture() {
  const models=[],editors=[],markers=[],languages=[],providers=[];
  const monaco={
    MarkerSeverity:{Error:8},KeyMod:{CtrlCmd:2048},KeyCode:{Enter:3},
    languages:{
      CompletionItemKind:{Field:1,Keyword:2,Function:3},
      getLanguages:()=>languages,register:language=>languages.push(language),
      setMonarchTokensProvider:(id,provider)=>providers.push({id,provider}),setLanguageConfiguration(){},
      registerCompletionItemProvider:(id,provider)=>providers.push({id,completion:provider})
    },
    editor:{
      createModel(value,language){
        let listener;
        const model={value,language,disposed:false,getValue(){return this.value;},
          getFullModelRange(){return {startLineNumber:1,startColumn:1,endLineNumber:1,endColumn:this.value.length+1};},
          getWordUntilPosition(){return {startColumn:1,endColumn:4,word:'pro'};},
          onDidChangeContent(fn){listener=fn;return {dispose:()=>{listener=undefined;}};},
          type(text){this.value=text;listener?.();},dispose(){this.disposed=true;}
        };models.push(model);return model;
      },
      create(host,options){
        const editor={host,options,actions:[],disposed:false,addAction(action){this.actions.push(action);},
          pushUndoStop(){},executeEdits(source,edits){options.model.type(edits[0].text);},focus(){},dispose(){this.disposed=true;}
        };editors.push(editor);return editor;
      },
      setModelMarkers:(model,owner,items)=>markers.push({model,owner,items})
    }
  };
  return {monaco,models,editors,markers,languages,providers};
}
test('dashboard selection, safe rendering, JQL editing and conflict preserve the draft', async () => {
  const script=browserScript();
  const elements = Object.fromEntries(['status','choices','dashboard','search','query'].map(id => [id,new Element('div')]));
  elements.query.value = 'Team';
  const calls = [];
  const replies = [
    {dashboards:[{id:'10',name:'<img onerror=evil()>',owner:'admin'}]},
    {id:'10',name:'Team',filters:[{id:'20',name:'Shared',owner:'Other',jql:'project = OLD',gadgets:['30']}],gadgets:[]},
    {jql:'project = NEW',message:'Filter gespeichert.'},
    {error:'Der Filter wurde inzwischen geändert.'}
  ];
  const context = vm.createContext({
    document:documentFor(elements),
    location:{pathname:'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditor'},
    fetch:async (url,options)=> {if(url.endsWith('Save?action=validate')) return {ok:true,json:async()=>({valid:true,errors:[]})}; calls.push({url,options}); const data=replies.shift(); return {ok:!data.error,json:async()=>data};},
    setTimeout, clearTimeout, encodeURIComponent
  });
  vm.runInContext(script,context);
  await elements.search.onsubmit({preventDefault(){}});
  assert.equal(elements.choices.children[0].textContent,'<img onerror=evil()> · ID 10 · admin');
  await elements.choices.children[0].onclick();
  const area=all(elements.dashboard).find(el=>el.tag==='textarea');
  const save=all(elements.dashboard).find(el=>el.tag==='button');
  assert.equal(area.value,'project = OLD');
  area.value='project = NEW';
  await save.onclick();
  assert.equal(calls[2].url,'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditorSave');
  assert.equal(calls[2].options.method,'POST');
  assert.equal(calls[2].options.headers['X-Dashboard-CSRF'],script.match(/const csrf = '([^']+)'/)[1]);
  assert.deepEqual(JSON.parse(calls[2].options.body),{dashboardId:'10',filterId:'20',originalJql:'project = OLD',jql:'project = NEW'});
  area.value='project = DRAFT';
  await save.onclick();
  assert.equal(JSON.parse(calls[3].options.body).originalJql,'project = NEW');
  assert.equal(area.value,'project = DRAFT');
  assert.equal(save.disabled,false);
  assert.ok(all(elements.dashboard).some(el=>el.textContent==='Der Filter wurde inzwischen geändert.'));
});

test('validator checks after typing, clears green immediately and ignores stale responses', async () => {
  const script=browserScript();
  const elements = Object.fromEntries(['status','choices','dashboard','search','query'].map(id=>[id,new Element('div')]));
  const pending = [];
  let timer;
  const context = vm.createContext({
    document:documentFor(elements),
    location:{pathname:'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditor'},
    encodeURIComponent,
    setTimeout:fn=>{timer=fn;return 1;}, clearTimeout:()=>{timer=undefined;},
    fetch:async(url,options)=>{
      if(url.includes('action=dashboard'))return {ok:true,json:async()=>({id:'10',name:'Team',filters:[{id:'20',name:'Shared',jql:'project = OLD',gadgets:[]}],gadgets:[]})};
      assert.ok(url.endsWith('Save?action=validate'));
      assert.equal(options.method,'POST');
      return new Promise(resolve=>pending.push({resolve,jql:JSON.parse(options.body).jql}));
    }
  });
  vm.runInContext(script,context);
  await vm.runInContext("load('10')",context);
  const area=all(elements.dashboard).find(el=>el.tag==='textarea');
  const state=all(elements.dashboard).find(el=>el.className?.startsWith('jql-state'));
  const reply=async(index,valid)=>{
    pending[index].resolve({ok:true,json:async()=>({valid,errors:valid?[]:['Unknown field']})});
    await new Promise(resolve=>setImmediate(resolve));
  };
  await reply(0,true);
  assert.equal(state.className,'jql-state valid');
  area.value='BAD';area.oninput();
  assert.equal(state.className,'jql-state unknown');
  timer();
  area.value='GOOD';area.oninput();timer();
  assert.equal(pending[1].jql,'BAD');
  assert.equal(pending[2].jql,'GOOD');
  await reply(2,true);
  await reply(1,false);
  assert.equal(state.className,'jql-state valid');
  area.value='BAD AGAIN';area.oninput();timer();await reply(3,false);
  assert.equal(state.className,'jql-state invalid');
  assert.equal(state.textContent,'Unknown field');
  area.value='';area.oninput();timer();
  assert.equal(state.className,'jql-state invalid');
  assert.equal(pending.length,4);
});

test('charts show their named saved filters, other sources and read failures with one shared editor', async () => {
  const script=browserScript();
  const elements = Object.fromEntries(['status','choices','dashboard','search','query'].map(id=>[id,new Element('div')]));
  const calls = [];
  const gadgets = [
    {id:'43710',name:'Kreisdiagramm',type:'rest/gadgets/piechart-gadget.xml',filterIds:['20'],note:'Gespeicherte Filter erkannt.',sourcePreferences:[{name:'projectOrFilterId',value:'filter-20'}]},
    {id:'70511',name:'Zweidimensionale Filterstatistik',filterIds:['20'],note:'Gespeicherte Filter erkannt.'},
    {id:'43712',name:'Kreisdiagramm',projectIds:['123'],note:'Projektquelle ohne gespeicherten Filter.'},
    {id:'43713',name:'Kreisdiagramm',directJql:['project = TEST AND summary ~ "<img onerror=evil()>"'],note:'Direkte JQL im Gadget hinterlegt.'},
    {id:'44430',name:'Filterergebnisse',filterIds:['21'],note:'Gespeicherte Filter erkannt.'},
    {id:'44431',type:'rest/gadgets/custom.xml',filterIds:[],note:'Keine Standard-Datenquelle erkannt; Gadget ungeprüft.',preferenceNames:['pluginSource']}
  ];
  const context = vm.createContext({
    document:documentFor(elements),
    location:{pathname:'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditor'},
    encodeURIComponent,setTimeout,clearTimeout,
    fetch:async(url,options)=>{
      calls.push({url,options});
      if(url.includes('action=dashboard'))return {ok:true,json:async()=>({id:'10',name:'Team',filters:[
        {id:'20',name:'Shared <img onerror=evil()>',owner:'Other',jql:'project = TEST',gadgets:['43710','70511']},
        {id:'21',error:'Filter fehlt oder konnte nicht geladen werden.',gadgets:['44430']}
      ],gadgets})};
      assert.ok(url.endsWith('Save?action=validate'));
      return {ok:true,json:async()=>({valid:true,errors:[]})};
    }
  });
  vm.runInContext(script,context);
  await vm.runInContext("load('10')",context);
  const nodes=all(elements.dashboard);
  const table=nodes.find(el=>el.tag==='table');
  const rows=all(table).filter(el=>el.tag==='tr').slice(1);
  assert.equal(rows.length,6);
  const savedFilterLink=all(rows[0]).find(el=>el.tag==='a');
  assert.equal(savedFilterLink.textContent,'Shared <img onerror=evil()> · ID 20');
  assert.equal(savedFilterLink.href,'#filter-20');
  assert.equal(all(rows[1]).find(el=>el.tag==='a').href,'#filter-20');
  assert.ok(all(rows[0]).some(el=>el.textContent==='projectOrFilterId'));
  assert.ok(all(rows[0]).some(el=>el.textContent==='filter-20'));
  assert.ok(all(rows[2]).some(el=>el.textContent==='Projekt-ID 123 (ohne gespeicherten Filter)'));
  assert.equal(all(rows[2]).filter(el=>el.tag==='a').length,0);
  assert.ok(all(rows[3]).some(el=>el.tag==='pre' && el.textContent===gadgets[3].directJql[0]));
  assert.equal(all(rows[4]).find(el=>el.tag==='a').href,'#filter-21');
  assert.ok(all(rows[4]).some(el=>el.textContent==='Filter fehlt oder konnte nicht geladen werden.'));
  assert.ok(all(rows[5]).some(el=>el.textContent.includes('ungeprüft')));
  assert.ok(all(rows[5]).some(el=>el.textContent.includes('pluginSource')));
  assert.equal(nodes.filter(el=>el.tag==='article').length,2);
  assert.ok(nodes.some(el=>el.id==='filter-20'));
  assert.ok(nodes.some(el=>el.id==='filter-21'));
  assert.ok(nodes.some(el=>el.textContent.includes('Kreisdiagramm (ID 43710), Zweidimensionale Filterstatistik (ID 70511)')));
  assert.equal(nodes.filter(el=>el.tag==='textarea').length,1);
  assert.equal(nodes.filter(el=>el.tag==='img').length,0);
  assert.equal(calls.length,2); // One dashboard read and one validation, no remote gadget lookup or save.
});

test('Monaco edits JQL, offers completions, shows Jira errors and disposes models on dashboard changes', async () => {
  const fixture=monacoFixture(),elements=Object.fromEntries(['status','choices','dashboard','search','query'].map(id=>[id,new Element('div')]));
  let timer,saveBody;
  const events={};
  const context=vm.createContext({
    ...fixture,document:documentFor(elements),addEventListener:(name,fn)=>{events[name]=fn;},
    location:{pathname:'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditor'},encodeURIComponent,
    setTimeout:fn=>{timer=fn;return 1;},clearTimeout:()=>{timer=undefined;},
    fetch:async(url,options)=>{
      if(url.includes('action=dashboard'))return {ok:true,json:async()=>({id:'10',name:'Team',filters:[{id:'20',name:'Shared',jql:'project = OLD',gadgets:[]}],gadgets:[]})};
      const body=JSON.parse(options.body);
      if(url.includes('action=validate'))return {ok:true,json:async()=>({valid:body.jql!=='BAD',errors:body.jql==='BAD'?['Unknown project']:[]})};
      saveBody=body;return {ok:true,json:async()=>({jql:'project = SAVED',message:'Filter gespeichert.'})};
    }
  });
  vm.runInContext(browserScript(),context);
  await vm.runInContext("load('10')",context);await flush();
  const model=fixture.models[0],editor=fixture.editors[0],area=all(elements.dashboard).find(el=>el.tag==='textarea');
  assert.equal(model.language,'jql');assert.equal(model.getValue(),'project = OLD');
  assert.equal(area.hidden,true);assert.equal(editor.host.hidden,false);
  assert.equal(editor.options.wordWrap,'on');assert.equal(editor.options.minimap.enabled,false);
  const completion=fixture.providers.find(p=>p.completion).completion.provideCompletionItems(model,{lineNumber:1,column:4});
  assert.ok(completion.suggestions.some(item=>item.label==='project'));
  assert.ok(completion.suggestions.some(item=>item.label==='currentUser()'));
  model.type('BAD');assert.equal(area.value,'BAD');timer();await flush();
  assert.equal(fixture.markers.at(-1).owner,'jira-jql');
  assert.equal(fixture.markers.at(-1).items[0].message,'Unknown project');
  model.type('x'.repeat(32001));timer();await flush();
  assert.ok(fixture.markers.at(-1).items[0].message.includes('32000'));
  model.type('project = NEW');
  await editor.actions.find(action=>action.id==='save-jql').run();
  assert.equal(saveBody.jql,'project = NEW');assert.equal(saveBody.originalJql,'project = OLD');
  assert.equal(model.getValue(),'project = SAVED');assert.equal(area.value,'project = SAVED');
  assert.equal(fixture.markers.at(-1).items.length,0);
  await vm.runInContext("load('11')",context);await flush();
  assert.equal(editor.disposed,true);assert.equal(model.disposed,true);
  assert.equal(fixture.languages.length,1);
  events.pagehide();assert.equal(fixture.models[1].disposed,true);assert.equal(fixture.editors[1].disposed,true);
});

test('Monaco keeps a newer draft during save and uses the saved revision for the next request', async () => {
  const fixture=monacoFixture(),elements=Object.fromEntries(['status','choices','dashboard','search','query'].map(id=>[id,new Element('div')]));
  let completeSave;const bodies=[];
  const context=vm.createContext({
    ...fixture,document:documentFor(elements),location:{pathname:'/rest/scriptrunner/latest/custom/dashboardFilterEditor'},encodeURIComponent,
    setTimeout:()=>1,clearTimeout(){},
    fetch:async(url,options)=>{
      if(url.includes('action=dashboard'))return {ok:true,json:async()=>({id:'10',name:'Team',filters:[{id:'20',name:'Shared',jql:'project = OLD',gadgets:[]}],gadgets:[]})};
      if(url.includes('action=validate'))return {ok:true,json:async()=>({valid:true,errors:[]})};
      bodies.push(JSON.parse(options.body));
      return new Promise(resolve=>{completeSave=()=>resolve({ok:true,json:async()=>({jql:bodies.at(-1).jql,message:'Filter gespeichert.'})});});
    }
  });
  vm.runInContext(browserScript(),context);await vm.runInContext("load('10')",context);await flush();
  const model=fixture.models[0],save=fixture.editors[0].actions[0];
  model.type('project = FIRST');const firstSave=save.run();
  model.type('project = NEW_DRAFT');
  await save.run();assert.equal(bodies.length,1); // Repeated shortcut cannot submit twice.
  completeSave();await firstSave;
  assert.equal(model.getValue(),'project = NEW_DRAFT');
  assert.ok(all(elements.dashboard).some(el=>el.textContent.includes('Neuere Eingaben sind noch nicht gespeichert.')));
  const secondSave=save.run();assert.equal(bodies[1].originalJql,'project = FIRST');
  assert.equal(bodies[1].jql,'project = NEW_DRAFT');completeSave();await secondSave;
});

test('a delayed local Monaco loader preserves typing and does not mount a stale dashboard', async () => {
  const fixture=monacoFixture(),elements=Object.fromEntries(['status','choices','dashboard','search','query'].map(id=>[id,new Element('div')]));
  const doc=documentFor(elements);let script,amdReady,configuration;
  doc.head.append=el=>{script=el;};
  const amd=(modules,ready)=>{assert.equal(modules[0],'vs/editor/editor.main');amdReady=ready;};amd.config=value=>{configuration=value;};
  const context=vm.createContext({
    document:doc,require:amd,location:{pathname:'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditor'},encodeURIComponent,setTimeout,clearTimeout,
    fetch:async(url)=>({ok:true,json:async()=>url.includes('action=dashboard')?{id:'10',name:'Team',filters:[{id:'20',name:'Shared',jql:'project = OLD',gadgets:[]}],gadgets:[]}:{valid:true,errors:[]}})
  });
  const source=browserScript().replace(/const monacoBase = [^;]+;/,'const monacoBase = "/jira/download/resources/fixture:monaco/vs";');
  vm.runInContext(source,context);await vm.runInContext("load('10')",context);
  assert.equal(script.src,'/jira/download/resources/fixture:monaco/vs/loader.js');
  await vm.runInContext("load('11')",context);
  const area=all(elements.dashboard).find(el=>el.tag==='textarea');area.value='project = TYPED_BEFORE_LOAD';area.oninput();
  script.onload();assert.equal(configuration.paths.vs,'/jira/download/resources/fixture:monaco/vs');
  context.monaco=fixture.monaco;amdReady();await flush();
  assert.equal(fixture.models.length,1);assert.equal(fixture.models[0].getValue(),'project = TYPED_BEFORE_LOAD');
  assert.equal(area.hidden,true);
  assert.equal(context.MonacoEnvironment.getWorkerUrl(),'/jira/rest/scriptrunner/latest/custom/dashboardFilterEditor?action=monacoWorker');
});
