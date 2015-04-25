### Scenarios

For all scenarios key x of replication factor 3 there are three nodes selected [A, B, C]

###Successful write
- Id reads go to [A, B, C]
- Latest id is selected from read result
- If the id provided by write is greater than latest id then writes go to [A, B, C]

###Stale write
- Id reads go to [A, B, C]
- Latest id is selected from read result
- If the id provided by write is less than latest id then it's a stale write

###Successful read
- Id reads go to [A, B, C]
- One node is selected from  [A, B, C] for fetching data

### Concurrent writes
#### Same id from same machine
All writes after first one will be dropped. This helps reduce surge of load introduced when a new redis is added to cluster
#### Same id from different machines
All writes after first one will be stale writes.
#### Different ids
#####Machine 1
- Id reads go to [A, B, C]
- If id is fresh then writes go to [A, B, C] writes update data and id set.
#####Machine 2
- Id reads go to [A, B, C]
- If id is fresh then writes go to [A, B, C] writes update data and id set.


###Node failure / Network failure #1
- Node A fails
- Id reads go to [A, B, C] and read for A fails
- Latest id is selected from [B, C]
- Reads go to one node from [B, C]


####Node failure / Network failure #2
- Id reads go to [A, B, C]
- Node A fails
- A is selected for fetching data. Fetching data fails.
- This will be a cache miss

#### Node failure / Network failure #3
- A write is sent to [N1, N2, N3, N4]
- [N2, N3, N4] fail
- Another write is only sent to [N1]
- [N2] recovered and [N1] failed
- A read will now result in stale data
This can be avoided by always adding new nodes in cluster with data removed. This included recovered nodes as well. Check Ops section
