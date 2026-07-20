package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN instructions TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE projects ADD COLUMN reference_paths_json TEXT NOT NULL DEFAULT '[]'")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                instructions TEXT NOT NULL,
                workspace_path TEXT NOT NULL,
                capability_profile TEXT NOT NULL,
                reference_paths_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_groups_name ON agent_groups(name)")
        db.execSQL(
            """
            INSERT INTO agent_groups(name, instructions, workspace_path, capability_profile, reference_paths_json, created_at, updated_at)
            SELECT group_name, '', COALESCE(MAX(workspace_path), ''), 'workspace=true;terminal=true;browser=true;subagents=true', '[]', MIN(created_at), MAX(updated_at)
            FROM agents
            GROUP BY group_name
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE agents_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                group_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                instructions TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(group_id) REFERENCES agent_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agents_new(id, group_id, name, role, instructions, created_at, updated_at)
            SELECT agents.id, agent_groups.id, agents.name, agents.role, agents.instructions, agents.created_at, agents.updated_at
            FROM agents
            JOIN agent_groups ON agent_groups.name = agents.group_name
            """.trimIndent()
        )
        db.execSQL("DROP TABLE agents")
        db.execSQL("ALTER TABLE agents_new RENAME TO agents")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agents_group_id ON agents(group_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agents_group_id_name ON agents(group_id, name)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS delegation_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                group_id INTEGER NOT NULL,
                agent_id INTEGER NOT NULL,
                request TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(group_id) REFERENCES agent_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(agent_id) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_delegation_tasks_group_id ON delegation_tasks(group_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_delegation_tasks_agent_id ON delegation_tasks(agent_id)")
        db.execSQL(
            """
            UPDATE conversations
            SET owner_id = (
                SELECT CAST(group_id AS TEXT) FROM agents WHERE CAST(agents.id AS TEXT) = conversations.owner_id
            )
            WHERE assistant_mode = 'AGENT'
              AND EXISTS (SELECT 1 FROM agents WHERE CAST(agents.id AS TEXT) = conversations.owner_id)
            """.trimIndent()
        )
    }
}
