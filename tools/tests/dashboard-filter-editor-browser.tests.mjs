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
