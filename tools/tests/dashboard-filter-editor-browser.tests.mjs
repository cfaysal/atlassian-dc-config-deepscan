import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.textContent = ''; this.value = ''; }
  append(el) { this.children.push(el); }
  replaceChildren() { this.children = []; }
  setAttribute() {}
}
const all = el => [el, ...el.children.flatMap(all)];
test('dashboard selection, safe rendering, JQL editing and conflict preserve the draft', async () => {
  const source = readFileSync('jira/dashboardFilterEditor.groovy', 'utf8');
  const script = source.match(/<script>\n([\s\S]*?)<\/script>/)[1];
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
    document:{getElementById:id=>elements[id],createElement:tag=>new Element(tag)},
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
  assert.equal(calls[2].options.headers['X-Dashboard-CSRF'],'__TOKEN__');
  assert.deepEqual(JSON.parse(calls[2].options.body),{dashboardId:'10',filterId:'20',originalJql:'project = OLD',jql:'project = NEW'});
  area.value='project = DRAFT';
  await save.onclick();
  assert.equal(JSON.parse(calls[3].options.body).originalJql,'project = NEW');
  assert.equal(area.value,'project = DRAFT');
  assert.equal(save.disabled,false);
  assert.ok(all(elements.dashboard).some(el=>el.textContent==='Der Filter wurde inzwischen geändert.'));
});

test('validator checks after typing, clears green immediately and ignores stale responses', async () => {
  const source = readFileSync('jira/dashboardFilterEditor.groovy', 'utf8');
  const script = source.match(/<script>\n([\s\S]*?)<\/script>/)[1];
  const elements = Object.fromEntries(['status','choices','dashboard','search','query'].map(id=>[id,new Element('div')]));
  const pending = [];
  let timer;
  const context = vm.createContext({
    document:{getElementById:id=>elements[id],createElement:tag=>new Element(tag)},
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
  const source = readFileSync('jira/dashboardFilterEditor.groovy', 'utf8');
  const script = source.match(/<script>\n([\s\S]*?)<\/script>/)[1];
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
    document:{getElementById:id=>elements[id],createElement:tag=>new Element(tag)},
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
