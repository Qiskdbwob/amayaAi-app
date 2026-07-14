import re

with open('app/src/main/java/com/amaya/intelligence/ui/screens/models/ManageModelsScreen.kt', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Update signature
code = re.sub(
    r'fun ManageModelsScreen\(\s*codexAuthManager: CodexAuthManager,\s*onNavigateBack: \(\) -> Unit\s*\)',
    'fun ManageModelsScreen(\n    codexAuthManager: CodexAuthManager,\n    onNavigateBack: () -> Unit,\n    onNavigateToProvider: (String) -> Unit\n)',
    code
)

# 2. Remove state vars
code = re.sub(
    r'var selectedConnectionId by remember \{ mutableStateOf<String\?>\(null\) \}.*?var showDiscardConfirm by remember \{ mutableStateOf\(false\) \}',
    'var setupProvider by remember { mutableStateOf<ProviderConfig?>(null) }',
    code,
    flags=re.DOTALL
)

code = re.sub(
    r'val connection = settings\.connections\.firstOrNull.*?fun closeDetail\(\) \{.*?\}\n',
    '',
    code,
    flags=re.DOTALL
)

# 3. Update BackHandler
code = re.sub(
    r'BackHandler\(enabled = selectedConnectionId != null \|\| sheet != null\) \{.*?\}\n\s*\}',
    'BackHandler(enabled = sheet != null) {\n        sheet = null\n    }',
    code,
    flags=re.DOTALL
)

# 4. Update Scaffold body
code = re.sub(
    r'if \(connection == null\) \{.*?onConnection = \{.*?\},\s*onSelectModel = \{ sheet = ModelsSheet\.SELECT_MODEL \},\s*onAddProvider = \{ sheet = ModelsSheet\.PROVIDERS \}\s*\)\s*\} else \{.*?\s+onDelete = \{ showDeleteConfirm = true \}\s*\)\s*\}',
    '''ConnectionsOverview(
                    settings = settings,
                    colors = colors,
                    onConnection = { onNavigateToProvider(it.id) },
                    onSelectModel = { sheet = ModelsSheet.SELECT_MODEL },
                    onAddProvider = { sheet = ModelsSheet.PROVIDERS }
                )''',
    code,
    flags=re.DOTALL
)

# 5. Update TopAppBar title and nav
code = code.replace(
    'if (connection == null) "Manage Models" else connection.name',
    '"Manage Models"'
)
code = code.replace(
    'SettingsBackButton(onClick = if (connection == null) onNavigateBack else ::closeDetail)',
    'SettingsBackButton(onClick = onNavigateBack)'
)
code = re.sub(
    r'actions = \{.*?if \(connection == null\) \{.*?IconButton.*?\}.*?\}\n\s*\},',
    '''actions = {
                    IconButton(
                        onClick = { sheet = ModelsSheet.PROVIDERS },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Add, "Add provider")
                    }
                },''',
    code,
    flags=re.DOTALL
)

# 6. Update ModelsSheet enum
code = re.sub(
    r'enum class ModelsSheet \{ PROVIDERS, SETUP, SELECT_MODEL, REPLACE_KEY, ADD_MODEL \}',
    'enum class ModelsSheet { PROVIDERS, SETUP, SELECT_MODEL }',
    code
)

# 7. Remove REPLACE_KEY and ADD_MODEL sheet handlers
code = re.sub(
    r'ModelsSheet\.REPLACE_KEY -> connection\?\.let \{.*?onDismiss = \{ sheet = null \}\s*\)\s*\}',
    '',
    code,
    flags=re.DOTALL
)
code = re.sub(
    r'ModelsSheet\.ADD_MODEL -> connection\?\.let \{.*?onDismiss = \{ sheet = null \}\s*\)\s*\}',
    '',
    code,
    flags=re.DOTALL
)

# 8. Remove helper composables that are now in ModelSettingsShared
code = re.sub(r'@Composable\s*private fun ProviderModelsDetail.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun ReplaceCredentialSheet.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun AddModelSheet.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun ModelCheckboxRow.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun InlineError.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun ModelSection.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun ModelSettingsRow.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'@Composable\s*private fun ModelDivider.*?\}', '', code, flags=re.DOTALL)
code = re.sub(r'private data class ModelSettingsColors.*', '', code, flags=re.DOTALL)

with open('app/src/main/java/com/amaya/intelligence/ui/screens/models/ManageModelsScreen.kt', 'w', encoding='utf-8') as f:
    f.write(code)
