import fs from 'node:fs'
import assert from 'node:assert/strict'

const [metadataFile,reportFile,secondFile]=process.argv.slice(2)
if(!metadataFile||!reportFile)throw Error('Usage: node tools/verify-capacity-http.mjs PRIVATE_METADATA REPORT [SECOND_PRIVATE_METADATA]')
const fixture=JSON.parse(fs.readFileSync(metadataFile,'utf8')),bases=[fixture.baseUrl]
if(secondFile)bases.push(JSON.parse(fs.readFileSync(secondFile,'utf8')).baseUrl)
assert(fixture.synthetic&&fixture.database==='127.0.0.1:13379/yuwatang_capacity_load_fixture')
for(const base of bases){const u=new URL(base);assert(u.hostname==='127.0.0.1'&&['18892','18893'].includes(u.port))}
const checks=[],admin=fixture.admins[0],store=fixture.stores[0],run=Date.now().toString(),day=fixture.day
async function request(base,path,method='GET',body,token=admin,key){
  const response=await fetch(base+path,{method,headers:{Authorization:'Bearer '+token,...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)})
  return {status:response.status,body:await response.json()}
}
function input(n,time){return {storeId:store,serviceId:'moxa',date:day,startTime:time,headcount:1,name:'合成并发客户',phone:'139'+String(Number(run.slice(-5))*100+n).padStart(8,'0'),privacyConsent:true,memberClaim:'UNKNOWN',visitedClaim:'UNKNOWN'}}
// Small waves keep this correctness check below the intentional write-admission ceiling.
const results=[]
for(let wave=0;wave<10;wave++)results.push(...await Promise.all(Array.from({length:8},(_,j)=>{const n=wave*8+j;return request(bases[n%bases.length],'/api/appointments','POST',input(n,'10:00'),fixture.customers[n],`capacity-http-${run}-${n}`)})))
assert.equal(results.filter(r=>r.status===200).length,4);assert(results.every(r=>[200,409].includes(r.status)),JSON.stringify(results.filter(r=>![200,409].includes(r.status))))
checks.push({name:'80 requests share four seats across API instances',success:4,rejectedAsFull:76,instances:bases.length})
const key='capacity-http-idem-'+run,payload=input(81,'10:15'),retries=[]
for(let wave=0;wave<6;wave++)retries.push(...await Promise.all(Array.from({length:8},(_,j)=>request(bases[j%bases.length],'/api/appointments','POST',payload,fixture.customers[81],key))))
assert(retries.every(r=>r.status===200),JSON.stringify(retries.filter(r=>r.status!==200)));assert.equal(new Set(retries.map(r=>r.body.id)).size,1)
checks.push({name:'48 repeated submissions create one appointment across instances',appointmentCount:1})
const booking=results.find(r=>r.status===200).body,changes=[]
for(let wave=0;wave<4;wave++)changes.push(...await Promise.all(Array.from({length:8},(_,j)=>request(bases[j%bases.length],'/api/admin/appointments/'+booking.id,'PATCH',{action:'confirm',version:1}))))
assert.equal(changes.filter(r=>r.status===200).length,1);assert(changes.every(r=>[200,409].includes(r.status)))
checks.push({name:'32 changes of the same version accept exactly one',accepted:1,conflicts:31})
const cleanup=new Map(results.filter(r=>r.status===200).map(r=>[r.body.id,1]));cleanup.set(booking.id,2);cleanup.set(retries[0].body.id,1)
for(const [id,version] of cleanup){const response=await request(bases[0],'/api/admin/appointments/'+id,'PATCH',{action:'cancel',version});assert.equal(response.status,200,JSON.stringify(response))}
for(const base of bases){const response=await fetch(base+'/api/health/live');assert.equal(response.status,200)}
checks.push({name:'All synthetic active test bookings cancelled; instances remain healthy',cancelled:cleanup.size})
fs.writeFileSync(reportFile,JSON.stringify({measuredAt:new Date().toISOString(),scope:'Isolated MySQL HTTP correctness',checks},null,2)+'\n')
console.log(JSON.stringify({passed:checks.length,instances:bases.length,checks}))
