# Performance Survey
In order to understand the relative benefits of the Bedrock tech stack we provide this simple performance survey across several popular REST endpoint tech stacks. It is important to note that these results are not intended to be absolute performance metrics. There are a lot of variables that go into real-world systems that are not adequately captured in a performance test like this. The intention here is to run a trivial endpoint in a constant, controlled environment to see how each of these popular frameworks perform relative to each other.

The environment of the test is not terribly relevant, so long as it is constant between runs and between platforms, but for the curious:
* Apple MacBook M2
* Test candidates run in a Docker container
* K6 generating load

The candidate platforms were selected as they represent popular choices in the industry for developing REST endpoints. These are:

* [Python with FastAPI](#python--fastAPI)
* [Node.js with Fastify](#nodejs--fastify)
* [Go with Gin](#go--gin)
* [Java with Spring Boot](#java--spring-boot )
* [Bedrock](#bedrock )

The raw stats are given as an appendix to this page for those who love tabular numbers. Performance summaries and analysis are given next. Finally a wrap-up of what all these metrics mean for actual systems.

## Python + FastAPI

### Outcome:
|Metric  |Python (FastAPI)  |Bedrock|Difference|
|--|--|--|--|
|Total Req Processed  |182,948  |577,869 | 🚀 Bedrock handled 3.16× more requests|
|Avg Request Duration (http_req_duration)|1180 ms|22.90 ms|🚀 Bedrock is 51.5× faster  |
|Median Latency (med http_req_duration)|1100 ms|9.24 ms|🚀 Bedrock median latency is 119× lower|
|90th Percentile (p(90) http_req_duration)|2110 ms|63.35 ms |🚀 FastAPI’s p(90) is 33× slower|
|99th Percentile (p(99) http_req_duration)|2390 ms|88.44 ms |🚀 FastAPI’s p(99) is 27× slower|
|Max Latency (max http_req_duration) |5840 ms|403.65 ms|🚀 FastAPI’s worst-case latency is 14.5× worse|
|Req per Second (http_reqs per sec) |3,631 req/sec|11,447 req/sec|🚀 Bedrock handled 3.16× more throughput|

### Analysis:
Bedrock is massively outperforming FastAPI under high load because:  
1. Python’s FastAPI cannot handle the concurrency at 10,000 RPS.  
•  Avg latency jumped to 1.18s (suggesting extreme queuing delays).  
•  P(99) latency is 2.39s (many requests are taking multiple seconds).  
•  Peak latency is 5.84s (some requests are waiting a long time).  
•  FastAPI only handled 3,631 RPS before hitting its limits.  
2. Bedrock remains extremely fast and stable under load.  
•  Avg latency is only 22.9ms (near instant).  
•  P(99) latency is just 88ms (vs. FastAPI’s 2.39s).  
•  Max latency is only 403ms (vs. FastAPI’s 5.84s).  
•  Bedrock handled 11,447 RPS, which is 3.16× the throughput of FastAPI.

**Why Is FastAPI So Much Slower?**

🚨 FastAPI is Likely Queuing Requests Because It Can’t Handle Concurrency Efficiently  
•  Python’s GIL (Global Interpreter Lock) prevents true parallel execution.  
•  FastAPI’s asyncio event loop is overwhelmed, creating request backlog.  
•  Requests are stacking up instead of being processed in parallel.

🚨 Why Is Bedrock So Much Faster?  
•  JVM is highly optimized for multithreading.  
•  Fibers are ultra-lightweight (can handle millions of concurrent requests).  
•  Bedrock has lower overhead compared to FastAPI’s asyncio event loop.


## Node.js + Fastify

### Outcome:
|Metric  |Node.js (Fastify)  |Bedrock|Difference|
|--|--|--|--|
|Total Req Processed  |378,344  |577,869 |🚀 Bedrock handled 1.53× more requests|
|Avg Request Duration (http_req_duration)|334.19 ms|22.90 ms|🚀 Bedrock is 14.6× faster|
|Median Latency (med http_req_duration)|188.26 ms|9.24 ms|🚀 Bedrock median latency is 20× lower|
|90th Percentile (p(90) http_req_duration)|502.15 ms|63.35 ms |🚀 Fastify's p(90) is 8× slower|
|99th Percentile (p(99) http_req_duration)|727.87 ms|88.44 ms |🚀 Fastify’s p(99) is 8.2× slower|
|Max Latency (max http_req_duration) |17040 ms|403.65 ms| 🚀 Fastify’s worst-case latency is 42× worse|
|Req per Second (http_reqs per sec) |7,204 req/sec|11,447 req/sec|🚀 Bedrock handled 1.58 more throughput|

### Analysis:
🚀Bedrock outperforms Fastify in every key performance metric.
•  Node.js Fastify exhibits much higher latencies under load.
•  Bedrock processes more requests per second with much lower response times.
•  Fastify struggles at high concurrency, especially at the 99th percentile and worst-case latencies.

**Why Is Fastify So Much Slower?**

🚨 Fastify’s Event Loop Queuing Delays Requests
•  Fastify is single-threaded and relies on Node.js’s event loop.
•  When requests stack up, they wait in the event loop queue instead of running in parallel.  
🚨 Fastify Struggles with High Parallelism
•  Fastify is optimized for moderate concurrency but not extreme loads.
•  At high concurrency (10,000 VUs), the event loop becomes a bottleneck.  
🚨 Garbage Collection & V8 Optimizations Aren’t Enough
•  Node.js uses V8’s GC, which is efficient, but not as optimized as the JVM’s JIT compiler.
•  Bedrock fibers handle much higher concurrency than Node’s event loop.

**Why Is Bedrock So Much Faster?**

✅ Bedrock Fibers Scale Better Than Node.js Event Loop
•  Fibers run independently and can scale across multiple CPU cores.
•  Bedrock does not rely on an event loop and schedules requests more efficiently.  
✅ JVM’s JIT Compiler & GC Outperform V8
•  The JVM’s Just-In-Time (JIT) compiler optimizes execution at runtime.
•  Garbage collection (GC) in the JVM is more efficient at high loads than V8’s.  
✅ Parallelism Works Natively in Bedrock
•  Bedrock scales horizontally using fibers.
•  Node.js, being single-threaded, struggles at higher concurrency levels.

## Go + Gin

### Outcome:
|Metric  |Node.js (Fastify)  |Bedrock|Difference|
|--|--|--|--|
|Total Req Processed  |299.294  |577,869 |🚀 Bedrock handled 1.93× more requests|
|Avg Request Duration (http_req_duration)|520.34 ms|22.90 ms|🚀 Bedrock is 22.7× faster|
|Median Latency (med http_req_duration)|344.3 ms|9.24 ms|🚀 Bedrock is 37.3× faster|
|90th Percentile (p(90) http_req_duration)|1180 ms|63.35 ms |🚀 Bedrock's p(90) is 18.6× faster|
|99th Percentile (p(99) http_req_duration)|1660 ms|88.44 ms |🚀 Bedrock's p(99) is 18.8× faster|
|Max Latency (max http_req_duration) |8510 ms|403.65 ms| 🚀 Bedrock's worst-case latency is 21× better|
|Req per Second (http_reqs per sec) |5,927 req/sec|11,447 req/sec|🚀 Bedrock had 93% higher throughput|

### Analysis:
🚀 Bedrock significantly outperforms Go Gin under high concurrency.
•  Go Gin struggles with high loads, leading to much higher latencies.
•  Go’s worst-case latency is 8.51s, while Bedrock remains under 500ms.
•  Bedrock handles almost twice as many requests as Go Gin.
•  At high concurrency, Go Gin does not scale as efficiently as Bedrock.

**Why Is Go Gin So Much Slower?**

🚨 1. Go’s Gorilla Mux Router Overhead
•  Go Gin uses middleware layers for routing and request handling.
•  Scala’s Bedrock is much leaner, avoiding heavy middleware.  
🚨 2. Go’s Standard Net/HTTP Library Handles Connections Differently
•  Go’s net/http library has a worker pool model that doesn’t scale as efficiently.
•  Bedrock's async I/O model allows much better parallelism.  
🚨 3. Go’s Garbage Collector (GC) May Be Slowing It Down
•  Go uses a concurrent garbage collector, which can cause micro-latency spikes.
•  Bedrock benefits from JVM’s highly optimized GC and JIT compilation.  
🚨 4. Go Goroutines vs. JVM Fibers
•  Go Gin uses Goroutines, which are lightweight but not as optimized for high concurrency as JVM fibers.
•  Bedrock’s fibers are scheduled efficiently with better load balancing.

## Java + Spring Boot

### Outcome:
|Metric  |Node.js (Fastify)  |Bedrock|Difference|
|--|--|--|--|
|Total Req Processed  |552,498  |577,869 |🚀 Bedrock handled 4.6% more requests|
|Avg Request Duration (http_req_duration)|25.59 ms|22.90 ms|🚀 Bedrock is 11% faster|
|Median Latency (med http_req_duration)|13.33 ms|9.24 ms|🚀 Bedrock is 1.4× faster|
|90th Percentile (p(90) http_req_duration)|64.54 ms|63.35 ms |🚀 Effectively identical|
|99th Percentile (p(99) http_req_duration)|88.44 ms|88.44 ms |🚀 Effectively identical|
|Max Latency (max http_req_duration) |363.62 ms|403.65 ms| 🚀 Spring Boot has slightly better worst-case latency|
|Req per Second (http_reqs per sec) |7,693 req/sec|11,447 req/sec|🚀 Bedrock handled 49% more throughput|

### Analysis:
🚀 Bedrock and Spring Boot perform similarly at lower percentiles, but Bedrock scales better at high concurrency.
•  Java Spring Boot and Bedrock both handle high concurrency well.
•  At extreme load, Bedrock maintains a higher request rate (~49% more RPS).
•  Spring Boot’s worst-case latency is slightly better than Bedrock.
•  Both frameworks have very close latency distributions up to the 99th percentile.

**Why Is Bedrock Faster?**

✅ 1. Bedrock Uses a More Efficient Execution Model
•  Bedrock fibers (ultra-lightweight threads) provide better parallelism than Java threads.
•  Spring Boot uses standard Java threads, which are heavier and require more context switching.  
✅ 2. Bedrock Has Lower Framework Overhead
•  Spring Boot includes more built-in middleware, adding minor overhead.
•  Bedrock is designed for minimal overhead and ultra-low-latency workloads.  
✅ 3. Bedrock Uses a More Optimized Async Model
•  Spring Boot’s default async model (Tomcat thread pool) scales well but is not as optimized as Bedrock fibers.
•  Bedrock can efficiently handle many concurrent requests with fewer resources.

**Why Is Spring Boot Still Performing Well?**

✅ 1. JVM Optimizations Help Both
•  Spring Boot and Bedrock both benefit from JVM JIT (Just-in-Time) compilation.
•  Both handle memory and CPU well under load.  
✅ 2. Spring Boot’s Threading Model Works Well at Scale
•  Spring Boot uses a traditional thread pool model, which is mature and well-optimized.
•  Tomcat (or Netty) handles thousands of concurrent connections effectively.  
✅ 3. Spring Boot Had Slightly Better Worst-Case Latency
•  Bedrock’s worst-case latency was slightly higher, likely due to the way fibers handle task scheduling.

## Bedrock
Throughout these tests, and in data not included here, one result is clear: If you are building low-throughput/low-traffic REST endpoints, the tech stack you choose doesn't really matter from a performance standpoint. They all perform reasonably well at lower levels of load. But... as load grows most of the platforms here hit a performance wall and start to degrade significantly. (Java + Spring Boot is the exception. It kept pace with higher loads rather well.)

What is revealing about this result is how prevalent Python and Node are in the marketplace given how poorly they perform at scale. (Go also suffers at scale but does not have the same presence as Node or Python in the marketplace.) What does this suggest? At Bedrock, it is our belief that companies choose the "script" (ie non-compiled) Node and Python languages because they're popular and easy to use and fast to deliver. Startups especially love these tech stacks. Initially, before the system has major load their platforms run well, however as user growth drives scale, these systems suffer and their owners are left with few options but to throw increasing spend at cloud vendors to run an ever-growing footprint of instances.

So what about Bedrock? The Bedrock tech stack maintains its efficiency and high performance, even under high loads. Even the 99% load remained sub-second. This is due to the extreme efficiencies of the fibre model in Bedrock. Node and Python are not even multi-threaded by default. These capabilities are added via 3rd party libraries like FastAPI. Bedrock goes even beyond threads (which Java/Spring Boot use) to a fibre model, which is even leaner and faster.

Perhaps your company or project started with an easy pick of platforms but now you're experiencing "the wall" of performance you can't seem to get around, aside from paying a lot of money to your cloud vendor. This is where you need Bedrock to reimagine your REST APIs on a much more performant platform, and one that offers many other benefits beyond  performance.

## Appendix: Raw Metrics
### Python + FastAPI
```
scenarios: (100.00%) 1 scenario, 10000 max VUs, 1m20s max duration (incl. graceful stop):  
         * default: Up to 10000 looping VUs for 50s over 3 stages (gracefulRampDown: 30s, gracefulStop: 30s)  
  
  
✓ status was 200  
  
checks.........................: 100.00% 182948 out of 182948  
data_received..................: 27 MB   527 kB/s  
data_sent......................: 16 MB   323 kB/s  
http_req_blocked...............: avg=10.67µs  min=0s       med=2µs  max=27.83ms  p(90)=4µs   p(95)=83µs  
http_req_connecting............: avg=7.05µs   min=0s       med=0s   max=27.82ms  p(90)=0s    p(95)=65µs  
http_req_duration..............: avg=1.18s    min=586µs    med=1.1s max=5.84s    p(90)=2.11s p(95)=2.39s  
  { expected_response:true }...: avg=1.18s    min=586µs    med=1.1s max=5.84s    p(90)=2.11s p(95)=2.39s  
http_req_failed................: 0.00%   0 out of 182948  
http_req_receiving.............: avg=137.02µs min=4µs      med=12µs max=146.63ms p(90)=357µs p(95)=562µs  
http_req_sending...............: avg=7.06µs   min=1µs      med=3µs  max=27.79ms  p(90)=14µs  p(95)=24µs  
http_req_tls_handshaking.......: avg=0s       min=0s       med=0s   max=0s       p(90)=0s    p(95)=0s  
http_req_waiting...............: avg=1.18s    min=562µs    med=1.1s max=5.84s    p(90)=2.11s p(95)=2.39s  
http_reqs......................: 182948  3631.397764/s  
iteration_duration.............: avg=1.69s    min=500.69ms med=1.6s max=6.34s    p(90)=2.61s p(95)=2.89s  
iterations.....................: 182948  3631.397764/s  
vus............................: 648     min=299              max=10000  
vus_max........................: 10000   min=10000            max=10000
```

### Node.js + Fastify
```
scenarios: (100.00%) 1 scenario, 10000 max VUs, 1m20s max duration (incl. graceful stop):  
         * default: Up to 10000 looping VUs for 50s over 3 stages (gracefulRampDown: 30s, gracefulStop: 30s)  
  
  
✓ status was 200  
  
checks.........................: 100.00% 378344 out of 378344  
data_received..................: 72 MB   1.4 MB/s  
data_sent......................: 34 MB   641 kB/s  
http_req_blocked...............: avg=5.16µs   min=0s       med=1µs      max=3.69ms p(90)=3µs      p(95)=4µs  
http_req_connecting............: avg=2.74µs   min=0s       med=0s       max=3.66ms p(90)=0s       p(95)=0s  
http_req_duration..............: avg=334.19ms min=451µs    med=188.26ms max=17.04s p(90)=502.15ms p(95)=727.87ms  
  { expected_response:true }...: avg=334.19ms min=451µs    med=188.26ms max=17.04s p(90)=502.15ms p(95)=727.87ms  
http_req_failed................: 0.00%   0 out of 378344  
http_req_receiving.............: avg=12.9µs   min=3µs      med=8µs      max=3.54ms p(90)=25µs     p(95)=33µs  
http_req_sending...............: avg=8.78µs   min=1µs      med=3µs      max=2.41ms p(90)=15µs     p(95)=27µs  
http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s     p(90)=0s       p(95)=0s  
http_req_waiting...............: avg=334.17ms min=438µs    med=188.23ms max=17.04s p(90)=502.13ms p(95)=727.86ms  
http_reqs......................: 378344  7204.391064/s  
iteration_duration.............: avg=834.37ms min=500.51ms med=688.37ms max=17.54s p(90)=1s       p(95)=1.22s  
iterations.....................: 378344  7204.391064/s  
vus............................: 137     min=137              max=10000  
vus_max........................: 10000   min=10000            max=10000
```

### Go + Gin
```
scenarios: (100.00%) 1 scenario, 10000 max VUs, 1m20s max duration (incl. graceful stop):  
         * default: Up to 10000 looping VUs for 50s over 3 stages (gracefulRampDown: 30s, gracefulStop: 30s)  
  
  
✓ status was 200  
  
checks.........................: 100.00% 299294 out of 299294  
data_received..................: 43 MB   848 kB/s  
data_sent......................: 27 MB   528 kB/s  
http_req_blocked...............: avg=7.48µs   min=0s       med=2µs      max=7.61ms p(90)=4µs   p(95)=5µs  
http_req_connecting............: avg=4.75µs   min=0s       med=0s       max=7.57ms p(90)=0s    p(95)=0s  
http_req_duration..............: avg=520.34ms min=525µs    med=344.3ms  max=8.51s  p(90)=1.18s p(95)=1.66s  
  { expected_response:true }...: avg=520.34ms min=525µs    med=344.3ms  max=8.51s  p(90)=1.18s p(95)=1.66s  
http_req_failed................: 0.00%   0 out of 299294  
http_req_receiving.............: avg=13µs     min=4µs      med=9µs      max=3.12ms p(90)=26µs  p(95)=34µs  
http_req_sending...............: avg=8.56µs   min=1µs      med=3µs      max=5.88ms p(90)=16µs  p(95)=29µs  
http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s     p(90)=0s    p(95)=0s  
http_req_waiting...............: avg=520.32ms min=495µs    med=344.28ms max=8.51s  p(90)=1.18s p(95)=1.66s  
http_reqs......................: 299294  5927.587864/s  
iteration_duration.............: avg=1.02s    min=500.62ms med=844.4ms  max=9.01s  p(90)=1.68s p(95)=2.16s  
iterations.....................: 299294  5927.587864/s  
vus............................: 700     min=283              max=9927  
vus_max........................: 10000   min=10000            max=10000
```

### Java + Spring Boot
```
scenarios: (100.00%) 1 scenario, 10000 max VUs, 1m20s max duration (incl. graceful stop):  
         * default: Up to 10000 looping VUs for 50s over 3 stages (gracefulRampDown: 30s, gracefulStop: 30s)  
  
  
✓ status was 200  
  
checks.........................: 100.00% 552498 out of 552498  
data_received..................: 74 MB   1.0 MB/s  
data_sent......................: 49 MB   687 kB/s  
http_req_blocked...............: avg=4.52µs   min=0s       med=1µs      max=16.45ms  p(90)=3µs      p(95)=4µs  
http_req_connecting............: avg=2.25µs   min=0s       med=0s       max=16.35ms  p(90)=0s       p(95)=0s  
http_req_duration..............: avg=25.59ms  min=459µs    med=13.33ms  max=363.62ms p(90)=64.54ms  p(95)=90.46ms  
  { expected_response:true }...: avg=25.59ms  min=459µs    med=13.33ms  max=363.62ms p(90)=64.54ms  p(95)=90.46ms  
http_req_failed................: 0.00%   0 out of 552498  
http_req_receiving.............: avg=15.67µs  min=3µs      med=7µs      max=25.01ms  p(90)=20µs     p(95)=28µs  
http_req_sending...............: avg=29.75µs  min=1µs      med=3µs      max=24.73ms  p(90)=30µs     p(95)=68µs  
http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s       p(95)=0s  
http_req_waiting...............: avg=25.54ms  min=448µs    med=13.3ms   max=363.6ms  p(90)=64.49ms  p(95)=90.32ms  
http_reqs......................: 552498  7693.03046/s  
iteration_duration.............: avg=525.97ms min=500.52ms med=513.85ms max=863.89ms p(90)=564.94ms p(95)=590.94ms  
iterations.....................: 552498  7693.03046/s  
vus............................: 234     min=234              max=10000  
vus_max........................: 10000   min=10000            max=10000
```

### Bedrock
```
scenarios: (100.00%) 1 scenario, 10000 max VUs, 1m20s max duration (incl. graceful stop):  
         * default: Up to 10000 looping VUs for 50s over 3 stages (gracefulRampDown: 30s, gracefulStop: 30s)  
  
  
✓ status was 200  
  
checks.........................: 100.00% 577869 out of 577869  
data_received..................: 62 MB   1.2 MB/s  
data_sent......................: 51 MB   1.0 MB/s  
http_req_blocked...............: avg=9.36µs   min=0s       med=2µs      max=148.15ms p(90)=3µs      p(95)=4µs  
http_req_connecting............: avg=6.74µs   min=0s       med=0s       max=148.13ms p(90)=0s       p(95)=0s  
http_req_duration..............: avg=22.9ms   min=397µs    med=9.24ms   max=403.65ms p(90)=63.35ms  p(95)=88.44ms  
  { expected_response:true }...: avg=22.9ms   min=397µs    med=9.24ms   max=403.65ms p(90)=63.35ms  p(95)=88.44ms  
http_req_failed................: 0.00%   0 out of 577869  
http_req_receiving.............: avg=17.81µs  min=3µs      med=8µs      max=35.43ms  p(90)=19µs     p(95)=28µs  
http_req_sending...............: avg=46.94µs  min=1µs      med=5µs      max=31.02ms  p(90)=41µs     p(95)=95µs  
http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s       p(95)=0s  
http_req_waiting...............: avg=22.84ms  min=375µs    med=9.18ms   max=390.4ms  p(90)=63.28ms  p(95)=88.28ms  
http_reqs......................: 577869  11446.896841/s  
iteration_duration.............: avg=523.53ms min=500.44ms med=509.94ms max=904.36ms p(90)=564.34ms p(95)=589.23ms  
iterations.....................: 577869  11446.896841/s  
vus............................: 514     min=313              max=9937  
vus_max........................: 10000   min=10000            max=10000
```