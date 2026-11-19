// Gemeinsame Helfer für beide Oberflächen
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function toast(t){let e=$('#toast');if(!e){e=document.createElement('div');e.id='toast';e.className='toast';document.body.append(e)}e.textContent=t;e.hidden=false;clearTimeout(toast.h);toast.h=setTimeout(()=>e.hidden=true,3200)}
async function api(path,opt={}){
  const r=await fetch(path,{headers:{'Content-Type':'application/json'},credentials:'same-origin',...opt,body:opt.body&&typeof opt.body!=='string'?JSON.stringify(opt.body):opt.body});
  const ct=r.headers.get('content-type')||'';const data=ct.includes('json')?await r.json():await r.text();
  if(!r.ok){if(r.status===401)dispatchEvent(new Event('hh401'));const msg=(data&&data.error)||(data&&data.title)||(typeof data==='string'&&data)||('Fehler '+r.status);const e=new Error(msg);e.status=r.status;throw e}
  return data;
}
const mmss=s=>!isFinite(s)?'--:--':String(Math.floor(s/60)).padStart(2,'0')+':'+String(Math.floor(s%60)).padStart(2,'0');
const ICON=k=>`/common/icons/${k}.png`;

/** Spielt den Soundboard-Zustand vom Server ab (Meister-Tab mit Ton und Spieler). */
class Player{
  constructor(){this.a={music:new Audio(),bg:new Audio()};this.master=1;this.enabled=false;this.skew=0;this.state=null;
    for(const k in this.a){this.a[k].preload='auto';this.a[k].addEventListener('ended',()=>this.onEnded&&this.onEnded(k))}}
  url(f){return '/audio/'+f.split('/').map(encodeURIComponent).join('/')}
  apply(st){this.state=st;this.skew=st.serverNow-Date.now();
    for(const k in this.a){const c=st.ch[k],el=this.a[k];
      el.loop=!!c.repeat;el.volume=Math.max(0,Math.min(1,c.volume/100*this.master));
      if(!this.enabled||!c.file||!c.playing){if(!el.paused)el.pause();if(c.file&&el.dataset.f!==c.file){el.dataset.f=c.file;el.src=this.url(c.file)}continue}
      if(el.dataset.f!==c.file){el.dataset.f=c.file;el.src=this.url(c.file)}
      const want=()=>{let p=c.pos;if(el.duration&&isFinite(el.duration)&&c.repeat)p=p%el.duration;return p};
      const sync=()=>{const p=want();if(Math.abs(el.currentTime-p)>1.5){try{el.currentTime=p}catch(e){}}el.play().catch(()=>{})};
      if(el.readyState>=1)sync();else el.addEventListener('loadedmetadata',sync,{once:true});
    }}
  setMaster(v){this.master=v;if(this.state)this.apply(this.state)}
  fx(file){if(!this.enabled)return;const a=new Audio(this.url(file));a.volume=Math.max(0,Math.min(1,this.master*((this.state?.fxVolume??80)/100)));a.play().catch(()=>{})}
}
