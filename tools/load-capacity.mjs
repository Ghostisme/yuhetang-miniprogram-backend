import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {performance} from 'node:perf_hooks'

const [metadataFile,reportFile,...flags]=process.argv.slice(2)
if(!metadataFile||!reportFile)throw Error('Usage: node tools/load-capacity.mjs PRIVATE_METADATA REPORT [--rates=100,250,500,1000] [--seconds=30] [--second=PRIVATE_METADATA]')
const option=(name,fallback)=>flags.find(f=>f.startsWith('--'+name+'='))?.split('=').slice(1).join('=')||fallback
const fixture=JSON.parse(fs.readFileSync(metadataFile,'utf8'))
const bases=[fixture.baseUrl]
if(option('second',''))bases.push(JSON.parse(fs.readFileSync(option('second',''),'utf8')).baseUrl)
for(const base of bases){const u=new URL(base);if(!fixture.synthetic||u.hostname!=='127.0.0.1'||!['18892','18893'].includes(u.port)||fixture.database!=='127.0.0.1:13379/yuwatang_capacity_load_fixture')throw Error('Only the dedicated loopback fixtures are allowed')}
const seconds=Number(option('seconds','30')),rates=option('rates','100,250,500,1000').split(',').map(Number)
if(!Number.isFinite(seconds)||seconds<5||seconds>300||rates.some(r=>!Number.isInteger(r)||r<1||r>10000))throw Error('Invalid duration or rate')
const maxInFlight=256,results=[],pause=ms=>new Promise(r=>setTimeout(r,ms))
let serial=0
const percentile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return Math.round((sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)]||0)*100)/100}
const summarize=values=>({p50:percentile(values,.5),p95:percentile(values,.95),p99:percentile(values,.99),max:Math.round(values.reduce((max,value)=>Math.max(max,value),0)*100)/100})
const route=n=>n%100<35?'site':n%100<65?'slots':n%100<85?'mine':n%100<95?'appointments':'dashboard'
function specification(n){
  const kind=route(n),customer=fixture.customers[n%fixture.customers.length],admin=fixture.admins[n%fixture.admins.length]
  const store=fixture.stores[n%fixture.stores.length]
  const paths={site:'/api/site',slots:`/api/stores/${store}/slots?date=${fixture.day}`,mine:'/api/me/appointments',appointments:'/api/admin/appointments?page=1&pageSize=20',dashboard:'/api/admin/dashboard'}
  return {kind,path:paths[kind],token:['appointments','dashboard'].includes(kind)?admin:customer}
}
for(const targetRps of rates){
  const start=performance.now(),stats={targetRps,seconds,planned:targetRps*seconds,launched:0,completed:0,dropped:0,peakInFlight:0,statuses:{},routes:{},errors:[],latencies:[],schedulingLag:[],decodedBytes:0}
  const active=new Set();let scheduled=0
  async function request(n,lag){
    const spec=specification(n),began=performance.now();let status='network',bytes=0
    try{
      const response=await fetch(bases[n%bases.length]+spec.path,{headers:{Authorization:'Bearer '+spec.token},signal:AbortSignal.timeout(15000)})
      status=String(response.status);const body=await response.text();bytes=Buffer.byteLength(body)
      if(response.status>=500&&response.status!==503&&stats.errors.length<5)stats.errors.push({route:spec.kind,status,body:body.slice(0,300)})
    }catch(e){if(stats.errors.length<5)stats.errors.push({route:spec.kind,message:e.message})}
    const latency=performance.now()-began;stats.latencies.push(latency);stats.schedulingLag.push(lag);stats.decodedBytes+=bytes;stats.completed++
    stats.statuses[status]=(stats.statuses[status]||0)+1
    const group=stats.routes[spec.kind]??={count:0,statuses:{},latencies:[]};group.count++;group.latencies.push(latency);group.statuses[status]=(group.statuses[status]||0)+1
  }
  while(scheduled<stats.planned){
    const elapsed=performance.now()-start,due=Math.min(stats.planned,Math.floor(elapsed*targetRps/1000))
    while(scheduled<due){
      const lag=Math.max(0,elapsed-scheduled*1000/targetRps);scheduled++
      if(active.size>=maxInFlight){stats.dropped++;continue}
      stats.launched++;const promise=request(serial++,lag);active.add(promise);promise.finally(()=>active.delete(promise));stats.peakInFlight=Math.max(stats.peakInFlight,active.size)
    }
    if(scheduled<stats.planned)await pause(5)
  }
  await Promise.allSettled(active)
  stats.elapsedSeconds=Math.round((performance.now()-start)/10)/100;stats.completedRps=Math.round(stats.completed/stats.elapsedSeconds*100)/100
  stats.latencyMs=summarize(stats.latencies);stats.schedulingLagMs=summarize(stats.schedulingLag);delete stats.latencies;delete stats.schedulingLag
  for(const group of Object.values(stats.routes)){group.latencyMs=summarize(group.latencies);delete group.latencies}
  results.push(stats);console.log(JSON.stringify({targetRps,completedRps:stats.completedRps,statuses:stats.statuses,dropped:stats.dropped,latencyMs:stats.latencyMs}))
  await pause(1000)
}
const report={measuredAt:new Date().toISOString(),scope:'Local synthetic HTTP baseline; not a 50,000 simultaneous-user or cloud certification',fixtureCustomers:fixture.fixtureCustomers,fixtureAppointments:fixture.fixtureAppointments,bases,logicalCpus:os.cpus().length,hostMemoryGiB:Math.round(os.totalmem()/1024**3),loadGeneratorSharesHost:true,limits:{clientMaxInFlight:maxInFlight,apiAdmittedRequests:96,apiDatabasePool:24},mix:{site:35,slots:30,mine:20,appointments:10,dashboard:5},results}
fs.mkdirSync(path.dirname(path.resolve(reportFile)),{recursive:true})
fs.writeFileSync(reportFile,JSON.stringify(report,null,2)+'\n')
if(results.some(r=>r.errors.length||Object.keys(r.statuses).some(code=>code==='network'||Number(code)>=500&&code!=='503')))process.exitCode=1
