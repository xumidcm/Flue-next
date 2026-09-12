// Portable scheduling policy: Android can keep this contract with coroutine workers.
// Tools that change the document are barriers. Independent tools run concurrently;
// observations always return in the original tool-call order in a single batch.
export const DOCUMENT_WRITES=new Set(['write_document','edit_document']);
export async function executeToolBatch(calls,execute,{concurrency=4}={}){
  const limit=Math.max(1,Math.min(4,Math.floor(concurrency)||4)),results=[];
  async function flush(batch){if(!batch.length)return;const output=new Array(batch.length);let index=0;
    await Promise.all(Array.from({length:Math.min(limit,batch.length)},async()=>{while(index<batch.length){const i=index++;output[i]=await execute(batch[i]);}}));
    results.push(...output);
  }
  let parallel=[];
  for(const call of calls){if(DOCUMENT_WRITES.has(call.name)){await flush(parallel);parallel=[];results.push(await execute(call));}else parallel.push(call);}
  await flush(parallel);return results;
}
