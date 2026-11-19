// Gemeinsame Helfer für beide Oberflächen
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
/** Kurze Meldung. Fehler (toastErr) bleiben stehen, bis man sie schließt, und lassen sich kopieren. */
function toast(t,err){let e=$('#toast');if(e&&!e.querySelector('.tmsg')){e.remove();e=null}
  if(!e){e=document.createElement('div');e.id='toast';e.className='toast';e.setAttribute('role','status');
    e.innerHTML='<div class="tmsg"></div><div class="tact"><button type="button" data-tcopy>Kopieren</button><button type="button" data-tx aria-label="Schließen">✕</button></div>';document.body.append(e);
    e.onclick=async ev=>{if(ev.target.closest('[data-tx]')){e.hidden=true;return}
      if(ev.target.closest('[data-tcopy]')){const txt=e.querySelector('.tmsg').textContent;
        try{await navigator.clipboard.writeText(txt)}catch(_){const r=document.createRange();r.selectNodeContents(e.querySelector('.tmsg'));getSelection().removeAllRanges();getSelection().addRange(r);document.execCommand('copy')}
        ev.target.textContent='Kopiert';setTimeout(()=>ev.target.textContent='Kopieren',1500)}};
    e.onmouseenter=()=>clearTimeout(toast.h);e.onmouseleave=()=>{if(!e.classList.contains('err'))toast.h=setTimeout(()=>e.hidden=true,2500)}}
  e.querySelector('.tmsg').textContent=t;e.classList.toggle('err',!!err);e.hidden=false;clearTimeout(toast.h);
  if(!err)toast.h=setTimeout(()=>e.hidden=true,Math.max(3200,t.length*60))}
function toastErr(t){toast(t,true)}
async function api(path,opt={}){
  const r=await fetch(path,{headers:{'Content-Type':'application/json'},credentials:'same-origin',...opt,body:opt.body&&typeof opt.body!=='string'?JSON.stringify(opt.body):opt.body});
  const ct=r.headers.get('content-type')||'';const data=ct.includes('json')?await r.json():await r.text();
  if(!r.ok){if(r.status===401)dispatchEvent(new Event('hh401'));const msg=(data&&data.error)||(data&&data.title)||(typeof data==='string'&&data)||('Fehler '+r.status);const e=new Error(msg);e.status=r.status;throw e}
  return data;
}
const mmss=s=>!isFinite(s)?'--:--':String(Math.floor(s/60)).padStart(2,'0')+':'+String(Math.floor(s%60)).padStart(2,'0');
const ICON=k=>`/common/icons/${k}.png`;

/** Spielt den Soundboard-Zustand vom Server ab (Meister-Tab mit Ton und Spieler).
 *  Jede neue Anweisung macht ältere, noch wartende (z. B. "abspielen, sobald geladen") ungültig. */
const SILENT='data:audio/wav;base64,UklGRkQDAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YSADAACAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgA==';
class Player{
  constructor(){this.a={music:new Audio(),bg:new Audio()};this.gen={music:0,bg:0};this.master=1;this.enabled=false;this.skew=0;this.state=null;
    for(const k in this.a){const el=this.a[k];el.preload='metadata';
      el.addEventListener('ended',()=>this.onEnded&&this.onEnded(k));
      ['waiting','playing','pause','loadstart','canplay'].forEach(ev=>el.addEventListener(ev,()=>this.onStatus&&this.onStatus(k)))}}
  url(f){const [p,q]=f.split('?');return '/audio/'+p.split('/').map(encodeURIComponent).join('/')+(q?'?'+q:'')}
  /** Position laut Server, auf jetzt hochgerechnet */
  pos(k){const c=this.state&&this.state.ch[k];if(!c)return 0;return c.playing?c.pos+(Date.now()+this.skew-this.state.serverNow)/1000:c.pos}
  /** Uhrabgleich mit dem Server. Verspätete Nachrichten (Proxy puffert) lassen den Server-Zeitpunkt älter wirken;
   *  darum zählt die größte Differenz der letzten Minuten, nicht die letzte. Sonst springt der Titel zurück. */
  clock(serverNow){if(!serverNow)return;const s=serverNow-Date.now(),now=Date.now();
    this.samples=(this.samples||[]).filter(x=>now-x.t<300000);this.samples.push({t:now,s});this.skew=Math.max(...this.samples.map(x=>x.s))}
  apply(st){this.state=st;this.clock(st.serverNow);
    for(const k in this.a){const c=st.ch[k],el=this.a[k],g=++this.gen[k];
      el.loop=!!c.repeat;el.volume=Math.max(0,Math.min(1,c.volume/100*this.master));
      if(c.file&&el.dataset.f!==c.file){el.dataset.f=c.file;el.src=this.url(c.file)}
      if(!this.enabled||!c.file||!c.playing){if(!el.paused)el.pause();continue}
      el.preload='auto';
      const sync=()=>{if(g!==this.gen[k])return; // inzwischen kam eine neuere Anweisung
        // Während eines laufenden Spulvorgangs nichts anfassen: die Dauer ist dann oft nur geschätzt,
        // ein p%d mit falscher Dauer warf die Position an den Anfang und der Titel hing.
        if(el.seeking){el.play().catch(()=>{});return}
        // Gleicher Startpunkt wie zuletzt und der Titel läuft: nichts korrigieren (Lautstärke, Wiederholen, Zuhörer usw.)
        if(el.dataset.anchor===c.anchor&&!el.paused)return;
        el.dataset.anchor=c.anchor;
        let p=this.pos(k);const d=el.duration;
        if(d&&isFinite(d)&&p>=d){if(c.repeat)p=p%d;else{el.pause();return}}
        if(Math.abs(el.currentTime-p)>2){try{el.currentTime=p}catch(e){}}
        el.play().catch(()=>{})};
      if(el.readyState>=1)sync();else el.addEventListener('loadedmetadata',sync,{once:true});
    }}
  /** sofort lokal anhalten (ohne auf den Server zu warten) */
  pauseNow(k){this.gen[k]++;this.a[k].pause()}
  setVolumeNow(k,v){this.a[k].volume=Math.max(0,Math.min(1,v/100*this.master))}
  setMaster(v){this.master=v;if(this.state)for(const k in this.a)this.a[k].volume=Math.max(0,Math.min(1,this.state.ch[k].volume/100*v))}
  /** Effekt-Spieler einmal beim Klick freischalten (Firefox spielt sonst nur, was direkt ein Klick auslöst) */
  unlock(){if(this.fxPool)return;this.fxPool=[0,1,2,3].map(()=>{const a=new Audio(SILENT);a.play().catch(()=>{});return a});this.fxNext=0;
    for(const k in this.a){const el=this.a[k];if(!el.src){el.src=SILENT;el.play().then(()=>el.pause()).catch(()=>{})}}}
  fx(file){if(!this.enabled)return;let a;
    if(this.fxPool){a=this.fxPool[this.fxNext++%this.fxPool.length];a.pause();a.src=this.url(file)}else a=new Audio(this.url(file));
    a.onerror=()=>this.onFxError&&this.onFxError(file,a.error?('Code '+a.error.code+(a.error.message?': '+a.error.message:'')):'unbekannt');a.volume=Math.max(0,Math.min(1,this.master*((this.state?.fxVolume??80)/100)));a.play().catch(e=>this.onFxError&&this.onFxError(file,e.name+': '+e.message))}
}

/** Abstimmungs-Übersicht: Zeilen = Termine, Spalten = Personen. mine = eigene Konto-ID (Spieler), sonst null. */
const SYM={ja:'✓',vielleicht:'?',nein:'✗'};
function pollMatrix(p,mine,draftAns){
  const vote=(o,a)=>p.votes.find(v=>String(v.option)===String(o)&&String(v.account)===String(a));
  const others=p.voters.filter(v=>String(v.id)!==String(mine));
  return `<div class="tw"><table><thead><tr><th>Termin</th>${mine!=null?'<th>Du</th>':''}${others.map(v=>`<th>${esc(v.name)}</th>`).join('')}<th>Summe</th></tr></thead><tbody>${p.options.map(o=>{
    const all=p.voters.map(v=>vote(o.id,v.id)).filter(Boolean),ja=all.filter(v=>v.answer==='ja').length,vi=all.filter(v=>v.answer==='vielleicht').length;
    const cur=draftAns&&draftAns[o.id]||(vote(o.id,mine)||{}).answer;
    return `<tr><td><b>${esc(o.label)}</b></td>${mine!=null?`<td><span class="ans" data-opt="${o.id}">${['ja','vielleicht','nein'].map(a=>`<button class="${a}" data-a="${a}" aria-pressed="${cur===a}">${{ja:'kann',vielleicht:'vielleicht',nein:'kann nicht'}[a]}</button>`).join('')}</span></td>`:''}
      ${others.map(v=>{const x=vote(o.id,v.id);return `<td class="${x?'v-'+x.answer:'mut'}" title="${x?esc(x.answer):'noch offen'}">${x?SYM[x.answer]:'·'}</td>`}).join('')}
      <td class="mono small">${ja} ✓ · ${vi} ?</td></tr>`}).join('')}</tbody></table></div>`}
function fmtTermin(v){try{const d=new Date(v);if(isNaN(d))return v;return d.toLocaleString('de-AT',{weekday:'short',day:'2-digit',month:'2-digit',year:'numeric',hour:'2-digit',minute:'2-digit'})}catch(e){return v}}

/** Impressum als Dialog (Text vom Server, Meister pflegt ihn in der Konfiguration) */
async function showImpressum(){let d=document.getElementById('imprDlg');
  if(!d){d=document.createElement('dialog');d.id='imprDlg';d.className='sheetdlg';d.style.maxWidth='720px';
    d.innerHTML='<div class="spread"><h2>Impressum</h2><button class="btn sm" type="button" data-x>Schließen</button></div><div class="impr" id="imprTxt">…</div>';
    document.body.append(d);d.addEventListener('click',e=>{if(e.target===d||e.target.closest('[data-x]'))d.close()})}
  d.showModal();try{const r=await fetch('/api/public').then(r=>r.json());d.querySelector('#imprTxt').textContent=r.impressum}catch(e){d.querySelector('#imprTxt').textContent='Impressum nicht erreichbar.'}}

/** Formatierter Backgroundstory-Editor (gleich wie bei der Heldenerstellung). save(html) wird beim Klick auf Speichern gerufen. */
function bgEditor(root,html,save){
  root.innerHTML=`<div class="tb">
    <button type="button" data-cmd="bold" title="Fett"><b>F</b></button><button type="button" data-cmd="italic" title="Kursiv"><i>K</i></button><button type="button" data-cmd="underline" title="Unterstrichen"><u>U</u></button>
    <button type="button" data-cmd="formatBlock" data-v="h3" title="Überschrift">Ü</button><button type="button" data-cmd="formatBlock" data-v="p" title="Absatz">¶</button>
    <button type="button" data-cmd="insertUnorderedList" title="Liste">• Liste</button><button type="button" data-cmd="insertOrderedList" title="Nummeriert">1. Liste</button>
    <button type="button" data-cmd="formatBlock" data-v="blockquote" title="Zitat">„Zitat“</button><button type="button" data-cmd="removeFormat" title="Formatierung entfernen">⌫ Format</button></div>
    <div class="ed" contenteditable="true" data-ph="Woher kommt der Held? Was treibt ihn an? Familie, Feinde, Geheimnisse…"></div>
    <div class="row" style="margin-top:8px"><button type="button" class="btn sm pri" data-bgsave>Backgroundstory speichern</button><span class="small mut" data-bgst></span></div>`;
  const ed=root.querySelector('.ed'),tb=root.querySelector('.tb');ed.innerHTML=html||'';
  ed.oninput=()=>root.querySelector('[data-bgst]').textContent='nicht gespeichert';
  tb.onmousedown=e=>e.preventDefault();
  tb.onclick=e=>{const c=e.target.closest('[data-cmd]');if(!c)return;document.execCommand(c.dataset.cmd,false,c.dataset.v?`<${c.dataset.v}>`:null);ed.oninput()};
  root.querySelector('[data-bgsave]').onclick=async()=>{try{await save(ed.innerHTML);root.querySelector('[data-bgst]').textContent='gespeichert';toast('Backgroundstory gespeichert')}catch(x){toastErr(x.message)}};
}
/** PDF-Knopf mit Haken „Backgroundstory anhängen“. base = .../{file} */
function pdfLink(base,id){return `<span class="row" style="gap:6px;flex-wrap:nowrap"><a class="btn sm" data-pdf="${base}" data-pdfbg="${id}" href="${base}/pdf">PDF</a>
  <label class="small" style="display:inline-flex;gap:4px;align-items:center"><input type="checkbox" id="${id}" checked> Backgroundstory anhängen</label></span>`}
document.addEventListener('click',e=>{const a=e.target.closest('a[data-pdf]');if(!a)return;
  const c=document.getElementById(a.dataset.pdfbg);a.href=a.dataset.pdf+'/pdf'+(c&&c.checked?'?bg=1':'');toast('PDF wird von der helden.jar erzeugt…')});

/**
 * Verbindung zum Soundboard auf zwei Wegen:
 * 1. Live-Verbindung (EventSource): schnell, aber Proxies wie Cloudflare/NPM können sie puffern oder kappen.
 * 2. Abfrage jede Sekunde (/api/sound/state): kommt durch jeden Proxy, höchstens 1 s Verzögerung.
 * Was zuerst ankommt, gilt. Ältere Zustände und schon gespielte Effekte (Nummer) werden verworfen.
 */
function liveEvents(url,onMsg,onStatus=()=>{},query=()=>''){let es=null,lastSse=Date.now(),lastPoll=0,stopped=false,seenNow=0,lastFx=null,okShown=null;
  const status=()=>{const ok=Date.now()-lastPoll<5000||Date.now()-lastSse<20000;if(ok!==okShown){okShown=ok;onStatus(ok)}};
  const fx=e=>{if(lastFx!==null&&e.id<=lastFx)return;lastFx=e.id;onMsg(e)};
  const state=m=>{if(lastFx===null)lastFx=m.fxSeq||0; // alte Effekte beim Öffnen nicht nachspielen
    try{pl.clock(m.serverNow)}catch(_){}
    if(m.serverNow<=seenNow)return;seenNow=m.serverNow;const s={...m};delete s.fx;onMsg(s)};
  const handle=m=>{if(m.type==='ping'){try{pl.clock(m.serverNow)}catch(_){}return}if(m.type==='fx')return fx(m);if(m.type==='state')return state(m);onMsg(m)};
  const open=()=>{if(es)es.close();es=new EventSource(url);lastSse=Date.now();
    es.addEventListener('msg',ev=>{lastSse=Date.now();status();let m;try{m=JSON.parse(ev.data)}catch(_){return}handle(m)});
    es.onopen=()=>{lastSse=Date.now();status()};
    es.onerror=()=>{if(es.readyState===2)setTimeout(()=>{if(!stopped)open()},2000)}};
  let polling=false;
  const poll=async()=>{if(stopped||polling)return;polling=true;
    try{const r=await fetch('/api/sound/state'+query(),{cache:'no-store',credentials:'same-origin'});
      if(r.status===401){dispatchEvent(new Event('hh401'));return}
      if(r.ok){const m=await r.json();lastPoll=Date.now();
        if(lastFx!==null)for(const e of (m.fx||[]))if(m.serverNow-e.at<5000)fx(e); // zu alte Effekte nicht mehr abspielen
        state(m)}}catch(_){}finally{polling=false;status()}};
  const pt=setInterval(poll,1000);
  const dog=setInterval(()=>{if(!stopped&&Date.now()-lastSse>35000)open();status()},5000);
  document.addEventListener('visibilitychange',()=>{if(!stopped&&!document.hidden){poll();if(Date.now()-lastSse>20000)open()}});
  open();poll();
  return {close(){stopped=true;clearInterval(dog);clearInterval(pt);if(es)es.close()}}}
