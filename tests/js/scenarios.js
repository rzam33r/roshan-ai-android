window.__TAP_MS=0;
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
document.getElementById('inp').value='';window.__calls=[];document.getElementById('dbtn').click();ok('G DRIVE button with empty box opens the Drive root',window.__calls.join()==='dlist:root');window.onDriveResult(window.__drv.id,JSON.stringify({ok:true,error:'',items:[]}));
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
window.onAiResult(window.__aid,true,'ok');var all=sent.join('|');
ok('Gemini chat and TASK got only the typed words, no Drive query or file names',sent.length===3&&all.indexOf('Payroll')<0&&all.indexOf('Secret')<0&&all.indexOf('drive')<0&&/ask:what is the capital of France/.test(all)&&/plan:plan my week/.test(all)&&/ask:thanks/.test(all));
window.AndroidAI=oa;window.AndroidPlan.plan=op;})();

// FILES file manager: browse, download, move, upload, new folder. Every write waits for an explicit confirm.
(function(){
function drv(o){window.onDriveResult(window.__drv.id,JSON.stringify(o));}
function b(label){var all=[].slice.call(document.querySelectorAll('#log button'));var s=0;all.forEach(function(x,i){if(x.textContent==='UPLOAD HERE')s=i;});if(s>0&&all[s-1].textContent==='BACK')s--;return all.slice(s).filter(function(x){return x.textContent===label;});}
function lastb(label){var a=b(label);return a[a.length-1];}
function txt(){return document.getElementById('log').textContent;}
aiBusy=false;window.__calls=[];document.getElementById('inp').value='';document.getElementById('dbtn').click();
ok('G DRIVE opens root list',window.__calls.join()==='dlist:root');
var items=[{id:'fold11111',name:'Taxes',type:'application/vnd.google-apps.folder',folder:true,size:-1,modified:'2026-01-01T00:00:00Z',shared:false,link:''},{id:'file22222',name:'Plan.docx',type:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',folder:false,size:2048,modified:'2026-02-01T00:00:00Z',shared:false,link:'https://drive.google.com/file/d/file22222/view'},{id:'gdoc33333',name:'Notes',type:'application/vnd.google-apps.document',folder:false,size:-1,modified:'',shared:false,link:''}];
drv({ok:true,error:'',op:'list',items:items});
ok('root shows folder and files with buttons',/Drive: My Drive/.test(txt())&&b('OPEN FOLDER').length===1&&b('DOWNLOAD').length===2&&b('MOVE').length===2&&b('UPLOAD HERE').length===1&&b('NEW FOLDER').length===1);
window.__calls=[];lastb('OPEN FOLDER').click();ok('OPEN FOLDER lists that folder',window.__calls.join()==='dlist:fold11111');
drv({ok:true,error:'',op:'list',items:[]});ok('empty folder shown with BACK',/Taxes\s+\(empty\)/.test(txt())&&b('BACK').length>=1);
window.__calls=[];lastb('BACK').click();ok('BACK lists root again',window.__calls.join()==='dlist:root');drv({ok:true,error:'',op:'list',items:items});
// download
window.__calls=[];b('DOWNLOAD')[0].click();ok('DOWNLOAD passes id and type',window.__calls.join()==='ddl:file22222|application/vnd.openxmlformats-officedocument.wordprocessingml.document');
drv({ok:true,error:'',op:'dl',items:[],info:{name:'Plan.docx',bytes:2048}});ok('download result says saved to Downloads',/Saved Plan\.docx to your phone Downloads/.test(txt()));
window.__calls=[];b('DOWNLOAD')[1].click();drv({ok:false,error:'export_too_big'});ok('export limit stated honestly',/10 MB/.test(txt()));
// move: nothing changes before CONFIRM
window.__calls=[];b('MOVE')[0].click();ok('MOVE only arms move mode',window.__calls.length===0&&/Moving: Plan\.docx/.test(txt()));
window.__calls=[];lastb('OPEN FOLDER').click();drv({ok:true,error:'',op:'list',items:[]});
window.__calls=[];lastb('MOVE HERE').click();ok('MOVE HERE first only reads who can see the source',window.__calls.join()==='daccess:root');
drv({ok:true,error:'',op:'access',items:[],info:{level:'private',text:'Only you can see this folder.',name:'My Drive'}});
ok('then reads the destination',window.__calls.join()==='daccess:root,daccess:fold11111');
drv({ok:true,error:'',op:'access',items:[],info:{level:'shared',text:'Anyone with the link can see this folder.',name:'Taxes'}});
ok('confirm shows source, destination and access change; no move yet',/Move "Plan\.docx" from "My Drive" to "Taxes"/.test(txt())&&/Anyone with the link/.test(txt())&&/may change/.test(txt())&&window.__calls.join().indexOf('dmv')<0);
lastb('CANCEL').click();ok('cancel does not move',window.__calls.join().indexOf('dmv')<0);
window.__calls=[];lastb('MOVE HERE').click();drv({ok:true,error:'',op:'access',items:[],info:{level:'private',text:'Only you can see this folder.'}});drv({ok:true,error:'',op:'access',items:[],info:{level:'private',text:'Only you can see this folder.'}});
window.__calls=[];lastb('MOVE').click();ok('confirm MOVE calls bridge with file, from, to',window.__calls.join()==='dmv:file22222|root|fold11111');
drv({ok:true,error:'',op:'mv',items:[],info:{name:'Plan.docx'}});ok('move result shown, then folder refreshed',/Moved Plan\.docx to Taxes/.test(txt()));
drv({ok:true,error:'',op:'list',items:[]});
// move with unknown audience is refused (source unknown, then destination unknown)
window.__calls=[];lastb('BACK').click();drv({ok:true,error:'',op:'list',items:items});
b('MOVE')[0].click();window.__calls=[];lastb('OPEN FOLDER').click();drv({ok:true,error:'',op:'list',items:[]});
window.__calls=[];lastb('MOVE HERE').click();drv({ok:true,error:'',op:'access',items:[],info:{level:'unknown',text:'x'}});
ok('UNKNOWN source: move refused, destination not even read, no bridge move',window.__calls.join()==='daccess:root'&&/cannot tell who can see/.test(txt())&&b('MOVE').filter(function(x){return x.parentNode&&/Move "/.test(x.parentNode.textContent);}).length===0);
window.__calls=[];lastb('MOVE HERE').click();drv({ok:true,error:'',op:'access',items:[],info:{level:'private',text:'Only you can see this folder.'}});drv({ok:true,error:'',op:'access',items:[],info:{level:'unknown',text:'x'}});
ok('UNKNOWN destination: move refused, no bridge move',window.__calls.join().indexOf('dmv')<0);
lastb('CANCEL MOVE').click();drv({ok:true,error:'',op:'list',items:[]});
// upload and new folder wait for confirm
window.__calls=[];lastb('UPLOAD HERE').click();ok('UPLOAD HERE checks access first, no upload yet',window.__calls.join()==='daccess:fold11111');
drv({ok:true,error:'',op:'access',items:[],info:{level:'unknown',text:'Google did not say who can see this folder, so it may be shared.'}});
ok('UNKNOWN audience: upload refused, no confirm, no bridge call',/cannot tell who can see/.test(txt())&&window.__calls.join().indexOf('dup')<0&&b('CONTINUE').length===0);
window.__calls=[];lastb('UPLOAD HERE').click();drv({ok:true,error:'',op:'access',items:[],info:{level:'shared',text:'Anyone with the link can see this folder.'}});
ok('upload confirm names folder, shows who can see it, says never replaces',/Upload a file from your phone into "Taxes"/.test(txt())&&/Anyone with the link/.test(txt())&&/never replaced/.test(txt())&&window.__calls.join().indexOf('dup')<0);
window.__calls=[];lastb('CONTINUE').click();ok('CONTINUE starts the picker upload',window.__calls.join()==='dup:fold11111');
drv({ok:true,error:'',op:'up',items:[],info:{name:'a (2).pdf',renamed:true,bytes:5}});ok('upload result mentions the new name',/Uploaded a \(2\)\.pdf/.test(txt())&&/new name/.test(txt()));drv({ok:true,error:'',op:'list',items:[]});
window.__calls=[];lastb('NEW FOLDER').click();var ti=document.querySelector('#log input[type=text]');ti.value='Receipts';lastb('CREATE').click();
ok('NEW FOLDER checks access first',window.__calls.join()==='daccess:fold11111');
drv({ok:true,error:'',op:'access',items:[],info:{level:'shared',text:'Other people can see this folder (1 people, 0 groups, 0 domains).'}});
ok('shared parent needs confirm before mkdir',window.__calls.join().indexOf('dmk')<0&&/Create "Receipts" inside "Taxes"/.test(txt()));
window.__calls=[];lastb('CREATE').click();ok('confirmed mkdir passes name',window.__calls.join()==='dmk:fold11111|Receipts');
drv({ok:true,error:'',op:'mkdir',items:[],info:{name:'Receipts'}});drv({ok:true,error:'',op:'list',items:[]});
window.__calls=[];lastb('NEW FOLDER').click();ti=document.querySelector('#log input[type=text]');ti.value='X1';lastb('CREATE').click();drv({ok:true,error:'',op:'access',items:[],info:{level:'unknown',text:'x'}});ok('UNKNOWN audience: new folder refused',window.__calls.join().indexOf('dmk')<0&&/cannot tell who can see/.test(txt()));
window.__calls=[];lastb('NEW FOLDER').click();ti=document.querySelector('#log input[type=text]');ti.value='X2';lastb('CREATE').click();drv({ok:true,error:'',op:'access',items:[],info:{level:'private',text:'Only you can see this folder.'}});ok('PRIVATE parent still asks confirm before mkdir',window.__calls.join().indexOf('dmk')<0&&/Create "X2" inside/.test(txt()));lastb('CANCEL').click();
// errors are fixed sentences
window.__calls=[];lastb('UPLOAD HERE').click();drv({ok:false,error:'unsupported',detail:'Secret Drive.xlsx'});ok('error text is generic',/shared drive/.test(txt())&&txt().indexOf('Secret Drive')<0);
// FILES then Gemini / TASK: only typed words go to the model bridges
var sent=[];var oa=window.AndroidAI,op=window.AndroidPlan.plan;
window.AndroidAI={available:function(){return true;},reset:function(){window.__calls.push('reset');},retry:function(){},ask:function(id,q){sent.push('ask:'+q);window.__aid=id;}};
window.AndroidPlan.plan=function(id,g){sent.push('plan:'+g);window.__ps.id=id;};
document.getElementById('inp').value='hello there';document.getElementById('send').click();window.onAiResult(window.__aid,true,'Hi');
document.getElementById('inp').value='plan lunch';document.getElementById('tbtn').click();
window.onAiResult(window.__aid,true,'ok');var all=sent.join('|');ok('after file manager use, model bridges got only typed words',sent.length===2&&all.indexOf('Taxes')<0&&all.indexOf('Plan.docx')<0&&all.indexOf('Receipts')<0);
window.AndroidAI=oa;window.AndroidPlan.plan=op;
// FOUR main buttons only: FILES, TASK, G DRIVE, SEND. No NEW CHAT, MIC or PROMPTS button.
var all2=[].slice.call(document.querySelectorAll('button')).map(function(x){return x.textContent;});
var row=[].slice.call(document.querySelectorAll('#fbtn,#tbtn,#send')).map(function(x){return x.textContent;});
ok('PROMPTS button is gone, no prompt panel',all2.indexOf('PROMPTS')<0&&!document.getElementById('plib'));
ok('visible main row is exactly FILES, TASK, SEND (G DRIVE moved to Cloud menu)',row.join()==='FILES,TASK,SEND'&&document.getElementById('dbtn').offsetParent===null&&all2.indexOf('NEW CHAT')<0&&all2.indexOf('MIC')<0&&!document.getElementById('pbtn')&&!document.getElementById('mic'));
// typed reset, local only
(function(){var sent=[];var oa=window.AndroidAI;
window.AndroidAI={available:function(){return true;},reset:function(){sent.push('reset');},retry:function(){},ask:function(id,q){sent.push('ask:'+q);}};
window.__calls=[];document.getElementById('inp').value='new chat';document.getElementById('send').click();
ok('typed "new chat" resets, nothing goes to Gemini',sent.join()==='reset'&&document.getElementById('inp').value===''&&/New chat started/.test(txt()));
var inpEl=document.getElementById('inp');inpEl.dispatchEvent(new Event('focus'));inpEl.focus();
ok('focusing the typing box does not reset',sent.join()==='reset');
window.AndroidAI=oa;})();
// SEND: one tap sends once, two quick taps = mic only, draft kept
(function(){var sent=[],voice=[],timers=[];var oa=window.AndroidAI,ov=window.AndroidVoice;
window.AndroidAI={available:function(){return true;},reset:function(){},retry:function(){},ask:function(id,q){sent.push(q);window.__aid=id;}};
window.AndroidVoice={start:function(){voice.push('start');},stop:function(){voice.push('stop');}};
window.__TAP_MS=300;window.__tapSet=function(f,ms){timers.push(f);return timers.length;};window.__tapClear=function(h){timers[h-1]=null;};
var s=document.getElementById('send'),i=document.getElementById('inp');
i.value='my draft';s.click();
ok('first tap alone sends nothing yet',sent.length===0&&voice.length===0&&i.value==='my draft');
timers[0]&&timers[0]();
ok('single tap sends exactly once after the wait',sent.join('|')==='my draft'&&voice.length===0&&i.value==='');window.onAiResult(window.__aid,true,'ok');
timers.length=0;i.value='keep me';s.click();s.click();
ok('double tap starts mic, sends nothing, draft kept',sent.length===1&&voice.join()==='start'&&i.value==='keep me'&&s.textContent==='STOP');
timers.forEach(function(f){if(f)f();});
ok('timer from a double tap never sends later',sent.length===1);
s.click();ok('tap while listening stops the mic at once',voice.join()==='start,stop');
window.onVoiceResult(true,'hello');
ok('dictation is added to the draft, not replacing it',i.value==='keep me hello'&&s.textContent==='SEND');
window.__TAP_MS=0;delete window.__tapSet;delete window.__tapClear;window.AndroidAI=oa;window.AndroidVoice=ov;})();
})();
(function(){function txt(){return document.getElementById('log').textContent;}window.__calls=[];document.getElementById('inp').value='';document.getElementById('dbtn').click();window.onDriveResult(window.__drv.id,JSON.stringify({ok:false,error:'auth_c13',items:[]}));ok('auth code is shown as a number only',/failed with code 13/.test(txt()));
window.__calls=[];document.getElementById('dbtn').click();window.onDriveResult(window.__drv.id,JSON.stringify({ok:false,error:'auth_p_authorize_SomeException',items:[]}));ok('auth step and class name are shown',/step "authorize" \(SomeException\)/.test(txt()));
window.__calls=[];document.getElementById('dbtn').click();window.onDriveResult(window.__drv.id,JSON.stringify({ok:false,error:'auth_p_x<b>_Y',items:[]}));ok('unknown auth key is not shown raw',txt().indexOf('<b>')<0);})();

// /command and Cloud dropdowns
(function(){function txt(){return document.getElementById('log').textContent;}
var mn=document.getElementById('menu');
ok('top-right text is /command, FILAMENT TORUS gone',document.getElementById('chip-cmd').textContent.indexOf('/command')===0&&document.getElementById('chip-gfx')===null);
ok('chip shows /command',document.getElementById('chip-cmd').textContent.indexOf('/command')===0);
document.getElementById('chip-cmd').click();
var its=[].slice.call(mn.querySelectorAll('button')).map(function(x){return x.textContent;});
ok('/command menu lists only implemented commands',its.join()==='/graphics,/new,/files,/task,/drive'&&mn.style.display==='block');
window.__calls=[];aiBusy=false;var before=txt();
mn.querySelectorAll('button')[4].click();
ok('/drive menu item runs the Drive flow and closes menu',window.__calls.join()==='dlist:root'&&mn.style.display==='none');
document.getElementById('chip-cmd').click();mn.querySelectorAll('button')[0].click();
ok('/graphics menu item opens picker',document.getElementById('picker').classList.contains('open'));
document.getElementById('chip-cloud').click();
var c=[].slice.call(mn.querySelectorAll('button')).map(function(x){return x.textContent;});
ok('Cloud menu lists G Drive only',c.join()==='G Drive');
window.onDriveResult(window.__drv.id,JSON.stringify({ok:false,error:'auth_p_x',items:[]}));window.__calls=[];aiBusy=false;mn.querySelectorAll('button')[0].click();
ok('Cloud G Drive runs Drive flow',window.__calls.join()==='dlist:root');
ok('menu selections do not reach Gemini or planner',window.__calls.indexOf('ask')<0);
})();

// real-touch regression: header must not be pointer-events:none for the chips, and header must sit above #dash
(function(){var cs=function(id){return getComputedStyle(document.getElementById(id)).pointerEvents;};
ok('chips accept pointer events (not inherited none)',cs('chip-cmd')==='auto'&&cs('chip-cloud')==='auto');
ok('menu accepts pointer events',getComputedStyle(document.getElementById('menu')).pointerEvents==='auto');
document.body.classList.remove('pk');ok('header stacks above dash',parseInt(getComputedStyle(document.querySelector('header')).zIndex,10)>parseInt(getComputedStyle(document.getElementById('dash')).zIndex,10));})();
res.push('JSTEST DONE');var p=document.createElement('pre');p.id='jsres';p.textContent=res.join('\n');document.body.appendChild(p);
})();
