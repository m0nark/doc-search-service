# Enterprise Experience Showcase

# Distributed Systems & Backend Engineering Experience

This document highlights selected examples from prior backend engineering experience relevant to distributed systems, scalability, performance optimization, resiliency, and architectural decision-making.

---

# 1. Similar Distributed System Built and Its Scale/Impact

At PureSoftware, I worked on backend systems responsible for financial batch processing workflows involving statement generation, accrual computation, and maturity payout handling for large-scale banking accounts across the Arttha FinTech Platform.

One of the major engineering challenges was designing scheduler-based workflows capable of processing 100,000+ financial accounts efficiently while ensuring transactional correctness and operational reliability. These schedulers handled time-sensitive financial operations such as interest accruals, statement generation, payout processing, and end-of-day settlement activities.

To improve throughput and reduce processing delays, I redesigned parts of the scheduler execution pipeline using multithreading and parallel workload partitioning. Instead of sequentially processing all accounts in a single execution flow, workloads were divided into smaller concurrent processing units, allowing multiple account batches to be processed simultaneously while still maintaining transactional integrity.

The architecture also incorporated concurrency safeguards, scheduler coordination mechanisms, and database-level consistency protections to avoid duplicate payouts or overlapping financial operations. This significantly improved processing efficiency, reduced execution time for large batch jobs, and enabled the platform to scale reliably for high-volume financial workloads.

---

# 2. Performance Optimization Resulting in Significant Improvements

One of the performance optimization initiatives I worked on involved the prematurity workflow for deposit accounts in the Arttha FinTech Platform. The workflow was responsible for calculating the final payout amount when a customer prematurely closed a deposit account before maturity.

The computation logic was fairly intensive because the final amount depended on multiple factors including:
- payoff frequency
- deposit duration
- applicable interest rules
- fixed vs percentage-based penalty calculations
- accrual adjustments
- maturity configuration rules

These calculations were being executed repeatedly for the same accounts, which introduced unnecessary computational overhead and increased API response times under high traffic conditions.

After analyzing the workflow, I observed that the computed prematurity data remained effectively unchanged throughout a given day because the smallest recalculation interval in the system was daily. Based on this observation, I introduced a Redis-based caching strategy for the computed prematurity response objects.

Once a user’s prematurity calculation was generated, the final computed response was cached with a TTL lasting until the end of the day. Subsequent requests for the same account could then directly retrieve the precomputed result from Redis instead of recomputing the entire financial calculation pipeline again.

This optimization significantly reduced redundant computation load on the application layer, improved response times for repeated requests, and reduced database and CPU utilization during peak usage periods while still maintaining correctness because the underlying financial data only changed on a daily cycle.

---

# 3. Critical Production Incident Resolved in a Distributed System

While working on fintech transaction services, I handled several production issues involving large-scale transaction retrieval and statement generation workflows used by back-office operations teams.

One particularly critical issue involved a transaction statement API that was intermittently experiencing severe latency spikes. Under normal conditions, the API responded in under 500 milliseconds, but during peak operational windows certain requests were taking 7–8 seconds to complete. Since the API was heavily used by operations and reconciliation teams for customer support and transaction investigations, the slowdown was significantly impacting operational efficiency.

I investigated the issue by analyzing query execution behavior, request patterns, and database performance metrics. During the analysis, I identified that a newly introduced filter in the transaction search API — specifically a transaction status + settlement channel filter used during statement generation workflows — had been added without proper database indexing support.

As a result, the underlying query planner frequently performed large sequential scans across high-volume transaction tables instead of using indexed lookups. This became increasingly problematic because the affected tables contained millions of financial transaction records, causing query execution times to degrade dramatically under load.

To resolve the issue, I redesigned the query indexing strategy and introduced optimized composite indexes aligned with the API’s filtering and sorting patterns. I also reviewed query execution plans to ensure the database optimizer consistently used indexed paths for high-frequency search operations.

After deploying the optimization, query execution time dropped back to sub-second latency levels, significantly improving statement generation performance and reducing load on the database during peak operational hours. The incident reinforced the importance of aligning API-level filtering features with proper indexing and query-planning strategies in high-volume distributed financial systems.

---

# 4. Architectural Decision Balancing Competing Concerns

One architectural decision I worked on involved balancing data consistency, scheduler throughput, and database load while designing large-scale financial batch processing workflows for accruals, statement generation, and payout handling.

The platform processed financial operations for over 100,000 accounts through scheduled jobs that executed periodically throughout the day. A straightforward implementation using a single sequential processing pipeline would have ensured strong execution ordering, but it significantly increased execution time and created scalability bottlenecks as account volumes grew.

To address this, I redesigned parts of the scheduler architecture using multithreaded batch partitioning, where account datasets were divided into smaller processing groups and executed concurrently across worker threads. This improved throughput substantially and reduced overall execution time for large financial workflows.

However, introducing concurrency into financial processing also introduced risks around duplicate execution, race conditions, and transactional consistency. To balance performance with reliability, I implemented concurrency safeguards including scheduler coordination checks, transactional boundaries, pessimistic locking for sensitive operations, and controlled batch partition sizing to avoid database contention.

The final design achieved a balance between:
- high throughput batch execution
- transactional correctness
- operational stability
- efficient resource utilization

This approach allowed the platform to process large-scale financial workloads more efficiently while still maintaining the reliability guarantees required for sensitive banking operations.
