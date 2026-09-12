import test from 'node:test';
import assert from 'node:assert/strict';
import {executeToolBatch} from './public/tool-scheduler.js';
test('independent tools overlap and their observations retain input order',async()=>{
  let active=0,peak=0;const result=await executeToolBatch([1,2,3,4].map(id=>({id,name:'search_web'})),async c=>{active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,20*(5-c.id)));active--;return c.id;},{concurrency:3});
  assert.equal(peak,3);assert.deepEqual(result,[1,2,3,4]);
});
test('document writes are barriers around parallel phases',async()=>{
  let active=0;const order=[];const calls=['search_web','preview_face','edit_document','search_web','write_document'];
  const result=await executeToolBatch(calls.map((name,id)=>({name,id})),async c=>{
    if(c.name.endsWith('_document'))assert.equal(active,0);
    active++;order.push('start'+c.id);await new Promise(r=>setTimeout(r,10));active--;order.push('end'+c.id);return c.id;
  });assert.deepEqual(result,[0,1,2,3,4]);assert.ok(order.indexOf('start2')>order.indexOf('end0'));assert.ok(order.indexOf('start2')>order.indexOf('end1'));assert.ok(order.indexOf('start3')>order.indexOf('end2'));assert.ok(order.indexOf('start4')>order.indexOf('end3'));
});
