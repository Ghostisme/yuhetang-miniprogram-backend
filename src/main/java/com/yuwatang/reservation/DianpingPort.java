package com.yuwatang.reservation;

import java.util.List;
import java.util.Map;

/** Shared ingestion boundary for spreadsheet imports and a future authorized platform adapter. */
interface DianpingPort {
    int SCHEMA_VERSION = 1;
    enum Kind { CUSTOMERS, ORDERS }
    record Source(String name, String sha256) {}
    Map<String,Object> ingest(Admin actor, Kind kind, List<Map<String,Object>> rows, boolean commit, Source source);
}
