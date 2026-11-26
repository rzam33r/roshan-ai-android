window.__calls=[];window.__ps={};window.__sched={ok:true};
window.AndroidPlan={available:function(){return true;},plan:function(id,g){window.__calls.push('plan');window.__ps.id=id;window.__ps.goal=g;}};
window.AndroidSched={add:function(k,t,y,mo,d,h,mi){window.__calls.push('add:'+k+':'+t);return JSON.stringify(window.__sched.ok?{ok:true,id:'1'}:{ok:false,error:window.__sched.err});},list:function(){return '[]';},cancel:function(){return true;}};
window.AndroidApps={find:function(n){window.__calls.push('find:'+n);return JSON.stringify(window.__find||{ok:true,exact:true,seen:5,total:1,items:[{label:'YouTube',pkg:'com.yt'}]});},open:function(p){window.__calls.push('open:'+p);return '{"ok":true}';},handoff:function(k,a,b){window.__calls.push('handoff:'+k+':'+a+':'+b);return JSON.stringify(window.__ho||{ok:true});}};
window.AndroidAI={available:function(){return false;},reset:function(){window.__calls.push('reset');}};

window.__drv={};window.__thumbs=[];
function __rec(n){return function(){var a=[].slice.call(arguments);window.__drv.id=a[0];window.__calls.push(n+':'+a.slice(1).join('|'));};}
window.AndroidDrive={available:function(){return true;},search:function(id,t){window.__calls.push('drive:'+t);window.__drv.id=id;},open:function(l){window.__calls.push('dopen:'+l);return true;},
 list:__rec('dlist'),thumbs:function(g,c){window.__thumbs.push('thumbs:'+g+':'+c);},access:__rec('daccess'),download:__rec('ddl'),mkdir:__rec('dmk'),move:__rec('dmv'),upload:__rec('dup')};
