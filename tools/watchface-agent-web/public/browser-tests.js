// Developer-only self-check; never imported by the application or called by an Agent.
import {renderWatch,parseWatch,validateWatch,DEFAULT_XML,DEFAULT_STATE} from './renderer.js';
import {StudioAgent} from './agent.js';
export async function runBrowserTests(){
  const passed=[],assert=(ok,name)=>{if(!ok)throw Error(name);passed.push(name);};
  const canvas=document.createElement('canvas');
  const at=(x,y)=>[...canvas.getContext('2d').getImageData(x,y,1,1).data];
  await renderWatch(canvas,DEFAULT_XML,[],DEFAULT_STATE,{size:512});
  const px=canvas.getContext('2d').getImageData(0,0,512,512).data;let bright=0;for(let i=0;i<px.length;i+=4)if(px[i]>150&&px[i+3]>0)bright++;
  assert(bright>3000,'default watch renders visible dynamic text and ticks');
  const rect=corner=>`<Watch shape="S" bg_color="000000"><Layer type="rounded" width="160" height="100" radius="40" corner_type="${corner}" color="ffffff" /></Watch>`;
  await renderWatch(canvas,rect(0),[],DEFAULT_STATE);assert(at(180,210)[0]===255,'corner_type=0 renders square corners');
  await renderWatch(canvas,rect(1),[],DEFAULT_STATE);assert(at(180,210)[0]===0,'corner_type=1 renders rounded corners');
  await renderWatch(canvas,'<Watch shape="S" bg_color="000000"><Layer type="shape" shape="Square" width="40" height="40" color="ffffff" outline="Outline" o_size="10" o_color="ff0000" /></Watch>',[],DEFAULT_STATE);
  assert(at(279,256)[0]>200&&at(279,256)[1]<20,'Outline reads standard o_color/o_size');
  await renderWatch(canvas,'<Watch shape="S" bg_color="000000"><Layer type="shape" shape="Square" width="20" height="20" color="ffffff" shadow="Drop" w_distance="30" w_angle="0" w_color="ff0000" /></Watch>',[],DEFAULT_STATE);
  assert(at(225,256)[0]>200&&at(225,256)[1]<20,'Drop reads standard w_angle/w_distance/w_color');
  const dynamic='<Watch shape="S"><Layer type="shape" shape="Square" width="50" height="50" color="{bl} &lt; 20 and &apos;ff0000&apos; or &apos;ffffff&apos;"/></Watch>';
  await renderWatch(canvas,dynamic,[],{...DEFAULT_STATE,battery:10});assert(at(256,256)[0]===255&&at(256,256)[1]===0,'dynamic color expression turns red for low battery');
  await renderWatch(canvas,dynamic,[],{...DEFAULT_STATE,battery:80});assert(at(256,256)[1]===255,'dynamic color expression follows current battery state');
  await renderWatch(canvas,'<Watch shape="S"><Layer type="shape" shape="Square" color="ff0000" width="50" height="50"/><Layer type="shape" shape="Square" color="00ff00" width="50" height="50"/></Watch>',[],DEFAULT_STATE,{target:'layer-0',mode:'isolated'});
  assert(at(256,256)[0]===255&&at(256,256)[1]===0,'single-layer preview isolates correct layer');
  await renderWatch(canvas,'<Watch build_source="studio" shape="S"><Layer type="shape" shape="Square" width="50" height="50" color="ffffff"><Ambient color="ff0000"/></Layer></Watch>',[],{...DEFAULT_STATE,display:'dim'});
  assert(at(256,256)[0]===255&&at(256,256)[1]===0,'Studio Ambient applies in dim mode');
  assert(!validateWatch('<bad/>').ok,'invalid XML roots rejected');
  assert(validateWatch('<Watch><Layer type="video"/></Watch>','on_second=function() end').warnings.length===2,'unsupported scripts/layers explicitly diagnosed');
  assert(parseWatch('<Watch><Layer type="group" id="g"><Layer type="text" text="A"/></Layer></Watch>').layers[1].parent.id==='g','nested group ownership retained');
  const originalFetch=window.fetch,observations=[],messages=[];let revision=1,xml=DEFAULT_XML,round=0;
  const fakeConfig={protocol:'chat',providers:{chat:{baseUrl:'http://127.0.0.1',model:'local-test',apiKey:''}},image:{},search:{},options:{vision:true,maxRounds:8}};
  const agent=new StudioAgent({getConfig:()=>fakeConfig,getProject:()=>({xml,script:'',assets:[],revision}),getState:()=>DEFAULT_STATE,getRuntime:()=>({python:'test'}),updateProject:async change=>{xml=change.xml||xml;revision++;},addAsset:()=>{},beginGroup:()=>{},endGroup:()=>{},onBusy:()=>{},onMessage:(_,text)=>messages.push(text),onStatus:()=>{},onProjectChanged:()=>{},onToolStart:()=>({}),onToolEnd:(_,result)=>observations.push(result)});
  try{
    window.fetch=async (url,init)=>{if(url!=='/api/chat')return originalFetch(url,init);const body=JSON.parse(init.body);round++;let toolCalls=[],text='';
      if(round===1)toolCalls=[{id:'read',name:'read_document',arguments:{path:'watch.xml'}}];
      if(round===2)toolCalls=[{id:'edit',name:'edit_document',arguments:{path:'watch.xml',old_text:'bg_color="111E1B"',new_text:'bg_color="122332"',base_revision:1}}];
      if(round===3)toolCalls=[{id:'preview',name:'preview_face',arguments:{target:'face'}},{id:'validate',name:'validate_face',arguments:{}}];
      if(round===4){assert(body.messages.some(m=>m.role==='tool'&&m.images?.[0]?.startsWith('data:image/png;base64,')),'actual PNG enters model tool-result history');text='Mock tool loop complete';}
      return new Response(JSON.stringify({text,toolCalls}),{status:200,headers:{'Content-Type':'application/json'}});
    };
    await agent.run('Test a real document edit and preview');
    assert(xml.includes('bg_color="122332"')&&revision===2,'agent executes document mutation with revision');
    assert(round===4&&messages.includes('Mock tool loop complete'),'agent continues across four model/tool rounds');
    assert(observations.length===4&&!observations.some(o=>o.error),'all tool observations succeed');
    const conflict=await agent.execute('edit_document',{path:'watch.xml',old_text:'a',new_text:'b',base_revision:1},fakeConfig,new AbortController().signal).then(()=>false,e=>e.message.includes('版本冲突'));
    assert(conflict,'stale revisions cannot overwrite current document');
  }finally{window.fetch=originalFetch;}
  return {passed:passed.length,checks:passed};
}
