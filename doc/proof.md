Mathematical analysis

Number of queries under normal operation

N - Number of redis nodes
x - Payload size
x-ts - Payload size for timestamps
B - Max bandwidth
n - Number of queries possible (B * N) / ((N * x-ts) + x)

N - 1
x - 20000 (bytes)
x-ts - 1 (byte)
B - 120 MBps = 120000000 Bps

n = (960000000 * 1) / (( 1 * 1 ) + 20)
n = 5999


[10 59970]
[20 119880]
[30 179730]
[40 239520]
[50 299251]
[60 358923]
[70 418535]
[80 478087]
[90 537580]
[100 597014]
[500 2926829]
[1000 5714285]
[5000 24000000]
[10000 40000000]


Number of queries in case of new nodes added

N1 - Number of nodes at t1
N2 - Number of nodes at t2

(N2 - N1)

Number of queries in case of recovery after node failure

N1 - Number of nodes at t1
N2 number of nodes failed at t2
Same number of nodes recovered at t3

number of repair on read queries N2




Latency

q - Average query time
qw - Worst case time for redusa data fetch


qw = (N + 1) * q
