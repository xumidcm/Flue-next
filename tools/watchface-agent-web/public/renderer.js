export const SUPPORTED=['text','image','shape','rounded','ring','progress','markers','text_curved','text_ring','group'];
export const DEFAULT_XML=`<?xml version="1.0" encoding="UTF-8"?>
<Watch name="Quiet Hours" author="Flue Studio" shape="R" bg_color="111E1B" ucolor_default="C2E5CB">
  <Layer type="ring" name="外圈轨道" radius_outer="237" radius_inner="236" angle_total="360" angle="360" color="344B43" x="0" y="0" />
  <Layer type="markers" name="分钟刻度" radius="224" m_count="60" m_width="2" m_height="5" color="52675D" />
  <Layer type="text" name="日期" text="{ddww}, {dnnn} {dd}" transform="uc" text_size="19" x="0" y="-125" color="AEC2B5" font="sans-serif" />
  <Layer type="text" name="动态时间" text="{dh23z}:{dmz}" text_size="101" x="0" y="-31" color="E5EFE4" font="sans-serif" />
  <Layer type="rounded" name="分隔线" width="40" height="2" radius="1" x="0" y="51" color="668875" />
  <Layer type="ring" name="电量环" x="-78" y="125" radius_outer="29" radius_inner="26" angle_total="360" angle="{bl}*3.6" color="C2E5CB" color3="344B43" outside_opacity="100" />
  <Layer type="text" name="电量" text="{bl}" text_size="19" x="-78" y="125" color="E5EFE4" />
  <Layer type="text" name="步数" text="{ssc}" text_size="29" x="56" y="117" color="E5EFE4" />
  <Layer type="text" name="步数说明" text="STEPS TODAY" text_size="11" x="56" y="145" color="8FA899" />
</Watch>`;
export const DEFAULT_STATE={time:'10:08:30',battery:76,steps:6280,heartRate:72,temperature:24,weather:'晴',display:'bright',date:'2026-09-11'};
const clamp=(v,a,b)=>Math.min(b,Math.max(a,v));
function attrs(el){return Object.fromEntries([...el.attributes].map(a=>[a.name,a.value]));}
export function parseWatch(xml){
  if(typeof xml!=='string'||new TextEncoder().encode(xml).length>2*1024*1024)throw Error('XML 为空或超过 2 MiB UTF-8 限制');
  if(/<!DOCTYPE|<!ENTITY/i.test(xml))throw Error('不支持 XML DTD 或实体声明');
  const doc=new DOMParser().parseFromString(xml,'application/xml');
  if(doc.querySelector('parsererror'))throw Error('XML 语法错误：'+doc.querySelector('parsererror').textContent.slice(0,200));
  const root=doc.documentElement;
  if(root.tagName!=='Watch')throw Error('根节点必须是 <Watch>（大小写敏感）');
  const descendants=[...root.querySelectorAll('*')];if(descendants.length+1>8192)throw Error('XML 超过 8192 个节点');
  const pending=[[root,1]];while(pending.length){const [el,depth]=pending.pop();if(depth>64)throw Error('XML 嵌套超过 64 层');for(const child of el.children)pending.push([child,depth+1]);}
  const elements=descendants.filter(e=>e.tagName.toLowerCase()==='layer');
  if(elements.length>512)throw Error('本原型最多支持 512 个图层');
  const layers=elements.map((e,i)=>({ref:'layer-'+i,index:i,id:e.getAttribute('id')||'',type:(e.getAttribute('type')||'').toLowerCase(),name:e.getAttribute('name')||e.getAttribute('text')||e.getAttribute('type')||'图层',attrs:attrs(e),element:e}));
  const ids=new Map(layers.filter(l=>l.id).map(l=>[l.id,l]));
  for(const l of layers){let p=l.element.parentElement;while(p&&p!==root&&p.tagName.toLowerCase()!=='layer')p=p.parentElement;l.parent=ids.get(l.attrs.parentId)||layers.find(x=>x.element===p)||null;}
  return {doc,root:attrs(root),layers,version:root.getAttribute('build_source')==='studio'?'2.0':'1.0'};
}

export function tagValues(state={}){
  const s={...DEFAULT_STATE,...state},[h,m,sec]=s.time.split(':').map(Number),date=new Date(s.date+'T12:00:00'),pad=n=>String(n).padStart(2,'0');
  const month=date.getMonth(),day=date.getDate(),year=date.getFullYear(),weekday=date.getDay();
  return {dh:h%12||12,dhz:pad(h%12||12),dh23:h,dh23z:pad(h),dm:m,dmz:pad(m),ds:sec||0,dsz:pad(sec||0),da:h>=12?'PM':'AM',dd:day,ddz:pad(day),dn:month+1,dnn:pad(month+1),dnnn:date.toLocaleString('en',{month:'short'}),dnnnn:date.toLocaleString('en',{month:'long'}),dy:String(year).slice(-2),dyy:year,ddw:date.toLocaleString('en',{weekday:'short'}),ddww:date.toLocaleString('en',{weekday:'long'}),drh:(h%12+m/60)*30,drm:(m+(sec||0)/60)*6,drs:(sec||0)*6,drss:(sec||0)*6,bl:+s.battery,blp:s.battery+'%',ssc:+s.steps,sscp:clamp(+s.steps/100,0,100),shr:+s.heartRate,wt:+s.temperature,wct:s.weather,ucolor:s.accent||'C2E5CB'};
}
// A small parser, never JavaScript eval or Function. This is intentionally not a Lua VM.
export function evaluateExpression(source,tags={}){
  const s=String(source).trim();if(s.length>4096)throw Error('表达式过长');
  const tokens=[];let at=0;
  while(at<s.length){if(/\s/.test(s[at])){at++;continue;}const rest=s.slice(at);let m;
    if((m=/^\{([A-Za-z0-9_]+)\}/.exec(rest))){if(!(m[1] in tags))throw Error('不支持标签 {'+m[1]+'}');tokens.push({v:tags[m[1]],t:'v'});at+=m[0].length;}
    else if((m=/^(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?/.exec(rest))){tokens.push({t:'v',v:Number(m[0])});at+=m[0].length;}
    else if(s[at]==='"'||s[at]==="'"){const q=s[at++];let v='',closed=false;while(at<s.length){const c=s[at++];if(c===q){closed=true;break;}v+=c==='\\'&&at<s.length?s[at++]:c;}if(!closed)throw Error('字符串未闭合');v=v.replace(/\{(\w+)\}/g,(_,t)=>{if(!(t in tags))throw Error('不支持标签 {'+t+'}');return tags[t];});tokens.push({t:'v',v});}
    else if((m=/^(and\b|or\b|not\b|true\b|false\b|nil\b|math\.[a-zA-Z]+|tonumber\b|tostring\b|[()+*/%^,<>=-]|\.\.|~=)/.exec(rest))){let v=m[0];if(['<','>','='].includes(v)&&rest[1]==='=')v+='=';tokens.push(['true','false','nil'].includes(v)?{t:'v',v:v==='nil'?null:v==='true'}:{t:'op',v});at+=v.length;}
    else throw Error('网页暂不支持此表达式：'+rest.slice(0,24));
    if(tokens.length>512)throw Error('表达式过于复杂');
  }
  let i=0;const peek=()=>tokens[i]?.v,take=()=>tokens[i++],truth=v=>v!==false&&v!==null&&v!==undefined;
  const math={sin:Math.sin,cos:Math.cos,tan:Math.tan,abs:Math.abs,floor:Math.floor,ceil:Math.ceil,min:Math.min,max:Math.max,sqrt:Math.sqrt,rad:v=>v*Math.PI/180,deg:v=>v*180/Math.PI};
  const precedence={or:1,and:2,'==':3,'~=':3,'<':3,'>':3,'<=':3,'>=':3,'..':4,'+':5,'-':5,'*':6,'/':6,'%':6,'^':7};
  function primary(){const t=take();if(!t)throw Error('表达式不完整');if(t.t==='v')return t.v;if(t.v==='-')return -Number(expr(7));if(t.v==='not')return !truth(primary());if(t.v==='('){const v=expr(0);if(take()?.v!==')')throw Error('括号未闭合');return v;}if(t.v==='math.pi')return Math.PI;
    const fn=t.v==='tonumber'?Number:t.v==='tostring'?String:math[t.v.replace('math.','')];if(!fn||peek()!=='(')throw Error('不支持函数 '+t.v);take();const args=[];if(peek()!==')'){do{args.push(expr(0));if(peek()!==',')break;take();}while(args.length<24);}if(take()?.v!==')')throw Error('函数参数错误');return fn(...args);}
  function expr(min){let a=primary();while(i<tokens.length&&precedence[peek()]>=min){const op=take().v,p=precedence[op],b=expr(p+(['^','..'].includes(op)?0:1));switch(op){case 'or':a=truth(a)?a:b;break;case 'and':a=truth(a)?b:a;break;case '..':a=String(a)+String(b);break;case '+':a=+a + +b;break;case '-':a=+a - +b;break;case '*':a=+a * +b;break;case '/':a=+a / +b;break;case '%':a=((+a % +b)+ +b)% +b;break;case '^':a=(+a)**(+b);break;case '==':a=a===b;break;case '~=':a=a!==b;break;case '>':a=a>b;break;case '<':a=a<b;break;case '>=':a=a>=b;break;case '<=':a=a<=b;}}
    return a;}
  const result=expr(0);if(i!==tokens.length)throw Error('表达式包含未解析内容');if(typeof result==='number'&&!Number.isFinite(result))throw Error('表达式结果不是有限数值');return result;
}

export function validateWatch(xml,script='',assets=[]){
  const errors=[],warnings=[];let parsed;
  try{parsed=parseWatch(xml);}catch(e){return {ok:false,errors:[e.message],warnings,layers:0};}
  if(script.trim())warnings.push('全局 Lua 脚本会保留导出，但网页预览不会执行。');
  const paths=new Set(assets.map(a=>a.name));const ids=new Set();
  for(const l of parsed.layers){if(!SUPPORTED.includes(l.type))warnings.push(`${l.ref}：${l.type} 图层尚不支持网页预览。`);
    if(l.id&&ids.has(l.id))warnings.push(`${l.ref}：重复 id ${l.id}`);ids.add(l.id);
    if(l.type==='image'&&l.attrs.path&&!paths.has('images/'+l.attrs.path)&&!paths.has(l.attrs.path))warnings.push(`${l.ref}：缺少图片 ${l.attrs.path}`);
    const seen=new Set([l]);let p=l.parent;while(p){if(seen.has(p)){warnings.push(`${l.ref}：分组父引用形成循环。`);break;}seen.add(p);p=p.parent;}
    for(const k of ['shader','mask','stencil','skew_x','skew_y'])if(l.attrs[k])warnings.push(`${l.ref}：${k} 的原生效果未实现。`);
    if(l.attrs.corner_type&&l.attrs.corner_type!=='0'&&l.attrs.corner_type!=='1')warnings.push(`${l.ref}：网页按统一圆角近似 corner_type。`);
    if(l.attrs.color2)warnings.push(`${l.ref}：多色渐变在网页中为近似效果。`);
    if(l.attrs.shadow&&l.attrs.shadow!=='Drop')warnings.push(`${l.ref}：尚未实现 ${l.attrs.shadow} 阴影。`);
    if(l.attrs.outline&&l.attrs.outline!=='Outline')warnings.push(`${l.ref}：尚未实现 ${l.attrs.outline} 描边效果。`);
    if((l.attrs.outline||l.attrs.shadow)&&!['text','shape','rounded'].includes(l.type))warnings.push(`${l.ref}：此图层的阴影/描边尚未实现。`);
    if(l.type==='image'&&l.attrs.color&&!['ffffff','FFFFFF'].includes(l.attrs.color))warnings.push(`${l.ref}：图片 tint 在网页中尚未实现。`);
    for(const k of ['hide_text','squarify','end_style','anim_in','anim_out'])if(l.attrs[k]&&l.attrs[k]!=='0'&&l.attrs[k]!=='flat')warnings.push(`${l.ref}：${k} 在网页中为近似或尚未实现。`);
  }
  return {ok:!errors.length,errors,warnings:[...new Set(warnings)],layers:parsed.layers.length,version:parsed.version};
}

const imageCache=new Map(),fontCache=new Map();
async function loadAsset(asset){if(!asset)return null;const key=asset.dataUrl;if(!imageCache.has(key)){imageCache.set(key,new Promise(resolve=>{const image=new Image();image.onload=()=>resolve(image);image.onerror=()=>resolve(null);image.src=key;}));if(imageCache.size>100)imageCache.delete(imageCache.keys().next().value);}return imageCache.get(key);}
async function loadFonts(assets){for(const a of assets.filter(a=>/\.(ttf|otf|woff2?)$/i.test(a.name))){if(fontCache.has(a.dataUrl))continue;try{const name=a.name.split('/').pop().replace(/\.[^.]+$/,'');const font=new FontFace(name,`url(${a.dataUrl})`);await font.load();document.fonts.add(font);fontCache.set(a.dataUrl,true);}catch{fontCache.set(a.dataUrl,false);}}}
function pathShape(ctx,shape,w,h,r=0){ctx.beginPath();if(shape==='Circle'){ctx.ellipse(0,0,w/2,h/2,0,0,Math.PI*2);return;}
  if(shape==='Square'||shape==='rounded'){ctx.roundRect(-w/2,-h/2,w,h,clamp(r,0,Math.min(w,h)/2));return;}
  if(shape==='Heart'){ctx.moveTo(0,h/2);ctx.bezierCurveTo(-w,-h/6,-w/3,-h*.85,0,-h/4);ctx.bezierCurveTo(w/3,-h*.85,w,-h/6,0,h/2);return;}
  const count=({Triangle:3,Pentagon:5,Hexagon:6,Star:10})[shape]||4;for(let k=0;k<count;k++){const a=k/count*Math.PI*2-Math.PI/2,scale=shape==='Star'&&k%2?.43:1;const x=Math.cos(a)*w/2*scale,y=Math.sin(a)*h/2*scale;k?ctx.lineTo(x,y):ctx.moveTo(x,y);}ctx.closePath();}
export async function renderWatch(canvas,xml,assets=[],state={},options={}){
  const parsed=parseWatch(xml),s={...DEFAULT_STATE,...state},tags={...tagValues(s),ucolor:parsed.root.ucolor_default||'C2E5CB'},warnings=[];
  const size=clamp(options.size||512,128,1024);canvas.width=size;canvas.height=size;
  const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.clearRect(0,0,size,size);await loadFonts(assets);
  const target=options.target||'face',isolated=target!=='face'&&options.mode==='isolated';
  const selected=target==='face'?null:parsed.layers.find(l=>l.ref===target||l.id===target);if(target!=='face'&&!selected)throw Error('预览图层已不存在，请重新读取图层列表');
  const attr=l=>{const a={...l.attrs};if(s.display==='dim'){const ambient=[...l.element.children].find(e=>e.tagName.toLowerCase()==='ambient');if(ambient)Object.assign(a,attrs(ambient));if(a.color_dim)a.color=a.color_dim;}return a;};
  const resolve=(v,def=0,ref='')=>{if(v===undefined||v==='')return def;try{const n=Number(evaluateExpression(v,tags));if(!Number.isFinite(n))throw Error('非数值');return clamp(n,-10000,10000);}catch(e){warnings.push(`${ref}: ${e.message}`);return def;}};
  const expressionLike=raw=>/^\s*(?:['"]|math\.|tonumber\b|tostring\b)/.test(raw)||(/^\s*(?:\{|[\d(+-]|true\b|false\b|nil\b|not\b)/.test(raw)&&/(?:\band\b|\bor\b|\bnot\b|\.\.|[+*/^<>=]|-\s*(?:\d|\{|\()|%\s*(?:\d|\{|\())/.test(raw));
  const color=(v,fallback='E5EFE4')=>{let raw=String(v||fallback);try{raw=expressionLike(raw)?String(evaluateExpression(raw,tags)):raw.replace(/\{(\w+)\}/g,(_,t)=>{if(!(t in tags))throw Error('不支持标签 {'+t+'}');return tags[t];});raw=raw.replace(/^#/,'');if(!/^[0-9a-f]{6}$/i.test(raw))throw Error('颜色必须是六位 RRGGBB：'+raw.slice(0,60));return '#'+raw;}catch(e){warnings.push(e.message);return '#'+fallback;}};
  const text=v=>{let raw=String(v??'');if(expressionLike(raw)){try{return String(evaluateExpression(raw,tags));}catch(e){warnings.push(e.message);return '—';}}
    return raw.replace(/\{(\w+)\}/g,(_,t)=>{if(t in tags)return String(tags[t]);warnings.push('未实现标签 {'+t+'}');return '—';});};
  const visible=a=>{const d=a.display||'bd';return s.display==='dim'?d.includes('d'):d.includes('b');};
  ctx.save();ctx.scale(size/512,size/512);ctx.translate(256,256);
  if(!isolated){if(parsed.root.shape!=='S'){ctx.beginPath();ctx.arc(0,0,256,0,Math.PI*2);ctx.clip();}ctx.fillStyle=color(s.display==='dim'?'080D0B':parsed.root.bg_color,'111E1B');ctx.fillRect(-256,-256,512,512);}
  function transform(a,ref){ctx.translate(resolve(a.x,0,ref),resolve(a.y,0,ref));ctx.rotate(resolve(a.rotation,0,ref)*Math.PI/180);ctx.scale(resolve(a.anim_scale_x,100,ref)/100,resolve(a.anim_scale_y,100,ref)/100);ctx.globalAlpha*=clamp(resolve(a.opacity,100,ref)/100,0,1);}
  function inSelection(l){if(!selected)return true;if(l===selected)return true;const visited=new Set();for(let p=l.parent;p&&!visited.has(p);p=p.parent){if(p===selected)return true;visited.add(p);}return false;}
  for(const l of parsed.layers){if(!inSelection(l)||l.type==='group')continue;const a=attr(l),chain=[],seen=new Set([l]);let parent=l.parent;
    while(parent&&!seen.has(parent)&&chain.length<64){chain.unshift(parent);seen.add(parent);parent=parent.parent;}if(!visible(a)||chain.some(p=>!visible(attr(p))))continue;
    ctx.save();for(const p of chain)transform(attr(p),p.ref);transform(a,l.ref);
    const n=(key,def=0)=>resolve(a[key],def,l.ref),c=(key,def)=>color(a[key],def),w=Math.max(0,n('width',100)),h=Math.max(0,n('height',100));
    ctx.fillStyle=c('color');ctx.strokeStyle=c('color');ctx.lineWidth=clamp(n('o_size',4),0,50);
    const paintEffects=(fill,stroke)=>{if(a.shadow==='Drop'){ctx.save();const distance=clamp(n('w_distance',5),0,100),angle=n('w_angle',135)*Math.PI/180;ctx.translate(-distance*Math.cos(angle),distance*Math.sin(angle));ctx.fillStyle=c('w_color','000000');ctx.globalAlpha*=clamp(n('w_opacity',100)/100,0,1);fill();ctx.restore();}if(a.outline==='Outline'&&ctx.lineWidth>0){ctx.save();ctx.strokeStyle=c('o_color','ffffff');ctx.globalAlpha*=clamp(n('o_opacity',100)/100,0,1);stroke();ctx.restore();}fill();};
    const align=a.alignment||'cc';let ww=w,hh=h;if(l.type==='text'){
      const fs=Math.max(1,n('text_size',30));ctx.font=`${a.font_weight||'400'} ${fs}px "${(a.font||'sans-serif').replace(/["\\]/g,'')}", sans-serif`;ww=ctx.measureText(text(a.text)).width;hh=fs;
    }
    if(['ring','markers','text_ring','text_curved'].includes(l.type)){ww=hh=2*n(l.type==='ring'?'radius_outer':'radius',100);}
    ctx.translate(align[1]==='l'?ww/2:align[1]==='r'?-ww/2:0,align[0]==='t'?hh/2:align[0]==='b'?-hh/2:0);
    try{switch(l.type){
      case 'text':{let value=text(a.text||a.value);if(a.transform==='uc')value=value.toUpperCase();if(a.transform==='lc')value=value.toLowerCase();ctx.textAlign='center';ctx.textBaseline='middle';paintEffects(()=>ctx.fillText(value,0,0),()=>ctx.strokeText(value,0,0));break;}
      case 'shape':case 'rounded':{const draw=()=>pathShape(ctx,l.type==='rounded'?'rounded':a.shape||'Square',w,h,l.type==='rounded'&&a.corner_type!=='0'?n('radius',20):0);paintEffects(()=>{draw();ctx.fill();},()=>{draw();ctx.stroke();});break;}
      case 'ring':{const outer=Math.max(0,n('radius_outer',60)),inner=clamp(n('radius_inner',50),0,outer),radius=(outer+inner)/2,thick=outer-inner,total=clamp(n('angle_total',360),0,360),angle=clamp(n('angle',360),0,total),direction=a.is_clockwise==='N'?-1:1;ctx.lineWidth=thick;ctx.lineCap=a.radius==='0'?'butt':'round';
        const arc=(deg,paint,opacity=1)=>{if(deg<=0||thick<=0)return;ctx.save();ctx.globalAlpha*=opacity;ctx.strokeStyle=paint;ctx.beginPath();ctx.arc(0,0,radius,-Math.PI/2,-Math.PI/2+direction*deg*Math.PI/180,direction<0);ctx.stroke();ctx.restore();};
        if(a.color3)arc(total,c('color3'),clamp(n('outside_opacity',100)/100,0,1));arc(angle,c('color'));break;}
      case 'progress':{const pct=clamp(n('pct_complete',50),0,100)/100,margin=clamp(n('margin',0),0,Math.min(w,h)/2),radius=a.corner_type==='0'?0:clamp(n('radius',50),0,Math.min(w,h)/2);if(margin){ctx.fillStyle=c('color4','344B43');pathShape(ctx,'rounded',w,h,radius);ctx.fill();ctx.fillStyle=c('color3','344B43');pathShape(ctx,'rounded',w-2*margin,h-2*margin,Math.max(0,radius-margin));ctx.fill();}ctx.save();pathShape(ctx,'rounded',w-2*margin,h-2*margin,Math.max(0,radius-margin));ctx.clip();ctx.fillStyle=c('color');if(a.angle==='0'||a.angle==='180')ctx.fillRect(-w/2+margin,h/2-margin-(h-2*margin)*pct,w-2*margin,(h-2*margin)*pct);else ctx.fillRect(-w/2+margin,-h/2+margin,(w-2*margin)*pct,h-2*margin);ctx.restore();break;}
      case 'markers':{const count=clamp(Math.floor(n('m_count',60)),1,360),radius=n('radius',200),mw=Math.max(0,n('m_width',2)),mh=Math.max(0,n('m_height',8));for(let j=0;j<count;j++){ctx.save();ctx.rotate(j/count*Math.PI*2);ctx.translate(0,-radius);pathShape(ctx,a.shape||'Square',mw,mh);ctx.fill();ctx.restore();}break;}
      case 'image':{const path=a.path||'',asset=assets.find(x=>x.name===path||x.name==='images/'+path),image=await loadAsset(asset);if(image)ctx.drawImage(image,-w/2,-h/2,w,h);else warnings.push(`${l.ref}：图片未就绪 ${path}`);break;}
      case 'text_curved':{const val=[...text(a.text)],r=Math.max(1,n('radius',100)),fs=Math.max(1,n('text_size',20));ctx.font=`${fs}px "${a.font||'sans-serif'}",sans-serif`;ctx.textAlign='center';ctx.textBaseline='middle';const widths=val.map(v=>ctx.measureText(v).width),sum=widths.reduce((x,y)=>x+y,0);let cursor=-sum/2;for(let j=0;j<val.length;j++){const ang=(cursor+widths[j]/2)/r*(a.curve_dir==='Down'?-1:1);ctx.save();ctx.rotate(ang);ctx.translate(0,-r);ctx.fillText(val[j],0,0);ctx.restore();cursor+=widths[j];}break;}
      case 'text_ring':{const count=a.ring_type==='0-59'?60:12,r=n('radius',190),fs=n('text_size',20),every=Math.max(1,Math.floor(n('show_every',1)));ctx.font=`${fs}px sans-serif`;ctx.textAlign='center';ctx.textBaseline='middle';for(let j=0;j<count;j+=every){const value=count===12?j||12:j,angle=(n('angle_start',0)+j/count*(n('angle_end',360)-n('angle_start',0)))*Math.PI/180;ctx.save();ctx.translate(Math.sin(angle)*r,-Math.cos(angle)*r);if(a.rotated_text==='y')ctx.rotate(angle);ctx.fillText(String(value),0,0);ctx.restore();}break;}
      default:warnings.push(`${l.ref}：不支持 ${l.type}`);
    }}catch(e){warnings.push(`${l.ref}：${e.message}`);}finally{ctx.restore();}
  }
  ctx.restore();
  if(isolated){const pixels=ctx.getImageData(0,0,size,size).data;let x0=size,y0=size,x1=0,y1=0;for(let y=0;y<size;y++)for(let x=0;x<size;x++)if(pixels[(y*size+x)*4+3]>8){x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y);}if(x1>x0&&y1>y0){const copy=document.createElement('canvas');copy.width=x1-x0+1;copy.height=y1-y0+1;copy.getContext('2d').drawImage(canvas,x0,y0,copy.width,copy.height,0,0,copy.width,copy.height);ctx.clearRect(0,0,size,size);const k=(size-64)/Math.max(copy.width,copy.height);ctx.drawImage(copy,(size-copy.width*k)/2,(size-copy.height*k)/2,copy.width*k,copy.height*k);}}
  return {warnings:[...new Set(warnings)],layerCount:parsed.layers.length,version:parsed.version,state:s,dataUrl:canvas.toDataURL('image/png')};
}
