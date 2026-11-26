#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Execute the actual companion methods with explicit platform boundaries.
// This does not emulate the NFC controller or prove a physical RF/SPP reply.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = fs.readFileSync(new URL('./watch/companion/src/pages/index/index.ux', import.meta.url), 'utf8');
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace('export default', 'globalThis.component =');
const nonce = 's4-spp-rf-20261125';
const response = '5334544553549000';
const request = {probe:'s4-spp-test', op:'request', nonce, seq:1, epoch:7, apdu:'00a4040007f001020304050600'};
const results = [];
function fixture({deferReads=false, deferWrites=false}={}) {
  const sent=[], sendFailures=[], reads=[], fileWrites=[], writes=new Map(), intervals=new Map(), timeouts=new Map(), readyCallbacks=[];
  let id=0, terminated=false;
  const conn={
    send({data,fail}) {sent.push(JSON.parse(JSON.stringify(data)));sendFailures.push(fail);},
    getReadyState(options) {if(options)readyCallbacks.push(options); return undefined;},
  };
  const file={
    writeText({uri,text,success,fail}) {
      const complete=()=>{writes.set(uri,text);if(success)success();};
      if(deferWrites && uri.endsWith('/rf-spp-response.txt'))fileWrites.push({complete,fail});
      else complete();
    },
    readText({uri,success,fail}) {
      if(deferReads && uri.endsWith('/rf-spp-request.txt'))reads.push({success,fail});
      else if(writes.has(uri))success({text:writes.get(uri)});
      else if(fail)fail();
    },
  };
  const context=vm.createContext({
    require(name) {
      if(name==='@system.interconnect')return {instance:()=>conn};
      if(name==='@system.file')return file;
      if(name==='@system.app')return {terminate:()=>{terminated=true;}};
      throw Error('Unmocked platform API '+name);
    },
    setInterval(fn,ms) {intervals.set(++id,{fn,ms});return id;},
    clearInterval(n) {intervals.delete(n);},
    setTimeout(fn,ms) {timeouts.set(++id,{fn,ms});return id;},
    clearTimeout(n) {timeouts.delete(n);},
  });
  vm.runInContext(script,context);
  const app=context.component;app.onInit();
  return {app,conn,sent,sendFailures,reads,fileWrites,writes,intervals,timeouts,readyCallbacks,terminated:()=>terminated};
}
function check(name,fn) {fn();results.push({case:name,passed:true});}
function start(f) {f.app.testMessage({probe:'s4-spp-test',op:'rf-start',nonce});}
function poll(f, body=request) {
  f.writes.set('internal://files/rf-spp-request.txt',JSON.stringify(body));
  f.app.rfRead();
}
function reply(f, body=request) {f.app.testMessage({...body,op:'response',rapdu:response});}
function messages(f, op) {return f.sent.filter(body=>body.op===op);}

check('rf_start_acknowledges_existing_onopen_without_synchronous_getReadyState',()=>{
  const f=fixture();f.conn.onopen();start(f);
  assert.equal(f.sent.filter(x=>x.op==='bridge-ready' && x.connected).length,1);
  assert.ok([...f.intervals.values()].some(x=>x.ms===40));
});
check('late_onopen_emits_exactly_one_bridge_ready',()=>{
  const f=fixture();start(f);assert.equal(f.sent.filter(x=>x.op==='bridge-ready').length,0);
  f.conn.onopen();f.conn.onopen();
  assert.equal(f.sent.filter(x=>x.op==='bridge-ready').length,1);
});
check('readiness_has_bounded_retries_for_a_dropped_initial_connection_message',()=>{
  const f=fixture();f.conn.onopen();start(f);
  const retries=[...f.timeouts.values()].filter(x=>x.ms===250 || x.ms===750);
  assert.equal(retries.length,2);for(const x of retries)x.fn();
  assert.equal(f.sent.filter(x=>x.op==='bridge-ready').length,3);
  f.app.onDestroy();for(const x of retries)x.fn();
  assert.equal(f.sent.filter(x=>x.op==='bridge-ready').length,3);
});
check('documented_async_status_callback_can_establish_ready_state',()=>{
  const f=fixture();start(f);f.readyCallbacks.at(-1).success({status:1});
  assert.equal(f.sent.filter(x=>x.op==='bridge-ready').length,1);
});
check('fixed_request_crosses_file_to_interconnect_when_getReadyState_returns_undefined',()=>{
  const f=fixture();f.conn.onopen();start(f);
  f.writes.set('internal://files/rf-spp-request.txt',JSON.stringify(request));
  f.app.rfRead();f.app.rfRead();
  assert.equal(f.sent.filter(x=>x.op==='request').length,1);
  assert.deepEqual(f.sent.find(x=>x.op==='request'),request);
});
check('reply_requires_matching_pending_epoch_and_sequence_before_file_write',()=>{
  const f=fixture();f.conn.onopen();start(f);
  f.writes.set('internal://files/rf-spp-request.txt',JSON.stringify(request));f.app.rfRead();
  for(const delta of [{seq:2},{epoch:6},{rapdu:'9000'}])
    f.app.testMessage({...request,op:'response',rapdu:response,...delta});
  assert.equal(f.writes.has('internal://files/rf-spp-response.txt'),false);
  f.app.testMessage({...request,op:'response',rapdu:response});
  assert.equal(f.writes.get('internal://files/rf-spp-response.txt'),'S4RF1:1:7:'+response);
  assert.equal(f.sent.filter(x=>x.op==='ack' && x.response_matches).length,1);
});
check('unknown_apdu_is_not_forwarded_and_stop_terminates_polling',()=>{
  const f=fixture();f.conn.onopen();start(f);
  f.writes.set('internal://files/rf-spp-request.txt',JSON.stringify({...request,apdu:'00a4040000'}));
  f.app.rfRead();assert.equal(f.sent.filter(x=>x.op==='request').length,0);
  f.app.testMessage({probe:'s4-spp-test',op:'rf-stop',nonce});
  assert.equal(f.terminated(),true);assert.equal(f.app.rfRunning,false);
  assert.equal([...f.intervals.values()].some(x=>x.ms===40),false);
});
check('standalone_test_retains_onopen_and_async_ready_paths',()=>{
  for(const callbackFirst of [false,true]) {
    const f=fixture();
    f.app.testMessage({probe:'s4-spp-test',op:'start',nonce:'s4-spp-test-20261125'});
    if(callbackFirst) {
      f.app.testWhenReady(2);f.readyCallbacks.at(-1).success({status:1});
    } else f.conn.onopen();
    f.app.testWhenReady(1);
    assert.equal(f.sent.filter(x=>x.op==='request').length,1);
  }
});
check('disconnect_clears_pending_and_reconnection_rearms_ready_without_old_retries',()=>{
  const f=fixture();f.conn.onopen();start(f);poll(f);
  const staleRetries=[...f.timeouts.values()].filter(x=>x.ms===250 || x.ms===750);
  f.conn.onclose();
  assert.equal(f.app.rfPending,null);
  assert.equal(f.app.rfReadySent,false);
  f.conn.onopen();
  assert.equal(messages(f,'bridge-ready').length,2);
  for(const {fn} of staleRetries)fn();
  assert.equal(messages(f,'bridge-ready').length,2);
  poll(f);
  assert.equal(messages(f,'request').length,1);
  poll(f,{...request,seq:2,epoch:8});
  assert.equal(messages(f,'request').length,2);
});
check('stale_status_callbacks_cannot_open_a_closed_link_or_override_reconnection',()=>{
  const f=fixture();start(f);
  const stale=f.readyCallbacks.at(-1);
  f.conn.onclose();stale.success({status:1});
  assert.equal(f.app.sppOpen,false);
  assert.equal(messages(f,'bridge-ready').length,0);
  f.conn.onopen();stale.fail();
  assert.equal(f.app.sppOpen,true);
  assert.equal(messages(f,'bridge-ready').length,1);
});
check('stale_file_read_callbacks_cannot_submit_or_unlock_a_new_read',()=>{
  for(const success of [true,false]) {
    const f=fixture({deferReads:true});f.conn.onopen();start(f);f.app.rfRead();
    f.conn.onclose();f.conn.onopen();f.app.rfRead();
    const [stale,current]=f.reads;
    if(success)stale.success({text:JSON.stringify(request)});
    else stale.fail();
    f.app.rfRead();
    assert.equal(f.reads.length,2);
    assert.equal(messages(f,'request').length,0);
    current.success({text:JSON.stringify({...request,seq:2,epoch:8})});
    assert.equal(messages(f,'request').length,1);
  }
});
check('late_reply_and_send_callbacks_do_not_write_or_clear_a_new_pending_request',()=>{
  const f=fixture();f.conn.onopen();start(f);poll(f);
  const staleSendFailure=f.sendFailures.at(-1);
  const staleTimeout=[...f.timeouts.values()].find(x=>x.ms===1200);
  f.conn.onclose();f.conn.onopen();
  const next={...request,seq:2,epoch:8};poll(f,next);
  reply(f);staleSendFailure();staleTimeout.fn();
  assert.equal(f.writes.has('internal://files/rf-spp-response.txt'),false);
  reply(f,next);
  assert.equal(f.writes.get('internal://files/rf-spp-response.txt'),'S4RF1:2:8:'+response);
  assert.deepEqual(messages(f,'ack').map(body=>body.seq),[2]);
});
check('outstanding_file_write_finishes_without_a_stale_ack_before_another_request',()=>{
  for(const success of [true,false]) {
    const f=fixture({deferWrites:true});f.conn.onopen();start(f);poll(f);reply(f);
    assert.equal(f.fileWrites.length,1);
    f.conn.onclose();f.conn.onopen();
    const next={...request,seq:2,epoch:8};poll(f,next);
    assert.equal(messages(f,'request').length,1);
    assert.equal(messages(f,'bridge-ready').length,1);
    if(success)f.fileWrites[0].complete();
    else f.fileWrites[0].fail();
    assert.equal(messages(f,'ack').length,0);
    assert.equal(messages(f,'bridge-ready').length,2);
    poll(f,next);reply(f,next);f.fileWrites[1].complete();
    assert.deepEqual(messages(f,'ack').map(body=>body.seq),[2]);
  }
});
check('destroy_ignores_stale_read_and_connection_callbacks',()=>{
  const f=fixture({deferReads:true});f.conn.onopen();start(f);f.app.rfRead();
  const before=f.sent.length;
  f.app.onDestroy();f.reads[0].success({text:JSON.stringify(request)});f.conn.onopen();
  assert.equal(f.sent.length,before);
});
console.log(JSON.stringify({execution:'OFFLINE_ACTUAL_COMPANION_WITH_MOCK_PLATFORM',cases_passed:results.length,cases:results,
  boundaries:['interconnect','private file API','timers','app lifecycle'],real_RF_SPP_roundtrip:false},null,2));
