package com.yuwatang.reservation;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/ku52-sync")
class Ku52SyncApi {
    final Ku52MemberSync sync;
    Ku52SyncApi(Ku52MemberSync sync){this.sync=sync;}
    @PostMapping("/claim") Map<String,Object> claim(@RequestHeader(value="X-YHT-Sync-Key",required=false) String key){sync.authorize(key);return sync.claim();}
    @PostMapping("/complete") Map<String,Object> complete(@RequestHeader(value="X-YHT-Sync-Key",required=false) String key,@RequestBody Map<String,Object> in){sync.authorize(key);return sync.complete(in);}
}
