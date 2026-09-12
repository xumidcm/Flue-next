import test from 'node:test';
import assert from 'node:assert/strict';
import {tagValues,evaluateExpression} from './public/renderer.js';
test('WatchMaker date tags match the documented format, not locale guesses',()=>{
  const t=tagValues({date:'2026-09-11',time:'00:08:30'});
  assert.deepEqual([t.dn,t.dnn,t.dnnn,t.dnnnn,t.dy,t.dyy],[9,'09','Sep','September','26',2026]);
  assert.deepEqual([t.ddw,t.ddww,t.dh,t.dhz,t.dh23z],['Fri','Friday',12,'12','00']);
});
test('Battery arc and padded time expressions resolve against explicit state',()=>{
  const t=tagValues({battery:25,time:'16:05:07'});
  assert.equal(evaluateExpression('{bl}*3.6',t),90);
  assert.equal(evaluateExpression("'{dh23z}' .. ':' .. '{dmz}'",t),'16:05');
  assert.equal(evaluateExpression('{bl} < 30 and \'low\' or \'ok\'',t),'low');
});
test('Arithmetic precedence, Lua zero truth, concatenation and math',()=>{
  assert.equal(evaluateExpression('2+3*4'),14);
  assert.equal(evaluateExpression('2^3^2'),512);
  assert.equal(evaluateExpression('-2^2'),-4);
  assert.equal(evaluateExpression('2^-2'),.25);
  assert.equal(evaluateExpression("0 and 'yes' or 'no'"),'yes');
  assert.equal(evaluateExpression('math.floor(math.sin(math.pi/2)*10)'),10);
  assert.equal(evaluateExpression('-1 % 5'),4);
});
test('Unknown tags, code, malformed expressions and nonfinite output fail explicitly',()=>{
  for(const source of ['{unknown}','window.alert(1)','1/0','(1+2','function() end','1 +'])assert.throws(()=>evaluateExpression(source));
});
