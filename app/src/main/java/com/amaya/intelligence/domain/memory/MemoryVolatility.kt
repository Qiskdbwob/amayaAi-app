package com.amaya.intelligence.domain.memory

/**
 * Volatility class of a saved memory, derived automatically from the memory type.
 *
 * Decides how fast a memory's retrieval priority decays with age (biomimetic decay, self-improving
 * memory scheme §2). `STABLE` memories (user preferences) are the most durable and fade the
 * slowest; `PERISHABLE` ones (environment/site facts) fade the fastest. The multiplier is applied
 * per 30-day period since [MemoryRecord.updatedAt].
 */
enum class MemoryVolatility(val decayMultiplier: Double) {
    STABLE(0.97),
    MODERATE(0.90),
    PERISHABLE(0.75);

    companion object {
        /** Automatic mapping: preference → stable, project fact → moderate, environment → perishable. */
        fun fromType(type: MemoryType): MemoryVolatility = when (type) {
            MemoryType.USER_PROFILE -> STABLE
            MemoryType.WORKSPACE_FACT -> MODERATE
        }
    }
}
