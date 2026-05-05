package com.amaya.intelligence.di

import com.amaya.intelligence.data.repository.BrainSettingsRepository
import com.amaya.intelligence.data.repository.DataStoreSelfImprovementSettingsRepository
import com.amaya.intelligence.data.repository.FileMemoryRepository
import com.amaya.intelligence.data.repository.FilePendingProposalRepository
import com.amaya.intelligence.data.repository.FileSessionMemoryRepository
import com.amaya.intelligence.data.repository.FileSkillRepository
import com.amaya.intelligence.data.repository.MaintenanceScheduler
import com.amaya.intelligence.data.repository.ManualMaintenanceScheduler
import com.amaya.intelligence.data.repository.MemoryRepository
import com.amaya.intelligence.data.repository.PendingProposalRepository
import com.amaya.intelligence.data.repository.SelfImprovementSettingsRepository
import com.amaya.intelligence.data.repository.SessionMemoryRepository
import com.amaya.intelligence.data.repository.SessionSummarizer
import com.amaya.intelligence.data.repository.SkillRepository
import com.amaya.intelligence.data.repository.DeterministicSessionSummarizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindSkillRepository(impl: FileSkillRepository): SkillRepository

    @Binds
    @Singleton
    abstract fun bindSessionMemoryRepository(impl: FileSessionMemoryRepository): SessionMemoryRepository

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: FileMemoryRepository): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindPendingProposalRepository(impl: FilePendingProposalRepository): PendingProposalRepository

    @Binds
    @Singleton
    abstract fun bindSelfImprovementSettingsRepository(impl: DataStoreSelfImprovementSettingsRepository): SelfImprovementSettingsRepository

    @Binds
    @Singleton
    abstract fun bindBrainSettingsRepository(impl: DataStoreSelfImprovementSettingsRepository): BrainSettingsRepository

    @Binds
    @Singleton
    abstract fun bindSessionSummarizer(impl: DeterministicSessionSummarizer): SessionSummarizer

    @Binds
    @Singleton
    abstract fun bindMaintenanceScheduler(impl: ManualMaintenanceScheduler): MaintenanceScheduler
}
