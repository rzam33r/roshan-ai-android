(function(){
var res=[];function ok(n,c){res.push('JSTEST '+(c?'PASS':'FAIL')+' '+n);}
function plan(steps,goal){window.__calls=[];document.getElementById('inp').value=goal||'task';document.getElementById('tbtn').click();
  window.onPlanResult(window.__ps.id,JSON.stringify({ok:true,model:'m',steps:steps}));}
function btns(){return [].slice.call(document.querySelectorAll('button.rty'));}
function btn(t,i){var a=btns().filter(function(x){return x.textContent===t;});return a[i===undefined?a.length-1:i];}
function calls(){return window.__calls.filter(function(c){return c!=='plan';});}
var A={tool:'set_alarm',args:{kind:'alarm',title:'a',y:2030,mo:1,d:1,h:7,mi:0},text:'alarm a'};
var APP={tool:'open_app',args:{name:'YouTube'},text:'open the app YouTube'};
// 1 nothing runs before confirm
plan([A,APP]);ok('no effect before confirm',calls().length===0);
// 2 confirm runs once, double tap no repeat, in order
var c=btn('CONFIRM');c.click();c.click();btn('CONFIRM').click();
ok('runs exactly once after confirm',calls().join()==='add:alarm:a,find:YouTube,open:com.yt');
// 3 cancel then confirm does nothing
plan([A]);btn('CANCEL').click();btn('CONFIRM').click();ok('cancel blocks run',calls().length===0);
// 4 stale: second plan supersedes first
plan([A]);var old=btn('CONFIRM');window.__calls=[];window.onPlanResult=window.onPlanResult;
document.getElementById('inp').value='x';document.getElementById('tbtn').click();window.onPlanResult(window.__ps.id,JSON.stringify({ok:true,model:'m',steps:[APP]}));
old.click();ok('old plan buttons dead after new plan',calls().length===0&&old.disabled===true);
btn('CONFIRM').click();ok('new plan runs',calls().join()==='find:YouTube,open:com.yt');
// 5 new chat kills pending plan
plan([A]);var pb=btn('CONFIRM');window.newChat();pb.click();ok('new chat cancels pending plan',calls().filter(function(x){return x!=='reset';}).length===0);
// 6 stale by age
plan([A]);var q=btn('CONFIRM');var RN=Date.now;Date.now=function(){return RN()+11*60000;};q.click();Date.now=RN;ok('expired plan not run',calls().length===0&&/more than 10 minutes/.test(document.getElementById('log').lastChild.textContent));
// 7 untrusted text is shown as text, not markup
plan([{tool:'open_link',args:{url:'https://a.b'},text:'<img src=x onerror=window.__pwn=1>'}]);ok('plan text is not parsed as HTML',!window.__pwn&&!document.querySelector('img[src="x"]'));
// 8 unknown tool at execution is refused, nothing launched
plan([{tool:'send_sms',args:{to:'+1',body:'x'},text:'sneaky'}]);btn('CONFIRM').click();ok('unknown tool refused',calls().length===0&&/FAILED/.test(document.getElementById('log').textContent));
// 9 permission denial is reported truthfully
window.__sched={ok:false,err:'notifications_off'};plan([A]);btn('CONFIRM').click();
ok('denied alarm reported as FAILED',/FAILED/.test(document.getElementById('log').lastChild.textContent)&&/0 of 1/.test(document.getElementById('log').lastChild.textContent)&&/Notifications are off/.test(document.getElementById('log').lastChild.textContent));
window.__sched={ok:true};
// 10 ambiguous app: not opened
window.__find={ok:true,exact:false,seen:5,total:2,items:[{label:'Maps',pkg:'p1'},{label:'Maps Go',pkg:'p2'}]};plan([{tool:'open_app',args:{name:'Maps'},text:'open Maps'}]);btn('CONFIRM').click();
ok('ambiguous app not guessed',calls().join()==='find:Maps'&&/more than one app/.test(document.getElementById('log').lastChild.textContent));window.__find=null;
// 11 OS refusal on compose is reported, and success text says not sent
window.__ho={ok:false,error:'no_app'};plan([{tool:'compose_message',args:{channel:'sms',to:'+971561935270',body:'hi'},text:'sms'}]);btn('CONFIRM').click();
ok('no handler reported',/FAILED/.test(document.getElementById('log').lastChild.textContent));window.__ho=null;
plan([{tool:'compose_message',args:{channel:'sms',to:'+971561935270',body:'hi'},text:'sms'}]);btn('CONFIRM').click();
ok('compose success says NOT sent',/NOT sent/.test(document.getElementById('log').lastChild.textContent));
// 12 planner failure / validation failure does nothing
window.__calls=[];document.getElementById('inp').value='y';document.getElementById('tbtn').click();window.onPlanResult(window.__ps.id,JSON.stringify({ok:false,error:'bad_url'}));
ok('rejected plan does nothing and offers no confirm',calls().length===0&&btns().filter(function(x){return !x.disabled&&x.textContent==='CONFIRM';}).length===0);
window.__calls=[];document.getElementById('inp').value='z';document.getElementById('tbtn').click();window.onPlanResult(window.__ps.id,JSON.stringify({failed:'The planner call failed (m): boom'}));
ok('planner error shown honestly',/Planning failed, so nothing was done/.test(document.getElementById('log').lastChild.textContent));
// Drive: command routes only to the Drive bridge, results stay local, errors are generic
window.__calls=[];document.getElementById('inp').value='drive tax invoice';document.getElementById('send').click();
ok('drive command calls Drive bridge once with the term',window.__calls.join()==='drive:tax invoice');
window.onDriveResult(window.__drv.id,JSON.stringify({ok:true,error:'',items:[{name:'Invoice 1.pdf',type:'application/pdf',modified:'2026-01-02T03:04:05Z',link:'https://drive.google.com/file/d/1/view'}]}));
var dopen=[].slice.call(document.querySelectorAll('#log button')).filter(function(x){return x.textContent==='OPEN';}).pop();
ok('drive result drawn with OPEN',!!dopen&&/Invoice 1\.pdf/.test(document.getElementById('log').textContent));
window.__calls=[];dopen.click();ok('OPEN passes link to bridge',window.__calls.join()==='dopen:https://drive.google.com/file/d/1/view');
window.__calls=[];document.getElementById('inp').value='x';document.getElementById('dbtn').click();
window.onDriveResult(window.__drv.id,JSON.stringify({ok:false,error:'denied',detail:'Secret.pdf'}));
var lt=document.getElementById('log').lastChild.textContent;ok('drive error is generic, no names',/Google refused Drive access/.test(lt)&&lt.indexOf('Secret')<0);
window.__calls=[];document.getElementById('inp').value='drive ';document.getElementById('send').click();
ok('empty drive term does not call bridge',window.__calls.indexOf('drive:')<0);
document.getElementById('inp').value='';document.getElementById('dbtn').click();ok('DRIVE button with empty box asks for a name',/Type part of a file name/.test(document.getElementById('log').lastChild.textContent));
// Drive then ordinary Gemini chat, FILE and TASK: nothing shown by Drive may reach the model-bound bridges
(function(){var sent=[];var oa=window.AndroidAI;
window.AndroidAI={available:function(){return true;},reset:function(){},retry:function(){},ask:function(id,q){sent.push('ask:'+q);setTimeout(function(){},0);window.__aid=id;}};
var op=window.AndroidPlan.plan;window.AndroidPlan.plan=function(id,g){sent.push('plan:'+g);window.__ps.id=id;};
document.getElementById('inp').value='drive Quarterly Payroll';document.getElementById('send').click();
window.onDriveResult(window.__drv.id,JSON.stringify({ok:true,error:'',items:[{name:'Payroll Secret Q3.xlsx',type:'x',modified:'2026-01-01T00:00:00Z',link:'https://drive.google.com/file/d/9/view'}]}));
document.getElementById('inp').value='what is the capital of France';document.getElementById('send').click();
window.onAiResult(window.__aid,true,'Paris');
document.getElementById('inp').value='plan my week';document.getElementById('tbtn').click();
document.getElementById('inp').value='drive nothing';document.getElementById('send').click();
window.onDriveResult(window.__drv.id,JSON.stringify({ok:false,error:'network'}));
window.__ps.id&&window.onPlanResult&&window.onPlanResult(window.__ps.id,JSON.stringify({ok:false,error:'no_actions'}));
document.getElementById('inp').value='thanks';document.getElementById('send').click();
var all=sent.join('|');
ok('Gemini chat and TASK got only the typed words, no Drive query or file names',sent.length===3&&all.indexOf('Payroll')<0&&all.indexOf('Secret')<0&&all.indexOf('drive')<0&&/ask:what is the capital of France/.test(all)&&/plan:plan my week/.test(all)&&/ask:thanks/.test(all));
window.AndroidAI=oa;window.AndroidPlan.plan=op;})();
res.push('JSTEST DONE');var p=document.createElement('pre');p.id='jsres';p.textContent=res.join('\n');document.body.appendChild(p);
})();
