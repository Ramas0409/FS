# RequestMetrics Methods 4 & 5: Manual Timing

## 📋 Method Signatures

```java
// Method 4: Start a timer
public Timer.Sample startTimer()

// Method 5: Get the pre-registered timer
public Timer getRequestTimer()
```

## 🎯 Purpose

These methods allow **manual timing** when you need more control over when timing starts and stops. Useful for:
- Timing specific code blocks within a larger request
- Timing async operations
- Timing code where you can't easily calculate duration
- Advanced patterns (reactive streams, callbacks, etc.)

---

## 📖 Understanding Timer.Sample

### What is Timer.Sample?

A `Timer.Sample` is Micrometer's way of tracking elapsed time. Think of it like a stopwatch:

```java
Timer.Sample sample = requestMetrics.startTimer();  // ⏱️ Start stopwatch
// ... do work ...
long duration = sample.stop(timer);  // ⏱️ Stop and get elapsed time
```

**Key features:**
- Captures start time automatically
- Can be stopped multiple times (returns duration each time)
- Thread-safe
- Integrates with Micrometer registry

---

## 💻 Method 4: startTimer() - Basic Usage

### Example 1: Simple Timing
```java
@GetMapping("/process")
public Result processData() {
    // Start timer
    Timer.Sample sample = requestMetrics.startTimer();
    
    try {
        // Do work
        Result result = doExpensiveWork();
        
        // Stop timer and record
        long durationMs = sample.stop(requestMetrics.getRequestTimer());
        
        // Manual recording if needed
        requestMetrics.recordRequest(
            durationMs,
            "endpoint", "/api/process"
        );
        
        return result;
        
    } catch (Exception e) {
        long durationMs = sample.stop(requestMetrics.getRequestTimer());
        requestMetrics.recordError(
            durationMs,
            e.getClass().getSimpleName(),
            "endpoint", "/api/process"
        );
        throw e;
    }
}
```

---

### Example 2: Timing Specific Code Block

```java
@GetMapping("/multi-step")
public Result multiStepProcess() {
    
    // Step 1: Database query
    Timer.Sample dbSample = requestMetrics.startTimer();
    List<Data> data = database.query("SELECT * FROM data");
    long dbDuration = dbSample.stop(requestMetrics.getRequestTimer());
    log.info("Database query took {}ms", dbDuration);
    
    // Step 2: External API call
    Timer.Sample apiSample = requestMetrics.startTimer();
    ApiResponse response = externalApi.call(data);
    long apiDuration = apiSample.stop(requestMetrics.getRequestTimer());
    log.info("API call took {}ms", apiDuration);
    
    // Step 3: Data processing
    Timer.Sample processSample = requestMetrics.startTimer();
    Result result = processData(response);
    long processDuration = processSample.stop(requestMetrics.getRequestTimer());
    log.info("Processing took {}ms", processDuration);
    
    return result;
}
```

---

### Example 3: Try-With-Resources Pattern (Java 7+)

**Note:** Timer.Sample doesn't implement AutoCloseable, so we need a wrapper:

```java
// Custom wrapper for try-with-resources
public class TimedOperation implements AutoCloseable {
    private final Timer.Sample sample;
    private final Timer timer;
    private final RequestMetrics metrics;
    private final String endpoint;
    
    public TimedOperation(RequestMetrics metrics, String endpoint) {
        this.sample = metrics.startTimer();
        this.timer = metrics.getRequestTimer();
        this.metrics = metrics;
        this.endpoint = endpoint;
    }
    
    @Override
    public void close() {
        long duration = sample.stop(timer);
        metrics.recordRequest(duration, "endpoint", endpoint);
    }
}

// Usage
@GetMapping("/auto-timed")
public Result autoTimedOperation() {
    try (TimedOperation timing = new TimedOperation(requestMetrics, "/api/auto-timed")) {
        return doWork();
        // Automatically records metrics when exiting try block
    }
}
```

---

### Example 4: Async Operation Timing

```java
@GetMapping("/async")
public CompletableFuture<Result> asyncOperation() {
    
    // Start timer BEFORE async operation
    Timer.Sample sample = requestMetrics.startTimer();
    
    return CompletableFuture.supplyAsync(() -> {
        try {
            Result result = doExpensiveWork();
            
            // Stop timer in async context
            long duration = sample.stop(requestMetrics.getRequestTimer());
            requestMetrics.recordRequest(
                duration,
                "endpoint", "/api/async",
                "execution", "async"
            );
            
            return result;
            
        } catch (Exception e) {
            long duration = sample.stop(requestMetrics.getRequestTimer());
            requestMetrics.recordError(
                duration,
                e.getClass().getSimpleName(),
                "endpoint", "/api/async"
            );
            throw e;
        }
    });
}
```

---

## 💻 Method 5: getRequestTimer() - Advanced Usage

### What is the Request Timer?

The request timer is a **pre-registered Timer** created when RequestMetrics initializes:

```java
// In RequestMetrics constructor
this.requestTimer = Timer.builder(metricNamePrefix + ".request_duration_ms")
        .description("Request duration in milliseconds")
        .tags(getCommonTags())  // service, region, environment
        .publishPercentiles(0.50, 0.90, 0.95, 0.99)
        .register(meterRegistry);
```

**Why pre-register?**
- Performance: No lookup needed
- Consistency: Same timer for all base metrics
- Convenience: Can use directly with Timer.Sample

---

### Example 1: Direct Timer Recording

```java
@GetMapping("/direct-timing")
public Result directTiming() {
    Timer.Sample sample = requestMetrics.startTimer();
    
    try {
        Result result = doWork();
        
        // Directly record to the timer (no manual metrics call)
        sample.stop(requestMetrics.getRequestTimer());
        
        // Timer automatically updates histograms and percentiles
        
        return result;
        
    } catch (Exception e) {
        sample.stop(requestMetrics.getRequestTimer());
        throw e;
    }
}
```

**Note:** This updates the timer but **doesn't increment counters**. You'd still need to manually call `recordRequest()` or `recordError()` if you want counter updates.

---

### Example 2: Multiple Timers for Different Operations

```java
@Service
public class DataService {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    // Create custom timers for different operations
    private final Timer queryTimer;
    private final Timer transformTimer;
    private final Timer saveTimer;
    
    public DataService(MeterRegistry meterRegistry) {
        this.queryTimer = Timer.builder("data_service.query_duration")
            .register(meterRegistry);
        
        this.transformTimer = Timer.builder("data_service.transform_duration")
            .register(meterRegistry);
        
        this.saveTimer = Timer.builder("data_service.save_duration")
            .register(meterRegistry);
    }
    
    public Result processData(String input) {
        // Time query operation
        Timer.Sample querySample = requestMetrics.startTimer();
        Data data = queryDatabase(input);
        querySample.stop(queryTimer);  // Record to query timer
        
        // Time transform operation
        Timer.Sample transformSample = requestMetrics.startTimer();
        Data transformed = transform(data);
        transformSample.stop(transformTimer);  // Record to transform timer
        
        // Time save operation
        Timer.Sample saveSample = requestMetrics.startTimer();
        saveToDatabase(transformed);
        saveSample.stop(saveTimer);  // Record to save timer
        
        return new Result(transformed);
    }
}
```

---

### Example 3: Reactive Streams (WebFlux)

```java
@GetMapping("/reactive")
public Mono<Result> reactiveOperation() {
    
    return Mono.fromCallable(() -> {
        // Start timer
        Timer.Sample sample = requestMetrics.startTimer();
        return sample;
    })
    .flatMap(sample -> {
        // Do async work
        return externalService.callAsync()
            .map(response -> {
                // Stop timer when done
                long duration = sample.stop(requestMetrics.getRequestTimer());
                
                requestMetrics.recordRequest(
                    duration,
                    "endpoint", "/api/reactive"
                );
                
                return response;
            });
    })
    .onErrorResume(error -> {
        // Handle error - but we don't have access to sample here!
        // This is why reactive metrics are tricky
        return Mono.error(error);
    });
}
```

**Better reactive pattern:**

```java
@GetMapping("/reactive-better")
public Mono<Result> reactiveBetter() {
    long startTime = System.currentTimeMillis();
    
    return externalService.callAsync()
        .doOnSuccess(result -> {
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordRequest(
                duration,
                "endpoint", "/api/reactive"
            );
        })
        .doOnError(error -> {
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordError(
                duration,
                error.getClass().getSimpleName(),
                "endpoint", "/api/reactive"
            );
        });
}
```

---

## 🎯 When to Use Manual Timing

### ✅ Use startTimer() / getRequestTimer() when:

1. **Timing specific code blocks**
   ```java
   Timer.Sample sample = requestMetrics.startTimer();
   expensiveOperation();
   long duration = sample.stop(requestMetrics.getRequestTimer());
   ```

2. **Async operations**
   ```java
   Timer.Sample sample = requestMetrics.startTimer();
   CompletableFuture.supplyAsync(() -> {
       // ... work ...
       sample.stop(requestMetrics.getRequestTimer());
   });
   ```

3. **Callbacks**
   ```java
   Timer.Sample sample = requestMetrics.startTimer();
   asyncService.call(result -> {
       sample.stop(requestMetrics.getRequestTimer());
   });
   ```

4. **Multiple timing points**
   ```java
   Timer.Sample sample1 = requestMetrics.startTimer();
   step1();
   long duration1 = sample1.stop(timer1);
   
   Timer.Sample sample2 = requestMetrics.startTimer();
   step2();
   long duration2 = sample2.stop(timer2);
   ```

### ❌ Don't use when:

1. **Simple synchronous code**
   ```java
   // DON'T: Overcomplicated
   Timer.Sample sample = requestMetrics.startTimer();
   result = doWork();
   sample.stop(requestMetrics.getRequestTimer());
   
   // DO: Use simple timing
   long startTime = System.currentTimeMillis();
   result = doWork();
   long duration = System.currentTimeMillis() - startTime;
   requestMetrics.recordRequest(duration);
   ```

2. **You just need duration**
   ```java
   // DON'T: Overkill
   Timer.Sample sample = requestMetrics.startTimer();
   doWork();
   long duration = sample.stop(requestMetrics.getRequestTimer());
   
   // DO: Simple is better
   long startTime = System.currentTimeMillis();
   doWork();
   long duration = System.currentTimeMillis() - startTime;
   ```

---

## 🔄 Comparison: Manual vs Automatic Timing

### Automatic Timing (Recommended for most cases)
```java
@GetMapping("/auto")
public Result automatic() {
    long startTime = System.currentTimeMillis();
    
    try {
        Result result = doWork();
        long duration = System.currentTimeMillis() - startTime;
        requestMetrics.recordRequest(duration);
        return result;
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        requestMetrics.recordError(duration, e.getClass().getSimpleName());
        throw e;
    }
}
```

**Pros:**
- ✅ Simple and clear
- ✅ Easy to understand
- ✅ Works for 90% of cases

**Cons:**
- ⚠️ Manual duration calculation

---

### Manual Timing (For special cases)
```java
@GetMapping("/manual")
public Result manual() {
    Timer.Sample sample = requestMetrics.startTimer();
    
    try {
        Result result = doWork();
        long duration = sample.stop(requestMetrics.getRequestTimer());
        requestMetrics.recordRequest(duration);
        return result;
    } catch (Exception e) {
        long duration = sample.stop(requestMetrics.getRequestTimer());
        requestMetrics.recordError(duration, e.getClass().getSimpleName());
        throw e;
    }
}
```

**Pros:**
- ✅ Integrates with Micrometer
- ✅ Can time async operations
- ✅ Can use same sample multiple times
- ✅ More flexible

**Cons:**
- ⚠️ More verbose
- ⚠️ Requires understanding of Timer.Sample
- ⚠️ Easy to forget to stop timer

---

## 🎓 Advanced Patterns

### Pattern 1: Timing Multiple Steps

```java
@PostMapping("/multi-step-process")
public Result multiStepProcess(@RequestBody Request request) {
    Map<String, Long> stepDurations = new HashMap<>();
    
    // Step 1: Validation
    Timer.Sample validationSample = requestMetrics.startTimer();
    validate(request);
    stepDurations.put("validation", validationSample.stop(requestMetrics.getRequestTimer()));
    
    // Step 2: Database lookup
    Timer.Sample dbSample = requestMetrics.startTimer();
    Data data = database.lookup(request.getId());
    stepDurations.put("database", dbSample.stop(requestMetrics.getRequestTimer()));
    
    // Step 3: External API
    Timer.Sample apiSample = requestMetrics.startTimer();
    ApiResponse response = externalApi.call(data);
    stepDurations.put("api", apiSample.stop(requestMetrics.getRequestTimer()));
    
    // Step 4: Processing
    Timer.Sample processSample = requestMetrics.startTimer();
    Result result = process(response);
    stepDurations.put("processing", processSample.stop(requestMetrics.getRequestTimer()));
    
    // Log all durations
    log.info("Step durations: {}", stepDurations);
    
    // Total duration
    long total = stepDurations.values().stream().mapToLong(Long::longValue).sum();
    requestMetrics.recordRequest(
        total,
        "endpoint", "/api/multi-step"
    );
    
    return result;
}
```

---

### Pattern 2: Conditional Timing

```java
@GetMapping("/conditional")
public Result conditionalTiming(@RequestParam boolean enableCache) {
    Timer.Sample sample = requestMetrics.startTimer();
    Result result;
    String source;
    
    if (enableCache) {
        result = cache.get();
        source = "cache";
    } else {
        result = database.query();
        source = "database";
    }
    
    long duration = sample.stop(requestMetrics.getRequestTimer());
    
    requestMetrics.recordRequest(
        duration,
        "endpoint", "/api/conditional",
        "source", source
    );
    
    return result;
}
```

---

### Pattern 3: Parallel Operations

```java
@GetMapping("/parallel")
public Result parallelOperations() {
    Timer.Sample overallSample = requestMetrics.startTimer();
    
    // Start three parallel operations
    CompletableFuture<Data1> future1 = CompletableFuture.supplyAsync(() -> {
        Timer.Sample sample1 = requestMetrics.startTimer();
        Data1 result = service1.call();
        long duration = sample1.stop(requestMetrics.getRequestTimer());
        log.info("Service1 took {}ms", duration);
        return result;
    });
    
    CompletableFuture<Data2> future2 = CompletableFuture.supplyAsync(() -> {
        Timer.Sample sample2 = requestMetrics.startTimer();
        Data2 result = service2.call();
        long duration = sample2.stop(requestMetrics.getRequestTimer());
        log.info("Service2 took {}ms", duration);
        return result;
    });
    
    CompletableFuture<Data3> future3 = CompletableFuture.supplyAsync(() -> {
        Timer.Sample sample3 = requestMetrics.startTimer();
        Data3 result = service3.call();
        long duration = sample3.stop(requestMetrics.getRequestTimer());
        log.info("Service3 took {}ms", duration);
        return result;
    });
    
    // Wait for all
    CompletableFuture.allOf(future1, future2, future3).join();
    
    // Record overall duration
    long overallDuration = overallSample.stop(requestMetrics.getRequestTimer());
    
    Result result = combineResults(
        future1.get(),
        future2.get(),
        future3.get()
    );
    
    requestMetrics.recordRequest(
        overallDuration,
        "endpoint", "/api/parallel",
        "parallel_calls", "3"
    );
    
    return result;
}
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Forgetting to stop timer
```java
// WRONG - Timer never stopped!
Timer.Sample sample = requestMetrics.startTimer();
Result result = doWork();
return result;  // ❌ Forgot to stop!

// CORRECT - Always stop
Timer.Sample sample = requestMetrics.startTimer();
Result result = doWork();
long duration = sample.stop(requestMetrics.getRequestTimer());  // ✅
return result;
```

### ❌ MISTAKE 2: Stopping wrong timer
```java
// WRONG - Different timers
Timer.Sample sample = requestMetrics.startTimer();
Timer customTimer = Timer.builder("custom").register(meterRegistry);
sample.stop(customTimer);  // ❌ Started with one, stopped with another

// CORRECT - Use matching timer
Timer.Sample sample = requestMetrics.startTimer();
sample.stop(requestMetrics.getRequestTimer());  // ✅
```

### ❌ MISTAKE 3: Using when simple timing is better
```java
// WRONG - Overcomplicated
Timer.Sample sample = requestMetrics.startTimer();
doSimpleWork();
long duration = sample.stop(requestMetrics.getRequestTimer());

// CORRECT - Simple is better
long startTime = System.currentTimeMillis();
doSimpleWork();
long duration = System.currentTimeMillis() - startTime;
```

### ❌ MISTAKE 4: Not handling exceptions
```java
// WRONG - Timer not stopped on exception
Timer.Sample sample = requestMetrics.startTimer();
Result result = doWork();  // Might throw!
long duration = sample.stop(requestMetrics.getRequestTimer());

// CORRECT - Use try-finally
Timer.Sample sample = requestMetrics.startTimer();
try {
    return doWork();
} finally {
    sample.stop(requestMetrics.getRequestTimer());
}
```

---

## 📊 Timer.Sample Methods

```java
Timer.Sample sample = requestMetrics.startTimer();

// Method 1: Stop and record (returns duration in nanoseconds)
long nanos = sample.stop(timer);

// Method 2: Stop and record (with TimeUnit conversion)
long millis = TimeUnit.NANOSECONDS.toMillis(sample.stop(timer));

// Method 3: Stop with custom timer
sample.stop(customTimer);

// Can stop multiple times!
long duration1 = sample.stop(timer1);
long duration2 = sample.stop(timer2);  // Same sample, different timers
```

---

## ✅ Quick Checklist

Before using manual timing:

- [ ] Considered if simple timing (`System.currentTimeMillis()`) is sufficient
- [ ] Started timer at the right point
- [ ] Remembered to stop timer
- [ ] Used correct timer in `stop()` call
- [ ] Handled exceptions (try-finally)
- [ ] Not overcomplicating simple code

---

## 🎯 Summary

| Method | Purpose | Use Case |
|--------|---------|----------|
| `startTimer()` | Start timing | Begin measuring elapsed time |
| `getRequestTimer()` | Get pre-registered timer | Use with Timer.Sample.stop() |
| Together | Manual timing | Async, callbacks, specific blocks |

**Default recommendation:** Use simple `System.currentTimeMillis()` for 90% of cases. Use manual timing only when needed.

---

**Ready for the last method?** Type "next" for **Method 6: `recordThroughput()`**!
